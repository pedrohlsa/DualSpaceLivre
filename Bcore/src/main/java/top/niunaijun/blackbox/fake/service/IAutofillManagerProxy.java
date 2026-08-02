package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.view.BRIAutoFillManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.proxy.ProxyManifest;


public class IAutofillManagerProxy extends BinderInvocationStub {
    public static final String TAG = "AutofillManagerStub";

    private static ComponentName hostComponent() {
        return new ComponentName(
                BlackBoxCore.getHostPkg(),
                ProxyManifest.getProxyActivity(BlackBoxCore.getAppPid())
        );
    }

    private static void replaceComponents(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof ComponentName) {
                args[i] = hostComponent();
            }
        }
    }

    private static void replaceFirstInteger(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                args[i] = BlackBoxCore.getHostUserId();
                return;
            }
        }
    }

    private static void replaceLastInteger(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Integer) {
                args[i] = BlackBoxCore.getHostUserId();
                return;
            }
        }
    }

    private static void replaceUserAfterComponent(Object[] args) {
        if (args == null) {
            return;
        }
        int componentIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof ComponentName) {
                args[i] = hostComponent();
                componentIndex = i;
            }
        }
        if (componentIndex >= 0) {
            for (int i = componentIndex + 1; i < args.length; i++) {
                if (args[i] instanceof Integer) {
                    args[i] = BlackBoxCore.getHostUserId();
                    return;
                }
            }
        }
        replaceLastInteger(args);
    }

    public IAutofillManagerProxy() {
        super(BRServiceManager.get().getService("autofill"));
    }

    @Override
    protected Object getWho() {
        return BRIAutoFillManagerStub.get().asInterface(BRServiceManager.get().getService("autofill"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("autofill");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("addClient")
    public static class AddClient extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Android's AutofillManager waits synchronously for this call.
            // A guest reports its virtual user (normally 0), but the system
            // service must receive the real Android profile that owns the
            // host process or it never answers the result receiver.
            replaceUserAfterComponent(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("startSession")
    public static class StartSession extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            replaceComponents(args);
            // userId is the first integer in startSession; later integers are
            // session flags and must not be modified.
            replaceFirstInteger(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethods({
            "removeClient",
            "setAuthenticationResult",
            "setHasCallback",
            "finishSession",
            "cancelSession",
            "disableOwnedAutofillServices",
            "isServiceSupported",
            "isServiceEnabled"
    })
    public static class ReplaceTrailingUserId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            replaceLastInteger(args);
            if ("isServiceEnabled".equals(method.getName()) && args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof String) {
                        args[i] = BlackBoxCore.getHostPkg();
                    }
                }
            }
            return method.invoke(who, args);
        }
    }
}
