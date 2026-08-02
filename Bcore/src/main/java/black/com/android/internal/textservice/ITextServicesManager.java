package black.com.android.internal.textservice;

import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

@BClassName("com.android.internal.textservice.ITextServicesManager")
public interface ITextServicesManager {
    @BClassName("com.android.internal.textservice.ITextServicesManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder binder);
    }
}
