package top.niunaijun.blackbox.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Lets a cloned app talk to Play Services under a name Play Services accepts.
 *
 * Binding to GMS succeeds; the call right after it is what fails. The client
 * takes the broker binder it was handed and calls {@code getService(callback,
 * GetServiceRequest)}, and the request carries the caller's own package name.
 * GMS resolves the binder's calling uid — the host's, because that is the
 * process the guest really runs in — and refuses:
 *
 * <pre>
 * E/GoogleApiManager: Failed to get service from broker.
 * java.lang.SecurityException: Unknown calling package name 'com.instagram.android'.
 * W/GCM: Invalid caller: com.instagram.android 1110304
 * </pre>
 *
 * which is why push registration never completes inside a space and
 * {@code IgFcmTokenRegistrar} only ever reports {@code SERVICE_NOT_AVAILABLE}.
 *
 * Nothing in {@code fake/service} can see this: it is a direct binder call to
 * the Play Services app, not a system service, so it never passes through
 * ServiceManager. ({@code GmsProxy} aimed at the right interface but bound to a
 * ServiceManager entry named "gms" that does not exist.) The interception has to
 * happen where the binder is delivered — here.
 *
 * The binder is wrapped rather than the parcel rewritten. Its
 * {@code queryLocalInterface} answers with a proxy of the broker interface, so
 * {@code IGmsServiceBroker.Stub.asInterface} hands the client that proxy instead
 * of building a parcel-level one, and the request arrives as a live object whose
 * package field can simply be reassigned. No transaction-code or parcel-layout
 * assumptions, which would not survive a GMS update.
 */
public class GmsBrokerDelegate extends IServiceConnection.Stub {
    private static final String TAG = "GmsBrokerDelegate";
    private static final String BROKER_INTERFACE =
            "com.google.android.gms.common.internal.IGmsServiceBroker";
    /** Older builds of the client library used this package for the interface. */
    private static final String BROKER_INTERFACE_LEGACY =
            "com.google.android.gms.common.api.internal.IGmsServiceBroker";

    private final IServiceConnection mBase;
    private final ComponentName mComponent;

    private GmsBrokerDelegate(IServiceConnection base, ComponentName component) {
        this.mBase = base;
        this.mComponent = component;
    }

    /** True when this bind is heading for Play Services. */
    public static boolean handles(Intent intent) {
        if (intent == null) {
            return false;
        }
        String pkg = intent.getPackage();
        if (pkg == null && intent.getComponent() != null) {
            pkg = intent.getComponent().getPackageName();
        }
        return "com.google.android.gms".equals(pkg);
    }

    public static IServiceConnection wrap(IServiceConnection base, Intent intent) {
        if (base == null) {
            return null;
        }
        return new GmsBrokerDelegate(base, intent == null ? null : intent.getComponent());
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        IBinder delivered = service == null ? null : wrapBroker(service);
        if (BuildCompat.isOreo()) {
            BRIServiceConnectionO.get(mBase).connected(
                    mComponent != null ? mComponent : name, delivered, dead);
        } else {
            mBase.connected(name, delivered);
        }
    }

    private IBinder wrapBroker(final IBinder real) {
        final Class<?> brokerInterface = findBrokerInterface();
        if (brokerInterface == null) {
            return real;
        }
        try {
            final ClassLoader loader = brokerInterface.getClassLoader();
            final String descriptor = brokerInterface.getName();
            return (IBinder) Proxy.newProxyInstance(loader,
                    new Class[]{IBinder.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("queryLocalInterface".equals(method.getName())
                                    && args != null && args.length == 1
                                    && descriptor.equals(args[0])) {
                                return brokerProxy(loader, brokerInterface, real);
                            }
                            return method.invoke(real, args);
                        }
                    });
        } catch (Throwable error) {
            Slog.w(TAG, "Unable to wrap the Play Services broker, passing it through", error);
            return real;
        }
    }

    /**
     * A broker that rewrites the caller's package before forwarding.
     *
     * The real interface is reached through {@code Stub.asInterface} on the
     * untouched binder, so the call still leaves the process normally.
     */
    private Object brokerProxy(ClassLoader loader, final Class<?> brokerInterface, IBinder real) {
        final Object realBroker = asRealBroker(loader, brokerInterface, real);
        if (realBroker == null) {
            return null;
        }
        return Proxy.newProxyInstance(loader, new Class[]{brokerInterface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (args != null) {
                            for (Object arg : args) {
                                rewriteCallingPackage(arg);
                            }
                        }
                        return method.invoke(realBroker, args);
                    }
                });
    }

    /**
     * Builds the real broker interface over the untouched binder.
     *
     * The client library is obfuscated, so the stub is not reliably
     * {@code <interface>$Stub}: it is found by looking for whatever class
     * exposes a static {@code asInterface(IBinder)} for this interface.
     */
    private static Object asRealBroker(ClassLoader loader, Class<?> brokerInterface, IBinder real) {
        for (Class<?> candidate : brokerInterface.getDeclaredClasses()) {
            Object broker = tryAsInterface(candidate, brokerInterface, real);
            if (broker != null) {
                return broker;
            }
        }
        for (String suffix : new String[]{"$Stub", "$a", "$zza"}) {
            try {
                Class<?> candidate = Class.forName(brokerInterface.getName() + suffix, false, loader);
                Object broker = tryAsInterface(candidate, brokerInterface, real);
                if (broker != null) {
                    return broker;
                }
            } catch (Throwable ignored) {
            }
        }
        Slog.w(TAG, "No asInterface found for " + brokerInterface.getName()
                + "; leaving the broker untouched");
        return null;
    }

    private static Object tryAsInterface(Class<?> candidate, Class<?> brokerInterface, IBinder real) {
        if (candidate == null) {
            return null;
        }
        for (Method method : candidate.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != IBinder.class) {
                continue;
            }
            if (!brokerInterface.isAssignableFrom(method.getReturnType())
                    && method.getReturnType() != Object.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object broker = method.invoke(null, real);
                if (broker != null && brokerInterface.isInstance(broker)) {
                    Slog.d(TAG, "broker resolved via " + candidate.getName() + "." + method.getName());
                    return broker;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Replaces the guest package wherever the request carries it.
     *
     * The field names in {@code GetServiceRequest} are obfuscated and move
     * between GMS releases, so the value is matched instead of the name: any
     * String field holding this guest's package becomes the host's, which is the
     * package the calling uid genuinely owns.
     */
    private void rewriteCallingPackage(Object request) {
        if (request == null) {
            return;
        }
        String guestPkg = BActivityThread.getAppPackageName();
        if (guestPkg == null) {
            return;
        }
        String hostPkg = BlackBoxCore.getHostPkg();
        for (Class<?> type = request.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (guestPkg.equals(field.get(request))) {
                        field.set(request, hostPkg);
                        Slog.d(TAG, "broker request: " + guestPkg + " -> " + hostPkg
                                + " (" + type.getSimpleName() + "." + field.getName() + ")");
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Class<?> findBrokerInterface() {
        ClassLoader loader = BActivityThread.getApplication() != null
                ? BActivityThread.getApplication().getClassLoader()
                : GmsBrokerDelegate.class.getClassLoader();
        for (String name : new String[]{BROKER_INTERFACE, BROKER_INTERFACE_LEGACY}) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
