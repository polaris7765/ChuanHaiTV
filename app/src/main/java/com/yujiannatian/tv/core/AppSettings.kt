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

    /** 已发现过的播放源名称（保持首次出现顺序，用于设置页下拉选择） */
    fun knownSources(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_KNOWN_SOURCES, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("\u0001").filter { it.isNotBlank() }
    }

    /** 把详情页解析到的播放源名称累积进已知列表（去重、保序） */
    fun addKnownSources(context: Context, names: List<String>) {
        if (names.isEmpty()) return
        val set = LinkedHashSet(knownSources(context))
        set.addAll(names)
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KNOWN_SOURCES, set.joinToString("\u0001"))
            .apply()
    }
}
