package top.niunaijun.blackboxa.view.setting

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity

class SettingFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initTheme()
        initGms()
        initAbout()
    }

    private fun initTheme() {
        val themePreference = findPreference<ListPreference>(ThemePrefs.KEY) ?: return
        themePreference.setOnPreferenceChangeListener { _, newValue ->
            AppCompatDelegate.setDefaultNightMode(ThemePrefs.modeOf(newValue as? String))
            true
        }
    }

    private fun initGms() {
        val gmsManagerPreference: Preference = findPreference("gms_manager") ?: return

        if (BlackBoxCore.get().isSupportGms) {
            gmsManagerPreference.setOnPreferenceClickListener {
                GmsManagerActivity.start(requireContext())
                true
            }
        } else {
            gmsManagerPreference.summary = getString(R.string.no_gms)
            gmsManagerPreference.isEnabled = false
        }
    }

    private fun initAbout() {
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            AboutActivity.start(requireContext())
            true
        }
    }
}
