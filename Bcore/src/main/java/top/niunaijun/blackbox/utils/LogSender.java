package top.niunaijun.blackbox.utils;

import java.io.File;

/**
 * External telemetry is intentionally disabled in Dual Space Livre.
 *
 * The upstream project uploaded logcat files to a third-party endpoint. Keeping
 * this no-op class preserves binary compatibility with the engine while
 * guaranteeing that no diagnostic data leaves the device through this path.
 */
public final class LogSender {
    private LogSender() {
    }

    public static String send(String chatId, File logFile, String caption) {
        return "External log upload is disabled.";
    }
}
