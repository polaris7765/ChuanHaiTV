package com.yujiannatian.tv.core

import android.content.Context
import com.google.gson.Gson

/**
 * 首页「历史记录 / 我的收藏」的一条记录。
 * video 保存详情路径与封面，点开后可直接复用现有详情页；
 * episode / episodePath 保存最近观看的剧集，note 会显示在卡片副标题上。
 */
data class LibraryEntry(
    val video: VideoItem = VideoItem(),
    val episode: String = "",
    val episodePath: String = "",
    val position: Long = 0L,
    val time: Long = 0L
)

private data class LibraryIndex(
    val history: List<LibraryEntry> = emptyList(),
    val favorites: List<LibraryEntry> = emptyList()
)

/**
 * 轻量本地资料库：用 SharedPreferences + Gson 持久化，不依赖数据库。
 * 与站点缓存分离，换站点地址不会清空历史与收藏；写入失败（低版本 / 存储异常）不影响播放。
 */
object LibraryStore {

    private const val PREF = "yujian_library"
    private const val KEY = "index"
    private const val MAX_HISTORY = 60
    private val gson = Gson()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun read(context: Context): LibraryIndex = try {
        val raw = prefs(context).getString(KEY, null) ?: return LibraryIndex()
        gson.fromJson(raw, LibraryIndex::class.java) ?: LibraryIndex()
    } catch (t: Throwable) {
        LibraryIndex()
    }

    private fun write(context: Context, value: LibraryIndex) {
        try {
            // apply() 会同步更新内存中的值，紧接着的读取能立刻看到结果
            prefs(context).edit().putString(KEY, gson.toJson(value)).apply()
        } catch (t: Throwable) {
            // 存储异常不影响主流程
        }
    }

    @Synchronized
    fun history(context: Context): List<LibraryEntry> =
        read(context).history.sortedByDescending { it.time }

    @Synchronized
    fun favorites(context: Context): List<LibraryEntry> =
        read(context).favorites.sortedByDescending { it.time }

    /** 同一部影片只保留一条，新的排在最前 */
    @Synchronized
    fun addHistory(context: Context, entry: LibraryEntry) {
        val cur = read(context)
        val kept = cur.history.filter { it.video.path != entry.video.path }
            .take((MAX_HISTORY - 1).coerceAtLeast(0))
        write(
            context,
            cur.copy(history = listOf(entry.copy(time = System.currentTimeMillis())) + kept)
        )
    }

    @Synchronized
    fun isFavorite(context: Context, path: String): Boolean =
        path.isNotBlank() && read(context).favorites.any { it.video.path == path }

    /** 返回切换后是否已收藏 */
    @Synchronized
    fun toggleFavorite(context: Context, entry: LibraryEntry): Boolean {
        val cur = read(context)
        val exists = cur.favorites.any { it.video.path == entry.video.path }
        val updated = if (exists) {
            cur.favorites.filterNot { it.video.path == entry.video.path }
        } else {
            listOf(entry.copy(time = System.currentTimeMillis())) + cur.favorites
        }
        write(context, cur.copy(favorites = updated))
        return !exists
    }

    @Synchronized
    fun clearHistory(context: Context) {
        write(context, read(context).copy(history = emptyList()))
    }

    @Synchronized
    fun clearFavorites(context: Context) {
        write(context, read(context).copy(favorites = emptyList()))
    }
}
