package com.yujiannatian.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拼音首字母转换与模糊匹配的契约 */
class PinyinTest {

    @Test
    fun convertsCommonTitlesToInitials() {
        assertEquals("sj", Pinyin.initials("似锦"))
        assertEquals("qyn", Pinyin.initials("庆余年"))
        assertEquals("wjzcx", Pinyin.initials("我叫赵出息"))
        assertTrue(Pinyin.initials("狂飙").startsWith("kb"))
        assertEquals("sj", Pinyin.initials("SJ"))
        assertEquals("2025", Pinyin.initials("2025"))
    }

    @Test
    fun matchesByInitialsPrefixContainsAndSubsequence() {
        assertEquals(0, Pinyin.score("似锦", "似锦"))
        assertTrue(Pinyin.score("sj", "似锦") >= 0)
        assertTrue(Pinyin.score("qyn", "庆余年") >= 0)
        // 模糊子序列：wjc 命中「我(w) 叫(j) 赵(z) 出(c) 息(x)」
        assertTrue(Pinyin.score("wjc", "我叫赵出息") >= 0)
        assertTrue(Pinyin.score("似", "繁华似锦") >= 0)
        assertTrue(Pinyin.score("zzzzzz", "似锦") < 0)
        assertTrue(Pinyin.score("", "似锦") < 0)
    }

    @Test
    fun normalizesAndFiltersTheIndex() {
        assertEquals("sj2025", Pinyin.normalize(" S J ！ 2 0 2 5 "))
        val items = listOf(
            VideoItem(title = "似锦", path = "/a/"),
            VideoItem(title = "繁华似锦", path = "/b/"),
            VideoItem(title = "庆余年", path = "/c/")
        )
        val result = Pinyin.filter("sj", items)
        assertTrue(result.any { it.title == "似锦" })
        assertTrue(result.none { it.title == "庆余年" })
    }
}
