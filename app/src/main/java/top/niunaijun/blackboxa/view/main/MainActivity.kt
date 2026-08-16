package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
import top.niunaijun.blackboxa.view.base.Ambient
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0
    private var selectedRealUser: Int? = null
    private var currentColor = 0

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
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_wordmark)
            // A status line under the wordmark: what this app manages, and how
            // much of it there is. It costs one line and it is the difference
            // between a title bar and a product header.
            viewBinding.toolbarLayout.toolbar.subtitle = resources
                    .getQuantityString(
                            R.plurals.space_count,
                            SpaceUi.sortedUsers().size,
                            SpaceUi.sortedUsers().size
                    ) + " · " + getString(R.string.product_tagline)
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

            // On launch, greet and let the user pick which space to enter.
            if (savedInstanceState == null && SpaceUi.sortedUsers().size > 1) {
                viewBinding.viewPager.post { showWelcome() }
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

    /**
     * Paints the whole screen with the current space's colour: ambient glow,
     * hero border, dot and FAB. Switching spaces crossfades the glow so the
     * change reads as a transition instead of a flicker.
     */
    private fun applySpaceIdentity(userId: Int, animate: Boolean) {
        try {
            val base = SpaceUi.colorOf(userId)
            val changed = base != currentColor
            currentColor = base

            // The panel keeps its own structural surface and hairline. Repainting
            // it per space is what made the screen read as "whatever colour this
            // space is"; the dot, the wash and the switch control carry context.
            // The workspace surface is drawn by the layout; the space section is
            // part of it, not a card of its own.
            // The drawable declared in XML is shared by every view referencing
            // @drawable/bg_dot, so it must never be tinted in place.
            viewBinding.spaceDot.background = Ambient.dot(base)
            viewBinding.spaceChevron.setColorFilter(base)

            val glow = Ambient.glow(this, base)
            if (animate && changed && viewBinding.glow.background != null) {
                viewBinding.glow.animate().alpha(0f).setDuration(130).withEndAction {
                    viewBinding.glow.background = glow
                    viewBinding.glow.animate().alpha(1f).setDuration(280).start()
                }.start()
            } else {
                viewBinding.glow.background = glow
                viewBinding.glow.alpha = 1f
            }

            fragmentList.forEach { it.setAccentColor(base) }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying space identity: ${e.message}")
        }
    }

    /**
     * Makes the grid as tall as the apps it holds.
     *
     * The workspace surface wraps its content, so sizing the pager is what sizes
     * the region: the panel ends where the apps end and the rest of the screen is
     * background. Measured from the same span and tile rule the grid itself uses,
     * so the two cannot disagree.
     *
     * An empty space gets a taller pager, because the empty state is centred in
     * it and wants the room.
     */
    private fun sizeAppsPanel(count: Int) {
        try {
            val density = resources.displayMetrics.density
            val heightDp = if (count == 0) {
                260f
            } else {
                val widthDp = resources.displayMetrics.widthPixels / density
                val span = AppsFragment.spanFor(count, widthDp)
                val rows = Math.ceil(count / span.toDouble()).toInt()
                (rows * AppsFragment.rowHeightDp(span)).coerceAtMost(
                        resources.displayMetrics.heightPixels / density * 0.46f)
            }
            viewBinding.viewPager.layoutParams = viewBinding.viewPager.layoutParams.apply {
                height = (heightDp * density).toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sizing apps grid: ${e.message}")
        }
    }

    private fun updateSpaceHeader(userId: Int, animate: Boolean = false) {
        try {
            val users = SpaceUi.sortedUsers()
            val position = users.indexOf(userId)

            val count = SpaceUi.appCount(userId)
            // The empty state already explains an empty space; a section label
            // over nothing would just be a heading with no section.
            viewBinding.appsSection.visibility = if (count == 0) View.GONE else View.VISIBLE
            // INVISIBLE, not GONE: the count is the weighted spacer that holds
            // "Adicionar" against the right edge. Removing it let the action slide
            // to the left the moment a space had no apps.
            viewBinding.appsCount.visibility =
                    if (count == 0) View.INVISIBLE else View.VISIBLE
            viewBinding.appsCount.text = count.toString()
            sizeAppsPanel(count)
            val countText = if (count == 0) {
                getString(R.string.space_empty_summary)
            } else {
                resources.getQuantityString(R.plurals.space_apps_count, count, count)
            }
            val summary = if (position >= 0 && users.size > 1) {
                "$countText · " + getString(R.string.space_position, position + 1, users.size)
            } else {
                countText
            }

            viewBinding.spaceName.text = SpaceUi.nameOf(userId)
            viewBinding.spaceSummary.text = summary
            applySpaceIdentity(userId, animate)

            if (animate) {
                listOf(viewBinding.spaceName, viewBinding.spaceSummary).forEach { view ->
                    view.alpha = 0f
                    view.translationY = Ambient.dp(this, 8f)
                    view.animate().alpha(1f).translationY(0f).setDuration(240).start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating space header: ${e.message}")
        }
    }

    // --------------------------------------------------------------- welcome

    private var welcomeGlowColor = 0
    private var welcomeScrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    /**
     * Launch screen: a greeting plus one card per space, side by side. Picking an
     * account should feel like choosing a card, not scrolling a menu; the full
     * list with rename/colour/delete stays one tap away under "Gerenciar espaços".
     */
    private fun showWelcome() {
        try {
            val users = SpaceUi.sortedUsers()
            if (users.isEmpty()) return
            SpaceUi.ensureUniqueColors(users)

            val container = viewBinding.welcomeCards
            container.removeAllViews()
            // Two columns flowing downwards. Cards take an equal share of the
            // width rather than a fixed one, so nothing is ever clipped at the
            // screen edge and a single space still fills its column properly.
            container.columnCount = 2

            var slot = 0
            fun place(card: View) {
                val column = slot % 2
                card.layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(88f)
                    columnSpec = GridLayout.spec(column, 1f)
                    rowSpec = GridLayout.spec(slot / 2)
                    marginStart = if (column == 0) 0 else dp(5f)
                    marginEnd = if (column == 0) dp(5f) else 0
                    bottomMargin = dp(10f)
                }
                slot++
                container.addView(card)
            }

            users.forEachIndexed { index, userId ->
                val color = SpaceUi.colorOf(userId, users)
                val onColor = Ambient.onColor(color)
                val name = SpaceUi.nameOf(userId)

                val card = layoutInflater.inflate(R.layout.item_space_card, container, false)
                // Neutral card, coloured monogram. Eight tinted cards side by side
                // read as eight themes; eight neutral cards with coloured initials
                // read as one product with eight spaces in it.
                // No fill: the block behind them already groups the entries, and
                // giving each one a surface would rebuild the grid of cards this
                // replaced. Only the press state is drawn.
                card.background = Ambient.pressable(
                        this,
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                        Ambient.dp(this, 16f))

                val accent = Ambient.readable(this, color)
                card.findViewById<TextView>(R.id.cardInitial).apply {
                    text = name.trim().take(1).uppercase()
                    setTextColor(accent)
                    background = Ambient.chip(color, Ambient.dp(this@MainActivity, 13f))
                }
                card.findViewById<TextView>(R.id.cardName).apply {
                    text = name
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ds_on_surface))
                }
                val count = SpaceUi.appCount(userId)
                card.findViewById<TextView>(R.id.cardCount).apply {
                    text = if (count == 0) {
                        getString(R.string.space_empty_summary)
                    } else {
                        resources.getQuantityString(R.plurals.space_apps_count, count, count)
                    }
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ds_on_surface_muted))
                }

                card.setOnClickListener {
                    hideWelcome()
                    viewBinding.viewPager.setCurrentItem(index, false)
                }
                place(card)
            }

            val addCard = layoutInflater.inflate(R.layout.item_space_card_add, container, false)
            // Same shape as a space entry, and the product's lavender where a real
            // space would carry its own colour: told apart by role, not by frame.
            val productAccent = ContextCompat.getColor(this, R.color.ds_accent)
            addCard.background = Ambient.pressable(
                    this,
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                    Ambient.dp(this, 16f))
            addCard.findViewById<View>(R.id.addSlot).background =
                    Ambient.chip(productAccent, Ambient.dp(this, 13f))
            addCard.setOnClickListener {
                hideWelcome()
                createNewSpace()
            }
            place(addCard)

            // A tonal surface with a ripple, so the secondary action still looks
            // pressable. As bare text it read as a caption nobody would tap.
            viewBinding.welcomeManage.background = Ambient.pressable(
                    this,
                    Ambient.tile(this, Ambient.dp(this, 14f)),
                    Ambient.dp(this, 14f)
            )
            viewBinding.welcomeManage.setOnClickListener {
                hideWelcome()
                showSpacePicker()
            }
            viewBinding.welcomeClose.setOnClickListener { hideWelcome() }

            // The ambient light follows whichever pair of cards is in front.
            welcomeGlowColor = 0
            updateWelcomeGlow(users)
            welcomeScrollListener = ViewTreeObserver.OnScrollChangedListener {
                updateWelcomeGlow(users)
            }
            viewBinding.welcomeScroll.viewTreeObserver
                    .addOnScrollChangedListener(welcomeScrollListener)

            val overlay = viewBinding.welcomeOverlay
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(220).start()

            container.layoutAnimation = android.view.animation.AnimationUtils
                    .loadLayoutAnimation(this, R.anim.layout_slide_in)
            container.scheduleLayoutAnimation()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing welcome: ${e.message}")
        }
    }

    private fun updateWelcomeGlow(users: List<Int>) {
        try {
            // Two spaces per column, so a column step covers two entries.
            val step = Ambient.dp(this, 170f + 12f)
            val column = ((viewBinding.welcomeScroll.scrollX + step / 2f) / step).toInt()
            val index = (column * 2).coerceIn(0, users.size - 1)
            val color = SpaceUi.colorOf(users[index], users)
            if (color != welcomeGlowColor) {
                welcomeGlowColor = color
                viewBinding.welcomeGlow.background = Ambient.glow(this, color)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating welcome glow: ${e.message}")
        }
    }

    private fun hideWelcome() {
        try {
            val overlay = viewBinding.welcomeOverlay
            if (overlay.visibility != View.VISIBLE) return

            welcomeScrollListener?.let {
                viewBinding.welcomeScroll.viewTreeObserver.removeOnScrollChangedListener(it)
            }
            welcomeScrollListener = null

            overlay.animate().alpha(0f).setDuration(180)
                    .withEndAction { overlay.visibility = View.GONE }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding welcome: ${e.message}")
        }
    }

    override fun onBackPressed() {
        if (viewBinding.welcomeOverlay.visibility == View.VISIBLE) {
            hideWelcome()
            return
        }
        super.onBackPressed()
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
                val rowColor = SpaceUi.colorOf(userId, users)
                val isCurrent = userId == currentUser

                // The panel around the list owns the boundary; a row only marks
                // itself when it is the current one.
                if (index > 0) {
                    container.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            marginStart = dp(56f)
                            marginEnd = dp(8f)
                        }
                        setBackgroundColor(ContextCompat.getColor(
                                this@MainActivity, R.color.ds_outline))
                    })
                }
                row.background = Ambient.panelRow(
                        this, rowColor, Ambient.dp(this, 14f), selected = isCurrent)
                row.findViewById<View>(R.id.spaceChip).background =
                        Ambient.chip(rowColor, Ambient.dp(this, 14f))

                val spaceName = SpaceUi.nameOf(userId)
                row.findViewById<TextView>(R.id.spaceName).text = spaceName
                // The initial does the work the colour cannot: with eight spaces
                // several colours land close together, and a letter still reads.
                row.findViewById<TextView>(R.id.spaceMonogram).apply {
                    text = spaceName.trim().take(1).uppercase()
                    setTextColor(Ambient.readable(this@MainActivity, rowColor))
                }

                val count = SpaceUi.appCount(userId)
                row.findViewById<TextView>(R.id.spaceSummary).text = if (count == 0) {
                    getString(R.string.space_empty_summary)
                } else {
                    resources.getQuantityString(R.plurals.space_apps_count, count, count)
                }

                val activeTag = row.findViewById<TextView>(R.id.spaceActive)
                if (isCurrent) {
                    activeTag.visibility = View.VISIBLE
                    activeTag.background =
                            Ambient.softPill(rowColor, Ambient.dp(this, 8f), 38)
                    // Lifted only as far as legibility needs: the colour still
                    // reads as the space's own.
                    activeTag.setTextColor(Ambient.readable(this, rowColor))
                } else {
                    activeTag.visibility = View.GONE
                }

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

            container.layoutAnimation = android.view.animation.AnimationUtils
                    .loadLayoutAnimation(this, R.anim.layout_slide_in)

            applyNavigationBarPadding(root)
            fitSheet(root, scroll)
            sheet.setContentView(root)
            expand(sheet)
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
     * Sheets open fully. Left collapsed, BottomSheetDialog would show only its
     * peek height and hide the rows behind a drag the user has no reason to
     * discover.
     */
    private fun expand(sheet: BottomSheetDialog) {
        try {
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        } catch (e: Exception) {
            Log.e(TAG, "Error expanding sheet: ${e.message}")
        }
    }

    /**
     * A sheet with many spaces is taller than the screen. Measure the content
     * before attaching it and give the scrolling area exactly the leftover
     * height, so the pinned footer is always visible above the navigation bar.
     */
    private fun fitSheet(root: View, scroll: View) {
        try {
            val metrics = resources.displayMetrics
            val insets = ViewCompat.getRootWindowInsets(viewBinding.root)
                    ?.getInsets(WindowInsetsCompat.Type.systemBars())
            val available = metrics.heightPixels -
                    (insets?.top ?: 0) -
                    (insets?.bottom ?: navigationBarHeight()) -
                    dp(20f)

            root.measure(
                    View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))

            val overflow = root.measuredHeight - available
            if (overflow > 0) {
                scroll.layoutParams.height =
                        (scroll.measuredHeight - overflow).coerceAtLeast(dp(180f))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fitting sheet: ${e.message}")
        }
    }

    /**
     * The per-space contextual panel.
     *
     * Built from views rather than from a PopupMenu. A platform menu with colours
     * applied still reads as a platform menu — its row height, its padding and
     * its width are Android's, and against panels that are this app's own it was
     * the one component that gave the game away. This is the same surface,
     * hairline and radius as everything else, at the product's density.
     */
    private fun showSpaceMenu(anchor: View, userId: Int, spaceCount: Int, sheet: BottomSheetDialog) {
        try {
            val root = layoutInflater.inflate(R.layout.view_popover, null) as LinearLayout
            val popup = android.widget.PopupWindow(
                    root,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    true
            ).apply {
                elevation = Ambient.dp(this@MainActivity, 12f)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.TRANSPARENT))
            }

            fun divider() {
                root.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        topMargin = dp(4f)
                        bottomMargin = dp(4f)
                    }
                    setBackgroundColor(ContextCompat.getColor(
                            this@MainActivity, R.color.ds_outline))
                })
            }

            fun action(labelRes: Int, iconRes: Int, tintRes: Int, run: () -> Unit) {
                val item = layoutInflater.inflate(R.layout.item_popover, root, false)
                val tint = ContextCompat.getColor(this, tintRes)
                item.findViewById<ImageView>(R.id.popoverIcon).apply {
                    setImageResource(iconRes)
                    setColorFilter(tint)
                }
                item.findViewById<TextView>(R.id.popoverLabel).apply {
                    setText(labelRes)
                    setTextColor(tint)
                }
                item.setOnClickListener {
                    popup.dismiss()
                    run()
                }
                root.addView(item)
            }

            action(R.string.rename_space, R.drawable.ic_edit_24, R.color.ds_on_surface) {
                sheet.dismiss(); showRenameDialog(userId)
            }
            action(R.string.space_color, R.drawable.ic_palette_24, R.color.ds_on_surface) {
                sheet.dismiss(); showColorPicker(userId)
            }
            action(R.string.stop_space, R.drawable.ic_stop_24, R.color.ds_on_surface) {
                sheet.dismiss(); confirmStopSpace(userId)
            }

            // The one irreversible action, in its own group.
            divider()
            action(R.string.delete_space, R.drawable.ic_delete_24, R.color.ds_danger) {
                sheet.dismiss()
                if (spaceCount <= 1) {
                    toast(R.string.delete_space_last)
                } else {
                    confirmDeleteSpace(userId)
                }
            }

            popup.showAsDropDown(anchor, -dp(160f), -dp(8f))
        } catch (e: Exception) {
            Log.e(TAG, "Error showing space menu: ${e.message}")
        }
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

    /**
     * Explicit "stop this space": frees its RAM by killing the guest processes.
     * It is deliberately a manual action — the same call used to run on every
     * page change and cost Instagram its logged-in session.
     */
    private fun confirmStopSpace(userId: Int) {
        try {
            MaterialDialog(this).show {
                title(res = R.string.stop_space)
                message(text = getString(R.string.stop_space_message, SpaceUi.nameOf(userId)))
                positiveButton(res = R.string.action_stop) {
                    stopSpace(userId)
                    toast(R.string.stop_space_done)
                }
                negativeButton(res = R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error confirming space stop: ${e.message}")
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

                // Selection is a ring plus a tick, never the colour alone: two
                // swatches of similar hue are otherwise impossible to tell apart,
                // and the tick has to survive on a light swatch and a dark one.
                if (color == current) {
                    cell.addView(View(this).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.CENTER)
                        background = Ambient.halo(color, dp(19f).toFloat(), 255)
                    })
                }

                val swatch = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.CENTER)
                    background = Ambient.swatch(color, dp(15f).toFloat())
                }
                cell.addView(swatch)

                if (color == current) {
                    cell.addView(ImageView(this).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER)
                        setImageResource(R.drawable.ic_check_24)
                        setColorFilter(Ambient.onColor(color))
                    })
                }
                cell.setOnClickListener {
                    SpaceUi.setColor(userId, color)
                    if (userId == currentUser) updateSpaceHeader(userId)
                    sheet.dismiss()
                }
                grid.addView(cell)
            }

            applyNavigationBarPadding(root)
            sheet.setContentView(root)
            expand(sheet)
            sheet.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing colour picker: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // The overflow hides icons by default. Showing them keeps this menu
        // consistent with the per-space one, and tints them to the muted content
        // token so they support the label rather than compete with it.
        if (menu is androidx.appcompat.view.menu.MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        val tint = ContextCompat.getColor(this, R.color.ds_on_surface_muted)
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.mutate()?.setTint(tint)
        }
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
                                // Browsing to another page must never terminate the
                                // space you came from: stopUser() SIGKILLs its guest
                                // processes, and Instagram loses the session write it
                                // had pending. Stopping a space is an explicit action
                                // now (space menu -> "Parar espaço").
                                if (isRealSpace) {
                                    selectedRealUser = selectedUser
                                }
                                currentUser = selectedUser
                                updateSpaceHeader(currentUser, animate = true)
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
            // Adding an app is an action on the apps panel, so it lives in that
            // panel's header. A floating button hovering over an empty screen
            // said nothing about what it added to.
            viewBinding.appsAdd.setOnClickListener {
                openAppPicker(userIdAt(viewBinding.viewPager.currentItem))
            }
            viewBinding.spaceSwitch.setOnClickListener { showSpacePicker() }
        } catch (e: Exception) {
            Log.e(TAG, "Error wiring panel actions: ${e.message}")
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

    /**
     * Kept because AppsFragment still reports scroll direction.
     *
     * The add action sits in the apps panel header now: it scrolls with nothing,
     * covers no row, and never needs to get out of the way. There is no floating
     * button left to show or hide.
     */
    @Suppress("UNUSED_PARAMETER")
    fun showFloatButton(show: Boolean) {
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
