package com.yujiannatian.tv.ui

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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 轻量封面加载：内存 LRU + 采样解码 + 统一圆角 + App 图标兜底。
 *
 * 设计要点（针对「满屏空白占位块」的根因修复）：
 *  - 图床 CDN 在部分设备 / 网络下不可达，若等「加载失败」才显示兜底，长超时请求会把
 *    解码线程池占满，几十张卡片排队，兜底永远轮不到。因此**绑定时立即显示 App 图标**，
 *    只有真正下载并解码成功才替换成海报；失败/超时保持图标，界面任何时刻都有缩略图。
 *  - 图片请求走 [SiteHttpClient.imageBytes] 的短超时通道，并减少重试次数，快速收敛。
 *  - 所有封面（真实海报 / App 图标 / 占位）统一由 ViewOutlineProvider 裁成圆角，
 *    覆盖首页网格、详情页、Leanback 卡片等全部 ImageView，且所有设备表现一致。
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt().coerceAtLeast(2 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 图床熔断表：host -> 熔断截止时间。
     * 站点里混着大量已失效图床（实测 viptulz.com、img.lzipic.com、p.lztutu.com 等
     * 连接直接超时）。若每次都让它们走完整超时，会占满解码线程池，导致可用图床的
     * 海报也排不上队。同一 host 连续失败 2 次即熔断 5 分钟，期间直接走 App 图标兜底。
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

        // 关键：先立刻放下兜底缩略图（App 图标），保证任何网络状态下都看得见图，
        // 而不是等异步失败回调（长超时下该回调可能几分钟都轮不到）。
        target.setImageBitmap(iconFallback(target))
        if (url.isBlank()) return

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }
        val host = hostOf(url)
        // 已熔断的失效图床：不再发起请求，直接保持 App 图标，避免堵住线程池
        if (isBlocked(host)) return

        runCatching {
            pool.execute {
                val bitmap = loadBitmapWithRetry(url, host)
                if (bitmap != null) runCatching { cache.put(url, bitmap) }
                // 只有成功才替换；失败时保持已经显示着的 App 图标，无需再回主线程改图。
                if (bitmap == null) return@execute
                main.post {
                    // 仅当本次绑定仍有效（未被快速回收复用）时才落图，避免错位
                    if (target.getTag(R.id.tag_image_url) == url) {
                        target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    /** 带重试的封面下载与解码：短超时通道 + 最多 2 次，失败快速返回 null */
    private fun loadBitmapWithRetry(url: String, host: String): Bitmap? {
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
     * 对 setImageBitmap / setImageDrawable 都生效，无需改布局、无需自定义 View，
     * 因此首页网格、详情封面、Leanback ImageCardView 全都能保持一致圆角。
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
