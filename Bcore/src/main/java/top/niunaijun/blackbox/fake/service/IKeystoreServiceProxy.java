package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
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
 * Keystore entries are scoped to a uid, and every space runs under the host's
 * single uid, so all of them share one namespace. An app that stores a key under
 * a fixed alias — Instagram encrypts its session token with one — therefore
 * overwrites the key belonging to every other space the moment it logs in. The
 * next space to start reads its own ciphertext with the wrong key and the
 * authenticated decryption fails, so the app throws the session away without ever
 * reaching the network.
 *
 * That is what the daemon reported while a space started on 2026-08-14:
 *
 * <pre>
 * E/keystore2: In KeystoreOperation::finish
 *   Caused by: In finish: KeyMint::finish failed.
 *              Error::Km(ErrorCode(-30))
 * E/keystore2::gc: Trying to invalidate key.
 * </pre>
 *
 * Prefixing the alias with the space id makes the namespaces disjoint, so a login
 * in one space can no longer destroy the credentials of another. The rewrite has
 * to be symmetric — the same alias goes out on generate/import and comes back on
 * lookup — and {@code listEntries} has to hide the other spaces' entries and strip
 * the prefix again, otherwise an app enumerating its own keys would see aliases it
 * never created.
 *
 * Everything here fails open: if a field cannot be found, or the interface moved
 * between releases, the call is forwarded untouched. A keystore that rejects work
 * breaks far more than it fixes.
 */
public class IKeystoreServiceProxy extends BinderInvocationStub {
    public static final String TAG = "KeystoreProxy";
    /** Keystore2 registers under this exact name from Android 12 onwards. */
    private static final String SERVICE_NAME = "android.system.keystore2.IKeystoreService/default";
    private static final String SERVICE_INTERFACE = "android.system.keystore2.IKeystoreService";
    private static final String SECURITY_LEVEL_INTERFACE =
            "android.system.keystore2.IKeystoreSecurityLevel";
    private static final String KEY_DESCRIPTOR = "android.system.keystore2.KeyDescriptor";

    private static final java.util.concurrent.atomic.AtomicInteger sScoped =
            new java.util.concurrent.atomic.AtomicInteger();

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

    /** The prefix a given space stamps on every alias it owns. */
    private static String prefix() {
        int userId = BActivityThread.getUserId();
        return userId < 0 ? null : "bx" + userId + "_";
    }

    private static Field aliasField(Object descriptor) throws NoSuchFieldException {
        Field field = descriptor.getClass().getDeclaredField("alias");
        field.setAccessible(true);
        return field;
    }

    private static boolean isDescriptor(Object value) {
        return value != null && KEY_DESCRIPTOR.equals(value.getClass().getName());
    }


    /**
     * Reflective calls wrap whatever the service threw, and the keystore throws
     * for perfectly ordinary reasons — {@code getKeyEntry} raises
     * ServiceSpecificException(KEY_NOT_FOUND) every time an app asks about a key
     * it has not created yet. Rethrowing the wrapper instead of the cause turns
     * that into UndeclaredThrowableException, which is undeclared on the
     * interface and kills the process. Always unwrap.
     */
    static Object callUnwrapped(Object who, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(who, args);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw error.getCause() == null ? error : error.getCause();
        }
    }

    /** Stamps this space's prefix on every alias travelling into the daemon. */
    static void scopeArgs(Object[] args) {
        String prefix = prefix();
        if (prefix == null || args == null) {
            return;
        }
        for (Object arg : args) {
            if (!isDescriptor(arg)) {
                continue;
            }
            try {
                Field field = aliasField(arg);
                Object alias = field.get(arg);
                if (alias instanceof String && !((String) alias).startsWith(prefix)) {
                    field.set(arg, prefix + alias);
                    // Proof of life: this fork has shipped seventeen proxies that
                    // hooked nothing, so a hook that never logs is not trusted here.
                    if (sScoped.incrementAndGet() <= 8) {
                        Slog.d(TAG, "keystore alias scoped to space: " + alias);
                    }
                }
            } catch (Throwable ignored) {
                // Forward untouched rather than fail the call.
            }
        }
    }

    /**
     * Hides the other spaces' entries and gives this space its aliases back
     * unprefixed, so an app enumerating its keys sees exactly what it stored.
     */
    static Object unscopeResult(Object result) {
        String prefix = prefix();
        if (prefix == null || !(result instanceof Object[])) {
            return result;
        }
        Object[] entries = (Object[]) result;
        java.util.List<Object> mine = new java.util.ArrayList<>(entries.length);
        for (Object entry : entries) {
            if (!isDescriptor(entry)) {
                mine.add(entry);
                continue;
            }
            try {
                Field field = aliasField(entry);
                Object alias = field.get(entry);
                if (alias instanceof String && ((String) alias).startsWith(prefix)) {
                    field.set(entry, ((String) alias).substring(prefix.length()));
                    mine.add(entry);
                }
            } catch (Throwable ignored) {
                mine.add(entry);
            }
        }
        Object[] filtered = (Object[]) java.lang.reflect.Array.newInstance(
                entries.getClass().getComponentType(), mine.size());
        return mine.toArray(filtered);
    }

    /**
     * {@code generateKey} and {@code createOperation} live on the security level,
     * not on this interface, and that binder is handed back by
     * {@code getSecurityLevel}. Wrapping it here is the only place the engine sees
     * it — miss this and keys would be written unprefixed and read prefixed.
     */
    static Object wrapSecurityLevel(Object securityLevel) {
        if (securityLevel == null) {
            return null;
        }
        try {
            final Object target = securityLevel;
            Class<?> iface = Class.forName(SECURITY_LEVEL_INTERFACE);
            if (!iface.isInstance(target)) {
                return securityLevel;
            }
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            scopeArgs(args);
                            return callUnwrapped(target, method, args);
                        }
                    });
        } catch (Throwable error) {
            Slog.w(TAG, "Could not scope the keystore security level", error);
            return securityLevel;
        }
    }

    @ProxyMethods({"getKeyEntry", "getKeyEntryMetadata", "deleteKey", "updateSubcomponent",
            "grant", "ungrant"})
    public static class ScopeAlias extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            scopeArgs(args);
            return callUnwrapped(who, method, args);
        }
    }

    @ProxyMethods({"getSecurityLevel"})
    public static class GetSecurityLevel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return wrapSecurityLevel(callUnwrapped(who, method, args));
        }
    }

    @ProxyMethods({"listEntries"})
    public static class ListEntries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return unscopeResult(callUnwrapped(who, method, args));
        }
    }
}
