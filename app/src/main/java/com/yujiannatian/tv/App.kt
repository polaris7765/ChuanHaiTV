package com.yujiannatian.tv

import android.app.Application
import com.yujiannatian.tv.core.CrashHandler
import com.yujiannatian.tv.core.SiteRepository

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SiteRepository.init(this)
        CrashHandler.install(this)
    }
}
