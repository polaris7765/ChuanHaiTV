package com.yujiannatian.tv.core

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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
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
