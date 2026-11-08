package com.yujiannatian.tv.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 站点数据统一入口：内存缓存 + 磁盘缓存 + 错误归一化。
 * UI 层只与 LoadResult 打交道，不需要知道 HTTP / 解析细节。
 */
object SiteRepository {

    private const val TTL_CATEGORIES = 6 * 60 * 60 * 1000L
    private const val TTL_LIST = 20 * 60 * 1000L
    private const val TTL_DETAIL = 30 * 60 * 1000L
    private const val TTL_PLAYBACK = 60 * 1000L

    private val memory = ConcurrentHashMap<String, Any>()

    fun init(context: Context) {
        SiteConfig.init(context)
        CacheStore.init(context)
    }

    private fun key(vararg parts: String): String =
        (listOf(SiteConfig.version.toString(), SiteConfig.baseUrl) + parts).joinToString("|")

    fun clearCache() {
        memory.clear()
        CacheStore.clear()
    }

    fun resolve(base: String, path: String): String = try {
        URL(URL(base), path).toString()
    } catch (e: Exception) {
        throw SiteError(ErrorKind.INVALID_URL, "地址拼接失败", "$base + $path")
    }

    /** 站点相对路径（或绝对 URL）转可请求的完整地址 */
    fun urlFor(path: String): String = resolve(SiteConfig.baseUrl, path)

    private suspend fun <T> load(block: () -> T): LoadResult<T> = withContext(Dispatchers.IO) {
        try {
            LoadResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteError) {
            LoadResult.Err(e)
        } catch (e: Exception) {
            LoadResult.Err(
                SiteError(ErrorKind.PARSE, "数据解析异常", e.message ?: e.javaClass.simpleName)
            )
        } catch (t: Throwable) {
            // NoSuchMethodError / NoClassDefFoundError / OutOfMemoryError 属于 Error，
            // 低版本系统缺少类或方法时会走到这里；必须归一化，否则会直接闪退。
            LoadResult.Err(unexpected(t))
        }
    }

    private fun unexpected(t: Throwable): SiteError {
        Log.e("YuJianTV", "数据加载遇到系统级错误", t)
        val (message, detail) = when (t) {
            is OutOfMemoryError ->
                "内存不足，页面解析未完成" to "建议重启应用后重试，或减少同时加载的封面"
            is NoSuchMethodError, is NoClassDefFoundError, is ExceptionInInitializerError ->
                "系统版本与依赖库不兼容" to "缺少运行所需的系统组件，请把此信息反馈给开发者"
            else ->
                "数据加载出现异常" to "未知错误"
        }
        val trace = "${t.javaClass.simpleName}: ${t.message ?: ""}\n" +
            t.stackTrace.take(4).joinToString("\n") { "  at $it" }
        return SiteError(ErrorKind.PARSE, message, "$detail\n$trace".take(400))
    }

    suspend fun categories(forceRefresh: Boolean = false): LoadResult<List<CategoryGroup>> = load {
        val k = key("categories")
        if (!forceRefresh) {
            memory[k]?.let {
                @Suppress("UNCHECKED_CAST")
                return@load it as List<CategoryGroup>
            }
            CacheStore.read(k, CategoriesCache::class.java, TTL_CATEGORIES)?.let {
                memory[k] = it.groups
                return@load it.groups
            }
        }
        val html = SiteHttpClient.get(resolve(SiteConfig.baseUrl, "/sitemap.html"))
        val groups = HtmlParser.parseCategories(html)
        memory[k] = groups
        CacheStore.write(k, CategoriesCache(groups))
        groups
    }

    suspend fun featured(forceRefresh: Boolean = false): LoadResult<List<VideoItem>> = load {
        val k = key("featured")
        if (!forceRefresh) {
            memory[k]?.let {
                @Suppress("UNCHECKED_CAST")
                return@load it as List<VideoItem>
            }
            CacheStore.read(k, FeaturedCache::class.java, TTL_LIST)?.let {
                memory[k] = it.items
                return@load it.items
            }
        }
        val html = SiteHttpClient.get(SiteConfig.baseUrl)
        val items = HtmlParser.parseFeatured(html)
        memory[k] = items
        CacheStore.write(k, FeaturedCache(items))
        items
    }

    suspend fun list(categoryPath: String, page: Int): LoadResult<PageResult> = load {
        val requestPath = HtmlParser.pagePath(categoryPath, page)
        val k = key("list", requestPath)
        memory[k]?.let { return@load it as PageResult }
        CacheStore.read(k, PageCache::class.java, TTL_LIST)?.let {
            memory[k] = it.result
            return@load it.result
        }
        val html = SiteHttpClient.get(resolve(SiteConfig.baseUrl, requestPath))
        val result = HtmlParser.parseList(html, page)
        memory[k] = result
        CacheStore.write(k, PageCache(result))
        result
    }

    /**
     * 左栏分类的「热播剧集」列表：优先取按点击量排序的 index_____hits 页，
     * 站点某些大类（如「节目」）没有热播榜，自动回退到默认列表页。
     */
    suspend fun hotList(groupPath: String, page: Int): LoadResult<PageResult> = load {
        val k = key("hot", groupPath, page.toString())
        memory[k]?.let { return@load it as PageResult }
        CacheStore.read(k, PageCache::class.java, TTL_LIST)?.let {
            memory[k] = it.result
            return@load it.result
        }
        val candidates = listOf(
            HtmlParser.hotPath(groupPath, page),
            HtmlParser.pagePath(groupPath, page)
        )
        var last: SiteError? = null
        for (requestPath in candidates) {
            try {
                val html = SiteHttpClient.get(resolve(SiteConfig.baseUrl, requestPath))
                val result = HtmlParser.parseList(html, page)
                memory[k] = result
                CacheStore.write(k, PageCache(result))
                return@load result
            } catch (e: SiteError) {
                last = e
            }
        }
        throw last ?: SiteError(ErrorKind.EMPTY, "没有可用数据", groupPath)
    }

    /** 站内搜索，作为本地拼音索引的补充（中文片名可直接命中全站） */
    suspend fun search(keyword: String, page: Int): LoadResult<PageResult> = load {
        val k = key("search", keyword, page.toString())
        memory[k]?.let { return@load it as PageResult }
        CacheStore.read(k, PageCache::class.java, 10 * 60 * 1000L)?.let {
            memory[k] = it.result
            return@load it.result
        }
        val html = SiteHttpClient.get(
            resolve(SiteConfig.baseUrl, HtmlParser.searchPath(keyword, page))
        )
        val result = HtmlParser.parseSearch(html, page)
        memory[k] = result
        CacheStore.write(k, PageCache(result))
        result
    }

    suspend fun detail(videoPath: String): LoadResult<VideoDetail> = load {
        val k = key("detail", videoPath)
        memory[k]?.let { return@load it as VideoDetail }
        CacheStore.read(k, DetailCache::class.java, TTL_DETAIL)?.let {
            memory[k] = it.detail
            return@load it.detail
        }
        val html = SiteHttpClient.get(resolve(SiteConfig.baseUrl, videoPath))
        val result = HtmlParser.parseDetail(html)
        memory[k] = result
        CacheStore.write(k, DetailCache(result))
        result
    }

    suspend fun playback(episodePath: String): LoadResult<PlaybackInfo> = load {
        val k = key("playback", episodePath)
        // 播放地址有效期短，只走短期磁盘缓存，避免拿到过期的 m3u8
        CacheStore.read(k, PlaybackInfo::class.java, TTL_PLAYBACK)?.let { return@load it }
        val html = SiteHttpClient.get(resolve(SiteConfig.baseUrl, episodePath))
        val result = HtmlParser.parsePlayback(html)
        memory[k] = result
        CacheStore.write(k, result)
        result
    }

    /** 播放失败或重试时强制重新取一次地址 */
    fun invalidatePlayback(episodePath: String) {
        val k = key("playback", episodePath)
        memory.remove(k)
        CacheStore.delete(k)
    }

    /** 地址变更前的原子校验：不改当前配置，只探测新地址能否返回合法分类数据 */
    suspend fun probe(rawUrl: String): LoadResult<Int> = load {
        val base = SiteConfig.normalize(rawUrl)
        val html = SiteHttpClient.get(resolve(base, "/sitemap.html"))
        val groups = HtmlParser.parseCategories(html)
        groups.sumOf { it.categories.size }
    }
}
