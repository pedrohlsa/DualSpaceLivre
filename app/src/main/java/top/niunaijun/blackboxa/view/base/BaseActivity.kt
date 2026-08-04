package top.niunaijun.blackboxa.view.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import top.niunaijun.blackboxa.view.setting.ThemePrefs


open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The user's theme choice has to be in effect before any view inflates.
        ThemePrefs.apply(this)
        super.onCreate(savedInstanceState)
    }

    protected fun initToolbar(toolbar: Toolbar, title: Int, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        setSupportActionBar(toolbar)
        toolbar.setTitle(title)
        if (showBack) {
            supportActionBar?.let {
                it.setDisplayHomeAsUpEnabled(true)
                toolbar.setNavigationOnClickListener {
                    if (onBack != null) {
                        onBack()
                    }
                    finish()
                }
            }
        }
    }

    protected fun currentUserID(): Int {
        return intent.getIntExtra("userID", 0)
    }
}
