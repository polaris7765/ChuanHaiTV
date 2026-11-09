package com.yujiannatian.tv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 拼音搜索的本地片名索引。
 *
 * 数据来源：各分类热播榜前若干页 + 首页热门推荐；首次进入搜索页时并发建立，
 * 结果按「站点地址 + 配置版本」缓存到磁盘，改址后自动失效。中文关键词另走站内
 * 搜索接口补齐长尾片名。
 */
object SearchIndex {

    private const val TTL = 6 * 60 * 60 * 1000L
    /** 每个大类抓取的热播榜页数：太少会让长尾片名（如《走马上任》）不在索引里 */
    private const val PAGES_PER_GROUP = 12
    private const val MAX_ITEMS = 8000

    private val mutex = Mutex()
    private val pool = mutableListOf<VideoItem>()
    private var loadedKey = ""
    private var building = false
    /** 已做过首字母反查的关键词（含站点与配置版本），避免逐字输入时重复并发请求 */
    private val reversedQueries = ConcurrentHashMap.newKeySet<String>()

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

    /**
     * 拼音首字母兜底：站点搜索只认中文，直接把「ZMSR」丢给 /search/ 必然零结果。
     *
     * 做法是取首字母对应的若干常见片名首字（Pinyin.candidateChars，如 Z → 「走」），
     * 并发请求站内搜索第 1 页，把结果补进本地索引；随后调用方再用 Pinyin.filter
     * 按首字母过滤，即可让「ZMSR」命中《走马上任》。
     * 返回本次新增的影片数，便于调用方决定是否需要刷新提示。
     */
    suspend fun remoteByInitials(query: String): Int = withContext(Dispatchers.IO) {
        val q = Pinyin.normalize(query)
        if (q.isEmpty() || !q.all { it in 'a'..'z' }) return@withContext 0
        val marker = "${SiteConfig.baseUrl}|${SiteConfig.version}|$q"
        if (!reversedQueries.add(marker)) return@withContext 0
        val chars = Pinyin.candidateChars(q[0])
        if (chars.isEmpty()) return@withContext 0

        val found = coroutineScope {
            chars.map { c ->
                async {
                    when (val r = SiteRepository.search(c.toString(), 1)) {
                        is LoadResult.Ok -> r.value.items
                        is LoadResult.Err -> emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        val known = snapshot().mapTo(HashSet()) { it.path }
        val fresh = found.distinctBy { it.path }.filter { it.path !in known }
        merge(fresh)
        fresh.size
    }
}
