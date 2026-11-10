package com.yujiannatian.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 封面缓存 key 归一化：协议差异、HTML 实体、fragment、空白都应合并成同一个 key */
class ImageLoaderCacheKeyTest {

    @Test
    fun httpAndHttpsOfSamePathShareOneCacheEntry() {
        assertEquals(
            normalizeCoverKey("http://cdn.example.com/upload/a.jpg"),
            normalizeCoverKey("https://cdn.example.com/upload/a.jpg")
        )
    }

    @Test
    fun htmlEntityAndFragmentAreNormalized() {
        assertEquals(
            normalizeCoverKey("https://cdn.example.com/a.jpg?x=1&y=2"),
            normalizeCoverKey("  https://cdn.example.com/a.jpg?x=1&amp;y=2#cover  ")
        )
    }

    @Test
    fun differentPathsOrSignedQueryKeepDistinctKeys() {
        assertNotEquals(
            normalizeCoverKey("https://cdn.example.com/a.jpg"),
            normalizeCoverKey("https://cdn.example.com/b.jpg")
        )
        assertNotEquals(
            normalizeCoverKey("https://cdn.example.com/a.jpg?sign=abc"),
            normalizeCoverKey("https://cdn.example.com/a.jpg?sign=def")
        )
    }

    @Test
    fun relativePathIsLeftIntact() {
        assertEquals("/upload/a.jpg", normalizeCoverKey(" /upload/a.jpg "))
    }
}
