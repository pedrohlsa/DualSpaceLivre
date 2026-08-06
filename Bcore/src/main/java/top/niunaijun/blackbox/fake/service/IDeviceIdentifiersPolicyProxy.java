package top.niunaijun.blackbox.fake.service;


import java.lang.reflect.Method;

import black.android.os.BRIDeviceIdentifiersPolicyServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.identity.VirtualIdentityManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Md5Utils;


public class IDeviceIdentifiersPolicyProxy extends BinderInvocationStub {

    public IDeviceIdentifiersPolicyProxy() {
        super(BRServiceManager.get().getService("device_identifiers"));
    }

    @Override
    protected Object getWho() {
        return BRIDeviceIdentifiersPolicyServiceStub.get().asInterface(BRServiceManager.get().getService("device_identifiers"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("device_identifiers");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getSerialForPackage")
    public static class GetSerialForPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Was md5(host package): a constant, so every space reported the
            // same serial. Now it comes from the space's own identity file.
            try {
                String serial = VirtualIdentityManager.get()
                        .getSerial(BActivityThread.getUserId());
                if (serial != null) {
                    return serial;
                }
            } catch (Throwable ignored) {
            }
            return Md5Utils.md5(BlackBoxCore.getHostPkg());
        }
    }
}
