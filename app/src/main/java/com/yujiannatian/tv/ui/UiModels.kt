package com.yujiannatian.tv.ui

/** 通用操作卡片（设置、加载更多等） */
data class ActionItem(val id: String, val label: String)

/** 详情页的播放源卡片 */
data class SourceItem(val index: Int, val name: String, val count: Int)
