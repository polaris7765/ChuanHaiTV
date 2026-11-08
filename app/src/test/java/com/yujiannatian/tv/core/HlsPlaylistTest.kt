package com.yujiannatian.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** m3u8 解析：相对地址、主播放列表、AES-128 密钥与 IV */
class HlsPlaylistTest {

    @Test
    fun parsesMediaPlaylistWithRelativeSegmentsAndKeys() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key.key",IV=0x00000000000000000000000000000001
            #EXTINF:10.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylist.parsePlaylist(text, "https://cdn.example.com/hls/index.m3u8")
        assertEquals(2, p.segments.size)
        assertEquals("https://cdn.example.com/hls/seg0.ts", p.segments[0].url)
        assertEquals("", p.segments[0].keyUri)
        assertEquals("https://cdn.example.com/hls/key.key", p.segments[1].keyUri)
        assertEquals("00000000000000000000000000000001", p.segments[1].ivHex)
        assertTrue(p.variants.isEmpty())
    }

    @Test
    fun generatesSequenceIvWhenNotDeclared() {
        assertEquals(
            "00000000000000000000000000000007",
            HlsPlaylist.sequenceToIv(7L)
        )
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:3
            #EXT-X-KEY:METHOD=AES-128,URI="/k.key"
            #EXTINF:5,
            a.ts
        """.trimIndent()
        val p = HlsPlaylist.parsePlaylist(text, "https://cdn.example.com/hls/i.m3u8")
        assertEquals("https://cdn.example.com/k.key", p.segments[0].keyUri)
        assertEquals("00000000000000000000000000000003", p.segments[0].ivHex)
    }

    @Test
    fun parsesMasterPlaylistVariants() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1920x1080
            high/index.m3u8
        """.trimIndent()
        val p = HlsPlaylist.parsePlaylist(text, "https://cdn.example.com/master.m3u8")
        assertEquals(2, p.variants.size)
        assertEquals("https://cdn.example.com/high/index.m3u8", p.variants[1])
        assertTrue(p.segments.isEmpty())
    }

    @Test
    fun decryptsAes128Segment() {
        val key = ByteArray(16) { it.toByte() }
        val ivHex = "00000000000000000000000000000000"
        val plain = "hello hls segment".toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ByteArray(16))
        )
        val encrypted = cipher.doFinal(plain)
        val decrypted = HlsCrypto.decrypt(encrypted, key, ivHex)
        assertEquals(String(plain), String(decrypted))
    }
}
