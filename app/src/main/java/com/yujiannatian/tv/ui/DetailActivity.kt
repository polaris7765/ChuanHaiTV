package com.yujiannatian.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.LibraryEntry
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.VideoItem

/** 节目详情 + 播放源 / 选集页（紧凑排版，剧集卡片自带下载按钮） */
class DetailActivity : BaseTvActivity() {

    companion object {
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_PATH = "video_path"
        const val EXTRA_COVER = "video_cover"
        const val EXTRA_RESUME_PATH = "resume_path"
        const val EXTRA_RESUME_POSITION = "resume_position"

        fun start(context: Context, item: VideoItem) {
            context.startActivity(
                Intent(context, DetailActivity::class.java)
                    .putExtra(EXTRA_TITLE, item.title)
                    .putExtra(EXTRA_PATH, item.path)
                    .putExtra(EXTRA_COVER, item.cover)
            )
        }

        /** 从历史 / 收藏进入：带上次观看的剧集与进度，详情页加载后直接续播 */
        fun startResume(context: Context, entry: LibraryEntry) {
            context.startActivity(
                Intent(context, DetailActivity::class.java)
                    .putExtra(EXTRA_TITLE, entry.video.title)
                    .putExtra(EXTRA_PATH, entry.video.path)
                    .putExtra(EXTRA_COVER, entry.video.cover)
                    .putExtra(EXTRA_RESUME_PATH, entry.episodePath)
                    .putExtra(EXTRA_RESUME_POSITION, entry.position)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "节目详情"
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        val resumePath = intent.getStringExtra(EXTRA_RESUME_PATH).orEmpty()
        val resumePosition = intent.getLongExtra(EXTRA_RESUME_POSITION, 0L)
        // 单行紧凑标题栏：左上角返回 + 片名 + 当前站点，不再使用旧版两行大标题
        // （两行标题会把 1080p 的选集网格压成 0 高度）。
        showHeader(title, SiteConfig.baseUrl)
        val fragment = if (resumePath.isNotBlank()) {
            DetailFragment.newInstance(path, title, cover, resumePath, resumePosition)
        } else {
            DetailFragment.newInstance(path, title, cover)
        }
        setContent(fragment)
    }

    override fun onStatusRetry() {
        clearOverlay()
        (supportFragmentManager.findFragmentById(R.id.container) as? DetailFragment)?.retry()
    }
}
