package com.dualspace.appsettest;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.appset.AppSet;

public class MainActivity extends Activity {

    static final String TAG = "APPSETTEST";

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

            AppSet.getClient(getApplicationContext()).getAppSetIdInfo()
                    .addOnSuccessListener(i -> {
                        String msg = "APP_SET_ID=" + i.getId() + " scope=" + i.getScope();
                        Log.e(TAG, msg);
                        runOnUiThread(() -> tv.setText(
                                "ADVERTISING_ID=\n" + adF + "\n\n" + msg));
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "APP_SET_ID_FAIL=" + e);
                        runOnUiThread(() -> tv.setText(
                                "ADVERTISING_ID=\n" + adF + "\n\nAPP_SET_FAIL=" + e));
                    });
        }).start();
    }
}
