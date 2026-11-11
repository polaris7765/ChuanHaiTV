package com.yujiannatian.tv.core

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** 带 UA、超时、有限重试与编码兜底的页面抓取器 */
object SiteHttpClient {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 老电视盒子（Android 6/7/9）的 TLS 实现与根证书较旧，显式给出
     * MODERN_TLS / COMPATIBLE_TLS / CLEARTEXT 三种连接规格，避免 OkHttp 在部分设备上
     * 只协商到 TLSv1 而直接握手失败，导致整批 HTTPS 图床封面被判为「失效」。
     */
    private val CONNECTION_SPECS = listOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionSpecs(CONNECTION_SPECS)
            .build()
    }

    /**
     * 封面图专用 client：短超时且不做连接层自动重试。
     * 图床 CDN 在部分设备/网络下不可达，用页面抓取的长超时会让解码线程池被长期占满，
     * 导致几十张卡片排队、迟迟走不到失败兜底，界面一直停在空白占位块。
     */
    private val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .connectionSpecs(CONNECTION_SPECS)
            .build()
    }

    /**
     * 电视端封面专用：盒子普遍走 WiFi / 有线但 DNS 与 TLS 握手明显慢于手机，
     * 4s / 6s 很容易在真正失败前就超时，进而把一堆 URL 永久写进「失效」负缓存。
     * 这里放宽到 8s / 12s，并允许一次连接层重试。
     */
    private val imageClientRelaxed: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionSpecs(CONNECTION_SPECS)
            .build()
    }

    fun get(url: String): String = get(url, emptyMap())

    fun get(url: String, extraHeaders: Map<String, String>): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()

        var last: SiteError? = null
        repeat(3) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw SiteError(
                            ErrorKind.HTTP,
                            "HTTP ${response.code}",
                            "请求地址：$url"
                        )
                    }
                    val body = response.body
                        ?: throw SiteError(ErrorKind.HTTP, "响应内容为空", url)
                    return decode(body.bytes(), response.header("Content-Type"))
                }
            } catch (e: SiteError) {
                if (e.kind == ErrorKind.HTTP) throw e
                last = e
            } catch (e: SocketTimeoutException) {
                last = SiteError(ErrorKind.TIMEOUT, "连接或读取超时", url)
            } catch (e: UnknownHostException) {
                last = SiteError(ErrorKind.NETWORK, "域名无法解析", e.message ?: url)
            } catch (e: IOException) {
                last = SiteError(ErrorKind.NETWORK, "网络连接中断", e.message ?: url)
            } catch (e: IllegalArgumentException) {
                throw SiteError(ErrorKind.INVALID_URL, "地址格式不正确", e.message ?: url)
            } catch (e: Exception) {
                last = SiteError(ErrorKind.NETWORK, "请求失败", e.message ?: url)
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(400L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        throw last ?: SiteError(ErrorKind.NETWORK, "网络连接失败", url)
    }

    /**
     * 打开一个支持 HTTP Range 的响应流，用于 MP4 流式下载与断点续传。
     * from>0 时带 `Range: bytes=from-`；调用方负责 use{} 关闭 Response，
     * 并通过 response.code == 206 判断是否真的从断点继续（200 表示服务端忽略了 Range）。
     */
    fun openRange(
        url: String,
        from: Long,
        extraHeaders: Map<String, String>
    ): okhttp3.Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (from > 0) builder.header("Range", "bytes=$from-")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        return response
    }

    /** 图片等二进制资源；失败由调用方降级为占位图 */
    fun bytes(url: String): ByteArray = bytes(url, emptyMap())

    fun bytes(url: String, extraHeaders: Map<String, String>): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("empty body")
        }
    }

    /**
     * 封面图专用：短超时 client，快速失败以便立即回退到 App 图标。
     *
     * · [relaxedTimeout] 为 TV 设备放宽超时；
     * · [legacyFormat] 为 true 时只接受 jpeg/png，**不协商 webp/avif**——
     *   部分 CDN 会按 Accept 返回 AVIF，而 Android TV（常见 6~9）的 BitmapFactory
     *   无法解码 AVIF，手机能显示、电视只显示兜底图的差异就来自这里；
     * · Accept 常驻去掉了 avif 条目，避免首轮就拿到 TV 无法解码的格式。
     */
    fun imageBytes(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        relaxedTimeout: Boolean = false,
        legacyFormat: Boolean = false
    ): ByteArray {
        val accept = if (legacyFormat) {
            "image/jpeg,image/png,image/*;q=0.8"
        } else {
            "image/webp,image/jpeg,image/png,image/*;q=0.8"
        }
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val http = if (relaxedTimeout) imageClientRelaxed else imageClient
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("empty body")
        }
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val declared = Regex("charset\\s*=\\s*([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(contentType ?: "")?.groupValues?.get(1)?.lowercase()
        val primary = declared ?: "utf-8"
        return try {
            val text = String(bytes, Charset.forName(primary))
            // 部分镜像站声明 utf-8 实为 GBK
            if (primary.startsWith("utf") && text.count { it == '\uFFFD' } > 8) {
                String(bytes, Charset.forName("gbk"))
            } else {
                text
            }
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }
}
