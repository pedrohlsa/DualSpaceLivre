package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.com.android.internal.textservice.BRITextServicesManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;

/** Routes spell-checker/text-service requests to the Android user hosting the sandbox. */
public class ITextServicesManagerProxy extends BinderInvocationStub {
    private static final String TEXT_SERVICES = "textservices";

    public ITextServicesManagerProxy() {
        super(BRServiceManager.get().getService(TEXT_SERVICES));
    }

    @Override
    protected Object getWho() {
        return BRITextServicesManagerStub.get().asInterface(
                BRServiceManager.get().getService(TEXT_SERVICES));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(TEXT_SERVICES);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethods({"getCurrentSpellChecker", "getCurrentSpellCheckerSubtype",
            "getSpellCheckerService", "finishSpellCheckerService",
            "isSpellCheckerEnabled", "getEnabledSpellCheckers",
            "getEnabledSpellCheckerInfos"})
    public static class ReplaceUserId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
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
