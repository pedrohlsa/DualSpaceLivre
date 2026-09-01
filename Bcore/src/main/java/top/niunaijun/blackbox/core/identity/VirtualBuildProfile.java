package top.niunaijun.blackbox.core.identity;

import android.os.Build;

import java.io.File;
import java.lang.reflect.Field;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Applies the public Java-side portion of the guest device profile.
 *
 * {@link Build} is initialized in the Android zygote, before a BlackBox guest
 * process knows which application it will host. Intercepting system-property
 * reads alone is therefore too late: apps reading Build.MODEL or
 * Build.FINGERPRINT would still see the physical phone. Each proxy process
 * hosts a single guest at a time, so replacing these cached String fields just
 * before Application.onCreate is both isolated and deterministic.
 *
 * <p>Only the cosmetic half of a device identity is virtual here. Everything
 * that resolves real vendor code stays physical: SDK_INT, the ABIs, BOARD,
 * HARDWARE and the SOC_* fields. An earlier revision set BOARD to "gs101" and
 * HARDWARE to "oriole"; on a MediaTek host that leaves libhardware searching
 * for Tensor modules that do not exist, while MediaCodecList — built in
 * mediaserver, beyond this process — keeps reporting the physical c2.mtk.*
 * encoders. An app that trusts the declared silicon then configures a pipeline
 * the chip cannot run.
 *
 * <p>The seam is deliberate. Build.MODEL says Pixel 6 while Build.HARDWARE says
 * mt6833, and a determined fingerprinter can see that. A Reel that never
 * finishes encoding is the worse failure.
 */
public final class VirtualBuildProfile {
    private static final String TAG = "VirtualBuildProfile";

    public static final String BRAND = "google";
    public static final String MANUFACTURER = "Google";
    public static final String MODEL = "Pixel 6";
    public static final String DEVICE = "oriole";
    public static final String PRODUCT = "oriole";
    public static final String BUILD_ID = "SQ1D.220105.007";
    public static final String INCREMENTAL = "8030436";
    public static final String FINGERPRINT =
            "google/oriole/oriole:12/SQ1D.220105.007/8030436:user/release-keys";
    public static final String SECURITY_PATCH = "2022-01-05";

    private VirtualBuildProfile() {
    }

    /**
     * Debug switch for bisecting media problems against the profile.
     *
     * <pre>adb shell run-as com.dualspace.livre touch files/no_device_profile</pre>
     *
     * removes the whole guest profile on the next guest start; deleting the file
     * restores it. Checked before the native hook is armed, so the read itself
     * still sees physical values.
     */
    private static final String DISABLE_MARKER = "no_device_profile";

    public static boolean isDisabled() {
        try {
            return new File(BlackBoxCore.getContext().getFilesDir(), DISABLE_MARKER).exists();
        } catch (Throwable error) {
            return false;
        }
    }

    public static void apply() {
        if (isDisabled()) {
            Slog.w(TAG, "guest profile disabled by " + DISABLE_MARKER + " marker");
            return;
        }
        set(Build.class, "BRAND", BRAND);
        set(Build.class, "MANUFACTURER", MANUFACTURER);
        set(Build.class, "MODEL", MODEL);
        set(Build.class, "DEVICE", DEVICE);
        set(Build.class, "PRODUCT", PRODUCT);
        set(Build.class, "ID", BUILD_ID);
        set(Build.class, "DISPLAY", BUILD_ID);
        set(Build.class, "FINGERPRINT", FINGERPRINT);
        set(Build.class, "TYPE", "user");
        set(Build.class, "TAGS", "release-keys");
        set(Build.VERSION.class, "INCREMENTAL", INCREMENTAL);
        set(Build.VERSION.class, "RELEASE", "12");
        set(Build.VERSION.class, "SECURITY_PATCH", SECURITY_PATCH);

        Slog.i(TAG, "guest profile applied: " + Build.MANUFACTURER + " "
                + Build.MODEL + " / " + Build.DEVICE
                + " (physical board " + Build.BOARD
                + ", hardware " + Build.HARDWARE + ")");
    }

    private static void set(Class<?> owner, String name, String value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable error) {
            Slog.w(TAG, "unable to set " + owner.getSimpleName() + "." + name, error);
        }
    }
}
