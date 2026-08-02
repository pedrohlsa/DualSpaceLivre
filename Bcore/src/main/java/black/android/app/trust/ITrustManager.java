package black.android.app.trust;

import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

@BClassName("android.app.trust.ITrustManager")
public interface ITrustManager {
    @BClassName("android.app.trust.ITrustManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder binder);
    }
}
