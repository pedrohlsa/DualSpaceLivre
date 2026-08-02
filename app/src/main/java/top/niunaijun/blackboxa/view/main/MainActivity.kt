package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.afollestad.materialdialogs.customview.customView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.viewpager2.widget.ViewPager2
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.Resolution
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.apps.AppsFragment
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0

    companion object {
        private const val TAG = "MainActivity"

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}")
            }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            initViewPager()
            initFab()
            initToolbarSubTitle()

            // On launch, let the user pick which space to enter first.
            if ((BlackBoxCore.get().users?.size ?: 0) > 1) {
                viewBinding.viewPager.post { showSpacePicker() }
            }

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")
            
            showErrorDialog("Não foi possível iniciar o aplicativo: ${e.message}")
        }
    }

    private fun showErrorDialog(message: String) {
        try {
            MaterialDialog(this).show {
                title(text = "Erro")
                message(text = message)
                positiveButton(text = "OK") { finish() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
            finish()
        }
    }

    private fun initToolbarSubTitle() {
        try {
            updateUserRemark(0)
            
            viewBinding.toolbarLayout.toolbar.getChildAt(1)?.setOnClickListener {
                showRenameDialog(currentUser)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initToolbarSubTitle: ${e.message}")
        }
    }

    private fun spaceName(userId: Int): String {
        val remark = AppManager.mRemarkSharedPreferences.getString(
                "Remark$userId", "Espaço ${userId + 1}")
        return if (remark.isNullOrEmpty()) "Espaço ${userId + 1}" else remark
    }

    private val spacePalette = intArrayOf(
            0xFF6C5CE7.toInt(), // violet
            0xFF00B894.toInt(), // green
            0xFF0984E3.toInt(), // blue
            0xFFE17055.toInt(), // coral
            0xFFE84393.toInt(), // pink
            0xFFF39C12.toInt(), // amber
            0xFF00CEC9.toInt(), // teal
            0xFF6D4C9F.toInt()  // purple
    )

    private val spacePaletteNames = arrayOf(
            "Violeta", "Verde", "Azul", "Coral", "Rosa", "Âmbar", "Turquesa", "Roxo")

    private fun spaceColor(userId: Int): Int {
        val stored = AppManager.mRemarkSharedPreferences.getInt("Color$userId", 0)
        return if (stored != 0) stored else spacePalette[Math.floorMod(userId, spacePalette.size)]
    }

    private fun shade(color: Int, factor: Float): Int {
        return Color.rgb(
                (Color.red(color) * factor).toInt().coerceIn(0, 255),
                (Color.green(color) * factor).toInt().coerceIn(0, 255),
                (Color.blue(color) * factor).toInt().coerceIn(0, 255))
    }

    private fun applySpaceColor(userId: Int) {
        try {
            val base = spaceColor(userId)
            val gradient = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(shade(base, 1.08f), shade(base, 0.90f)))
            viewBinding.toolbarLayout.toolbar.background = gradient
            window.statusBarColor = shade(base, 0.74f)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying space color: ${e.message}")
        }
    }

    private fun showColorPicker() {
        try {
            MaterialDialog(this).show {
                title(res = R.string.space_color)
                listItems(items = spacePaletteNames.toList()) { _, index, _ ->
                    try {
                        AppManager.mRemarkSharedPreferences.edit {
                            putInt("Color$currentUser", spacePalette[index])
                        }
                        applySpaceColor(currentUser)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving space color: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing color picker: ${e.message}")
        }
    }

    private fun showRenameDialog(userId: Int) {
        try {
            MaterialDialog(this).show {
                title(res = R.string.userRemark)
                input(hintRes = R.string.userRemark, prefill = spaceName(userId)) { _, input ->
                    try {
                        AppManager.mRemarkSharedPreferences.edit {
                            putString("Remark$userId", input.toString())
                        }
                        if (userId == currentUser) {
                            viewBinding.toolbarLayout.toolbar.subtitle = input
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving user remark: ${e.message}")
                    }
                }
                positiveButton(res = R.string.done)
                negativeButton(res = R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing remark dialog: ${e.message}")
        }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun showSpacePicker() {
        try {
            val users = BlackBoxCore.get().users
            val realCount = users?.size ?: (fragmentList.size - 1)
            val dialog = MaterialDialog(this)
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
            }
            fragmentList.forEachIndexed { index, _ ->
                val isAdd = index >= realCount
                // ViewPager2 creates fragments lazily, so an off-screen fragment's
                // userID is still the default 0. Derive the real id from the user
                // list (same order as the pages) instead.
                val uid = users?.getOrNull(index)?.id ?: index
                val label = when {
                    isAdd -> "＋   Novo espaço"
                    else -> {
                        val custom = AppManager.mRemarkSharedPreferences
                                .getString("Remark$uid", null)
                        if (custom.isNullOrEmpty()) "Espaço ${index + 1}" else custom
                    }
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                    isClickable = true
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        try {
                            viewBinding.viewPager.setCurrentItem(index, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error switching space: ${e.message}")
                        }
                        dialog.dismiss()
                    }
                }
                if (!isAdd) {
                    val dot = View(this).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(spaceColor(uid))
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f)).apply {
                            marginEnd = dp(16f)
                        }
                    }
                    row.addView(dot)
                }
                val text = TextView(this).apply {
                    this.text = label
                    setTextColor(if (isAdd) spaceColor(currentUser) else 0xFF20203A.toInt())
                    textSize = 16f
                    if (isAdd) setPadding(dp(2f), 0, 0, 0)
                }
                row.addView(text)
                list.addView(row)
            }
            dialog.show {
                title(res = R.string.switch_space)
                customView(view = ScrollView(this@MainActivity).apply { addView(list) })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing space picker: ${e.message}")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.main_switch_space -> {
                showSpacePicker(); true
            }
            R.id.main_rename_space -> {
                showRenameDialog(currentUser); true
            }
            R.id.main_color_space -> {
                showColorPicker(); true
            }
            R.id.main_setting -> {
                SettingActivity.start(this); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initViewPager() {
        try {
            val userList = BlackBoxCore.get().users
            userList.forEach { fragmentList.add(AppsFragment.newInstance(it.id)) }

            currentUser = userList.firstOrNull()?.id ?: 0
            fragmentList.add(AppsFragment.newInstance(userList.size))

            mViewPagerAdapter = ViewPagerAdapter(this)
            mViewPagerAdapter.replaceData(fragmentList)
            viewBinding.viewPager.adapter = mViewPagerAdapter
            viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
            viewBinding.viewPager.registerOnPageChangeCallback(
                    object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            try {
                                super.onPageSelected(position)
                                currentUser = fragmentList[position].userID
                                updateUserRemark(currentUser)
                                showFloatButton(true)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in onPageSelected: ${e.message}")
                            }
                        }
                    }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in initViewPager: ${e.message}")
        }
    }

    private fun initFab() {
        try {
            viewBinding.fab.setOnClickListener {
                try {
                    val userId = viewBinding.viewPager.currentItem
                    val intent = Intent(this, ListActivity::class.java)
                    intent.putExtra("userID", userId)
                    apkPathResult.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching ListActivity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFab: ${e.message}")
        }
    }

    fun showFloatButton(show: Boolean) {
        try {
            val tranY: Float = Resolution.convertDpToPixel(120F, App.getContext())
            val time = 200L
            if (show) {
                viewBinding.fab.animate().translationY(0f).alpha(1f).setDuration(time).start()
            } else {
                viewBinding.fab.animate().translationY(tranY).alpha(0f).setDuration(time).start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showFloatButton: ${e.message}")
        }
    }

    fun scanUser() {
        try {
            val userList = BlackBoxCore.get().users

            if (fragmentList.size == userList.size) {
                fragmentList.add(AppsFragment.newInstance(fragmentList.size))
            } else if (fragmentList.size > userList.size + 1) {
                fragmentList.removeLast()
            }

            mViewPagerAdapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanUser: ${e.message}")
        }
    }

    private fun updateUserRemark(userId: Int) {
        try {
            var remark =
                    AppManager.mRemarkSharedPreferences.getString(
                            "Remark$userId",
                            "Espaço ${userId + 1}"
                    )
            if (remark.isNullOrEmpty()) {
                remark = "Espaço ${userId + 1}"
            }

            viewBinding.toolbarLayout.toolbar.subtitle = remark
            applySpaceColor(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating user remark: ${e.message}")
            viewBinding.toolbarLayout.toolbar.subtitle = "Espaço ${userId + 1}"
        }
    }

    private val apkPathResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (it.resultCode == RESULT_OK) {
                        it.data?.let { data ->
                            val userId = data.getIntExtra("userID", 0)
                            val source = data.getStringExtra("source")
                            if (source != null) {
                                fragmentList[userId].installApk(source)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling APK path result: ${e.message}")
                }
            }

}
