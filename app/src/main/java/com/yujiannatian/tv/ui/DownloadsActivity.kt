package com.yujiannatian.tv.ui

import android.os.Bundle
import com.yujiannatian.tv.R

/** 已下载剧集：离线播放 / 删除 */
class DownloadsActivity : BaseTvActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHeader(getString(R.string.downloads_title), "保存在应用私有目录，卸载应用会一并清除")
        setContent(DownloadsFragment())
    }

    override fun onStatusRetry() {
        clearOverlay()
        (supportFragmentManager.findFragmentById(R.id.container) as? DownloadsFragment)?.refresh()
    }
}
