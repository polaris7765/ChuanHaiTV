package com.yujiannatian.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.Category
import com.yujiannatian.tv.core.SiteConfig

/** 分类资源列表页（5 列网格，遥控器方向键翻页） */
class GridActivity : BaseTvActivity() {

    companion object {
        const val EXTRA_NAME = "category_name"
        const val EXTRA_PATH = "category_path"

        fun start(context: Context, category: Category) {
            context.startActivity(
                Intent(context, GridActivity::class.java)
                    .putExtra(EXTRA_NAME, category.name)
                    .putExtra(EXTRA_PATH, category.path)
            )
        }
    }

    private lateinit var categoryName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categoryName = intent.getStringExtra(EXTRA_NAME) ?: "全部资源"
        val path = intent.getStringExtra(EXTRA_PATH) ?: "/"
        showHeader(categoryName, "正在加载…\n${SiteConfig.baseUrl}")
        setContent(GridFragment.newInstance(path))
    }

    fun onPageInfo(total: Int, page: Int, pages: Int) {
        showHeader(
            categoryName,
            "共 $total 部 · 第 $page/$pages 页 · 方向键移动、确认进入详情 · ${SiteConfig.baseUrl}"
        )
    }

    override fun onStatusRetry() {
        clearOverlay()
        (supportFragmentManager.findFragmentById(R.id.container) as? GridFragment)?.retry()
    }
}
