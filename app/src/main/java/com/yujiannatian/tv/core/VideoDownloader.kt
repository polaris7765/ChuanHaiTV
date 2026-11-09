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

    /**
     * 「下载中」记录的文件还没生成，不能按文件存在过滤；只有 done 记录才校验文件。
     * 排序：下载中置顶，其余按时间倒序。
     */
    @Synchronized
    fun list(context: Context): List<DownloadRecord> = try {
        val f = indexFile(context)
        if (!f.exists()) emptyList()
        else (gson.fromJson(f.readText(), DownloadIndex::class.java)?.records ?: emptyList())
            .filter { it.status != "done" || (it.file.isNotBlank() && File(it.file).exists()) }
            .sortedWith(
                compareBy<DownloadRecord> { if (it.status == "downloading") 0 else 1 }
                    .thenByDescending { it.time }
            )
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
 * 直链 MP4 则流式保存。两类都支持**断点续传**：
 *  - m3u8：`.part` 文件记录「分片指纹 + 已完成分片数」，续传时从下一片以 append 写入；
 *  - MP4：用 HTTP Range 从已写入字节继续，服务端忽略 Range 时才从头覆盖。
 *
 * 下载任务在 enqueue 时立刻写入 DownloadsStore（status=downloading），
 * 因此「我的下载」能实时看到「下载中」分组与进度；状态通过监听器回调到主线程。
 */
object VideoDownloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, DownloadState>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<(String, DownloadState) -> Unit>()
    /** 进度持久化节流：避免每个分片都写一次 index.json */
    private val persistedProgress = ConcurrentHashMap<String, Int>()

    fun observe(listener: (String, DownloadState) -> Unit) {
        listeners.add(listener)
    }

    fun unobserve(listener: (String, DownloadState) -> Unit) {
        listeners.remove(listener)
    }

    fun state(context: Context?, key: String): DownloadState {
        states[key]?.let { return it }
        if (context != null) {
            val r = DownloadsStore.list(context).firstOrNull { it.key == key } ?: return DownloadState()
            return when (r.status) {
                "done" -> if (r.file.isNotBlank() && File(r.file).exists()) {
                    DownloadState(DownloadStatus.DONE, 100, "已下载", r.file)
                } else DownloadState()
                // paused / failed 都表示「文件还在、任务已停」，可回到详情页或点「继续」续传
                "paused", "failed" -> DownloadState(
                    DownloadStatus.FAILED, r.progress, "已暂停 ${r.progress}%，可续传"
                )
                else -> DownloadState(
                    DownloadStatus.DOWNLOADING, r.progress, "下载中 ${r.progress}%"
                )
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

        fun save(status: String, progress: Int, file: String = "", size: Long = 0L) {
            DownloadsStore.add(
                app,
                DownloadRecord(
                    key = key,
                    title = title,
                    episode = episode,
                    file = file,
                    size = size,
                    time = System.currentTimeMillis(),
                    status = status,
                    progress = progress,
                    episodePath = episodePath
                )
            )
        }

        // 立刻登记为「下载中」，让「我的下载」同步出现该任务
        save("downloading", 0)
        persistedProgress[key] = 0

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
                downloadTo(app, key, url, file) { p ->
                    // 取消瞬间仍有一片在途，回写进度会把 cancel() 刚写入的 paused 状态覆盖掉
                    if (!isActive(key)) return@downloadTo
                    update(key, DownloadState(DownloadStatus.DOWNLOADING, p, "下载中 $p%"))
                    // 每 2% 或状态到 100% 持久化一次，列表页刷新时也能看到进度
                    val last = persistedProgress[key] ?: -1
                    if (p >= last + 2 || p == 100) {
                        persistedProgress[key] = p
                        save("downloading", p, file.absolutePath.takeIf { file.exists() } ?: "",
                            if (file.exists()) file.length() else 0L)
                    }
                }
                save("done", 100, file.absolutePath, file.length())
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
                // 保留已下载部分（.part / 已写入文件）供下次断点续传
                save("failed", 0)
                update(key, DownloadState(DownloadStatus.FAILED, 0, msg))
            } finally {
                jobs.remove(key)
                persistedProgress.remove(key)
            }
        }
    }

    /**
     * 启动时校正残留记录：应用升级 / 进程被回收时，index.json 可能停在
     * status=downloading（最后一次 100% 已落盘，但 done 状态没来得及写）。
     * 文件完整则判定完成，否则标为可续传的暂停态，避免列表永远显示「下载中 100%」。
     */
    @Synchronized
    fun reconcile(context: Context) {
        val app = context.applicationContext
        DownloadsStore.list(app)
            .filter { it.status == "downloading" && !isActive(it.key) }
            .forEach { r ->
                val complete = r.progress >= 100 && r.file.isNotBlank() &&
                    File(r.file).exists() && r.size > 0L
                DownloadsStore.add(
                    app,
                    r.copy(
                        status = if (complete) "done" else "paused",
                        progress = if (complete) 100 else r.progress
                    )
                )
            }
    }

    /**
     * 取消任务：保留已下载文件与 .part 断点，并把记录标为「已暂停」，
     * 这样「我的下载」里可以直接点「继续」从断点续传，而不是条目凭空消失。
     */
    fun cancel(context: Context, key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
        states.remove(key)
        persistedProgress.remove(key)
        val app = context.applicationContext
        val r = DownloadsStore.list(app).firstOrNull { it.key == key }
        if (r != null && r.status != "done") {
            DownloadsStore.add(app, r.copy(status = "paused"))
        }
        val progress = r?.progress ?: 0
        update(
            key,
            DownloadState(DownloadStatus.FAILED, progress, "已暂停 ${progress}%，可续传")
        )
    }

    /** 删除任务：连同已下载文件与断点文件一起清除 */
    fun delete(context: Context, key: String) {
        val app = context.applicationContext
        cancel(app, key)
        DownloadsStore.remove(app, key)
        val dir = DownloadsStore.dir(app)
        dir.listFiles()?.filter { it.name.startsWith(stem(key)) }
            ?.forEach { runCatching { it.delete() } }
        // 记录已不存在，内存状态也要复位，否则详情页按钮会一直显示「重试」
        update(key, DownloadState())
    }

    private fun stem(key: String): String = "d${Integer.toHexString(key.hashCode())}"
    private fun fileName(key: String, ext: String): String = stem(key) + ext
    private fun partFile(app: Context, key: String) = File(DownloadsStore.dir(app), stem(key) + ".part")

    private data class Part(val fingerprint: String, val done: Int, val total: Int)

    private fun readPart(file: File): Part? = runCatching {
        val lines = file.readLines()
        Part(lines[0], lines[1].toInt(), lines[2].toInt())
    }.getOrNull()

    private fun writePart(file: File, fp: String, done: Int, total: Int) {
        runCatching { file.writeText("$fp\n$done\n$total") }
    }

    private suspend fun downloadTo(
        app: Context,
        key: String,
        url: String,
        target: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val headers = mapOf("Referer" to SiteConfig.baseUrl)

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
            // 指纹用于确认「播放列表没变」：直播 / 换源后必须从头下载，不能拼接错位分片
            val fingerprint = parsed.segments.joinToString("|") { it.url }.hashCode().toString()
            val part = partFile(app, key)
            val saved = readPart(part)
            var done = if (saved?.fingerprint == fingerprint) {
                saved.done.coerceIn(0, total)
            } else 0
            val append = done > 0 && target.exists() && target.length() > 0
            if (!append) {
                done = 0
                runCatching { target.delete() }
                runCatching { part.delete() }
            }
            if (done >= total) {
                onProgress(100)
                runCatching { part.delete() }
                return@withContext
            }

            FileOutputStream(target, append).use { out ->
                for (index in done until total) {
                    // 同步 OkHttp 调用不会响应协程取消，必须在每片之间显式检查，
                    // 否则点「取消」后任务仍会把整部片写完并误标为 done。
                    if (!isActive(key)) throw CancellationException("已取消下载")
                    val seg = parsed.segments[index]
                    val raw = SiteHttpClient.bytes(seg.url, headers)
                    val plain = if (seg.keyUri.isNotBlank()) {
                        val k = keys[seg.keyUri] ?: SiteHttpClient.bytes(seg.keyUri, headers)
                            .also { keys[seg.keyUri] = it }
                        HlsCrypto.decrypt(raw, k, seg.ivHex)
                    } else {
                        raw
                    }
                    out.write(plain)
                    val finished = index + 1
                    writePart(part, fingerprint, finished, total)
                    onProgress((finished * 100) / total)
                }
                out.flush()
            }
            runCatching { part.delete() }
        } else {
            val from = if (target.exists()) target.length() else 0L
            SiteHttpClient.openRange(url, from, headers).use { response ->
                val body = response.body ?: throw SiteError(ErrorKind.NETWORK, "下载内容为空", url)
                val resumed = response.code == 206 && from > 0
                val total = if (resumed) from + body.contentLength() else body.contentLength()
                if (!resumed) {
                    runCatching { target.delete() }
                    runCatching { partFile(app, key).delete() }
                }
                FileOutputStream(target, resumed).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var written = if (resumed) from else 0L
                        while (true) {
                            if (!isActive(key)) throw CancellationException("已取消下载")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 99))
                        }
                    }
                    out.flush()
                }
            }
            onProgress(100)
        }
    }
}
