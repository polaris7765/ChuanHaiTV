package com.yujiannatian.tv.core

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class DownloadStatus { IDLE, RESOLVING, DOWNLOADING, DONE, FAILED }

data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = 0,
    val message: String = "",
    val file: String = ""
)

/** 已下载记录的轻量持久化 */
private data class DownloadIndex(val records: List<DownloadRecord> = emptyList())

object DownloadsStore {

    private val gson = Gson()

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(base, "yujian_downloads").apply { mkdirs() }
    }

    private fun indexFile(context: Context) = File(dir(context), "index.json")

    @Synchronized
    fun list(context: Context): List<DownloadRecord> = try {
        val f = indexFile(context)
        if (!f.exists()) emptyList()
        else (gson.fromJson(f.readText(), DownloadIndex::class.java)?.records ?: emptyList())
            .filter { File(it.file).exists() }
            .sortedByDescending { it.time }
    } catch (t: Throwable) {
        emptyList()
    }

    @Synchronized
    fun add(context: Context, record: DownloadRecord) {
        val updated = list(context).filter { it.key != record.key } + record
        try {
            indexFile(context).writeText(gson.toJson(DownloadIndex(updated)))
        } catch (t: Throwable) {
            // 索引写失败不影响文件本身
        }
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        list(context).filter { it.key != key }.let {
            try {
                indexFile(context).writeText(gson.toJson(DownloadIndex(it)))
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}

/**
 * 剧集下载：m3u8 逐分片下载并拼接为本地 .ts（支持 AES-128 与密钥轮换），
 * 直链 MP4 则整文件保存。状态通过监听器回调到主线程，供剧集卡片显示进度。
 */
object VideoDownloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, DownloadState>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<(String, DownloadState) -> Unit>()

    fun observe(listener: (String, DownloadState) -> Unit) {
        listeners.add(listener)
    }

    fun unobserve(listener: (String, DownloadState) -> Unit) {
        listeners.remove(listener)
    }

    fun state(context: Context?, key: String): DownloadState {
        states[key]?.let { if (it.status == DownloadStatus.DONE) return it }
        if (context != null) {
            DownloadsStore.list(context).firstOrNull { it.key == key }?.let {
                if (File(it.file).exists()) {
                    val done = DownloadState(DownloadStatus.DONE, 100, "已下载", it.file)
                    states[key] = done
                    return done
                }
            }
        }
        return states[key] ?: DownloadState()
    }

    private fun update(key: String, state: DownloadState) {
        states[key] = state
        main.post {
            listeners.forEach { runCatching { it(key, state) } }
        }
    }

    fun isActive(key: String) = jobs[key]?.isActive == true

    fun enqueue(
        context: Context,
        key: String,
        title: String,
        episode: String,
        episodePath: String
    ) {
        if (isActive(key)) return
        val app = context.applicationContext
        if (state(app, key).status == DownloadStatus.DONE) return

        jobs[key] = scope.launch {
            update(key, DownloadState(DownloadStatus.RESOLVING, 0, "正在获取播放地址…"))
            try {
                val url = when (val r = SiteRepository.playback(episodePath)) {
                    is LoadResult.Ok -> r.value.url
                    is LoadResult.Err -> throw SiteError(
                        ErrorKind.PLAYBACK,
                        "播放地址解析失败",
                        r.error.message ?: ""
                    )
                }
                val ext = if (url.contains(".m3u8", ignoreCase = true)) ".ts" else ".mp4"
                val file = File(DownloadsStore.dir(app), fileName(key, ext))
                update(key, DownloadState(DownloadStatus.DOWNLOADING, 0, "开始下载 0%"))
                downloadTo(url, file) { p ->
                    update(key, DownloadState(DownloadStatus.DOWNLOADING, p, "下载中 $p%"))
                }
                DownloadsStore.add(
                    app,
                    DownloadRecord(
                        key = key,
                        title = title,
                        episode = episode,
                        file = file.absolutePath,
                        size = file.length(),
                        time = System.currentTimeMillis()
                    )
                )
                update(
                    key,
                    DownloadState(DownloadStatus.DONE, 100, "已下载", file.absolutePath)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = when (t) {
                    is SiteError -> t.message ?: "下载失败"
                    is OutOfMemoryError -> "内存不足，无法下载该分片"
                    else -> t.message?.take(80) ?: "下载失败"
                }
                update(key, DownloadState(DownloadStatus.FAILED, 0, msg))
            } finally {
                jobs.remove(key)
            }
        }
    }

    fun cancel(context: Context, key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
        states.remove(key)
        val app = context.applicationContext
        val dir = DownloadsStore.dir(app)
        dir.listFiles()?.filter { it.name.startsWith(stem(key)) }
            ?.forEach { runCatching { it.delete() } }
        update(key, DownloadState())
    }

    fun delete(context: Context, key: String) {
        cancel(context, key)
        DownloadsStore.remove(context, key)
    }

    private fun stem(key: String): String = "d${Integer.toHexString(key.hashCode())}"
    private fun fileName(key: String, ext: String): String = stem(key) + ext

    private suspend fun downloadTo(
        url: String,
        target: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val headers = mapOf("Referer" to SiteConfig.baseUrl)
        if (target.exists()) runCatching { target.delete() }

        if (url.contains(".m3u8", ignoreCase = true)) {
            var playlistUrl = url
            var parsed = HlsPlaylist.parsePlaylist(
                SiteHttpClient.get(playlistUrl, headers), playlistUrl
            )
            if (parsed.variants.isNotEmpty()) {
                playlistUrl = parsed.variants.last()
                parsed = HlsPlaylist.parsePlaylist(
                    SiteHttpClient.get(playlistUrl, headers), playlistUrl
                )
            }
            if (parsed.segments.isEmpty()) {
                throw SiteError(ErrorKind.PARSE, "播放列表里没有分片", playlistUrl)
            }
            val keys = HashMap<String, ByteArray>()
            val total = parsed.segments.size
            FileOutputStream(target, false).use { out ->
                parsed.segments.forEachIndexed { index, seg ->
                    val raw = SiteHttpClient.bytes(seg.url, headers)
                    val plain = if (seg.keyUri.isNotBlank()) {
                        val key = keys[seg.keyUri] ?: SiteHttpClient.bytes(seg.keyUri, headers)
                            .also { keys[seg.keyUri] = it }
                        HlsCrypto.decrypt(raw, key, seg.ivHex)
                    } else {
                        raw
                    }
                    out.write(plain)
                    onProgress(((index + 1) * 100) / total)
                }
                out.flush()
            }
        } else {
            FileOutputStream(target, false).use { it.write(SiteHttpClient.bytes(url, headers)) }
            onProgress(100)
        }
    }

}
