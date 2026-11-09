package com.yujiannatian.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.CrashHandler

class MainActivity : BaseTvActivity() {

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // 上次异常退出时先展示现场报告，由用户按「重试」再加载，避免一启动就重复崩溃
            val crash = CrashHandler.lastCrash(this)
            if (crash != null) showCrash(CrashHandler.summary(crash)) else showHome(false)
            if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings()
    }

    fun showHome(forceRefresh: Boolean = false) {
        hideHeader() // 首页是根页面，不显示返回按钮
        clearOverlay()
        setContent(HomeFragment.newInstance(forceRefresh))
    }

    fun showSettings() {
        CrashHandler.clear(this)
        clearOverlay()
        // 标题栏不再显示站点 http 地址（搜索 / 分类索引 / 设置统一）
        showHeader(getString(R.string.settings_title))
        setContent(SettingsFragment())
    }

    /** 「联系作者」：作者信息、赞赏二维码与版权联系邮箱 */
    fun showContact() {
        CrashHandler.clear(this)
        clearOverlay()
        showHeader(getString(R.string.contact_title))
        setContent(ContactFragment())
    }

    /** 首页加载失败或崩溃报告页的重试：清除记录并强制跳过缓存重新抓取分类 */
    override fun onStatusRetry() {
        CrashHandler.clear(this)
        showHome(true)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val overlayVisible = findViewById<FrameLayout>(R.id.overlay).visibility == View.VISIBLE
        val current: Fragment? = supportFragmentManager.findFragmentById(R.id.container)
        when {
            overlayVisible -> clearOverlay()
            current is SettingsFragment || current is ContactFragment -> showHome(false)
            else -> super.onBackPressed()
        }
    }
}
