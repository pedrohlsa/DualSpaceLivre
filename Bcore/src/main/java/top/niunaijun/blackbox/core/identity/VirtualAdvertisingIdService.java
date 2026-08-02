package top.niunaijun.blackbox.core.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Small local implementation of the public Advertising ID binder contract.
 *
 * <p>The Google client library talks to this binder through transaction codes,
 * so no Google SDK classes are needed in the virtualization engine.</p>
 */
public final class VirtualAdvertisingIdService {
    private static final String ACTION_PREFIX =
            "com.google.android.gms.ads.identifier.service.";
    private static final String DESCRIPTOR =
            "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService";
    private static final ComponentName COMPONENT = new ComponentName(
            "com.google.android.gms",
            "com.google.android.gms.ads.identifier.service.AdvertisingIdService"
    );
    private static final Set<IBinder> CONNECTIONS =
            ConcurrentHashMap.newKeySet();

    private VirtualAdvertisingIdService() {
    }

    public static boolean handles(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return false;
        }
        String action = intent.getAction();
        return action.startsWith(ACTION_PREFIX)
                && (intent.getPackage() == null
                || "com.google.android.gms".equals(intent.getPackage()));
    }

    public static ComponentName component() {
        return COMPONENT;
    }

    public static IBinder binderForUser(int userId) {
        return new AdvertisingIdBinder(
                BlackBoxCore.get().getVirtualAdvertisingId(userId)
        );
    }

    /**
     * Delivers the service through Android's hidden IServiceConnection AIDL.
     * Android 8+ uses connected(ComponentName, IBinder, boolean).
     */
    public static void connect(
            IBinder connection,
            IBinder advertisingIdBinder
    ) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.app.IServiceConnection");
            data.writeInt(1);
            COMPONENT.writeToParcel(data, 0);
            data.writeStrongBinder(advertisingIdBinder);
            data.writeInt(0);
            connection.transact(
                    IBinder.FIRST_CALL_TRANSACTION,
                    data,
                    null,
                    IBinder.FLAG_ONEWAY
            );
            CONNECTIONS.add(connection);
        } finally {
            data.recycle();
        }
    }

    public static boolean disconnect(IBinder connection) {
        return connection != null && CONNECTIONS.remove(connection);
    }

    private static final class AdvertisingIdBinder extends Binder {
        private static final int TRANSACTION_GET_ID = IBinder.FIRST_CALL_TRANSACTION;
        private static final int TRANSACTION_IS_LIMIT_AD_TRACKING =
                IBinder.FIRST_CALL_TRANSACTION + 1;
        private static final int TRANSACTION_IS_FAKE_FOR_DEBUG_LOGGING =
                IBinder.FIRST_CALL_TRANSACTION + 5;

        private final String advertisingId;

        AdvertisingIdBinder(String advertisingId) {
            this.advertisingId = advertisingId;
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(
                int code,
                Parcel data,
                Parcel reply,
                int flags
        ) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }

            switch (code) {
                case TRANSACTION_GET_ID:
                    data.enforceInterface(DESCRIPTOR);
                    reply.writeNoException();
                    reply.writeString(advertisingId);
                    return true;
                case TRANSACTION_IS_LIMIT_AD_TRACKING:
                    data.enforceInterface(DESCRIPTOR);
                    if (data.dataAvail() > 0) {
                        data.readInt();
                    }
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case TRANSACTION_IS_FAKE_FOR_DEBUG_LOGGING:
                    data.enforceInterface(DESCRIPTOR);
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }
}
