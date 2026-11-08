package com.yujiannatian.tv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.widget.EditText
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.SiteConfig

/** 拼音首字母模糊搜索页 */
@SuppressLint("RestrictedApi")
class SearchActivity : BaseTvActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHeader(getString(R.string.search_title), "拼音首字母 / 中文片名 · ${SiteConfig.baseUrl}")
        setContent(SearchFragment())
    }

    override fun onStatusRetry() {
        clearOverlay()
        (supportFragmentManager.findFragmentById(R.id.container) as? SearchFragment)?.retry()
    }

    /** 焦点在屏上键盘时，物理键盘输入的字母也直接追加到查询框 */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isPrintingKey &&
            currentFocus !is EditText
        ) {
            val c = event.unicodeChar.toChar()
            if (c.isLetterOrDigit()) {
                (supportFragmentManager.findFragmentById(R.id.container) as? SearchFragment)
                    ?.appendChar(c)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
