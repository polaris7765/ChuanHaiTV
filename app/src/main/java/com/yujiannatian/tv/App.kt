package com.yujiannatian.tv

import android.app.Application
import com.yujiannatian.tv.core.CrashHandler
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoDownloader
import com.yujiannatian.tv.ui.TouchFocus

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SiteRepository.init(this)
        CrashHandler.install(this)
        // 手机端去掉「第一次触摸只聚焦」的两段式点击；TV 端完全保持原样
        TouchFocus.install(this)
        // 校正上次退出时残留的「下载中」记录，恢复成已完成 / 可续传状态
        VideoDownloader.reconcile(this)
    }
}
