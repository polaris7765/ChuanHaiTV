package com.yujiannatian.tv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteError

/** 公共容器：主内容 + 可覆盖的状态层，保证重试时下层 Fragment 不被销毁 */
abstract class BaseTvActivity : FragmentActivity(), StatusHost {

    companion object {
        private const val TAG_STATUS = "status_overlay"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TvFullscreen.apply(this)
        findViewById<View>(R.id.btn_back).setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) TvFullscreen.apply(this)
    }

    fun showHeader(title: String, subtitle: String = "") {
        findViewById<View>(R.id.header).visibility = View.VISIBLE
        findViewById<TextView>(R.id.title).text = title
        findViewById<TextView>(R.id.subtitle).text = subtitle
    }

    /** Fragment 自带标题时隐藏全局两行标题栏，避免重复标题并腾出纵向空间 */
    fun hideHeader() {
        findViewById<View>(R.id.header).visibility = View.GONE
    }

    fun setContent(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commitAllowingStateLoss()
    }

    fun showLoading(text: String = "正在加载…") {
        showOverlay(StatusFragment.loading(text))
    }

    fun showError(error: SiteError) {
        showOverlay(StatusFragment.error(error))
    }

    fun showCrash(summary: String) {
        showOverlay(StatusFragment.crash(summary))
    }

    fun clearOverlay() {
        // 用 tag 查找：事务是异步提交的，刚 showLoading 就 clear 时
        // findFragmentById 可能仍为 null，导致状态层残留并遮住内容。
        supportFragmentManager.findFragmentByTag(TAG_STATUS)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }
        findViewById<FrameLayout>(R.id.overlay).visibility = View.GONE
    }

    private fun showOverlay(fragment: Fragment) {
        findViewById<FrameLayout>(R.id.overlay).visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.overlay, fragment, TAG_STATUS)
            .commitAllowingStateLoss()
    }

    fun openSiteSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (findViewById<FrameLayout>(R.id.overlay).visibility == View.VISIBLE) {
            clearOverlay()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStatusChangeSite() = openSiteSettings()

    override fun onStatusResetSite() {
        SiteConfig.reset()
        onStatusRetry()
    }

    /**
     * 网络失败 / 解析异常 / 崩溃报告页的「返回」：
     * 首页（根页面）回主菜单，其它页面直接 finish 回到上一级 Activity。
     * 错误覆盖层是全屏的，下层标题栏的返回按钮被遮住，必须在这里提供出口。
     */
    override fun onStatusBack() {
        Log.i("YuJianTV", "状态页返回：${javaClass.simpleName} → 上一级")
        clearOverlay()
        if (this is MainActivity) showHome(false) else finish()
    }
}
