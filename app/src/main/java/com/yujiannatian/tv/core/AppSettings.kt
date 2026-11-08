package com.yujiannatian.tv.core

import android.content.Context

/**
 * 应用级偏好（与站点缓存、历史收藏相互独立）。
 * 目前承载「是否允许下载」开关：未手动设置时按设备默认——
 * 电视端默认关闭（单集不提供下载），其余端默认开启。
 */
object AppSettings {

    private const val PREF = "yujian_app_settings"
    private const val KEY_ALLOW_DOWNLOAD = "allow_download"
    private const val KEY_DEFAULT_SOURCE = "default_source"
    private const val KEY_KNOWN_SOURCES = "known_sources"

    /**
     * 站点（赞片 CMS 模板）常见播放线路，实采详情页得到。
     * 作为保底：即使用户从未打开过任何影片，设置页也应有多个可选源，而不是只有「自动」。
     * 与 HtmlParser.parseDetail 取「更完整的名称」一致，这里用移动端下拉里的全称。
     */
    private val BUILTIN_SOURCES = listOf("新视觉云", "爱奇艺", "好好云")

    /** 是否允许下载：未设置过则按设备类型给默认值 */
    fun allowDownload(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_ALLOW_DOWNLOAD)) {
            prefs.getBoolean(KEY_ALLOW_DOWNLOAD, true)
        } else {
            !DeviceKind.isTv(context)
        }
    }

    fun setAllowDownload(context: Context, allow: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALLOW_DOWNLOAD, allow)
            .apply()
    }

    /** 默认播放源名称；空字符串表示「自动（第一个可用）」 */
    fun defaultSource(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_SOURCE, "") ?: ""
    }

    fun setDefaultSource(context: Context, name: String) {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT_SOURCE, name)
            .apply()
    }

    /** 本地已发现（详情页解析到）的播放源名称，保持首次出现顺序 */
    private fun discoveredSources(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_KNOWN_SOURCES, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("\u0001").filter { it.isNotBlank() }
    }

    /**
     * 供设置页展示的播放源列表 = 内置保底 + 已发现源，去重保序。
     * 内置源保证任何时机至少有 3 个可选；已发现源（含站点改版后的新线路）排在后面。
     */
    fun knownSources(context: Context): List<String> {
        val merged = LinkedHashSet<String>(BUILTIN_SOURCES)
        merged.addAll(discoveredSources(context))
        return merged.toList()
    }

    /** 把详情页解析到的真实播放源名称累积进已知列表（去重、保序） */
    fun addKnownSources(context: Context, names: List<String>) {
        if (names.isEmpty()) return
        val set = LinkedHashSet(discoveredSources(context))
        set.addAll(names)
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KNOWN_SOURCES, set.joinToString("\u0001"))
            .apply()
    }
}
