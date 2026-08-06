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
    private static final String ANDROID_ID = "android_id";
    private static final String GSF_ID = "gsf_id";
    private static final String SERIAL = "serial";
    private static final String WIDEVINE_SEED = "widevine_seed";
    private static final int ANDROID_ID_HEX_LENGTH = 16;
    private static final int WIDEVINE_ID_LENGTH = 32;
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

    /**
     * Stable per-space ANDROID_ID.
     *
     * Generated once and persisted, so a space keeps the same value for its
     * whole life. Instagram (and anything else that fingerprints the device)
     * binds its session to this value: handing out a fresh one on each launch
     * makes the account look like it hopped devices, and handing out the host's
     * real one lets every space be linked back to the same device.
     */
    public String getAndroidId(int userId) {
        synchronized (lock) {
            Properties identity = readOrCreate(userId);
            return identity.getProperty(ANDROID_ID);
        }
    }

    /**
     * Google Services Framework id for this space, as the decimal long string
     * the gservices provider hands out. Another strong cross-app device
     * identifier: left alone, every space reports the host's single value.
     */
    public String getGsfId(int userId) {
        synchronized (lock) {
            Properties identity = readOrCreate(userId);
            return identity.getProperty(GSF_ID);
        }
    }

    /** Build serial reported to this space. */
    public String getSerial(int userId) {
        synchronized (lock) {
            Properties identity = readOrCreate(userId);
            return identity.getProperty(SERIAL);
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

    /**
     * Widevine exposes a stable per-device value through
     * {@code MediaDrm.getPropertyByteArray("deviceUniqueId")}. Left alone it is
     * the same in every space, so it links all cloned accounts back to one
     * device. Each space gets its own value here, derived from a persisted seed
     * so it survives restarts. Only the identifier properties are replaced; the
     * rest of MediaDrm (security level, HDCP, provisioning) is untouched, so DRM
     * playback keeps working.
     *
     * @param length byte count the real device reported, so the replacement
     *               keeps the shape callers expect. Values below one fall back
     *               to the 32 bytes Widevine uses on current devices.
     */
    public byte[] getWidevineDeviceId(int userId, int length) {
        synchronized (lock) {
            Properties identity = readOrCreate(userId);
            String seed = identity.getProperty(WIDEVINE_SEED);
            return deriveBytes(seed, length < 1 ? WIDEVINE_ID_LENGTH : length);
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
        if (!isAndroidId(properties.getProperty(ANDROID_ID))) {
            byte[] raw = new byte[ANDROID_ID_HEX_LENGTH / 2];
            RANDOM.nextBytes(raw);
            properties.setProperty(ANDROID_ID, toHex(raw));
            changed = true;
        }
        if (!isDecimalLong(properties.getProperty(GSF_ID))) {
            // Positive 63-bit value: the gservices provider hands this out as a
            // signed decimal, and callers routinely parse it with Long.parseLong.
            long gsf = Math.abs(RANDOM.nextLong() | 1L);
            properties.setProperty(GSF_ID, Long.toString(gsf));
            changed = true;
        }
        if (!isAndroidId(properties.getProperty(SERIAL))) {
            byte[] rawSerial = new byte[ANDROID_ID_HEX_LENGTH / 2];
            RANDOM.nextBytes(rawSerial);
            properties.setProperty(SERIAL, toHex(rawSerial).toUpperCase());
            changed = true;
        }
        if (properties.getProperty(APP_SET_SEED) == null) {
            byte[] seed = new byte[32];
            RANDOM.nextBytes(seed);
            properties.setProperty(APP_SET_SEED, toHex(seed));
            changed = true;
        }
        if (properties.getProperty(WIDEVINE_SEED) == null) {
            byte[] seed = new byte[32];
            RANDOM.nextBytes(seed);
            properties.setProperty(WIDEVINE_SEED, toHex(seed));
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

    private static boolean isDecimalLong(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static boolean isAndroidId(String value) {
        if (value == null || value.length() != ANDROID_ID_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
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

    private static byte[] deriveBytes(String seed, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = new byte[length];
            int written = 0;
            int counter = 0;
            while (written < length) {
                digest.reset();
                String block = seed + " widevine " + counter;
                byte[] hash = digest.digest(block.getBytes("UTF-8"));
                int copy = Math.min(hash.length, length - written);
                System.arraycopy(hash, 0, result, written, copy);
                written += copy;
                counter++;
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to derive Widevine device ID", error);
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
