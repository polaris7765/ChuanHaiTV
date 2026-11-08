package com.yujiannatian.tv.core

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/** 简单的 TTL 磁盘缓存；缓存 key 内含站点地址与配置版本号，改址后自动失效 */
object CacheStore {

    private val gson = Gson()
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.applicationContext.cacheDir, "site_cache").apply { mkdirs() }
    }

    private fun fileOf(key: String): File = File(dir, hash(key).take(24) + ".json")

    fun <T> read(key: String, clazz: Class<T>, maxAgeMs: Long): T? {
        if (!::dir.isInitialized) return null
        val f = fileOf(key)
        if (!f.exists()) return null
        if (maxAgeMs > 0 && System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null
        return try {
            gson.fromJson(f.readText(), clazz)
        } catch (t: Throwable) {
            // 低版本系统缺类时会抛 Error，缓存损坏不应升级为崩溃
            runCatching { f.delete() }
            null
        }
    }

    fun write(key: String, value: Any) {
        if (!::dir.isInitialized) return
        try {
            fileOf(key).writeText(gson.toJson(value))
        } catch (_: Throwable) {
            // 缓存失败（含系统级 Error）不影响主流程
        }
    }

    fun delete(key: String) {
        if (!::dir.isInitialized) return
        runCatching { fileOf(key).delete() }
    }

    fun clear() {
        if (!::dir.isInitialized) return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun hash(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
