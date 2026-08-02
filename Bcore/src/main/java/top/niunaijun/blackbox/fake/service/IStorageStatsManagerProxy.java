package top.niunaijun.blackbox.fake.service;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;

import black.android.app.usage.BRIStorageStatsManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


@TargetApi(Build.VERSION_CODES.O)
public class IStorageStatsManagerProxy extends BinderInvocationStub {

    public IStorageStatsManagerProxy() {
        super(BRServiceManager.get().getService(Context.STORAGE_STATS_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIStorageStatsManagerStub.get().asInterface(BRServiceManager.get().getService(Context.STORAGE_STATS_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.STORAGE_STATS_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        if (args != null) {
            String name = method.getName();
            if ("queryStatsForUid".equals(name) || "getCacheQuotaBytes".equals(name)) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Integer) {
                        args[i] = BlackBoxCore.getHostUid();
                        break;
                    }
                }
            } else if ("queryStatsForPackage".equals(name)
                    || "queryStatsForUser".equals(name)) {
                for (int i = args.length - 1; i >= 0; i--) {
                    if (args[i] instanceof Integer) {
                        args[i] = BlackBoxCore.getHostUserId();
                        break;
                    }
                }
            }
        }
        return super.invoke(proxy, method, args);
    }
}
