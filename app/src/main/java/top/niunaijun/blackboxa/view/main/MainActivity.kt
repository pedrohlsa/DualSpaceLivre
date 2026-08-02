package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.viewpager2.widget.ViewPager2
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
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
                try {
                    MaterialDialog(this).show {
                        title(res = R.string.userRemark)
                        input(
                                hintRes = R.string.userRemark,
                                prefill = viewBinding.toolbarLayout.toolbar.subtitle
                        ) { _, input ->
                            try {
                                AppManager.mRemarkSharedPreferences.edit {
                                    putString("Remark$currentUser", input.toString())
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in initToolbarSubTitle: ${e.message}")
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
