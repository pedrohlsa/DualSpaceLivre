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
 * Maps out why a cloned app cannot register for push, and gets as far as the
 * object that decides it. <b>It does not fix anything yet.</b>
 *
 * Binding to Play Services succeeds; the call right after it is what fails. The
 * client calls {@code getService(callback, GetServiceRequest)} and the request
 * carries its own package name. GMS resolves the binder's calling uid — the
 * host's, because that is the process the guest really runs in — and refuses:
 *
 * <pre>
 * E/GoogleApiManager: Failed to get service from broker.
 * java.lang.SecurityException: Unknown calling package name 'com.instagram.android'.
 * W/GCM: Invalid caller: com.instagram.android 1110304
 * </pre>
 *
 * so {@code IgFcmTokenRegistrar} only ever reports {@code SERVICE_NOT_AVAILABLE}.
 *
 * Nothing under {@code fake/service} can see this: it is a direct binder call to
 * the Play Services app, never a system service, so it does not pass through
 * ServiceManager. ({@code GmsProxy} aimed at the right interface but bound to a
 * ServiceManager entry named "gms" that does not exist.) The delivery of the
 * binder — here — is the first point the engine controls.
 *
 * What was established on device, so the next attempt does not repeat it:
 * <ul>
 *   <li>The app-side {@code ServiceConnection} is reachable from the framework's
 *       inner connection, and {@code BaseGmsClient} keeps its real name under
 *       obfuscation, so the client instance <i>can</i> be found — but only by
 *       walking collections, since the supervisor holds it in a map.</li>
 *   <li>That client carries <b>no String field at all</b>. The package is not
 *       stored; it is computed when the request is built, from the client's
 *       {@code Context}. Patching a field therefore cannot work.</li>
 *   <li>Wrapping the binder and answering {@code queryLocalInterface} does fire
 *       at the right moment, but the guest's {@code IGmsServiceBroker} is
 *       obfuscated with no reachable {@code asInterface}, so the real interface
 *       cannot be rebuilt to forward through.</li>
 * </ul>
 *
 * The remaining directions are to make the Context that the GMS client reads
 * answer with the host package, or to rewrite the request at the parcel level —
 * the latter is unsafe, because the transaction carries a binder and a
 * reassembled parcel would break it.
 */
public class GmsBrokerDelegate extends IServiceConnection.Stub {
    private static final String TAG = "GmsBrokerDelegate";
    private static final String BROKER_INTERFACE =
            "com.google.android.gms.common.internal.IGmsServiceBroker";
    /** Older builds of the client library used this package for the interface. */
    private static final String BROKER_INTERFACE_LEGACY =
            "com.google.android.gms.common.api.internal.IGmsServiceBroker";
    /** Survives obfuscation, so the client can be found by type. */
    private static final String BASE_GMS_CLIENT =
            "com.google.android.gms.common.internal.BaseGmsClient";

    private static boolean sWarned;

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
        // Fix the client before it is told the service is up: the very next
        // thing it does is build a GetServiceRequest carrying its package name.
        patchGmsClientPackage();
        if (BuildCompat.isOreo()) {
            BRIServiceConnectionO.get(mBase).connected(
                    mComponent != null ? mComponent : name, service, dead);
        } else {
            mBase.connected(name, service);
        }
    }

    /**
     * Rewrites the package that {@code BaseGmsClient} will announce.
     *
     * The connection Play Services hands back is the client's own
     * {@code ServiceConnection}, which holds the {@code BaseGmsClient} that
     * builds the request — and while the app is obfuscated,
     * {@code com.google.android.gms.common.internal.BaseGmsClient} keeps its real
     * name, so the field can be found by type instead of by name. Patching the
     * package there fixes every request the client makes, without touching the
     * binder, the transaction codes or the parcel layout.
     */
    private void patchGmsClientPackage() {
        try {
            Object appConnection = appConnectionOf(mBase);
            if (appConnection == null) {
                return;
            }
            Object gmsClient = findByType(appConnection, BASE_GMS_CLIENT);
            if (gmsClient == null) {
                return;
            }
            if (!rewriteCallingPackage(gmsClient) && !sWarned) {
                sWarned = true;
                Slog.d(TAG, "Play Services client " + gmsClient.getClass().getName()
                        + " stores no package to rewrite; push stays unregistered");
            }
        } catch (Throwable error) {
            Slog.w(TAG, "Unable to patch the Play Services client package", error);
        }
    }

    /** The app's own ServiceConnection, behind the framework's inner connection. */
    private static Object appConnectionOf(IServiceConnection framework) {
        try {
            java.lang.ref.WeakReference<?> ref =
                    black.android.app.BRLoadedApkServiceDispatcherInnerConnection
                            .get(framework).mDispatcher();
            Object dispatcher = ref == null ? null : ref.get();
            if (dispatcher == null) {
                return null;
            }
            return black.android.app.BRLoadedApkServiceDispatcher.get(dispatcher).mConnection();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Finds an instance of {@code typeName} reachable from {@code holder}.
     *
     * The client is not always a direct field of the connection — obfuscation
     * moves it behind a supervisor or a holder object — so this walks a couple
     * of levels, skipping the framework and collection types that would make
     * the search explode.
     */
    private static Object findByType(Object holder, String typeName) {
        return findByType(holder, typeName, 5, new java.util.IdentityHashMap<Object, Boolean>());
    }

    private static Object findByType(Object holder, String typeName, int depth,
                                     java.util.IdentityHashMap<Object, Boolean> seen) {
        if (holder == null || depth < 0 || seen.put(holder, Boolean.TRUE) != null) {
            return null;
        }
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (typeName.equals(c.getName())) {
                return holder;
            }
        }
        if (depth == 0) {
            return null;
        }
        // The supervisor keeps its per-service connections in a map, and the
        // connection that owns the client sits inside it, so collections have to
        // be walked too — skipping them is why the client stayed out of reach.
        if (holder instanceof java.util.Map) {
            for (Object value : ((java.util.Map<?, ?>) holder).values()) {
                Object found = findByType(value, typeName, depth - 1, seen);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (holder instanceof Iterable) {
            for (Object value : (Iterable<?>) holder) {
                Object found = findByType(value, typeName, depth - 1, seen);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        for (Class<?> type = holder.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || field.getType().isArray()) {
                    continue;
                }
                String fieldType = field.getType().getName();
                if (fieldType.startsWith("android.")
                        || (fieldType.startsWith("java.") && !fieldType.startsWith("java.util."))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object found = findByType(field.get(holder), typeName, depth - 1, seen);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                }
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
    private boolean rewriteCallingPackage(Object request) {
        if (request == null) {
            return false;
        }
        String guestPkg = BActivityThread.getAppPackageName();
        if (guestPkg == null) {
            return false;
        }
        boolean patched = false;
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
                        patched = true;
                        Slog.d(TAG, "broker request: " + guestPkg + " -> " + hostPkg
                                + " (" + type.getSimpleName() + "." + field.getName() + ")");
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return patched;
    }



}
