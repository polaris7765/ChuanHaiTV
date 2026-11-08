package com.yujiannatian.tv.core

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 片名拼音首字母 / 模糊匹配。
 *
 * 主路径使用 pinyin4j（覆盖多音字与 GBK 扩展汉字），若该库的资源在某些
 * 设备上不可用，则回退到内置的 GB2312 区位码首字母表——保证搜索永远可用。
 */
object Pinyin {

    private val format by lazy {
        HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
        }
    }

    private val cache = ConcurrentHashMap<Char, String>()

    // GB2312 一级字库按拼音排序的区位码分界（24 个边界 → 23 个声母区间）
    private val SEC = intArrayOf(
        1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106,
        3212, 3472, 3635, 3722, 3730, 3858, 4027, 4086, 4390, 4558,
        4684, 4925, 5249, 5590
    )
    private val LETTERS = charArrayOf(
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j', 'k', 'l', 'm',
        'n', 'o', 'p', 'q', 'r', 's', 't', 'w', 'x', 'y', 'z'
    )

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    /** 单个字符的拼音首字母；非字母数字且无法映射的字符返回 null（跳过） */
    fun firstLetter(c: Char): Char? {
        val ascii = when (c) {
            in 'a'..'z' -> c
            in 'A'..'Z' -> c.lowercaseChar()
            in '0'..'9' -> c
            in '\uFF21'..'\uFF3A' -> (c.code - 0xFEE0).toChar().lowercaseChar()
            in '\uFF10'..'\uFF19' -> (c.code - 0xFEE0).toChar()
            else -> null
        }
        if (ascii != null) return ascii
        if (!isCjk(c)) return null

        // 缓存中 "" 表示「无法映射」，firstOrNull() 会自然返回 null
        cache[c]?.let { return it.firstOrNull() }

        val letter = try {
            PinyinHelper.toHanyuPinyinStringArray(c, format)
                ?.firstOrNull()
                ?.firstOrNull()
                ?.lowercaseChar()
        } catch (t: Throwable) {
            null
        } ?: gb2312Letter(c)

        cache[c] = letter?.toString() ?: ""
        return letter
    }

    private fun gb2312Letter(c: Char): Char? {
        val bytes = try {
            c.toString().toByteArray(Charset.forName("GBK"))
        } catch (t: Throwable) {
            return null
        }
        if (bytes.size != 2) return null
        val area = (bytes[0].toInt() and 0xff) - 160
        val pos = (bytes[1].toInt() and 0xff) - 160
        if (area !in 16..87 || pos !in 1..94) return null
        val code = area * 100 + pos
        for (i in LETTERS.indices) {
            if (code >= SEC[i] && code < SEC[i + 1]) return LETTERS[i]
        }
        return null
    }

    /** 片名 → 拼音首字母串（保留其中的英文字母与数字） */
    fun initials(text: String): String = buildString {
        for (c in text) firstLetter(c)?.let { append(it) }
    }

    /** 查询归一化：只保留小写字母、数字与汉字 */
    fun normalize(query: String): String = buildString {
        for (c in query) {
            when {
                c in 'A'..'Z' -> append(c.lowercaseChar())
                c in 'a'..'z' || c in '0'..'9' || isCjk(c) -> append(c)
                c in '\uFF21'..'\uFF3A' -> append((c.code - 0xFEE0).toChar().lowercaseChar())
                c in '\uFF10'..'\uFF19' -> append((c.code - 0xFEE0).toChar())
            }
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return false
        var i = 0
        for (ch in haystack) {
            if (ch == needle[i]) {
                i++
                if (i == needle.length) return true
            }
        }
        return false
    }

    /**
     * 返回匹配优先级：0 片名直接包含、1 首字母前缀、2 首字母包含、3 首字母模糊子序列；
     * -1 表示不匹配。数字越小排序越靠前。
     */
    fun score(query: String, title: String): Int {
        val q = normalize(query)
        if (q.isEmpty()) return -1
        val t = normalize(title)
        val ini = initials(title)
        val forms = linkedSetOf(q, initials(q))
        var best = -1
        for (f in forms) {
            if (f.isEmpty()) continue
            val s = when {
                t.contains(f, ignoreCase = true) -> 0
                ini.startsWith(f) -> 1
                ini.contains(f) -> 2
                f.length >= 2 && isSubsequence(f, ini) -> 3
                else -> -1
            }
            if (s >= 0 && (best < 0 || s < best)) best = s
        }
        return best
    }

    /** 在本地索引里按拼音首字母模糊查找，结果按相关度排序 */
    fun filter(query: String, items: Collection<VideoItem>, limit: Int = 120): List<VideoItem> {
        if (normalize(query).isEmpty()) return emptyList()
        return items.mapNotNull { v ->
            val s = score(query, v.title)
            if (s < 0) null else s to v
        }
            .sortedWith(compareBy<Pair<Int, VideoItem>> { it.first }.thenBy { it.second.title })
            .take(limit)
            .map { it.second }
    }
}
