package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.google.android.material.bottomsheet.BottomSheetDialog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.Resolution
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.apps.AppsFragment
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0
    private var selectedRealUser: Int? = null

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
            initSpaceHeader()

            // Guest apps query MediaStore through the host process. The virtual
            // permission alone is not enough: Android also requires the host app
            // to hold the real media/storage permission in this physical profile.
            // Ask once from the visible host activity so transferred photos are
            // available to Instagram and other cloned apps.
            if (!BlackBoxCore.get().hasStoragePermission()) {
                viewBinding.root.post {
                    try {
                        BlackBoxCore.get().requestStoragePermission(this)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error requesting storage permission: ${e.message}")
                    }
                }
            }

            // On launch, let the user pick which space to enter first.
            if (savedInstanceState == null && SpaceUi.sortedUsers().size > 1) {
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

    // ---------------------------------------------------------------- header

    private fun initSpaceHeader() {
        viewBinding.spaceHeader.setOnClickListener { showSpacePicker() }
        updateSpaceHeader(currentUser)
    }

    private fun updateSpaceHeader(userId: Int) {
        try {
            val users = SpaceUi.sortedUsers()
            val color = SpaceUi.colorOf(userId, users)
            val position = users.indexOf(userId)

            viewBinding.spaceName.text = SpaceUi.nameOf(userId)
            // Always build a fresh drawable: the one declared in XML is shared
            // across every view that references @drawable/bg_dot, so tinting it
            // in place would repaint the other dots too.
            viewBinding.spaceDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }

            val count = SpaceUi.appCount(userId)
            val countText = if (count == 0) {
                getString(R.string.space_empty_summary)
            } else {
                resources.getQuantityString(R.plurals.space_apps_count, count, count)
            }
            viewBinding.spaceSummary.text = if (position >= 0 && users.size > 1) {
                "$countText · " + getString(R.string.space_position, position + 1, users.size)
            } else {
                countText
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating space header: ${e.message}")
        }
    }

    // -------------------------------------------------------- space selector

    private fun dp(value: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun showSpacePicker() {
        try {
            val users = SpaceUi.sortedUsers()
            SpaceUi.ensureUniqueColors(users)

            val sheet = BottomSheetDialog(this)
            val root = layoutInflater.inflate(R.layout.sheet_spaces, null) as LinearLayout
            val container = root.findViewById<LinearLayout>(R.id.spacesContainer)
            val scroll = root.findViewById<ScrollView>(R.id.spacesScroll)

            users.forEachIndexed { index, userId ->
                val row = layoutInflater.inflate(R.layout.item_space, container, false)
                val dot = row.findViewById<View>(R.id.spaceDot)
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(SpaceUi.colorOf(userId, users))
                }
                row.findViewById<TextView>(R.id.spaceName).text = SpaceUi.nameOf(userId)

                val count = SpaceUi.appCount(userId)
                row.findViewById<TextView>(R.id.spaceSummary).text = if (count == 0) {
                    getString(R.string.space_empty_summary)
                } else {
                    resources.getQuantityString(R.plurals.space_apps_count, count, count)
                }

                row.findViewById<ImageView>(R.id.spaceActive).visibility =
                        if (userId == currentUser) View.VISIBLE else View.GONE

                row.setOnClickListener {
                    sheet.dismiss()
                    viewBinding.viewPager.setCurrentItem(index, true)
                }
                row.findViewById<ImageView>(R.id.spaceMenu).setOnClickListener { anchor ->
                    showSpaceMenu(anchor, userId, users.size, sheet)
                }
                container.addView(row)
            }

            val footer = root.findViewById<FrameLayout>(R.id.spacesFooter)
            val addRow = layoutInflater.inflate(R.layout.item_space_add, footer, false)
            addRow.setOnClickListener {
                sheet.dismiss()
                createNewSpace()
            }
            footer.addView(addRow)

            sheet.setContentView(root)
            applyNavigationBarPadding(root)
            shrinkScrollToFit(root, scroll)
            sheet.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing space picker: ${e.message}")
        }
    }

    /**
     * Real bottom inset of this device. The platform `navigation_bar_height`
     * resource under-reports it on the Moto G50 (70px vs the actual 138px), so
     * the activity's own window insets are the source of truth.
     */
    private fun navigationBarHeight(): Int {
        try {
            val insets = ViewCompat.getRootWindowInsets(viewBinding.root)
            val bottom = insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            if (bottom > 0) return bottom
        } catch (ignored: Exception) {
        }
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
    }

    /**
     * Keeps the last row of a sheet clear of the gesture/navigation bar.
     * BottomSheetDialog consumes the window insets itself, so the height is read
     * straight from the platform resource instead of an inset listener.
     */
    private fun applyNavigationBarPadding(root: View) {
        try {
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight,
                    root.paddingBottom + navigationBarHeight())
        } catch (e: Exception) {
            Log.e(TAG, "Error applying navigation bar padding: ${e.message}")
        }
    }

    /**
     * A sheet with many spaces is taller than the screen. Measure once it is
     * laid out and give the scrolling area exactly the leftover height, so the
     * pinned footer always stays visible above the navigation bar.
     */
    private fun shrinkScrollToFit(root: View, scroll: View) {
        root.post {
            try {
                val available = root.rootView.height - navigationBarHeight() - dp(16f)
                val excess = root.height - available
                if (excess > 0 && scroll.height > excess) {
                    scroll.layoutParams.height = scroll.height - excess
                    scroll.requestLayout()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fitting sheet: ${e.message}")
            }
        }
    }

    private fun showSpaceMenu(anchor: View, userId: Int, spaceCount: Int, sheet: BottomSheetDialog) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.rename_space)
        menu.menu.add(0, 2, 1, R.string.space_color)
        menu.menu.add(0, 3, 2, R.string.delete_space)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    sheet.dismiss(); showRenameDialog(userId)
                }
                2 -> {
                    sheet.dismiss(); showColorPicker(userId)
                }
                3 -> {
                    sheet.dismiss()
                    if (spaceCount <= 1) {
                        toast(R.string.delete_space_last)
                    } else {
                        confirmDeleteSpace(userId)
                    }
                }
            }
            true
        }
        menu.show()
    }

    /**
     * A space only materialises in the engine once it holds an app, so "create"
     * means: go to the trailing page and pick the first app for it.
     */
    private fun createNewSpace() {
        try {
            val last = fragmentList.size - 1
            viewBinding.viewPager.setCurrentItem(last, true)
            viewBinding.viewPager.postDelayed({ openAppPicker(userIdAt(last)) }, 320)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating space: ${e.message}")
        }
    }

    private fun confirmDeleteSpace(userId: Int) {
        try {
            MaterialDialog(this).show {
                title(res = R.string.delete_space)
                message(text = getString(R.string.delete_space_message, SpaceUi.nameOf(userId)))
                positiveButton(res = R.string.delete_space_confirm) { deleteSpace(userId) }
                negativeButton(res = R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error confirming space deletion: ${e.message}")
        }
    }

    private fun deleteSpace(userId: Int) {
        try {
            showLoading()
            BlackBoxCore.get().deleteUser(userId)
            AppManager.mRemarkSharedPreferences.edit {
                remove("Remark$userId")
                remove("Color$userId")
                remove("AppList$userId")
            }
            hideLoading()
            toast(R.string.delete_space_done)
            // Rebuilding the pager in place would leave stale fragments behind;
            // a clean restart of the screen is cheaper and safer.
            recreate()
        } catch (e: Exception) {
            hideLoading()
            Log.e(TAG, "Error deleting space $userId: ${e.message}")
        }
    }

    private fun showRenameDialog(userId: Int) {
        try {
            MaterialDialog(this).show {
                title(res = R.string.userRemark)
                input(hintRes = R.string.userRemark, prefill = SpaceUi.nameOf(userId)) { _, input ->
                    try {
                        SpaceUi.setName(userId, input.toString())
                        if (userId == currentUser) updateSpaceHeader(userId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving space name: ${e.message}")
                    }
                }
                positiveButton(res = R.string.done)
                negativeButton(res = R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing rename dialog: ${e.message}")
        }
    }

    private fun showColorPicker(userId: Int) {
        try {
            val users = SpaceUi.sortedUsers()
            val used = SpaceUi.colorsUsedByOthers(userId, users)
            val current = SpaceUi.colorOf(userId, users)

            val sheet = BottomSheetDialog(this)
            val root = layoutInflater.inflate(R.layout.sheet_colors, null) as LinearLayout
            val grid = root.findViewById<GridLayout>(R.id.colorGrid)

            var slot = 0
            SpaceUi.palette.forEachIndexed { index, color ->
                // A colour claimed by another space is simply not offered.
                if (color != current && SpaceUi.isColorTaken(color, used)) return@forEachIndexed

                val cell = FrameLayout(this).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(64f)
                        columnSpec = GridLayout.spec(slot % 5, 1f)
                    }
                    contentDescription = SpaceUi.paletteNames.getOrNull(index)
                }
                slot++
                val swatch = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.CENTER)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        if (color == current) setStroke(dp(3f), SpaceUi.shade(color, 0.55f))
                    }
                }
                cell.addView(swatch)
                if (color == current) {
                    cell.addView(ImageView(this).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER)
                        setImageResource(R.drawable.ic_check_24)
                        setColorFilter(android.graphics.Color.WHITE)
                    })
                }
                cell.setOnClickListener {
                    SpaceUi.setColor(userId, color)
                    if (userId == currentUser) updateSpaceHeader(userId)
                    sheet.dismiss()
                }
                grid.addView(cell)
            }

            sheet.setContentView(root)
            applyNavigationBarPadding(root)
            sheet.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing colour picker: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ menu

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
                showColorPicker(currentUser); true
            }
            R.id.main_setting -> {
                SettingActivity.start(this); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ----------------------------------------------------------- view pager

    private fun initViewPager() {
        try {
            val userList = SpaceUi.sortedUsers()
            userList.forEach { fragmentList.add(AppsFragment.newInstance(it)) }

            currentUser = userList.firstOrNull() ?: 0
            selectedRealUser = currentUser
            SpaceUi.ensureUniqueColors(userList)
            fragmentList.add(AppsFragment.newInstance(SpaceUi.nextAvailableId(userList)))

            mViewPagerAdapter = ViewPagerAdapter(this)
            mViewPagerAdapter.replaceData(fragmentList)
            viewBinding.viewPager.adapter = mViewPagerAdapter
            viewBinding.viewPager.registerOnPageChangeCallback(
                    object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            try {
                                super.onPageSelected(position)
                                val selectedUser = userIdAt(position)
                                val isRealSpace = position < SpaceUi.sortedUsers().size
                                val previousUser = selectedRealUser
                                if (isRealSpace && previousUser != null && previousUser != selectedUser) {
                                    stopSpace(previousUser)
                                    selectedRealUser = selectedUser
                                }
                                currentUser = selectedUser
                                updateSpaceHeader(currentUser)
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
                openAppPicker(userIdAt(viewBinding.viewPager.currentItem))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFab: ${e.message}")
        }
    }

    private fun openAppPicker(userId: Int) {
        try {
            val intent = Intent(this, ListActivity::class.java)
            intent.putExtra("userID", userId)
            apkPathResult.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching ListActivity: ${e.message}")
        }
    }

    fun showFloatButton(show: Boolean) {
        try {
            val tranY: Float = Resolution.convertDpToPixel(120F, App.getContext())
            val time = 180L
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
            val userList = SpaceUi.sortedUsers()

            if (fragmentList.size == userList.size) {
                fragmentList.add(AppsFragment.newInstance(SpaceUi.nextAvailableId(userList)))
            } else if (fragmentList.size > userList.size + 1) {
                fragmentList.removeLast()
            }

            SpaceUi.ensureUniqueColors(userList)
            mViewPagerAdapter.notifyDataSetChanged()
            updateSpaceHeader(currentUser)
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanUser: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mViewPagerAdapter.isInitialized) {
            currentUser = userIdAt(viewBinding.viewPager.currentItem)
            updateSpaceHeader(currentUser)
        }
    }

    private fun stopSpace(userId: Int) {
        try {
            BlackBoxCore.get().stopUser(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping previous space $userId: ${e.message}")
        }
    }

    private fun userIdAt(position: Int): Int {
        val users = SpaceUi.sortedUsers()
        return users.getOrNull(position) ?: SpaceUi.nextAvailableId(users)
    }

    private val apkPathResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (it.resultCode != RESULT_OK) return@registerForActivityResult
                    val data = it.data ?: return@registerForActivityResult
                    val userId = data.getIntExtra("userID", 0)

                    val sources = data.getStringArrayListExtra("sources")
                            ?: data.getStringExtra("source")?.let { single -> arrayListOf(single) }
                            ?: return@registerForActivityResult
                    if (sources.isEmpty()) return@registerForActivityResult

                    val page = fragmentList.firstOrNull { fragment ->
                        fragment.arguments?.getInt("userID", -1) == userId
                    }
                    if (page == null) {
                        Log.e(TAG, "No page found for space $userId")
                        return@registerForActivityResult
                    }
                    page.installApks(sources)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling APK path result: ${e.message}")
                }
            }
}
