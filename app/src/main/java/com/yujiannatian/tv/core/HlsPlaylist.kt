package com.yujiannatian.tv.core

import java.net.URL

/** 解析后的单个 HLS 分片；ivHex 为 AES-128 解密所需的初始化向量 */
data class HlsSegment(val url: String, val keyUri: String = "", val ivHex: String = "")

/**
 * m3u8 播放列表解析：支持主播放列表（多码率）、相对地址、AES-128 密钥轮换。
 * 纯 Kotlin 实现，便于 JVM 单测覆盖。
 */
object HlsPlaylist {

    fun resolve(base: String, ref: String): String = try {
        URL(URL(base), ref).toString()
    } catch (e: Exception) {
        ref
    }

    fun parse(text: String, sourceUrl: String): List<HlsSegment> {
        return parsePlaylist(text, sourceUrl, 0).segments
    }

    data class Parsed(val segments: List<HlsSegment>, val variants: List<String>)

    fun parsePlaylist(text: String, sourceUrl: String, depth: Int = 0): Parsed {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val segments = mutableListOf<HlsSegment>()
        val variants = mutableListOf<String>()

        var currentKeyUri = ""
        var explicitIv = ""
        var sequence = 0L
        var streamInfSeen = false

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    sequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> streamInfSeen = true
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = line.substringAfter(":")
                    val method = attr(attrs, "METHOD")
                    if (method.equals("NONE", ignoreCase = true)) {
                        currentKeyUri = ""
                        explicitIv = ""
                    } else {
                        currentKeyUri = attr(attrs, "URI")?.let { resolve(sourceUrl, it) } ?: ""
                        explicitIv = attr(attrs, "IV")?.removePrefix("0x")?.removePrefix("0X") ?: ""
                    }
                }
                line.startsWith("#") -> Unit
                streamInfSeen -> {
                    variants += resolve(sourceUrl, line)
                    streamInfSeen = false
                }
                else -> {
                    val iv = explicitIv.ifBlank { sequenceToIv(sequence) }
                    segments += HlsSegment(resolve(sourceUrl, line), currentKeyUri, iv)
                    sequence++
                }
            }
        }
        return Parsed(segments, variants)
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""$name\s*=\s*("([^"]*)"|[^,]*)""", RegexOption.IGNORE_CASE)
            .find(attrs)?.let { it.groupValues[2].ifEmpty { it.groupValues[1].trim() } }

    /** HLS 规范：未声明 IV 时，用媒体序列号作为 128 位大端 IV */
    fun sequenceToIv(seq: Long): String = buildString {
        append("%016x".format(0L))
        append("%016x".format(seq))
    }
}

/** AES-128 分片解密（纯 Java/Kotlin，便于 JVM 单测） */
object HlsCrypto {
    fun decrypt(data: ByteArray, key: ByteArray, ivHex: String): ByteArray {
        require(key.size >= 16) { "AES 密钥长度不足" }
        val hex = ivHex.padStart(32, '0').takeLast(32)
        val iv = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.copyOf(16), "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )
        return cipher.doFinal(data)
    }
}
