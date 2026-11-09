package com.yujiannatian.tv

import android.app.Application
import com.yujiannatian.tv.core.CrashHandler
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoDownloader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SiteRepository.init(this)
        CrashHandler.install(this)
        // 校正上次退出时残留的「下载中」记录，恢复成已完成 / 可续传状态
        VideoDownloader.reconcile(this)
    }
}
