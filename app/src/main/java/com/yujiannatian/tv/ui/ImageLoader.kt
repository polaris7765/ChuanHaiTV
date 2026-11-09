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
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteHttpClient
import com.yujiannatian.tv.core.SiteRepository
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轻量封面加载：内存 LRU + **磁盘缓存** + 采样解码 + 统一圆角 + App 图标兜底。
 *
 * 加载顺序（用户要求「获取到图片链接后先查本地缓存，不要每次都从网站取」）：
 *   1. 绑定瞬间同步显示 App 图标兜底，保证任何时刻都有缩略图；
 *   2. 内存 LRU 命中 → 直接显示；
 *   3. 线程池内查磁盘缓存文件 → 命中则解码显示，不发任何网络请求；
 *   4. 磁盘未命中 → 走短超时图片通道下载，成功后写入磁盘与内存，再显示。
 *
 * 其余既有策略保留：短超时、图床熔断、统一圆角、失败保持 App 图标。
 * 磁盘缓存以 URL 的 MD5 命名，存放原始图片字节；总体积超过上限时按修改时间裁剪。
 */
object ImageLoader {

    private const val DISK_LIMIT_BYTES = 64L * 1024 * 1024
    private const val DISK_TRIM_TO_BYTES = 48L * 1024 * 1024
    private const val TRIM_CHECK_INTERVAL = 40

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt().coerceAtLeast(2 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())
    private val writeCount = AtomicInteger(0)

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

    private fun coverDir(context: Context): File =
        File(context.applicationContext.cacheDir, "covers").apply { mkdirs() }

    private fun diskFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(coverDir(context), "$name.img")
    }

    /** 原子写入，避免下载中断留下半张损坏图片 */
    private fun writeDisk(context: Context, url: String, bytes: ByteArray) {
        runCatching {
            val f = diskFile(context, url)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) runCatching { tmp.delete() }
            if (writeCount.incrementAndGet() % TRIM_CHECK_INTERVAL == 0) trimDisk(context)
        }
    }

    /** 缓存体积超限时删掉最旧的一批文件，避免长期占用用户存储 */
    private fun trimDisk(context: Context) {
        runCatching {
            val dir = coverDir(context)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= DISK_LIMIT_BYTES) return
            files.sortedBy { it.lastModified() }.forEach {
                if (total <= DISK_TRIM_TO_BYTES) return
                val size = it.length()
                if (it.delete()) total -= size
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
        target.setTag(R.id.tag_image_url, url)

        // 关键：先立刻放下兜底缩略图（App 图标），保证任何网络状态下都看得见图。
        target.setImageBitmap(iconFallback(target))
        if (url.isBlank()) return

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }

        val app = target.context.applicationContext
        runCatching {
            pool.execute {
                // ① 先查磁盘缓存：命中就完全不请求站点
                val disk = diskFile(app, url)
                if (disk.isFile && disk.length() > 0) {
                    val cached = runCatching { decodeBytes(disk.readBytes()) }.getOrNull()
                    if (cached != null) {
                        runCatching { cache.put(url, cached) }
                        postBitmap(target, url, cached)
                        return@execute
                    }
                    // 损坏的缓存文件直接丢弃，重新走网络
                    runCatching { disk.delete() }
                }

                // ② 磁盘未命中才走网络（已熔断的失效图床直接跳过）
                val host = hostOf(url)
                if (isBlocked(host)) return@execute
                val bitmap = loadBitmapWithRetry(app, url, host) ?: return@execute
                runCatching { cache.put(url, bitmap) }
                postBitmap(target, url, bitmap)
            }
        }
    }

    private fun postBitmap(target: ImageView, url: String, bitmap: Bitmap) {
        main.post {
            // 仅当本次绑定仍有效（未被快速回收复用）时才落图，避免错位
            if (target.getTag(R.id.tag_image_url) == url) {
                target.setImageBitmap(bitmap)
            }
        }
    }

    /** 带重试的封面下载与解码：短超时通道 + 最多 2 次，成功后落磁盘缓存 */
    private fun loadBitmapWithRetry(app: Context, url: String, host: String): Bitmap? {
        val referer = runCatching { SiteConfig.baseUrl }.getOrDefault("")
        repeat(2) { attempt ->
            try {
                val bytes = if (referer.isNotBlank()) {
                    SiteHttpClient.imageBytes(url, mapOf("Referer" to referer))
                } else {
                    SiteHttpClient.imageBytes(url)
                }
                decodeBytes(bytes)?.let {
                    markSuccess(host)
                    writeDisk(app, url, bytes)
                    return it
                }
            } catch (t: Throwable) {
                // 解码失败 / 网络异常都进入重试；仍失败则返回 null（保持 App 图标）
            }
            if (attempt < 1) runCatching { Thread.sleep(200L) }
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
