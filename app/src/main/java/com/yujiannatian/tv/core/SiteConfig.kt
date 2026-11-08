package com.yujiannatian.tv.core

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

/**
 * 可配置的站点地址。所有解析结果都只保存"站点相对路径"，
 * 因此更换地址后旧缓存不会与新站点混淆；commit 会提升版本号使缓存整体失效。
 */
object SiteConfig {

    const val DEFAULT_SITE = "http://www.yujiannatian.net/"

    private const val PREF_NAME = "site_config"
    private const val KEY_URL = "base_url"
    private const val KEY_PREV = "prev_url"
    private const val KEY_VER = "config_version"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences =
        if (::prefs.isInitialized) prefs
        else error("SiteConfig 未初始化，请检查 App.onCreate")

    val baseUrl: String get() = p().getString(KEY_URL, DEFAULT_SITE) ?: DEFAULT_SITE
    val previousUrl: String get() = p().getString(KEY_PREV, "") ?: ""
    val version: Long get() = p().getLong(KEY_VER, 1L)

    /** 规范化并校验用户输入；不合法时抛 INVALID_URL */
    fun normalize(input: String): String {
        var s = input.trim().replace(Regex("\\s+"), "")
        if (s.isEmpty()) {
            throw SiteError(ErrorKind.INVALID_URL, "地址不能为空", "示例：$DEFAULT_SITE")
        }
        if (!s.contains("://")) s = "http://$s"
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            throw SiteError(ErrorKind.INVALID_URL, "地址无法识别", e.message ?: s)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SiteError(ErrorKind.INVALID_URL, "只支持 http / https 地址", s)
        }
        if (uri.host.isNullOrBlank()) {
            throw SiteError(ErrorKind.INVALID_URL, "缺少域名", s)
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        var path = uri.rawPath ?: ""
        if (path.isBlank()) path = "/"
        if (!path.endsWith("/")) path += "/"
        return "$scheme://${uri.host}$port$path"
    }

    /** 提交新地址：保留旧地址以便一键回退，并递增版本号让缓存失效 */
    fun commit(url: String) {
        val normalized = normalize(url)
        if (normalized.equals(baseUrl, ignoreCase = true)) return
        p().edit()
            .putString(KEY_PREV, baseUrl)
            .putString(KEY_URL, normalized)
            .putLong(KEY_VER, version + 1)
            .apply()
        SiteRepository.clearCache()
    }

    fun reset() = commit(DEFAULT_SITE)

    fun usePrevious(): Boolean {
        val prev = previousUrl
        if (prev.isBlank() || prev.equals(baseUrl, ignoreCase = true)) return false
        commit(prev)
        return true
    }
}
