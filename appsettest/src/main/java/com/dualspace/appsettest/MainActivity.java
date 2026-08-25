package com.dualspace.appsettest;

import android.app.Activity;
import android.media.MediaDrm;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.UUID;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.appset.AppSet;

public class MainActivity extends Activity {

    static final String TAG = "APPSETTEST";

    static final UUID WIDEVINE =
            new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final TextView tv = new TextView(this);
        tv.setPadding(40, 140, 40, 40);
        tv.setTextIsSelectable(true);
        tv.setText("lendo IDs...");
        setContentView(tv);

        Log.e(TAG, "START pkg=" + getPackageName());

        new Thread(() -> {
            String ad;
            try {
                AdvertisingIdClient.Info info =
                        AdvertisingIdClient.getAdvertisingIdInfo(getApplicationContext());
                ad = info.getId();
            } catch (Throwable t) {
                ad = "ERR:" + t;
            }
            final String adF = ad;
            Log.e(TAG, "ADVERTISING_ID=" + adF);

            final String drmF = readWidevineDeviceId();
            Log.e(TAG, "WIDEVINE_DEVICE_ID=" + drmF);

            final String head = "DEVICE_PROFILE=\n"
                    + Build.MANUFACTURER + " " + Build.MODEL
                    + "\ndevice=" + Build.DEVICE
                    + " board=" + Build.BOARD
                    + " hardware=" + Build.HARDWARE
                    + "\n" + Build.FINGERPRINT
                    + "\nAndroid " + Build.VERSION.RELEASE
                    + " / SDK " + Build.VERSION.SDK_INT
                    + " / patch " + Build.VERSION.SECURITY_PATCH
                    + "\n\nADVERTISING_ID=\n" + adF
                    + "\n\nWIDEVINE_DEVICE_ID=\n" + drmF + "\n\n";

            Log.e(TAG, "DEVICE_PROFILE=" + Build.MANUFACTURER + " " + Build.MODEL
                    + " device=" + Build.DEVICE + " board=" + Build.BOARD
                    + " hardware=" + Build.HARDWARE + " fingerprint=" + Build.FINGERPRINT);

            AppSet.getClient(getApplicationContext()).getAppSetIdInfo()
                    .addOnSuccessListener(i -> {
                        String msg = "APP_SET_ID=" + i.getId() + " scope=" + i.getScope();
                        Log.e(TAG, msg);
                        runOnUiThread(() -> tv.setText(head + msg));
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "APP_SET_ID_FAIL=" + e);
                        runOnUiThread(() -> tv.setText(head + "APP_SET_FAIL=" + e));
                    });
        }).start();
    }

    static String readWidevineDeviceId() {
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(WIDEVINE);
            return toHex(drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID));
        } catch (Throwable t) {
            return "ERR:" + t;
        } finally {
            if (drm != null) {
                try {
                    drm.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
