package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Gives every space its own corner of the Android keystore.
 *
 * Keystore entries are scoped to a uid and every space runs under the host's one
 * uid, so all of them share a single namespace. Instagram encrypts its session
 * token with a key stored under a fixed alias carrying no account or space
 * component — {@code AuthHeaderPrefs_single}, read off the device. A login in one
 * space therefore overwrites the key every other space needs; the next space to
 * start decrypts its own ciphertext with the wrong key, authenticated decryption
 * fails, and the app discards the session without ever reaching the network:
 *
 * <pre>
 * E/keystore2: In finish: KeyMint::finish failed. Error::Km(ErrorCode(-30))
 * E/keystore2::gc: Trying to invalidate key.
 * </pre>
 *
 * Prefixing the alias with the space id makes the namespaces disjoint.
 *
 * <b>This sits on the hot path of every cryptographic operation the guest
 * performs</b>, and a first version that allocated a {@link Proxy} per
 * {@code getSecurityLevel} and compared class names by string made the app
 * unusably slow. Everything here is therefore built to cost a type check when the
 * argument is not a key descriptor, which is almost every call: the descriptor
 * class, the alias field and this space's prefix are resolved once, and the
 * security-level wrapper is built once and reused.
 *
 * Lookups fall back to the unprefixed alias when the prefixed one does not exist
 * yet, so a session created before this hook keeps working and migrates the next
 * time its key is written. Writes and deletes are always prefixed — a space must
 * never be able to destroy another one's key, which is the whole bug.
 *
 * Everything fails open: if a field moves between releases the call is forwarded
 * untouched, because a keystore that rejects work breaks far more than it fixes.
 */
public class IKeystoreServiceProxy extends BinderInvocationStub {
    public static final String TAG = "KeystoreProxy";
    private static final String SERVICE_NAME = "android.system.keystore2.IKeystoreService/default";
    private static final String SERVICE_INTERFACE = "android.system.keystore2.IKeystoreService";
    private static final String SECURITY_LEVEL_INTERFACE =
            "android.system.keystore2.IKeystoreSecurityLevel";
    private static final String KEY_DESCRIPTOR = "android.system.keystore2.KeyDescriptor";
    /** {@code ResponseCode.KEY_NOT_FOUND}: the alias simply is not there yet. */
    private static final int KEY_NOT_FOUND = 7;

    private static volatile boolean sResolved;
    private static volatile Class<?> sDescriptorClass;
    private static volatile Field sAliasField;
    private static volatile String sPrefix;
    private static volatile Object sLevelTarget;
    private static volatile Object sLevelProxy;
    private static boolean sLogged;
    /**
     * Aliases that exist under neither name.
     *
     * The fallback below costs a second binder round trip, and an app checks for
     * keys it never created constantly — {@code KeyStore.containsAlias} on the
     * main thread, over and over. Paying twice for every one of those is what
     * blocked the guest's main thread hard enough for Instagram's own detector to
     * report it (Skipped 103 frames, MT_BLOCKED). Remembering the misses keeps the
     * migration retry for the one case it is for: a key stored before this hook
     * existed. Cleared whenever a key is created, since that makes misses stale.
     */
    private static final java.util.Set<String> sKnownMissing =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final int MISS_CACHE_LIMIT = 256;

    public IKeystoreServiceProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE_NAME);
        if (binder == null) {
            return null;
        }
        try {
            Class<?> stub = Class.forName(SERVICE_INTERFACE + "$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable error) {
            Slog.w(TAG, "Keystore service is not reachable, leaving it alone", error);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /** Resolves the reflection once; every later call is a field read. */
    private static void resolve() {
        if (sResolved) {
            return;
        }
        synchronized (IKeystoreServiceProxy.class) {
            if (sResolved) {
                return;
            }
            try {
                Class<?> descriptor = Class.forName(KEY_DESCRIPTOR);
                Field alias = descriptor.getDeclaredField("alias");
                alias.setAccessible(true);
                sDescriptorClass = descriptor;
                sAliasField = alias;
            } catch (Throwable ignored) {
                sDescriptorClass = null;
                sAliasField = null;
            }
            int userId = BActivityThread.getUserId();
            sPrefix = userId < 0 ? null : "bx" + userId + "_";
            sResolved = true;
        }
    }

    private static boolean active() {
        resolve();
        return sDescriptorClass != null && sAliasField != null && sPrefix != null;
    }

    /**
     * Stamps this space's prefix on every alias heading into the daemon and
     * returns what was there before, so a caller can retry unprefixed.
     *
     * @return the original aliases by argument index, or null if nothing changed.
     */
    static String[] scopeArgs(Object[] args) {
        if (args == null || args.length == 0 || !active()) {
            return null;
        }
        Class<?> descriptor = sDescriptorClass;
        String prefix = sPrefix;
        String[] originals = null;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null || !descriptor.isInstance(arg)) {
                continue;
            }
            try {
                Object value = sAliasField.get(arg);
                if (!(value instanceof String) || ((String) value).startsWith(prefix)) {
                    continue;
                }
                if (originals == null) {
                    originals = new String[args.length];
                }
                originals[i] = (String) value;
                sAliasField.set(arg, prefix + value);
                if (!sLogged) {
                    sLogged = true;
                    Slog.d(TAG, "keystore alias scoped to space: " + value);
                }
            } catch (Throwable ignored) {
                // Forward untouched rather than fail the call.
            }
        }
        return originals;
    }

    /** Puts the original aliases back, for the one retry after KEY_NOT_FOUND. */
    private static void restoreArgs(Object[] args, String[] originals) {
        for (int i = 0; i < args.length; i++) {
            if (originals[i] == null) {
                continue;
            }
            try {
                sAliasField.set(args[i], originals[i]);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String firstAlias(String[] originals) {
        for (String alias : originals) {
            if (alias != null) {
                return alias;
            }
        }
        return null;
    }

    private static boolean isKeyNotFound(Throwable error) {
        // Matched by name: ServiceSpecificException is not on this module's
        // compile classpath, and this only runs on the error path anyway.
        if (error == null
                || !"android.os.ServiceSpecificException".equals(error.getClass().getName())) {
            return false;
        }
        try {
            return error.getClass().getField("errorCode").getInt(error) == KEY_NOT_FOUND;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Reflective calls wrap whatever the service threw, and the keystore throws
     * for ordinary reasons — {@code getKeyEntry} raises KEY_NOT_FOUND for any key
     * the app has not created yet. Rethrowing the wrapper produces
     * UndeclaredThrowableException, undeclared on the interface, which killed the
     * guest outright the first time this was attempted. Always unwrap.
     */
    static Object callUnwrapped(Object who, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(who, args);
        } catch (InvocationTargetException error) {
            throw error.getCause() == null ? error : error.getCause();
        }
    }

    /**
     * {@code generateKey} and {@code createOperation} live on the security level,
     * whose binder {@code getSecurityLevel} hands back — the only place the engine
     * sees it. Built once and reused: this is called for every operation, and
     * allocating a proxy each time is what made the first version unusable.
     */
    static Object wrapSecurityLevel(Object level) {
        if (level == null || !active()) {
            return level;
        }
        if (level == sLevelTarget) {
            return sLevelProxy;
        }
        try {
            final Object target = level;
            Class<?> iface = Class.forName(SECURITY_LEVEL_INTERFACE);
            if (!iface.isInstance(target)) {
                return level;
            }
            Object wrapped = Proxy.newProxyInstance(iface.getClassLoader(),
                    new Class<?>[]{iface}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            scopeArgs(args);
                            // Creating or importing a key makes remembered misses
                            // stale, so the retry has to become available again.
                            sKnownMissing.clear();
                            return callUnwrapped(target, method, args);
                        }
                    });
            sLevelTarget = target;
            sLevelProxy = wrapped;
            return wrapped;
        } catch (Throwable error) {
            Slog.w(TAG, "Could not scope the keystore security level", error);
            return level;
        }
    }

    /** Writes and deletes: always this space's alias, never the shared one. */
    @ProxyMethods({"deleteKey", "updateSubcomponent", "grant", "ungrant"})
    public static class ScopeAlias extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            scopeArgs(args);
            return callUnwrapped(who, method, args);
        }
    }

    /**
     * Lookups: this space's alias first, then the unprefixed one, so a session
     * stored before this hook existed keeps working instead of being thrown away.
     */
    @ProxyMethods({"getKeyEntry", "getKeyEntryMetadata"})
    public static class LookupAlias extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String[] originals = scopeArgs(args);
            String probed = originals == null ? null : firstAlias(originals);
            if (probed != null && sKnownMissing.contains(probed)) {
                // Known absent under both names: answer from the first call alone.
                return callUnwrapped(who, method, args);
            }
            try {
                return callUnwrapped(who, method, args);
            } catch (Throwable error) {
                if (originals == null || !isKeyNotFound(error)) {
                    throw error;
                }
                restoreArgs(args, originals);
                try {
                    return callUnwrapped(who, method, args);
                } catch (Throwable second) {
                    if (probed != null && isKeyNotFound(second)
                            && sKnownMissing.size() < MISS_CACHE_LIMIT) {
                        sKnownMissing.add(probed);
                    }
                    throw second;
                }
            }
        }
    }

    @ProxyMethods({"getSecurityLevel"})
    public static class GetSecurityLevel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return wrapSecurityLevel(callUnwrapped(who, method, args));
        }
    }
}
