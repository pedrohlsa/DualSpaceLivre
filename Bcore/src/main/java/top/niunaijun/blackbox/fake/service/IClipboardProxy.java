package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.content.BRIClipboardStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Lets a cloned app use the system clipboard.
 *
 * Every clipboard call carries the caller's package name, and since Android 10
 * the service also checks that the caller currently holds input focus. A guest
 * passes its own package name ("com.instagram.android") while actually running
 * under the host's uid, so {@code AppOpsManager.checkPackage} rejects the call
 * and copy/paste silently does nothing inside a space.
 *
 * Rewriting the package name to the host makes both checks line up: the focused
 * window really does belong to the host, because the guest draws inside a
 * {@code ProxyActivity}. The attribution tag is dropped for the same reason —
 * the guest's tag is not declared by the host package and AppOps rejects tags it
 * cannot find. The trailing user id becomes the physical host user, like every
 * other system-service hook here.
 */
public class IClipboardProxy extends BinderInvocationStub {
    private static final String CLIPBOARD = Context.CLIPBOARD_SERVICE;

    public IClipboardProxy() {
        super(BRServiceManager.get().getService(CLIPBOARD));
    }

    @Override
    protected Object getWho() {
        return BRIClipboardStub.get().asInterface(BRServiceManager.get().getService(CLIPBOARD));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(CLIPBOARD);
        Slog.d(TAG, "clipboard hook installed");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * Every method on {@code IClipboard} ends with
     * {@code (..., String callingPackage, String attributionTag, int userId)}
     * on the API levels this engine supports, so the arguments are located by
     * type rather than by a per-method index.
     */
    @ProxyMethods({"getPrimaryClip", "setPrimaryClip", "clearPrimaryClip",
            "hasPrimaryClip", "getPrimaryClipDescription", "hasClipboardText",
            "getPrimaryClipSource",
            "addPrimaryClipChangedListener", "removePrimaryClipChangedListener"})
    public static class RewriteCaller extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null) {
                boolean packageReplaced = false;
                for (int i = 0; i < args.length; i++) {
                    if (!(args[i] instanceof String)) {
                        continue;
                    }
                    if (!packageReplaced) {
                        args[i] = BlackBoxCore.getHostPkg();
                        packageReplaced = true;
                    } else {
                        // attributionTag: the host never declared the guest's.
                        args[i] = null;
                    }
                }
                for (int i = args.length - 1; i >= 0; i--) {
                    if (args[i] instanceof Integer) {
                        args[i] = BlackBoxCore.getHostUserId();
                        break;
                    }
                }
            }
            try {
                Object result = method.invoke(who, args);
                Slog.d(TAG, method.getName() + " -> "
                        + (result == null ? "null" : result.getClass().getSimpleName()));
                return result;
            } catch (Throwable error) {
                Slog.w(TAG, method.getName() + " was refused", error);
                throw error;
            }
        }
    }
}
