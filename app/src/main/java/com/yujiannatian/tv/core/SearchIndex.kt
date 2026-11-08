package com.yujiannatian.tv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 拼音搜索的本地片名索引。
 *
 * 数据来源：各分类热播榜前若干页 + 首页热门推荐；首次进入搜索页时并发建立，
 * 结果按「站点地址 + 配置版本」缓存到磁盘，改址后自动失效。中文关键词另走站内
 * 搜索接口补齐长尾片名。
 */
object SearchIndex {

    private const val TTL = 6 * 60 * 60 * 1000L
    private const val PAGES_PER_GROUP = 8
    private const val MAX_ITEMS = 4000

    private val mutex = Mutex()
    private val pool = mutableListOf<VideoItem>()
    private var loadedKey = ""
    private var building = false

    @Volatile
    var message: String = "索引尚未建立"
        private set

    private fun key(baseUrl: String, version: Long) = "search_index|$version|$baseUrl"

    fun size(): Int = synchronized(pool) { pool.size }

    fun snapshot(): List<VideoItem> = synchronized(pool) { pool.toList() }

    private suspend fun report(text: String, onProgress: (String) -> Unit) {
        message = text
        withContext(Dispatchers.Main) { runCatching { onProgress(text) } }
    }

    suspend fun ensure(force: Boolean = false, onProgress: (String) -> Unit) {
        mutex.withLock {
            val k = key(SiteConfig.baseUrl, SiteConfig.version)
            if (!force && building) return
            if (!force && loadedKey == k && size() > 0) {
                report("已索引 ${size()} 部影片 · 输入拼音首字母即可查找", onProgress)
                return
            }
            if (!force) {
                CacheStore.read(k, SearchIndexCache::class.java, TTL)?.let {
                    replace(it.items)
                    loadedKey = k
                    if (size() > 0) {
                        report("已索引 ${size()} 部影片（缓存）", onProgress)
                        return
                    }
                }
            }
            building = true
            try {
                build(k, onProgress)
                loadedKey = k
                report("已索引 ${size()} 部影片 · 输入拼音首字母即可查找", onProgress)
            } finally {
                building = false
            }
        }
    }

    private suspend fun build(cacheKey: String, onProgress: (String) -> Unit) {
        val groups = when (val r = SiteRepository.categories(false)) {
            is LoadResult.Ok -> r.value
            is LoadResult.Err -> emptyList()
        }
        val paths = groups.map { it.path }.filter { it.isNotBlank() }.distinct()
        if (paths.isEmpty()) {
            report("分类数据不可用，站内搜索仍可使用", onProgress)
            return
        }
        replace(emptyList())
        report("正在建立片名索引…", onProgress)

        withContext(Dispatchers.IO) {
            coroutineScope {
                paths.map { path ->
                    async {
                        for (p in 1..PAGES_PER_GROUP) {
                            when (val r = SiteRepository.hotList(path, p)) {
                                is LoadResult.Ok -> merge(r.value.items)
                                is LoadResult.Err -> break
                            }
                        }
                        report("已索引 ${size()} 部影片…", onProgress)
                    }
                }.awaitAll()
            }
        }
        (SiteRepository.featured(false) as? LoadResult.Ok)?.value?.let { merge(it) }
        val result = snapshot().take(MAX_ITEMS)
        replace(result)
        CacheStore.write(cacheKey, SearchIndexCache(result))
    }

    fun merge(list: Collection<VideoItem>) {
        if (list.isEmpty()) return
        synchronized(pool) {
            val known = pool.mapTo(HashSet()) { it.path }
            list.forEach { if (it.path !in known) { pool.add(it); known.add(it.path) } }
        }
    }

    private fun replace(list: List<VideoItem>) = synchronized(pool) {
        pool.clear()
        pool.addAll(list)
    }

    /** 站内搜索兜底：中文片名可命中本地索引之外的长尾资源 */
    suspend fun remote(keyword: String, maxPages: Int = 2): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<VideoItem>()
            for (p in 1..maxPages) {
                when (val r = SiteRepository.search(keyword, p)) {
                    is LoadResult.Ok -> {
                        out.addAll(r.value.items)
                        if (p >= r.value.totalPages) break
                    }
                    is LoadResult.Err -> break
                }
            }
            val known = snapshot().mapTo(HashSet()) { it.path }
            out.distinctBy { it.path }.filter { it.path !in known }.also { merge(it) }
        }
}
