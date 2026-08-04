package top.niunaijun.blackboxa.view.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityAboutBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.BaseActivity

class AboutActivity : BaseActivity() {

    private val viewBinding: ActivityAboutBinding by inflate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.about_title, true)

        viewBinding.aboutVersion.text = getString(R.string.about_version, versionName())
    }

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }
    }
}
