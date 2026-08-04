package top.niunaijun.blackboxa.view.setting

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * App theme selection. Dark is the default look; the user can switch to light or
 * follow the system from Settings → Aparência.
 *
 * Applied from [top.niunaijun.blackboxa.view.base.BaseActivity] rather than from
 * Application.onCreate, because Application.onCreate also runs inside guest
 * processes and the night mode of a cloned app must not be touched.
 */
object ThemePrefs {

    const val KEY = "app_theme"

    private const val DARK = "dark"
    private const val LIGHT = "light"
    private const val SYSTEM = "system"

    fun modeOf(value: String?): Int = when (value) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun apply(context: Context) {
        try {
            val stored = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(KEY, DARK)
            val mode = modeOf(stored)
            if (AppCompatDelegate.getDefaultNightMode() != mode) {
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        } catch (ignored: Exception) {
            // Preferences are unavailable in some engine processes; keep the default.
        }
    }
}
