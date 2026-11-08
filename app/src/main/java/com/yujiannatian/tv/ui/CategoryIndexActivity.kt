package com.yujiannatian.tv.ui

import android.os.Bundle
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.SiteConfig

/** 全部子分类索引（独立页面，首页右栏不再堆叠分类检索卡片） */
class CategoryIndexActivity : BaseTvActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHeader(getString(R.string.categories_title), SiteConfig.baseUrl)
        setContent(CategoryIndexFragment())
    }

    override fun onStatusRetry() {
        clearOverlay()
        setContent(CategoryIndexFragment())
    }
}
