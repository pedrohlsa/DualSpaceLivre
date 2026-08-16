package top.niunaijun.blackboxa.view.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.databinding.ActivityListBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.Ambient
import top.niunaijun.blackboxa.view.base.BaseActivity

class ListActivity : BaseActivity() {

    private val viewBinding: ActivityListBinding by inflate()

    private lateinit var mAdapter: RVAdapter<InstalledAppBean>

    private lateinit var mAdapterFactory: ListAdapter

    private lateinit var viewModel: ListViewModel

    private var appList: List<InstalledAppBean> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.installed_app, true)
        // The toolbar title is redundant with the big heading below it.
        viewBinding.toolbarLayout.toolbar.title = ""
        initAmbient()

        mAdapterFactory = ListAdapter()
        mAdapter = RVAdapter<InstalledAppBean>(this, mAdapterFactory)
                .bind(viewBinding.recyclerView)
                .setItemClickListener { _, item, _ -> toggleSelection(item) }

        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
        // Rows are separated by a hairline inside the region, the way the spaces
        // sheet does it. A card per app turned a list of forty into forty
        // floating boxes.
        viewBinding.recyclerView.addItemDecoration(
                androidx.recyclerview.widget.DividerItemDecoration(
                        this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
                ).apply {
                    androidx.core.content.ContextCompat
                            .getDrawable(this@ListActivity, R.drawable.divider_row)
                            ?.let { setDrawable(it) }
                })

        initSearch()
        initAddBar()
        initViewModel()
    }

    private fun initAmbient() {
        val accent = ContextCompat.getColor(this, R.color.ds_violet)
        viewBinding.glow.background = Ambient.glow(this, accent)
        viewBinding.recyclerView.layoutAnimation = android.view.animation.AnimationUtils
                .loadLayoutAnimation(this, R.anim.layout_slide_in)
    }

    // ---------------------------------------------------------------- search

    private fun initSearch() {
        // A plain TextWatcher on a stock EditText: the previous SimpleSearchView
        // echoed composing text back into the field, which duplicated characters
        // typed with a dead-key accent (ã, ç, é...).
        viewBinding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                viewBinding.searchClear.visibility =
                        if (query.isEmpty()) View.GONE else View.VISIBLE
                filterApp(query)
            }
        })

        viewBinding.searchClear.setOnClickListener {
            viewBinding.searchInput.setText("")
        }
    }

    // ------------------------------------------------------------ selection

    private fun toggleSelection(item: InstalledAppBean) {
        if (item.isInstall) {
            toast(R.string.already_in_space)
            return
        }
        if (!mAdapterFactory.selected.remove(item.packageName)) {
            mAdapterFactory.selected.add(item.packageName)
        }
        mAdapter.notifyDataSetChanged()
        updateAddBar()
    }

    private fun initAddBar() {
        viewBinding.addButton.setOnClickListener {
            val selection = mAdapterFactory.selected.toList()
            if (selection.isNotEmpty()) {
                finishWithResult(selection)
            }
        }
        updateAddBar()
    }

    private fun updateAddBar() {
        val count = mAdapterFactory.selected.size
        val bar = viewBinding.addBar
        if (count == 0) {
            if (bar.visibility == View.VISIBLE) {
                bar.animate().translationY(bar.height.toFloat()).alpha(0f).setDuration(160)
                        .withEndAction { bar.visibility = View.GONE }.start()
            }
            return
        }

        viewBinding.addButton.text =
                resources.getQuantityString(R.plurals.add_n_apps, count, count)
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = Ambient.dp(this, 80f)
            bar.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
    }

    // ------------------------------------------------------------- view model

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getListFactory())
                .get(ListViewModel::class.java)
        val userID = intent.getIntExtra("userID", 0)
        viewModel.getInstallAppList(userID)

        viewModel.loadingLiveData.observe(this) {
            if (it) {
                viewBinding.stateView.showLoading()
            } else {
                viewBinding.stateView.showContent()
            }
        }

        viewModel.appsLiveData.observe(this) {
            if (it != null) {
                this.appList = it
                filterApp(viewBinding.searchInput.text?.toString().orEmpty())
                if (it.isNotEmpty()) {
                    viewBinding.stateView.showContent()
                    viewModel.previewInstalledList()
                } else {
                    viewBinding.stateView.showEmpty()
                }
            }
        }
    }

    private fun filterApp(newText: String) {
        val newList = if (newText.isBlank()) {
            appList
        } else {
            appList.filter {
                it.name.contains(newText, true) || it.packageName.contains(newText, true)
            }
        }
        mAdapter.setItems(newList)

        if (appList.isNotEmpty() && newList.isEmpty()) {
            viewBinding.stateView.showEmpty()
        } else if (appList.isNotEmpty()) {
            viewBinding.stateView.showContent()
        }
    }

    // ---------------------------------------------------------------- result

    private val openDocumentedResult =
            registerForActivityResult(ActivityResultContracts.GetContent()) {
                it?.run { finishWithResult(listOf(it.toString())) }
            }

    private fun finishWithResult(sources: List<String>) {
        intent.putStringArrayListExtra("sources", ArrayList(sources))
        // Kept for compatibility with any caller that still reads a single value.
        intent.putExtra("source", sources.first())
        setResult(Activity.RESULT_OK, intent)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run { imm.hideSoftInputFromWindow(windowToken, 0) }
        finish()
    }

    override fun onBackPressed() {
        if (!viewBinding.searchInput.text.isNullOrEmpty()) {
            viewBinding.searchInput.setText("")
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.list_choose) {
            openDocumentedResult.launch("application/vnd.android.package-archive")
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)
        return true
    }

    override fun onStop() {
        super.onStop()
        viewModel.loadingLiveData.postValue(true)
        viewModel.loadingLiveData.removeObservers(this)
        viewModel.appsLiveData.postValue(null)
        viewModel.appsLiveData.removeObservers(this)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ListActivity::class.java)
            context.startActivity(intent)
        }
    }
}
