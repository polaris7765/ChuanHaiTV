package com.yujiannatian.tv.ui

import com.yujiannatian.tv.R
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView

/**
 * 启动页：展示品牌启动图（appSplash.png），短暂展示后跳转到主页。
 * 同时承担 LAUNCHER / LEANBACK_LAUNCHER 入口，把原始 extras（如「打开设置」）转发给 MainActivity。
 */
class SplashActivity : Activity() {

    companion object {
        /** 启动图展示时长（毫秒） */
        private const val DISPLAY_MS = 1200L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            val forward = Intent(this, MainActivity::class.java).apply {
                putExtras(intent.extras ?: Bundle.EMPTY)
            }
            startActivity(forward)
            finish()
        }, DISPLAY_MS)
    }
}
