package com.yujiannatian.tv.core

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * 赞片CMS（zanpian cms12 模板）页面解析器。
 * 四类页面：sitemap.html 分类、列表页、详情页播放源、播放页播放器配置。
 * 所有 href 统一保存为"站点相对路径"，地址变更时不影响已缓存结构。
 */
object HtmlParser {

    /** 把 href 转成以 / 开头的站点相对路径；外链/js 链接返回 null */
    fun toPath(href: String): String? {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("#") || h.startsWith("javascript", true) ||
            h.startsWith("mailto", true)
        ) return null
        return try {
            if (h.startsWith("http", true)) {
                val u = URI(h)
                val p = u.rawPath?.takeIf { it.isNotBlank() } ?: "/"
                if (u.rawQuery.isNullOrBlank()) p else "$p?${u.rawQuery}"
            } else if (h.startsWith("//")) {
                val u = URI("http:$h")
                if (u.rawQuery.isNullOrBlank()) u.rawPath else "${u.rawPath}?${u.rawQuery}"
            } else if (h.startsWith("/")) {
                h
            } else {
                "/$h"
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 列表分页：/dongzuopian/ -> /dongzuopian/index-2.html */
    fun pagePath(path: String, page: Int): String {
        if (page <= 1) return path
        return when {
            path.endsWith(".html", true) -> path.substring(0, path.length - 5) + "-$page.html"
            path.endsWith("/") -> path + "index-$page.html"
            else -> "$path/index-$page.html"
        }
    }

    /**
     * 热播榜：/dianshiju/ -> /dianshiju/index_____hits.html
     * 该模板的热播分页用下划线（index_____hits_2.html），不是普通列表的 -2.html；
     * 传 -2.html 站点会静默返回第一页，导致翻页无效、搜索索引重复。
     */
    fun hotPath(groupPath: String, page: Int = 1): String {
        val base = groupPath.trimEnd('/') + "/index_____hits.html"
        if (page <= 1) return base
        return base.removeSuffix(".html") + "_$page.html"
    }

    /** 站内搜索：/search/关键词.html；第二页 -> /search/关键词-2.html */
    fun searchPath(keyword: String, page: Int = 1): String {
        val q = try {
            java.net.URLEncoder.encode(keyword.trim(), "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            keyword.trim().filter { it.isLetterOrDigit() }
        }
        val suffix = if (page > 1) "-$page" else ""
        return "/search/$q$suffix.html"
    }

    fun parseCategories(html: String): List<CategoryGroup> {
        val doc = Jsoup.parse(html)
        val groups = mutableListOf<CategoryGroup>()
        for (box in doc.select("div.layout-box")) {
            val titleEl = box.children().firstOrNull { it.hasClass("box-title") }
                ?.selectFirst("h2") ?: continue
            val groupName = titleEl.text().trim()
            if (groupName.isEmpty() || groupName == "其他") continue
            val groupPath = titleEl.selectFirst("a[href]")?.attr("href")
                ?.let { toPath(it) } ?: ""
            val sitemap = box.children().firstOrNull { it.hasClass("sitemap") } ?: continue

            val items = LinkedHashMap<String, Category>()
            if (groupPath.isNotBlank()) {
                items[groupPath] = Category("全部$groupName", groupPath, groupName, "全部")
            }
            for (ul in sitemap.children().filter { it.tagName() == "ul" }) {
                val tag = ul.children().firstOrNull { it.hasClass("title") }?.text()?.trim() ?: "分类"
                val links = ul.children()
                    .filter { it.tagName() == "li" && !it.hasClass("title") }
                    .flatMap { it.select("a[href]") }
                for (a in links) {
                    val path = toPath(a.attr("href")) ?: continue
                    val name = a.text().trim().ifBlank { a.attr("title").trim() }
                    if (name.isEmpty() || path.isBlank()) continue
                    // 不能用 Map.putIfAbsent：该方法直到 Android 7.0 (API 24) 才存在，
                    // 在 API 23 电视上会抛 NoSuchMethodError（属于 Error，catch(Exception) 拦不住）。
                    if (!items.containsKey(path)) {
                        items[path] = Category(name, path, groupName, tag)
                    }
                }
            }
            if (items.isNotEmpty()) {
                groups += CategoryGroup(groupName, groupPath, items.values.toList())
            }
        }
        if (groups.isEmpty()) {
            throw SiteError(
                ErrorKind.PARSE,
                "没有解析到任何分类",
                "页面可能已改版、跳转到了验证页，或该地址不是兼容的站点首页。请重试或更换地址。"
            )
        }
        return groups
    }

    fun parseFeatured(html: String): List<VideoItem> {
        val doc = Jsoup.parse(html)
        return doc.select("ul.img-list li")
            .mapNotNull { videoFromLi(it) }
            .distinctBy { it.path }
            .take(24)
    }

    fun parseList(html: String, requestedPage: Int): PageResult {
        val doc = Jsoup.parse(html)
        val container = doc.selectFirst("#content") ?: doc.selectFirst("ul.img-list")
            ?: throw SiteError(
                ErrorKind.PARSE,
                "没有找到资源列表容器",
                "列表页结构可能已变化（改版 / 验证页）。请重试，或更换站点地址。"
            )
        val items = container.children()
            .filter { it.tagName() == "li" }
            .mapNotNull { videoFromLi(it) }
            .distinctBy { it.path }

        val pageLinks = doc.select("#page a[href]").map { it.attr("href") }
        // 同时兼容 index-2.html、index_____hits-2.html、search/我-2.html
        val numbers = pageLinks.mapNotNull {
            Regex("""[_-](\d+)\.html""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val totalPages = maxOf(requestedPage, numbers.maxOrNull() ?: 1)
        // #count 形如「共有“3469”个影片,当前第“1”页」，只取影片总数，不能把页码拼进去
        val countText = doc.selectFirst("#count")?.text().orEmpty()
        val total = Regex("""[“"]?(\d+)[”"]?\s*个影片""")
            .find(countText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""[“"](\d+)[”"]""")
                .find(countText)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        if (items.isEmpty() && requestedPage == 1) {
            throw SiteError(
                ErrorKind.EMPTY,
                "该分类暂时没有资源",
                "页面请求成功但没有解析到影片卡片，可能是站点改版或该分类为空。"
            )
        }
        return PageResult(items, requestedPage, totalPages, total, requestedPage)
    }

    /** 搜索结果页：#content 里包了一层 ul.img-list，与列表页结构不同 */
    fun parseSearch(html: String, requestedPage: Int = 1): PageResult {
        val doc = Jsoup.parse(html)
        val container = doc.selectFirst("#content") ?: throw SiteError(
            ErrorKind.PARSE,
            "没有找到搜索结果容器",
            "搜索页结构可能已变化（改版 / 验证页）。请重试。"
        )
        val lis = container.select("ul.img-list > li").ifEmpty {
            container.children().filter { it.tagName() == "li" }
        }
        val items = lis.mapNotNull { videoFromLi(it) }.distinctBy { it.path }
        val pageLinks = doc.select("#page a[href]").map { it.attr("href") }
        val numbers = pageLinks.mapNotNull {
            Regex("""[_-](\d+)\.html""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val countText = doc.selectFirst("#count")?.text().orEmpty()
        val total = Regex("""[“"](\d+)[”"]""").find(countText)?.groupValues?.get(1)
            ?.toIntOrNull() ?: 0
        return PageResult(
            items = items,
            page = requestedPage,
            totalPages = maxOf(requestedPage, numbers.maxOrNull() ?: 1),
            total = total,
            requestedPage = requestedPage
        )
    }

    fun parseDetail(html: String): VideoDetail {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst(".vod-detail-info h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw SiteError(ErrorKind.PARSE, "没有解析到影片标题", "详情页结构可能已变化。")

        val coverEl = doc.selectFirst(".vod-detail-pic img")
        val cover = coverFromImg(coverEl)

        val infoUl = doc.selectFirst(".vod-detail-info ul")
        val meta = infoUl?.children()
            ?.filter { it.tagName() == "li" && !it.hasClass("detail-info") }
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() && !it.contains("影视/评论") }
            ?.take(6)
            ?.joinToString("  ·  ")
            ?.take(220) ?: ""

        val descP = doc.selectFirst("li.detail-info p") ?: doc.selectFirst(".detail-info p")
        val description = (descP?.ownText()?.trim().orEmpty().ifBlank {
            descP?.text()?.trim()?.removePrefix("简介：") ?: ""
        }).take(500)

        // 播放源名称：桌面版 tab 是简称，移动版下拉是全称，按 id 去重取更完整的名称
        val names = LinkedHashMap<String, String>()
        for (a in doc.select("a[id^='#con_playlist_']")) {
            val id = a.attr("id").removePrefix("#")
            val name = a.text().trim()
            if (id.isBlank() || name.isEmpty()) continue
            val old = names[id]
            if (old == null || name.length > old.length) names[id] = name
        }
        if (names.isEmpty()) {
            throw SiteError(
                ErrorKind.PARSE,
                "没有找到播放源列表",
                "详情页播放列表结构可能已变化，或该影片暂未提供在线源。"
            )
        }
        val sources = names.map { (id, name) ->
            val list = doc.selectFirst("ul#$id")
            val episodes = list?.select("li a[href]")?.mapNotNull { ep ->
                val path = toPath(ep.attr("href")) ?: return@mapNotNull null
                Episode(ep.text().trim(), path)
            }?.filter { it.path.isNotBlank() } ?: emptyList()
            PlaySource(name, episodes)
        }.filter { it.episodes.isNotEmpty() }

        if (sources.isEmpty()) {
            throw SiteError(
                ErrorKind.PARSE,
                "播放源存在但剧集列表为空",
                "可能是站点改版或该资源下架，请返回选择其它节目。"
            )
        }
        val related = doc.select("ul.img-list li")
            .mapNotNull { videoFromLi(it) }
            .distinctBy { it.path }
            .take(12)

        return VideoDetail(title, cover, meta, description, sources, related)
    }

    fun parsePlayback(html: String): PlaybackInfo {
        val jsonText = Regex("""(?s)zanpiancms_player\s*=\s*(\{.*?\})\s*;""")
            .find(html)?.groupValues?.get(1)
        var url = ""
        var name = ""
        var next = ""
        if (!jsonText.isNullOrBlank()) {
            try {
                val json = JSONObject(jsonText)
                url = json.optString("url", "").takeIf { it != "null" } ?: ""
                name = json.optString("name", "").takeIf { it != "null" } ?: ""
                next = json.optString("next", "").let { if (it == "null") "" else toPath(it) ?: "" }
            } catch (e: Exception) {
                throw SiteError(ErrorKind.PARSE, "播放器配置解析失败", e.message ?: "")
            }
        }
        if (url.isBlank()) {
            // 兼容部分模板直接把地址写在页面里的情况
            url = Regex("""(?s)["']url["']\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
                .find(html)?.groupValues?.get(1) ?: ""
            url = url.replace("\\/", "/")
        }
        if (url.isBlank()) {
            throw SiteError(
                ErrorKind.PARSE,
                "该播放源没有返回可直接播放的地址",
                "部分线路需要站点自带的解析接口或已失效，请返回选择其它播放源。"
            )
        }
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http", true)) {
            throw SiteError(
                ErrorKind.PARSE,
                "播放地址格式无法识别",
                "地址可能经过加密，请返回选择其它播放源。"
            )
        }
        return PlaybackInfo(url, name, next)
    }

    /**
     * 从（通常是懒加载的）<img> 标签取真实封面地址。
     * 站点对懒加载封面统一用 src 指向本地占位图（/public/tpl/.../pic.png），
     * 真正的地址放在 data-original；详情页海报则是 src 直接给真实地址、没有 data-original。
     * 因此优先取 data-original，回退 src，并把占位图 pic.png 视为无效（留给 ImageLoader 降级为标题文字）。
     */
    private fun coverFromImg(img: Element?): String {
        if (img == null) return ""
        val url = img.attr("data-original").takeIf { it.isNotBlank() }
            ?: img.attr("src").takeIf { it.isNotBlank() } ?: ""
        return if (url.contains("pic.png")) "" else url
    }

    private fun videoFromLi(li: Element): VideoItem? {
        val a = li.selectFirst("a.img-pic") ?: return null
        val path = toPath(a.attr("href")) ?: return null
        val title = (a.attr("title").trim().ifBlank {
            li.selectFirst("div.name")?.text()?.trim() ?: ""
        })
        if (title.isEmpty()) return null
        val img = a.selectFirst("img")
        val cover = coverFromImg(img)
        return VideoItem(
            title = title,
            path = path,
            cover = cover,
            note = a.selectFirst("span.vtitle")?.text()?.trim() ?: "",
            score = a.selectFirst("span.score")?.text()?.trim() ?: ""
        )
    }
}
