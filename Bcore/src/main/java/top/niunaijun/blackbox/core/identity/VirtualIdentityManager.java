package top.niunaijun.blackbox.core.identity;

import androidx.core.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.UUID;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.CloseUtils;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Owns the resettable identifiers assigned to a virtual user.
 *
 * Values live inside the virtual user's private directory, so deleting a space
 * deletes its identity as well. Advertising ID is shared by apps in one
 * virtual user. App Set ID is deterministically scoped to the calling package.
 */
public final class VirtualIdentityManager {
    private static final String IDENTITY_FILE = ".dual-space-identity";
    private static final String ADVERTISING_ID = "advertising_id";
    private static final String APP_SET_SEED = "app_set_seed";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final VirtualIdentityManager INSTANCE = new VirtualIdentityManager();

    private final Object lock = new Object();

    private VirtualIdentityManager() {
    }

    public static VirtualIdentityManager get() {
        return INSTANCE;
    }

    public String getAdvertisingId(int userId) {
        synchronized (lock) {
            Properties identity = readOrCreate(userId);
            return identity.getProperty(ADVERTISING_ID);
        }
    }

    public String getAppSetId(int userId, String packageName) {
        synchronized (lock) {
            Properties identity = readOrCreate(userId);
            String seed = identity.getProperty(APP_SET_SEED);
            String scope = packageName == null || packageName.trim().isEmpty()
                    ? "unknown"
                    : packageName.trim();
            return uuidFromSha256(seed + "\u0000" + scope);
        }
    }

    public void reset(int userId) {
        synchronized (lock) {
            File identityFile = getIdentityFile(userId);
            if (identityFile.exists() && !identityFile.delete()) {
                throw new IllegalStateException("Unable to reset virtual identity for user " + userId);
            }
            readOrCreate(userId);
        }
    }

    private Properties readOrCreate(int userId) {
        File userDir = BEnvironment.getUserDir(userId);
        FileUtils.mkdirs(userDir);
        AtomicFile atomicFile = new AtomicFile(getIdentityFile(userId));
        Properties properties = new Properties();
        FileInputStream input = null;

        try {
            if (atomicFile.getBaseFile().exists()) {
                input = atomicFile.openRead();
                properties.load(input);
            }
        } catch (Exception ignored) {
            properties.clear();
        } finally {
            CloseUtils.close(input);
        }

        boolean changed = false;
        if (!isUuid(properties.getProperty(ADVERTISING_ID))) {
            properties.setProperty(ADVERTISING_ID, UUID.randomUUID().toString());
            changed = true;
        }
        if (properties.getProperty(APP_SET_SEED) == null) {
            byte[] seed = new byte[32];
            RANDOM.nextBytes(seed);
            properties.setProperty(APP_SET_SEED, toHex(seed));
            changed = true;
        }

        if (changed) {
            write(atomicFile, properties);
        }
        return properties;
    }

    private void write(AtomicFile atomicFile, Properties properties) {
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            properties.store(output, "Dual Space Livre virtual identity");
            output.flush();
            atomicFile.finishWrite(output);
        } catch (Exception error) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw new IllegalStateException("Unable to persist virtual identity", error);
        }
    }

    private File getIdentityFile(int userId) {
        return new File(BEnvironment.getUserDir(userId), IDENTITY_FILE);
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String uuidFromSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes("UTF-8"));
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long most = buffer.getLong();
            long least = buffer.getLong();
            most = (most & 0xffffffffffff0fffL) | 0x0000000000004000L;
            least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(most, least).toString();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to derive App Set ID", error);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
