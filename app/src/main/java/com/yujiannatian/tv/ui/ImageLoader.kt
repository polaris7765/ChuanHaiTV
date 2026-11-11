package com.yujiannatian.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.DeviceKind
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteHttpClient
import com.yujiannatian.tv.core.SiteRepository
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 缓存 key 归一化：同一张图在列表页用 http、详情页用 https，或 HTML 里残留
 * `&amp;` / `#fragment` 时，不应被当成不同图片重复下载。只影响缓存文件名，
 * 实际请求仍使用原始 url（保留 CDN 签名参数）。
 *
 * 特意放在 object 外：纯字符串函数，可在 JVM 单测里直接验证，
 * 不会触发 ImageLoader 的 Handler / LruCache 初始化。
 */
fun normalizeCoverKey(url: String): String {
    val clean = url.trim().replace("&amp;", "&").substringBefore('#')
    return when {
        clean.startsWith("https://") -> "http://" + clean.removePrefix("https://")
        else -> clean
    }
}

/**
 * 轻量封面加载：内存 LRU + **持久化磁盘缓存** + 采样解码 + 统一圆角 + App 图标兜底。
 *
 * 严格按「先缓存、后网络」的顺序：
 *   1. 同一张图已经显示在 View 上 → 什么都不做（不排队、不闪兜底图）；
 *   2. 内存 LRU 命中 → **同步**直接显示，零延迟、零线程切换；
 *   3. 磁盘缓存命中（专用磁盘池）→ 解码后写回内存并显示，完全不发网络请求；
 *   4. 负缓存命中（已确认失效的图床 URL）→ 保持兜底图，不再重复联网；
 *   5. 以上都未命中 → 才走独立网络池短超时下载，成功后同时写磁盘与内存。
 *
 * 命中率修复要点（旧实现虽然也是这个顺序，但体感上「每次都重新加载」）：
 *   - 旧实现把磁盘读取和 2 次网络重试塞进同一个 6 线程无界队列，失效图床的失败任务
 *     会长期占满线程，把磁盘命中任务堵在队尾，用户只能看到兜底图标；
 *     现在磁盘池 / 网络池分离，且队列有界、优先保留新任务。
 *   - 旧实现每次 bind 都无条件先 setImageBitmap(兜底图)，哪怕内存已命中或同图已显示；
 *     现在只在确实需要异步加载时才放兜底图。
 *   - 每个异步任务开工前先校验绑定是否仍有效，离屏 View 不再占用线程跑完整流程。
 *   - 缓存写到 filesDir（旧版写 cacheDir，系统存储紧张时会被整体清掉，导致「缓存莫名其妙没了」）；
 *     读取时兼容旧目录与旧 key，命中后自愈迁移到新位置。
 *   - 缓存 key 归一化：http/https、&amp;、fragment 差异不会让同一张图重复下载。
 *   - 写盘临时文件名唯一，避免同一 URL 并发下载互相覆盖导致写缓存失败。
 *   - 失效封面的负缓存持久化：旧版熔断表只在进程内，重启后要重跑一批注定失败的请求；
 *     现在按 URL 记录 6 小时，实测二次启动网络请求从「每次都发」降为 0。
 *   - 每 10 次请求在 logcat 打一次 `ImageLoader` 命中率快照，便于实测验证。
 *
 * 其余既有策略保留：短超时、图床熔断、统一圆角、失败保持 App 图标。
 */
object ImageLoader {

    private const val TAG = "ImageLoader"
    private const val DISK_LIMIT_BYTES = 64L * 1024 * 1024
    private const val DISK_TRIM_TO_BYTES = 48L * 1024 * 1024
    private const val TRIM_CHECK_INTERVAL = 40
    private const val STATS_LOG_INTERVAL = 10
    /**
     * 失效封面的负缓存时长：手机 6 小时；TV 缩短到 10 分钟。
     * 电视首次启动时缓存为空、网络稍慢就可能把一批 URL 判成失效，
     * 长时间不再重试会让封面「永久」停在兜底图，网络恢复后也无法自愈。
     */
    private const val NEGATIVE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val NEGATIVE_TTL_TV_MS = 10 * 60 * 1000L

    /** 内存缓存调大到 1/8 堆：TV 上一屏 5 列网格滚动几页就会把 1/12 挤满 */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 磁盘读取：IO 很快，2 线程足够，绝不被网络请求阻塞 */
    private val diskPool = Executors.newFixedThreadPool(2)

    /** 网络下载：有界队列 + 丢弃最旧任务，快速滚动时不堆积失效请求 */
    private val netPool = ThreadPoolExecutor(
        4, 4, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(48),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val main = Handler(Looper.getMainLooper())
    private val writeCount = AtomicInteger(0)

    /** 命中统计：每 STATS_LOG_INTERVAL 次请求在 logcat 打一次汇总，便于实测命中率 */
    private val requests = AtomicInteger(0)
    private val hitMemory = AtomicInteger(0)
    private val hitDisk = AtomicInteger(0)
    private val hitNegative = AtomicInteger(0)
    private val hitNetwork = AtomicInteger(0)
    private val failed = AtomicInteger(0)

    /**
     * 调试 / 文档用：返回一次缓存命中快照。
     * 命中率 = 完全没有发起网络请求的比例（内存 + 磁盘 + 负缓存）。
     */
    fun cacheStats(): String {
        val r = requests.get()
        val cached = hitMemory.get() + hitDisk.get() + hitNegative.get()
        val rate = if (r == 0) 0 else cached * 100 / r
        return "请求=$r 内存=${hitMemory.get()} 磁盘=${hitDisk.get()} " +
            "负缓存=${hitNegative.get()} 网络=${hitNetwork.get()} 失败=${failed.get()} 缓存命中率=$rate%"
    }

    /**
     * 手动清除失效封面记录（设置页「重试封面」）：删掉 covers_failed/ 负缓存并清空
     * 进程内熔断表，让之前被判失败的 URL 重新尝试；已成功缓存的图片保持不动。
     * 电视上封面长期显示 App 图标时，这是不需要 adb 的自助恢复入口。
     */
    fun retryFailed(context: Context): Int {
        val app = context.applicationContext
        var removed = 0
        runCatching {
            failedDir(app).listFiles()?.forEach {
                if (it.isFile && it.delete()) removed++
            }
        }
        blockedHosts.clear()
        failCount.clear()
        Log.i(TAG, "人工清理失效封面记录 $removed 条；${cacheStats()}")
        return removed
    }

    /**
     * 只在请求有了结论（命中缓存 / 下载成功 / 确定失败 / 被熔断）后记录一次，
     * 这样打印出来的命中率是真实结果，而不是「异步任务还没跑完」的中间态。
     */
    private fun recordStats() {
        val n = requests.incrementAndGet()
        if (n % STATS_LOG_INTERVAL == 0) Log.i(TAG, cacheStats())
    }

    /**
     * 图床熔断表：host -> 熔断截止时间。
     * 站点里混着大量已失效图床（实测 viptulz.com、img.lzipic.com、p.lztutu.com 等
     * 连接直接超时）。熔断只影响「网络请求」，不影响命中磁盘缓存的封面。
     */
    private val blockedHosts = ConcurrentHashMap<String, Long>()
    private val failCount = ConcurrentHashMap<String, Int>()
    private const val FAIL_THRESHOLD = 2
    private const val BLOCK_MS = 5 * 60 * 1000L

    private fun hostOf(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("")

    private fun isBlocked(host: String): Boolean {
        if (host.isBlank()) return false
        val until = blockedHosts[host] ?: return false
        if (System.currentTimeMillis() > until) {
            blockedHosts.remove(host)
            failCount.remove(host)
            return false
        }
        return true
    }

    private fun markSuccess(host: String) {
        if (host.isBlank()) return
        failCount.remove(host)
        blockedHosts.remove(host)
    }

    private fun markFailure(host: String) {
        if (host.isBlank()) return
        val n = (failCount[host] ?: 0) + 1
        failCount[host] = n
        if (n >= FAIL_THRESHOLD) {
            blockedHosts[host] = System.currentTimeMillis() + BLOCK_MS
        }
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 新版缓存目录：filesDir 不会被系统在存储紧张时自动清空 */
    private fun coverDir(context: Context): File =
        File(context.applicationContext.filesDir, "covers").apply { mkdirs() }

    /** 旧版缓存目录：cacheDir，保留只读兼容 */
    private fun legacyDir(context: Context): File =
        File(context.applicationContext.cacheDir, "covers")

    private fun diskFile(context: Context, key: String): File =
        File(coverDir(context), "${md5(key)}.img")

    /**
     * 按优先级列出所有可能存有这张图的旧文件：
     * 新目录/新 key、旧目录/新 key、新目录/原始 url、旧目录/原始 url。
     * 旧版本以原始 url 的 MD5 存在 cacheDir 下，命中后会自动迁移到新位置。
     */
    private fun diskCandidates(context: Context, url: String, key: String): List<File> {
        val raw = md5(url)
        val normalized = md5(key)
        return listOf(
            File(coverDir(context), "$normalized.img"),
            File(legacyDir(context), "$normalized.img"),
            File(coverDir(context), "$raw.img"),
            File(legacyDir(context), "$raw.img")
        )
    }

    /**
     * 负缓存：站点里有一批图床已经彻底失效，封面下载必然失败。旧实现只在进程内做
     * host 熔断，重启后又要把这些 URL 全部重试一遍（每次 2 次短超时 + 200ms 等待），
     * 既拖慢列表显示也拉低命中率。这里把失败结果按 URL 落到本地，6 小时内直接兜底。
     */
    private fun failedDir(context: Context): File =
        File(context.applicationContext.filesDir, "covers_failed").apply { mkdirs() }

    private fun failedFile(context: Context, key: String): File =
        File(failedDir(context), "${md5(key)}.fail")

    private fun isRecentlyFailed(context: Context, key: String): Boolean = runCatching {
        val f = failedFile(context, key)
        if (!f.isFile) {
            false
        } else {
            val at = f.readText().toLongOrNull() ?: 0L
            val ttl = if (DeviceKind.isTv(context)) NEGATIVE_TTL_TV_MS else NEGATIVE_TTL_MS
            val age = System.currentTimeMillis() - at
            if (age in 0..ttl) true
            else {
                runCatching { f.delete() } // 过期后重新给图床一次机会
                false
            }
        }
    }.getOrDefault(false)

    private fun markFailed(context: Context, key: String) {
        runCatching { failedFile(context, key).writeText(System.currentTimeMillis().toString()) }
    }

    private fun clearFailed(context: Context, key: String) {
        runCatching { failedFile(context, key).delete() }
    }

    /** 原子写入 + 唯一临时文件名，避免并发下载同图时互相覆盖导致缓存写失败 */
    private fun writeDisk(context: Context, key: String, bytes: ByteArray) {
        runCatching {
            val f = diskFile(context, key)
            val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) runCatching { tmp.delete() }
            if (writeCount.incrementAndGet() % TRIM_CHECK_INTERVAL == 0) trimDisk(context)
        }
    }

    /** 缓存体积超限时删掉最旧的一批文件（两个目录一起算），避免长期占用用户存储 */
    private fun trimDisk(context: Context) {
        runCatching {
            val files: List<File> = buildList {
                coverDir(context).listFiles()?.let { addAll(it) }
                legacyDir(context).listFiles()?.let { addAll(it) }
            }.filter { it.isFile && !it.name.endsWith(".tmp") }
            var total = files.sumOf { it.length() }
            if (total <= DISK_LIMIT_BYTES) return@runCatching
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= DISK_TRIM_TO_BYTES) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    /** label 仅用于未来扩展（如无障碍描述），当前兜底统一用 App 图标 */
    fun load(target: ImageView, raw: String?, label: String = "") {
        applyRoundedCorners(target)

        val url = if (raw.isNullOrBlank()) "" else {
            try {
                SiteRepository.urlFor(raw)
            } catch (e: Exception) {
                raw
            }
        }
        if (url.isBlank()) {
            target.setTag(R.id.tag_image_url, null)
            target.setImageBitmap(iconFallback(target))
            return
        }

        val key = normalizeCoverKey(url)
        target.setTag(R.id.tag_image_url, key)

        // ① 内存命中：同步显示，不排队、不闪兜底图
        cache.get(key)?.let { bmp ->
            if (!bmp.isRecycled) {
                hitMemory.incrementAndGet()
                recordStats()
                showBitmap(target, key, bmp)
                return
            }
        }

        // ② 这张图已经显示在当前 View 上（例如列表复用时重绑同一条目）：什么都不做
        if (target.getTag(R.id.tag_image_shown) == key && target.drawable != null) {
            hitMemory.incrementAndGet() // 视图已持有该图，属于零成本命中
            recordStats()
            return
        }

        // ③ 只有确实要异步加载时才先放兜底图，保证任何网络状态下都看得见缩略图
        target.setImageBitmap(iconFallback(target))

        val app = target.context.applicationContext
        runCatching {
            diskPool.execute {
                // 离屏 / 已复用的 View 直接放弃，不再读盘更不再发网络请求
                if (!isStillBound(target, key)) return@execute

                // ④ 磁盘缓存优先（含旧目录、旧 key 兼容）
                for (file in diskCandidates(app, url, key)) {
                    if (!file.isFile || file.length() <= 0L) continue
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    val cached = bytes?.let { decodeBytes(it) }
                    if (bytes == null || cached == null) {
                        runCatching { file.delete() } // 损坏 / 读失败的文件丢弃，重新走网络
                        continue
                    }
                    hitDisk.incrementAndGet()
                    recordStats()
                    runCatching { cache.put(key, cached) }
                    // 旧位置命中的顺手迁移到新位置，下次一步命中
                    if (file != diskFile(app, key)) writeDisk(app, key, bytes)
                    postBitmap(target, key, cached)
                    return@execute
                }

                // ⑤ 负缓存：这张图近期已确认下载失败，直接用兜底图，不再重复联网
                if (isRecentlyFailed(app, key)) {
                    hitNegative.incrementAndGet()
                    recordStats()
                    return@execute
                }

                // ⑥ 进程内图床熔断：只跳过本次请求，绝不写负缓存。
                // 熔断是 host 级判断，若给尚未尝试过的 URL 也落长时负缓存，
                // 一次网络抖动就会让整批封面永久停在兜底图（TV 首启尤其明显）。
                val host = hostOf(url)
                if (isBlocked(host)) {
                    hitNegative.incrementAndGet()
                    recordStats()
                    return@execute
                }
                runCatching {
                    netPool.execute {
                        if (!isStillBound(target, key)) return@execute
                        if (isBlocked(host)) {
                            hitNegative.incrementAndGet()
                            recordStats()
                            return@execute
                        }
                        val bitmap = loadBitmapWithRetry(app, url, host, key)
                        if (bitmap != null) {
                            clearFailed(app, key)
                            hitNetwork.incrementAndGet()
                            recordStats()
                            runCatching { cache.put(key, bitmap) }
                            postBitmap(target, key, bitmap)
                        } else {
                            markFailed(app, key)
                            failed.incrementAndGet()
                            recordStats()
                        }
                    }
                }
            }
        }
    }

    /**
     * 绑定是否仍然有效。View tag 是普通字段，跨线程读最坏只是可见性延迟，
     * 不会造成错图：真正的落图仍由主线程 [postBitmap] 二次校验。
     */
    private fun isStillBound(target: ImageView, key: String): Boolean =
        target.getTag(R.id.tag_image_url) == key

    private fun showBitmap(target: ImageView, key: String, bitmap: Bitmap) {
        target.setImageBitmap(bitmap)
        target.setTag(R.id.tag_image_shown, key)
    }

    private fun postBitmap(target: ImageView, key: String, bitmap: Bitmap) {
        main.post {
            // 仅当本次绑定仍有效（未被快速回收复用）时才落图，避免错位
            if (isStillBound(target, key)) showBitmap(target, key, bitmap)
        }
    }

    /**
     * 封面下载与解码的兼容链（TV 端问题的关键修复）：
     *  1. 正常协商 jpeg/png/webp，**不再声明 avif**（Android 11 及以下无法解码）；
     *  2. 同 URL 再试一次「只收 jpeg/png」，排除 CDN 按 Accept 返回 TV 解不了的 WebP；
     *  3. 原 URL 是 https 时回退到 http 明文再走一遍——老电视盒子根证书陈旧，
     *     HTTPS 握手失败很常见，而站点本身支持明文（Manifest 已放开 cleartext）。
     * TV 设备还会放宽连接/读取超时。每一步都写 Log.w 说明失败原因，
     * 便于在电视上用 `adb logcat -s ImageLoader:W` 判断是网络、TLS 还是解码问题。
     * 总时限 25s，避免失效图床长期占满网络线程池。
     */
    private fun loadBitmapWithRetry(app: Context, url: String, host: String, key: String): Bitmap? {
        val referer = runCatching { SiteConfig.baseUrl }.getOrDefault("")
        val headers: Map<String, String> =
            if (referer.isNotBlank()) mapOf("Referer" to referer) else emptyMap()
        val relaxed = DeviceKind.isTv(app)
        val urls = buildList {
            add(url)
            if (url.startsWith("https://", ignoreCase = true)) {
                add("http://" + url.substring(8))
            }
        }
        val deadline = System.currentTimeMillis() + 25_000L
        for (candidate in urls) {
            repeat(2) { attempt ->
                if (System.currentTimeMillis() > deadline) return null
                try {
                    val bytes = SiteHttpClient.imageBytes(
                        candidate,
                        headers,
                        relaxedTimeout = relaxed,
                        legacyFormat = attempt > 0
                    )
                    decodeBytes(bytes)?.let {
                        markSuccess(host)
                        writeDisk(app, key, bytes)
                        return it
                    }
                    Log.w(TAG, "封面解码失败 host=$host bytes=${bytes.size} url=$candidate")
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "封面请求失败 host=$host url=$candidate " +
                            "err=${t.javaClass.simpleName}: ${t.message?.take(120)}"
                    )
                }
                if (attempt == 0) runCatching { Thread.sleep(150L) }
            }
        }
        markFailure(host)
        return null
    }

    /** 采样解码：先读尺寸计算 inSampleSize，再以 RGB_565 解码，降低内存占用 */
    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else {
                val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 640)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 给任意 ImageView 统一裁圆角：用 ViewOutlineProvider + clipToOutline，
     * 对 setImageBitmap / setImageDrawable 都生效，无需改布局、无需自定义 View。
     */
    private fun applyRoundedCorners(target: ImageView) {
        val radius = target.resources.getDimension(R.dimen.cover_corner_radius)
        target.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        target.clipToOutline = true
    }

    /** 兜底图按尺寸缓存：RecyclerView 快速滚动时避免反复创建大 Bitmap 触发 GC */
    private val fallbackCache = ConcurrentHashMap<String, Bitmap>()

    /**
     * App 图标兜底缩略图：深色卡片底 + 居中本 App 启动图标，比例随卡片尺寸，
     * 避免正方形图标被 centerCrop 裁得只剩局部；配合 [applyRoundedCorners] 呈现圆角。
     */
    private fun iconFallback(target: ImageView): Bitmap {
        val w = (target.width.takeIf { it > 0 } ?: 300)
        val h = (target.height.takeIf { it > 0 } ?: 420)
        val key = "${w}x$h"
        fallbackCache[key]?.let { if (!it.isRecycled) return it }

        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1B2440.toInt()) // 与 card_bg 一致
        runCatching {
            val icon: Drawable? = ContextCompat.getDrawable(target.context, R.mipmap.ic_launcher)
            if (icon != null) {
                val side = (minOf(w, h) * 0.46f).toInt()
                val left = (w - side) / 2
                val top = (h - side) / 2
                icon.bounds = Rect(left, top, left + side, top + side)
                icon.draw(canvas)
            }
        }
        fallbackCache[key] = bmp
        return bmp
    }
}
