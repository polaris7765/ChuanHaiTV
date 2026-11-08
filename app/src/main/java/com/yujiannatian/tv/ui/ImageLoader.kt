package com.yujiannatian.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteHttpClient
import com.yujiannatian.tv.core.SiteRepository
import java.util.concurrent.Executors

/**
 * 轻量封面加载：内存 LRU + 采样解码 + 占位图；图片失败不影响浏览。
 * 图片请求带站点 Referer（部分图床有防盗链），并做最多 3 次重试以应对弱网抖动。
 * 当某张封面最终仍加载失败（例如片源站某个 CDN 已下线、连接超时），不再留下空白块，
 * 而是用影片标题在占位底上渲染文字，保证卡片可辨识、界面不显“破图”。
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt().coerceAtLeast(2 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** 加载封面；label 为加载/解码失败时的文字兜底（通常是影片名） */
    fun load(target: ImageView, raw: String?, label: String = "") {
        val url = if (raw.isNullOrBlank()) "" else {
            try {
                SiteRepository.urlFor(raw)
            } catch (e: Exception) {
                raw
            }
        }
        target.setTag(R.id.tag_image_url, url)
        if (url.isNotBlank()) {
            target.setImageDrawable(ContextCompat.getDrawable(target.context, R.drawable.cover_placeholder))
        } else if (label.isNotBlank()) {
            target.setImageBitmap(textBitmap(target, label))
        } else {
            target.setImageDrawable(ContextCompat.getDrawable(target.context, R.drawable.cover_placeholder))
        }
        if (url.isBlank()) return
        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }
        // 线程池队列异常或系统资源紧张时也不应拖垮界面
        runCatching {
            pool.execute {
                val bitmap = loadBitmapWithRetry(url)
                if (bitmap != null) runCatching { cache.put(url, bitmap) }
                main.post {
                    // 仅当本次绑定仍有效（未被快速回收复用）时才落图，避免错位
                    if (target.getTag(R.id.tag_image_url) == url) {
                        if (bitmap != null) {
                            target.setImageBitmap(bitmap)
                        } else if (label.isNotBlank()) {
                            // 最终失败：用标题文字兜底，避免空白块
                            target.setImageBitmap(textBitmap(target, label))
                        }
                    }
                }
            }
        }
    }

    /** 带重试的封面下载与解码：最多 3 次，失败后短延迟重试 */
    private fun loadBitmapWithRetry(url: String): Bitmap? {
        val referer = runCatching { SiteConfig.baseUrl }.getOrDefault("")
        repeat(3) { attempt ->
            try {
                val bytes = if (referer.isNotBlank()) {
                    SiteHttpClient.bytes(url, mapOf("Referer" to referer))
                } else {
                    SiteHttpClient.bytes(url)
                }
                decodeBytes(bytes)?.let { return it }
            } catch (t: Throwable) {
                // 解码失败 / 网络异常都进入重试；仍失败则最终返回 null（保留占位图）
            }
            if (attempt < 2) runCatching { Thread.sleep(300L * (attempt + 1)) }
        }
        return null
    }

    /** 采样解码：先读尺寸计算 inSampleSize，再以 RGB_565 解码，降低内存占用 */
    private fun decodeBytes(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 640)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (t: Throwable) {
        null
    }

    /** 生成「标题文字占位图」：深色底 + 居中两行影片名，替代空白破图 */
    private fun textBitmap(target: ImageView, label: String): Bitmap {
        val w = (target.width.takeIf { it > 0 } ?: 320)
        val h = (target.height.takeIf { it > 0 } ?: 460)
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Bitmap.createBitmap(320, 460, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1B2440.toInt()) // 与 card_bg 一致
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA8C7.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        // 字号随宽度自适应，最多两行
        val lines = wrap(label, 8)
        val textSize = (w / 9f).coerceIn(18f, 30f)
        paint.textSize = textSize
        val lineHeight = textSize * 1.2f
        val totalH = lineHeight * lines.size
        var y = (h - totalH) / 2f + textSize
        for (line in lines) {
            canvas.drawText(line, w / 2f, y, paint)
            y += lineHeight
        }
        return bmp
    }

    /** 简单按字数折行（中文每约 n 字一行） */
    private fun wrap(text: String, perLine: Int): List<String> {
        if (text.length <= perLine) return listOf(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            out.add(text.substring(i, (i + perLine).coerceAtMost(text.length)))
            i += perLine
        }
        return out.take(3) // 至多三行，超出截断由 drawText 省略号处理
    }
}
