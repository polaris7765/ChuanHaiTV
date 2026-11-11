package com.yujiannatian.tv.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 封面解析在 TV 端换站点/模板时的兼容性回归 */
class HtmlParserCoverTest {

    @Test
    fun `多种懒加载属性都能取到真实封面且占位图被过滤`() {
        val html = """
            <div id="content">
              <li><a class="img-pic" href="/vod/1.html" title="甲">
                  <img src="/tpl/pic.png" data-src="/cover/a.jpg"></a></li>
              <li><a class="img-pic" href="/vod/2.html" title="乙">
                  <img class="lazyload" src="/static/loading.gif"
                       data-original="https://img.example.com/b.jpg"></a></li>
              <li><a class="img-pic" href="/vod/3.html" title="丙">
                  <img src="/tpl/pic.png" srcset="/cover/c.jpg 1x, /cover/c2.jpg 2x"></a></li>
              <li><a class="img-pic" href="/vod/4.html" title="丁">
                  <img src="/tpl/nopic.png" data-echo="/cover/d.jpg"></a></li>
            </div>
        """.trimIndent()

        val covers = HtmlParser.parseList(html, 1).items.map { it.cover }
        assertEquals(
            listOf(
                "/cover/a.jpg",
                "https://img.example.com/b.jpg",
                "/cover/c.jpg",
                "/cover/d.jpg"
            ),
            covers
        )
    }

    @Test
    fun `只有占位图时封面留空交给 ImageLoader 兜底`() {
        val html = """
            <div id="content">
              <li><a class="img-pic" href="/vod/1.html" title="甲">
                  <img src="/tpl/pic.png"></a></li>
            </div>
        """.trimIndent()
        assertEquals("", HtmlParser.parseList(html, 1).items.first().cover)
    }
}
