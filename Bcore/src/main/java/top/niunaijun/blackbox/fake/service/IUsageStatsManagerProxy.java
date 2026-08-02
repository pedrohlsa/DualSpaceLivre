package top.niunaijun.blackbox.fake.service;

import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.lang.reflect.Method;

import black.android.app.BRIUsageStatsManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/** Isolates guest standby queries from the physical Android user's usage database. */
public class IUsageStatsManagerProxy extends BinderInvocationStub {

    public IUsageStatsManagerProxy() {
        super(BRServiceManager.get().getService(Context.USAGE_STATS_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIUsageStatsManagerStub.get().asInterface(
                BRServiceManager.get().getService(Context.USAGE_STATS_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.USAGE_STATS_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getAppStandbyBucket")
    public static class GetAppStandbyBucket extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return UsageStatsManager.STANDBY_BUCKET_ACTIVE;
        }
    }
}
