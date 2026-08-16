package top.niunaijun.blackboxa.view.apps

import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.FragmentAppsBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.MemoryManager
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.main.MainActivity
import java.util.*
import kotlin.math.abs


class AppsFragment : Fragment() {

    var userID: Int = 0

    private lateinit var viewModel: AppsViewModel

    private lateinit var mAdapter: RVAdapter<AppInfo>

    private lateinit var mAdapterFactory: AppsAdapter

    private val viewBinding: FragmentAppsBinding by inflate()

    private var popupMenu: PopupMenu? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingAccent: Int = 0
    private var entrancePlayed = false

    /** Safety net: never leave a tile stuck in the "launching" state. */
    private val clearLaunching = Runnable { setLaunching(null) }

    companion object {
        private const val TAG = "AppsFragment"

        /** A cold start of a heavy clone can take a while; this is only a fallback. */
        private const val LAUNCH_INDICATOR_TIMEOUT = 20_000L

        fun newInstance(userID: Int): AppsFragment {
            val fragment = AppsFragment()
            fragment.arguments = bundleOf("userID" to userID)
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            viewModel = ViewModelProvider(this, InjectionUtil.getAppsFactory())
                    .get(AppsViewModel::class.java)
            userID = requireArguments().getInt("userID", 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        try {
            viewBinding.stateView.showEmpty()

            mAdapterFactory = AppsAdapter()
            mAdapterFactory.accentColor = pendingAccent
            mAdapter = RVAdapter<AppInfo>(requireContext(), mAdapterFactory)
                    .bind(viewBinding.recyclerView)

            viewBinding.recyclerView.adapter = mAdapter

            val layoutManager = GridLayoutManager(requireContext(), 2)
            layoutManager.isItemPrefetchEnabled = true
            layoutManager.initialPrefetchItemCount = 8
            viewBinding.recyclerView.layoutManager = layoutManager

            viewBinding.recyclerView.setItemViewCacheSize(20)
            viewBinding.recyclerView.setHasFixedSize(true)

            viewBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    try {
                        super.onScrollStateChanged(recyclerView, newState)
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            MemoryManager.optimizeMemoryForRecyclerView()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in scroll state change: ${e.message}")
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    try {
                        super.onScrolled(recyclerView, dx, dy)
                        if (abs(dy) > 100 && MemoryManager.isMemoryCritical()) {
                            Log.w(TAG, "Memory critical during fast scrolling, forcing GC")
                            MemoryManager.forceGarbageCollectionIfNeeded()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in scroll: ${e.message}")
                    }
                }
            })

            val touchCallBack = AppsTouchCallBack { from, to ->
                try {
                    onItemMove(from, to)
                    viewModel.updateSortLiveData.postValue(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in touch callback: ${e.message}")
                }
            }

            ItemTouchHelper(touchCallBack).attachToRecyclerView(viewBinding.recyclerView)

            mAdapter.setItemClickListener { _, data, _ ->
                try {
                    // While one clone is starting, ignore taps on any tile.
                    if (mAdapterFactory.launchingPackage != null) return@setItemClickListener
                    setLaunching(data.packageName)
                    viewModel.launchApk(data.packageName, userID)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching app: ${e.message}")
                    setLaunching(null)
                }
            }

            interceptTouch()
            setOnLongClick()
            return viewBinding.root
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreateView: ${e.message}")
            return View(requireContext())
        }
    }

    /** Called by MainActivity so the grid picks up the colour of its space. */
    fun setAccentColor(color: Int) {
        pendingAccent = color
        if (this::mAdapterFactory.isInitialized && mAdapterFactory.accentColor != color) {
            mAdapterFactory.accentColor = color
            if (this::mAdapter.isInitialized) mAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Staggered entrance for the grid, played once. The layout animation is
     * cleared afterwards so the later notifyDataSetChanged() calls (the
     * launching indicator) do not replay it.
     */
    private fun playEntrance() {
        if (entrancePlayed) return
        entrancePlayed = true
        try {
            val recycler = viewBinding.recyclerView
            recycler.layoutAnimation = android.view.animation.AnimationUtils
                    .loadLayoutAnimation(requireContext(), R.anim.layout_rise)
            recycler.scheduleLayoutAnimation()
            recycler.postDelayed({ recycler.layoutAnimation = null }, 700)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing entrance: ${e.message}")
        }
    }

    /**
     * The grid divides itself by how many apps the space has: one app fills the
     * screen, and each new app splits the room a bit further. The upper bound
     * still comes from the screen width so it stays sane on a tablet.
     */
    private fun applyGridFor(count: Int) {
        try {
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            val widthCap = (widthDp / 92f).toInt().coerceAtLeast(3)

            val span = when {
                count <= 1 -> 1
                count <= 4 -> 2
                count <= 9 -> 3
                else -> 4
            }.coerceAtMost(widthCap)

            val tileDp = when (span) {
                1 -> 96f
                2 -> 84f
                3 -> 72f
                else -> 60f
            }
            val labelSp = when (span) {
                1 -> 16f
                2 -> 14f
                3 -> 12.5f
                else -> 12f
            }

            val manager = viewBinding.recyclerView.layoutManager as? GridLayoutManager ?: return
            val changed = manager.spanCount != span || mAdapterFactory.tileIconDp != tileDp
            manager.spanCount = span
            mAdapterFactory.tileIconDp = tileDp
            mAdapterFactory.labelSp = labelSp
            if (changed && this::mAdapter.isInitialized) {
                mAdapter.notifyDataSetChanged()
            }

            centerSparseGrid(count, span, tileDp)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying grid: ${e.message}")
        }
    }

    /**
     * Keeps the grid anchored under the header.
     *
     * This used to pad the top so a short grid read as vertically centred, which
     * left two apps floating in the middle of an empty screen and pushed them
     * away from the header they belong to. Content starts where content starts;
     * the empty space below is the room the space has to grow into.
     */
    private fun centerSparseGrid(count: Int, span: Int, tileDp: Float) {
        val recycler = viewBinding.recyclerView
        recycler.post {
            try {
                recycler.setPadding(
                    recycler.paddingLeft, 0, recycler.paddingRight, recycler.paddingBottom
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting grid padding: ${e.message}")
            }
        }
    }

    private fun setLaunching(packageName: String?) {
        try {
            if (mAdapterFactory.launchingPackage == packageName) return
            mAdapterFactory.launchingPackage = packageName
            if (this::mAdapter.isInitialized) {
                mAdapter.notifyDataSetChanged()
            }
            mainHandler.removeCallbacks(clearLaunching)
            if (packageName != null) {
                mainHandler.postDelayed(clearLaunching, LAUNCH_INDICATOR_TIMEOUT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating launching state: ${e.message}")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try {
            super.onViewCreated(view, savedInstanceState)
            initData()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated: ${e.message}")
        }
    }

    override fun onStart() {
        try {
            super.onStart()
            try {
                BlackBoxCore.get().addServiceAvailableCallback {
                    Log.d(TAG, "Services became available, refreshing app list")
                    viewModel.getInstalledAppsWithRetry(userID)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering service available callback: ${e.message}")
            }

            viewModel.getInstalledAppsWithRetry(userID)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStart: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a clone (or from a failed start): drop the spinner.
        setLaunching(null)
    }

    private fun interceptTouch() {
        try {
            val point = Point()
            var isScrolling = false
            var scrollStartTime = 0L

            viewBinding.recyclerView.setOnTouchListener { _, e ->
                try {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isScrolling = false
                            scrollStartTime = System.currentTimeMillis()
                            point.set(0, 0)
                        }

                        MotionEvent.ACTION_UP -> {
                            val scrollDuration = System.currentTimeMillis() - scrollStartTime
                            if (!isScrolling && !isMove(point, e) && scrollDuration < 500) {
                                try {
                                    popupMenu?.show()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error showing popup menu: ${e.message}")
                                }
                            }
                            popupMenu = null
                            point.set(0, 0)
                            isScrolling = false
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (point.x == 0 && point.y == 0) {
                                point.x = e.rawX.toInt()
                                point.y = e.rawY.toInt()
                            }
                            if (isMove(point, e)) {
                                isScrolling = true
                                popupMenu?.dismiss()
                            }
                            isDownAndUp(point, e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in touch listener: ${e.message}")
                }
                return@setOnTouchListener false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in interceptTouch: ${e.message}")
        }
    }

    private fun isMove(point: Point, e: MotionEvent): Boolean {
        return try {
            val max = 40
            abs(point.x - e.rawX) > max || abs(point.y - e.rawY) > max
        } catch (e: Exception) {
            Log.e(TAG, "Error in isMove: ${e.message}")
            false
        }
    }

    private fun isDownAndUp(point: Point, e: MotionEvent) {
        try {
            val min = 10
            val yU = point.y - e.rawY
            if (abs(yU) > min) {
                try {
                    (requireActivity() as? MainActivity)?.showFloatButton(yU < 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing/hiding float button: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in isDownAndUp: ${e.message}")
        }
    }

    private fun onItemMove(fromPosition: Int, toPosition: Int) {
        try {
            val items = mAdapter.getItems()
            if (fromPosition < 0 || toPosition < 0 ||
                    fromPosition >= items.size || toPosition >= items.size) {
                Log.w(TAG, "Invalid positions for move: from=$fromPosition, to=$toPosition, size=${items.size}")
                return
            }

            if (fromPosition < toPosition) {
                for (i in fromPosition until toPosition) {
                    Collections.swap(items, i, i + 1)
                }
            } else {
                for (i in fromPosition downTo toPosition + 1) {
                    Collections.swap(items, i, i - 1)
                }
            }

            try {
                mAdapter.notifyItemMoved(fromPosition, toPosition)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying item moved: ${e.message}")
                mAdapter.notifyDataSetChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onItemMove: ${e.message}")
        }
    }

    private fun setOnLongClick() {
        try {
            mAdapter.setItemLongClickListener { view, data, _ ->
                try {
                    popupMenu = PopupMenu(requireContext(), view).also {
                        it.inflate(R.menu.app_menu)
                        it.setOnMenuItemClickListener { item ->
                            try {
                                when (item.itemId) {
                                    R.id.app_remove -> {
                                        if (data.isXpModule) {
                                            toast(R.string.uninstall_module_toast)
                                        } else {
                                            unInstallApk(data)
                                        }
                                    }

                                    R.id.app_clear -> clearApk(data)

                                    R.id.app_stop -> stopApk(data)

                                    R.id.app_shortcut ->
                                        ShortcutUtil.createShortcut(requireContext(), userID, data)
                                }
                                return@setOnMenuItemClickListener true
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in menu item click: ${e.message}")
                                return@setOnMenuItemClickListener false
                            }
                        }
                        it.show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in long click: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setOnLongClick: ${e.message}")
        }
    }

    private fun initData() {
        try {
            viewBinding.stateView.showLoading()
            viewModel.getInstalledApps(userID)
            viewModel.appsLiveData.observe(viewLifecycleOwner) {
                try {
                    if (it != null) {
                        applyGridFor(it.size)
                        mAdapter.setItems(it)
                        if (it.isEmpty()) {
                            viewBinding.stateView.showEmpty()
                        } else {
                            viewBinding.stateView.showContent()
                            playEntrance()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing apps data: ${e.message}")
                }
            }

            viewModel.resultLiveData.observe(viewLifecycleOwner) {
                try {
                    if (!TextUtils.isEmpty(it)) {
                        hideLoading()
                        requireContext().toast(it)
                        viewModel.getInstalledApps(userID)
                        scanUser()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing result data: ${e.message}")
                }
            }

            viewModel.launchLiveData.observe(viewLifecycleOwner) {
                try {
                    it?.run {
                        if (!it) {
                            setLaunching(null)
                            toast(R.string.start_fail)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing launch data: ${e.message}")
                }
            }

            viewModel.updateSortLiveData.observe(viewLifecycleOwner) {
                try {
                    if (this::mAdapter.isInitialized) {
                        viewModel.updateApkOrder(userID, mAdapter.getItems())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing sort data: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initData: ${e.message}")
        }
    }

    override fun onStop() {
        try {
            super.onStop()
            viewModel.resultLiveData.value = null
            viewModel.launchLiveData.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStop: ${e.message}")
        }
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(clearLaunching)
        super.onDestroyView()
    }

    private fun unInstallApk(info: AppInfo) {
        try {
            MaterialDialog(requireContext()).show {
                title(R.string.uninstall_app)
                message(text = getString(R.string.uninstall_app_hint, info.name))
                positiveButton(R.string.action_remove) {
                    try {
                        showLoading()
                        viewModel.unInstall(info.packageName, userID)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error uninstalling app: ${e.message}")
                        hideLoading()
                    }
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing uninstall dialog: ${e.message}")
        }
    }

    private fun stopApk(info: AppInfo) {
        try {
            MaterialDialog(requireContext()).show {
                title(R.string.app_stop)
                message(text = getString(R.string.app_stop_hint, info.name))
                positiveButton(R.string.action_stop) {
                    try {
                        BlackBoxCore.get().stopPackage(info.packageName, userID)
                        toast(getString(R.string.is_stop, info.name))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping app: ${e.message}")
                    }
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing stop dialog: ${e.message}")
        }
    }

    private fun clearApk(info: AppInfo) {
        try {
            MaterialDialog(requireContext()).show {
                title(R.string.app_clear)
                message(text = getString(R.string.app_clear_hint, info.name))
                positiveButton(R.string.action_clear) {
                    try {
                        showLoading()
                        viewModel.clearApkData(info.packageName, userID)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing app data: ${e.message}")
                        hideLoading()
                    }
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing clear dialog: ${e.message}")
        }
    }

    fun installApk(source: String) {
        installApks(listOf(source))
    }

    /** Adds one or several apps to this space in a single pass. */
    fun installApks(sources: List<String>) {
        try {
            if (sources.isEmpty()) return
            showLoading()
            viewModel.installAll(sources, userID)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APKs: ${e.message}")
            hideLoading()
        }
    }

    private fun scanUser() {
        try {
            (requireActivity() as? MainActivity)?.scanUser()
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning user: ${e.message}")
        }
    }

    private fun showLoading() {
        try {
            (activity as? LoadingActivity)?.showLoading()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing loading: ${e.message}")
        }
    }

    private fun hideLoading() {
        try {
            (activity as? LoadingActivity)?.hideLoading()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding loading: ${e.message}")
        }
    }
}
