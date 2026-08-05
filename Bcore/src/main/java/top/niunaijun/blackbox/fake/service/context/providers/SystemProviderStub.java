package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.Bundle;
import android.os.IInterface;
import android.provider.Settings;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.identity.VirtualIdentityManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ContextCompat;


public class SystemProviderStub extends ClassInvocationStub implements BContentProvider {
    private static final String TAG = "SystemProviderStub";
    /** Settings.NameValueTable.VALUE — the key call() answers a lookup with. */
    private static final String SETTINGS_VALUE_KEY = "value";

    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        
        String methodName = method.getName();
        
        
        
        if ("call".equals(methodName)) {
            Bundle virtualAndroidId = getVirtualAndroidId(args);
            if (virtualAndroidId != null) {
                return virtualAndroidId;
            }

            if (args != null) {
                Class<?> attributionSourceClass = BRAttributionSource.getRealClass();
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];

                    if (arg != null && attributionSourceClass != null &&
                            arg.getClass().getName().equals(attributionSourceClass.getName())) {
                        ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                    }
                }
            }
            return method.invoke(mBase, args);
        }
        
        
        if (args != null && args.length > 0) {
            Object arg = args[0];
            if (arg instanceof String) {
                String authority = (String) arg;
                
                if (!isSystemProviderAuthority(authority)) {
                    args[0] = BlackBoxCore.getHostPkg();
                }
            } else if (arg != null) {
                Class<?> attrSourceClass = BRAttributionSource.getRealClass();
                
                if (attrSourceClass != null && arg.getClass().getName().equals(attrSourceClass.getName())) {
                    ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                }
            }
        }
        return method.invoke(mBase, args);
    }

    /**
     * Answers {@code Settings.Secure.ANDROID_ID} with the value that belongs to
     * this space instead of the host's.
     *
     * Without this every space reported the host's single ANDROID_ID, so
     * Instagram could tie all the cloned accounts back to one device. The
     * previous {@code AndroidIdProxy} looked like it handled this, but it was an
     * empty stub — it hooked nothing, and its fallback minted a brand new random
     * id on every call anyway.
     *
     * The settings provider is reached through {@code call()}, whose signature
     * moved between API levels, so the selector and the setting name are located
     * by value rather than by position.
     */
    private Bundle getVirtualAndroidId(Object[] args) {
        if (args == null) {
            return null;
        }
        boolean isGet = false;
        boolean wantsAndroidId = false;
        for (Object arg : args) {
            if (!(arg instanceof String)) {
                continue;
            }
            String value = (String) arg;
            if (value.startsWith("GET_")) {
                isGet = true;
            } else if (Settings.Secure.ANDROID_ID.equals(value)) {
                wantsAndroidId = true;
            }
        }
        if (!isGet || !wantsAndroidId) {
            return null;
        }

        try {
            String androidId = VirtualIdentityManager.get()
                    .getAndroidId(BActivityThread.getUserId());
            if (androidId == null) {
                return null;
            }
            Slog.d(TAG, "ANDROID_ID served for space " + BActivityThread.getUserId());
            Bundle result = new Bundle();
            result.putString(SETTINGS_VALUE_KEY, androidId);
            return result;
        } catch (Throwable error) {
            Slog.w(TAG, "Unable to virtualize ANDROID_ID, falling through", error);
            return null;
        }
    }

    private boolean isSystemProviderAuthority(String authority) {
        if (authority == null) return false;
        
        return authority.equals("settings") || 
               authority.equals("media") || 
               authority.equals("downloads") || 
               authority.equals("contacts") || 
               authority.equals("call_log") || 
               authority.equals("telephony") || 
               authority.equals("calendar") || 
               authority.equals("browser") || 
               authority.equals("user_dictionary") || 
               authority.equals("applications") ||
               authority.startsWith("com.android.") ||
               authority.startsWith("android.");
    }
}
