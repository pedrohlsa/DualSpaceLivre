package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.app.trust.BRITrustManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;

/** Keeps KeyguardManager calls inside the physical Android user hosting the sandbox. */
public class ITrustManagerProxy extends BinderInvocationStub {
    private static final String TRUST_SERVICE = "trust";

    public ITrustManagerProxy() {
        super(BRServiceManager.get().getService(TRUST_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRITrustManagerStub.get().asInterface(
                BRServiceManager.get().getService(TRUST_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(TRUST_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethods({"isDeviceLocked", "isDeviceSecure"})
    public static class ReplaceUserId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null) {
                for (int i = args.length - 1; i >= 0; i--) {
                    if (args[i] instanceof Integer) {
                        args[i] = BlackBoxCore.getHostUserId();
                        break;
                    }
                }
            }
            return method.invoke(who, args);
        }
    }
}
