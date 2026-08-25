package top.niunaijun.blackboxa.view.setting

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.base.DsDialogs
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity

class SettingFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initTheme()
        initGms()
        initAbout()
    }

    private fun initTheme() {
        val themePreference = findPreference<Preference>(ThemePrefs.KEY) ?: return
        val entries = resources.getStringArray(R.array.theme_entries)
        val values = resources.getStringArray(R.array.theme_values)

        fun syncSummary(value: String = ThemePrefs.get(requireContext())) {
            val index = values.indexOf(value).coerceAtLeast(0)
            themePreference.summary = entries[index]
        }
        syncSummary()

        themePreference.setOnPreferenceClickListener {
            val checked = values.indexOf(ThemePrefs.get(requireContext())).coerceAtLeast(0)
            DsDialogs.singleChoice(
                context = requireContext(),
                title = R.string.theme_title,
                options = entries,
                checkedIndex = checked
            ) { index ->
                ThemePrefs.set(requireContext(), values[index])
                syncSummary(values[index])
                AppCompatDelegate.setDefaultNightMode(ThemePrefs.modeOf(values[index]))
            }
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
