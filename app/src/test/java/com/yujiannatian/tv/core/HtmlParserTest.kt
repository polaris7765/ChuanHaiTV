package com.yujiannatian.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 四类页面的解析契约；站点改版后这些用例会第一时间暴露问题 */
class HtmlParserTest {

    private val sitemap = """
        <html><body>
          <div class="layout-box">
            <div class="box-title"><h2><a href="/dianshiju/">电视剧</a></h2></div>
            <div class="sitemap">
              <ul>
                <li class="title">分类</li>
                <li><a href="/guocanju/" title="国产剧">国产剧</a></li>
                <li><a href="/hanguoju/" title="韩国剧">韩国剧</a></li>
                <li><a href="javascript:;">忽略</a></li>
              </ul>
              <ul>
                <li class="title">类型</li>
                <li><a href="/dianshiju/index_guzhuang____.html">古装</a></li>
              </ul>
            </div>
          </div>
          <div class="layout-box"><div class="box-title"><h2>其他</h2></div></div>
        </body></html>
    """.trimIndent()

    private val list = """
        <html><body>
          <div id="count">共有“3469”个影片,当前第“1”页</div>
          <ul id="content">
            <li><a class="img-pic" href="/dongzuopian/aaa/" title="影片甲">
              <img data-original="https://img.example/a.jpg">
              <span class="vtitle">更新至10集</span><span class="score">8.0</span></a>
              <div class="name">影片甲</div></li>
            <li><a class="img-pic" href="/dongzuopian/bbb/" title="影片乙">
              <img data-original="/public/tpl/x/images/pic.png"></a>
              <div class="name">影片乙</div></li>
          </ul>
          <div id="page"><a href="/dongzuopian/index-2.html">2</a><a href="/dongzuopian/index-116.html">尾页</a></div>
        </body></html>
    """.trimIndent()

    private val detail = """
        <html><body>
          <div class="vod-detail-pic"><img src="https://img.example/cover.jpg"></div>
          <div class="vod-detail-info"><ul>
            <li><h1>似锦</h1></li>
            <li>状态：连载中</li><li>年代：2025</li>
            <li class="detail-info"><p><span>简介：</span>讲述姜似重启命运的故事。</p></li>
          </ul></div>
          <ul class="details-play-nav hidden-xs" id="Tab">
            <li><a id="#con_playlist_1" href="javascript:;">新视</a></li>
            <li><a id="#con_playlist_2" href="javascript:;">爱奇</a></li>
          </ul>
          <ul class="player-more-list">
            <li><a id="#con_playlist_1" href="javascript:;">新视觉云</a></li>
            <li><a id="#con_playlist_2" href="javascript:;">爱奇艺</a></li>
          </ul>
          <ul id="con_playlist_1"><li><a href="/guocanju/sijin/2-2.html">第02集</a></li>
            <li><a href="/guocanju/sijin/2-1.html">第01集</a></li></ul>
          <ul id="con_playlist_2"><li><a href="/guocanju/sijin/1-1.html">第1集</a></li></ul>
        </body></html>
    """.trimIndent()

    private val play = """
        <html><body><div id="cms_play"><script>
        var zanpiancms_player = {"player":"\/public\/","url":"https:\/\/v.example.com\/2025\/index.m3u8",
          "next":"http:\/\/www.yujiannatian.net\/guocanju\/sijin\/2-2.html","name":"lzm3u8",
          "danmu":{"status":0}};
        </script></div></body></html>
    """.trimIndent()

    @Test
    fun parsesAllCategoryGroupsAndFilters() {
        val groups = HtmlParser.parseCategories(sitemap)
        assertEquals(1, groups.size)
        assertEquals("电视剧", groups[0].name)
        assertEquals("/dianshiju/", groups[0].path)
        // 全部 + 国产剧 + 韩国剧 + 古装；javascript: 链接被丢弃
        assertEquals(4, groups[0].categories.size)
        assertEquals("全部电视剧", groups[0].categories[0].name)
        assertTrue(groups[0].categories.any { it.name == "古装" && it.tag == "类型" })
    }

    @Test
    fun deduplicatesRepeatedCategoryPaths() {
        // 覆盖去重逻辑：此路径原先使用 Map.putIfAbsent（Android 7.0 / API 24 才有），
        // 在 API 23 电视上会抛 NoSuchMethodError 导致启动闪退。
        val html = """
            <html><body>
              <div class="layout-box">
                <div class="box-title"><h2><a href="/dianying/">电影</a></h2></div>
                <div class="sitemap">
                  <ul>
                    <li class="title">类型</li>
                    <li><a href="/dongzuopian/">动作片</a></li>
                    <li><a href="/dongzuopian/">动作片（重复）</a></li>
                  </ul>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val groups = HtmlParser.parseCategories(html)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].categories.size) // 全部电影 + 动作片
        assertEquals("动作片", groups[0].categories[1].name)
    }

    @Test
    fun parsesListItemsAndPagination() {
        val result = HtmlParser.parseList(list, 1)
        assertEquals(2, result.items.size)
        assertEquals("影片甲", result.items[0].title)
        assertEquals("https://img.example/a.jpg", result.items[0].cover)
        assertEquals("更新至10集", result.items[0].note)
        // 占位图应被过滤
        assertEquals("", result.items[1].cover)
        assertEquals(116, result.totalPages)
        assertEquals(3469, result.total)
    }

    @Test
    fun parsesDetailSourcesWithFullNamesAndEpisodes() {
        val d = HtmlParser.parseDetail(detail)
        assertEquals("似锦", d.title)
        assertTrue(d.description.startsWith("讲述姜似"))
        assertEquals(2, d.sources.size)
        assertEquals("新视觉云", d.sources[0].name)
        assertEquals(2, d.sources[0].episodes.size)
        assertEquals("/guocanju/sijin/2-2.html", d.sources[0].episodes[0].path)
        assertEquals("爱奇艺", d.sources[1].name)
    }

    @Test
    fun parsesPlaybackUrlAndNextEpisode() {
        val p = HtmlParser.parsePlayback(play)
        assertEquals("https://v.example.com/2025/index.m3u8", p.url)
        assertEquals("lzm3u8", p.name)
        assertEquals("/guocanju/sijin/2-2.html", p.next)
    }

    @Test
    fun buildsPaginationPaths() {
        assertEquals("/dongzuopian/", HtmlParser.pagePath("/dongzuopian/", 1))
        assertEquals("/dongzuopian/index-2.html", HtmlParser.pagePath("/dongzuopian/", 2))
        assertEquals(
            "/dianshiju/index_guzhuang____-3.html",
            HtmlParser.pagePath("/dianshiju/index_guzhuang____.html", 3)
        )
    }

    @Test(expected = SiteError::class)
    fun rejectsUnparseableCategoryPage() {
        HtmlParser.parseCategories("<html><body>Not a compatible site</body></html>")
    }

    @Test
    fun buildsHotAndSearchPaths() {
        assertEquals("/dianshiju/index_____hits.html", HtmlParser.hotPath("/dianshiju/"))
        assertEquals("/dianshiju/index_____hits_2.html", HtmlParser.hotPath("/dianshiju/", 2))
        assertEquals("/dianshiju/index_____hits_5.html", HtmlParser.hotPath("/dianshiju/", 5))
        assertEquals("/search/sj.html", HtmlParser.searchPath("sj"))
        assertEquals(
            "/search/%E4%BC%BC%E9%94%A6-3.html",
            HtmlParser.searchPath("似锦", 3)
        )
    }

    @Test
    fun parsesSearchResultsWithNestedListAndPagination() {
        val html = """
            <html><body>
              <div id="count">共有“26”个相关视频,当前第“1”页</div>
              <div id="content"><ul class="img-list clearfix news-list">
                <li><a class="img-pic" href="/guocanju/sijin/" title="似锦">
                  <img data-original="https://img.example/s.jpg">
                  <span class="vtitle">更新至40集</span></a></li>
                <li><a class="img-pic" href="/dianying/fh/" title="繁花似锦"></a></li>
              </ul></div>
              <div id="page"><a href="/search/sj-2.html">2</a><a href="/search/sj-4.html">4</a></div>
            </body></html>
        """.trimIndent()
        val r = HtmlParser.parseSearch(html, 1)
        assertEquals(2, r.items.size)
        assertEquals("似锦", r.items[0].title)
        assertEquals("更新至40集", r.items[0].note)
        assertEquals(26, r.total)
        assertEquals(4, r.totalPages)
    }
}
