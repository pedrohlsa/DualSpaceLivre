package top.niunaijun.blackboxa.view.base

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import top.niunaijun.blackboxa.R


/**
 * Blocking progress dialog shared by the host screens.
 *
 * Replaces the old third-party "cat loading" animation with a plain themed
 * dialog so it follows the app's dark/light palette and costs almost nothing to
 * draw on low-end devices.
 */
abstract class LoadingActivity : BaseActivity() {

    private var loadingDialog: Dialog? = null

    @JvmOverloads
    fun showLoading(message: CharSequence? = null) {
        try {
            if (isFinishing || isDestroyed) return

            val dialog = loadingDialog ?: Dialog(this).also {
                it.setContentView(R.layout.dialog_loading)
                it.setCancelable(false)
                it.setCanceledOnTouchOutside(false)
                it.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                it.setOnKeyListener { _, keyCode, _ ->
                    keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
                }
                loadingDialog = it
            }

            dialog.findViewById<TextView>(R.id.loadingText)?.text =
                    message ?: getString(R.string.loading)

            if (!dialog.isShowing) {
                dialog.show()
            }
        } catch (e: Exception) {
            Log.e("LoadingActivity", "Error showing loading: ${e.message}")
        }
    }

    fun hideLoading() {
        try {
            loadingDialog?.takeIf { it.isShowing }?.dismiss()
        } catch (e: Exception) {
            Log.e("LoadingActivity", "Error hiding loading: ${e.message}")
        }
    }

    override fun onDestroy() {
        hideLoading()
        loadingDialog = null
        super.onDestroy()
    }
}
