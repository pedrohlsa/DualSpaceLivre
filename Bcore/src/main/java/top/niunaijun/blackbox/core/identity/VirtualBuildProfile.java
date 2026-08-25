package top.niunaijun.blackbox.core.identity;

import android.os.Build;

import java.lang.reflect.Field;

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
 * SDK_INT, ABIs and graphics-driver properties deliberately remain physical.
 * Pretending that a Moto/Adreno runtime contains Pixel/Mali native libraries
 * can make camera, video and OpenGL initialization fail.
 */
public final class VirtualBuildProfile {
    private static final String TAG = "VirtualBuildProfile";

    public static final String BRAND = "google";
    public static final String MANUFACTURER = "Google";
    public static final String MODEL = "Pixel 6";
    public static final String DEVICE = "oriole";
    public static final String PRODUCT = "oriole";
    public static final String BOARD = "gs101";
    public static final String HARDWARE = "oriole";
    public static final String BUILD_ID = "SQ1D.220105.007";
    public static final String INCREMENTAL = "8030436";
    public static final String FINGERPRINT =
            "google/oriole/oriole:12/SQ1D.220105.007/8030436:user/release-keys";
    public static final String SECURITY_PATCH = "2022-01-05";

    private VirtualBuildProfile() {
    }

    public static void apply() {
        set(Build.class, "BRAND", BRAND);
        set(Build.class, "MANUFACTURER", MANUFACTURER);
        set(Build.class, "MODEL", MODEL);
        set(Build.class, "DEVICE", DEVICE);
        set(Build.class, "PRODUCT", PRODUCT);
        set(Build.class, "BOARD", BOARD);
        set(Build.class, "HARDWARE", HARDWARE);
        set(Build.class, "ID", BUILD_ID);
        set(Build.class, "DISPLAY", BUILD_ID);
        set(Build.class, "FINGERPRINT", FINGERPRINT);
        set(Build.class, "TYPE", "user");
        set(Build.class, "TAGS", "release-keys");
        set(Build.VERSION.class, "INCREMENTAL", INCREMENTAL);
        set(Build.VERSION.class, "RELEASE", "12");
        set(Build.VERSION.class, "SECURITY_PATCH", SECURITY_PATCH);

        // Android 12 exposes these fields; keep compatibility with older hosts.
        setIfPresent(Build.class, "SOC_MANUFACTURER", "Google");
        setIfPresent(Build.class, "SOC_MODEL", "Tensor");

        Slog.i(TAG, "guest profile applied: " + Build.MANUFACTURER + " "
                + Build.MODEL + " / " + Build.DEVICE + " / " + Build.BOARD
                + " / " + Build.FINGERPRINT);
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

    private static void setIfPresent(Class<?> owner, String name, String value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (NoSuchFieldException ignored) {
            // Field was introduced after the app's minimum Android version.
        } catch (Throwable error) {
            Slog.w(TAG, "unable to set optional " + owner.getSimpleName() + "." + name, error);
        }
    }
}
