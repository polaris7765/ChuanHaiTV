package com.yujiannatian.tv.core

/** 一个可浏览的分类项（可能是子分类页，也可能是筛选页） */
data class Category(
    val name: String = "",
    val path: String = "",
    val group: String = "",
    val tag: String = ""
)

/** 站点地图中的一个大类，例如「电视剧 / 电影 / 动漫」 */
data class CategoryGroup(
    val name: String = "",
    val path: String = "",
    val categories: List<Category> = emptyList()
)

data class VideoItem(
    val title: String = "",
    val path: String = "",
    val cover: String = "",
    val note: String = "",
    val score: String = ""
)

data class PageResult(
    val items: List<VideoItem> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val total: Int = 0,
    val requestedPage: Int = 1
)

data class Episode(val name: String = "", val path: String = "")

/** 一个播放源（例如「新视觉云」）及其全部剧集 */
data class PlaySource(val name: String = "", val episodes: List<Episode> = emptyList())

data class VideoDetail(
    val title: String = "",
    val cover: String = "",
    val meta: String = "",
    val description: String = "",
    val sources: List<PlaySource> = emptyList(),
    val related: List<VideoItem> = emptyList()
)

data class PlaybackInfo(val url: String = "", val name: String = "", val next: String = "")

// 磁盘缓存包装类：避免 Gson 泛型擦除
data class CategoriesCache(val groups: List<CategoryGroup> = emptyList())
data class FeaturedCache(val items: List<VideoItem> = emptyList())
data class PageCache(val result: PageResult = PageResult())
data class DetailCache(val detail: VideoDetail = VideoDetail())
data class SearchIndexCache(val items: List<VideoItem> = emptyList())

/**
 * 下载记录：既包含「下载中」也包含「下载完成」。
 * status: downloading / done / failed；旧版本记录的缺失字段由默认值补齐（兼容升级）。
 */
data class DownloadRecord(
    val key: String = "",
    val title: String = "",
    val episode: String = "",
    val file: String = "",
    val size: Long = 0L,
    val time: Long = 0L,
    val status: String = "done",
    val progress: Int = 100,
    val episodePath: String = "",
    /** 本地播放进度（毫秒）；播放完成写 0，旧记录缺字段时默认从头播 */
    val position: Long = 0L
)
