package top.niunaijun.blackboxa.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.util.ContextUtil.openAppSystemSettings
import top.niunaijun.blackboxa.view.base.DsDialogs
import top.niunaijun.blackboxa.view.main.ShortcutActivity


object ShortcutUtil {


    
    fun createShortcut(context: Context,userID: Int, info: AppInfo) {

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            val labelName = info.name + userID
            val intent = Intent(context, ShortcutActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra("pkg", info.packageName)
                .putExtra("userId", userID)
            DsDialogs.input(
                context = context,
                title = R.string.app_shortcut,
                hint = R.string.shortcut_name,
                prefill = labelName
            ) { input ->
                    val shortcutInfo: ShortcutInfoCompat =
                        ShortcutInfoCompat.Builder(context, info.packageName + userID)
                            .setIntent(intent)
                            .setShortLabel(input)
                            .setLongLabel(input)
                            .setIcon(IconCompat.createWithBitmap(info.icon!!.toBitmap()))
                            .build()

                    ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
                    showAllowPermissionDialog(context)
            }

        } else {
            toast(R.string.cannot_create_shortcut)
        }
    }

    private fun showAllowPermissionDialog(context: Context){
        if (!AppManager.mBlackBoxLoader.showShortcutPermissionDialog()){
            return
        }

        DsDialogs.show(
            context = context,
            title = R.string.try_add_shortcut,
            message = context.getString(R.string.add_shortcut_fail_msg),
            positive = R.string.done,
            negative = R.string.permission_setting,
            onNegative = { App.getContext().openAppSystemSettings() },
            neutral = R.string.no_reminders,
            onNeutral = { AppManager.mBlackBoxLoader.invalidShortcutPermissionDialog(false) }
        )

    }
}
