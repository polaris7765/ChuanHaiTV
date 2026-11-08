package com.yujiannatian.tv.core

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

data class CastDevice(
    val name: String,
    val controlUrl: String,
    val location: String
)

/**
 * 轻量 DLNA / UPnP 投屏发送端：SSDP 发现局域网 MediaRenderer，
 * 解析设备描述得到 AVTransport 控制地址，再用 SOAP 下发 SetAVTransportURI / Play / Stop。
 * 不依赖 Google Play 服务，适合国内电视与无 GMS 环境。
 */
object CastController {

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun buildSearch(st: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n" +
            "USER-AGENT: YuJianTV/1.0 UPnP/1.0\r\n\r\n"

    fun parseLocation(response: String): String? =
        Regex(
            """^LOCATION\s*:\s*(\S+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
            .find(response)?.groupValues?.get(1)?.trim()

    private fun resolve(base: String, ref: String): String = try {
        if (ref.startsWith("http", ignoreCase = true)) ref
        else URL(URL(base), ref).toString()
    } catch (e: Exception) {
        ref
    }

    /** 从设备描述 XML 中取出 AVTransport 的 controlURL */
    fun parseControlUrl(xml: String, location: String): String? {
        val urlBase = Regex("""<URLBase>\s*([^<]+?)\s*</URLBase>""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
        val base = if (urlBase.isNullOrBlank()) location else resolve(location, urlBase)
        val services = Regex(
            """<service>([\s\S]*?)</service>""",
            RegexOption.IGNORE_CASE
        ).findAll(xml)
        for (m in services) {
            val block = m.groupValues[1]
            if (!block.contains(AV_TRANSPORT, ignoreCase = true)) continue
            val ctrl = Regex(
                """<controlURL>\s*([^<]+?)\s*</controlURL>""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.get(1)
            if (!ctrl.isNullOrBlank()) return resolve(base, ctrl)
        }
        return null
    }

    fun friendlyName(xml: String, fallback: String): String =
        Regex("""<friendlyName>\s*([^<]*?)\s*</friendlyName>""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun didl(title: String, url: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/*:DLNA.ORG_OP=01\">" +
            "${escapeXml(url)}</res></item></DIDL-Lite>"

    fun soap(action: String, inner: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\">$inner</u:$action>" +
            "</s:Body></s:Envelope>"

    fun setUriBody(url: String, title: String): String =
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escapeXml(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${escapeXml(didl(title, url))}</CurrentURIMetaData>"

    fun playBody(): String = "<InstanceID>0</InstanceID><Speed>1</Speed>"
    fun stopBody(): String = "<InstanceID>0</InstanceID>"

    /** 手动添加设备：给出 /rootDesc.xml 之类的描述地址即可 */
    suspend fun fromLocation(location: String): CastDevice? = withContext(Dispatchers.IO) {
        try {
            val xml = SiteHttpClient.get(location)
            val control = parseControlUrl(xml, location) ?: return@withContext null
            CastDevice(friendlyName(xml, location), control, location)
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun discover(context: Context, timeoutMs: Long = 4000L): List<CastDevice> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = try {
                wifi?.createMulticastLock("YuJianTVCast")?.apply { setReferenceCounted(true); acquire() }
            } catch (t: Throwable) {
                null
            }
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 600
                socket.broadcast = true
                val group = InetAddress.getByName(SSDP_HOST)
                listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "ssdp:all"
                ).forEach { st ->
                    val bytes = buildSearch(st).toByteArray(Charsets.UTF_8)
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT)) }
                }

                val locations = LinkedHashSet<String>()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parseLocation(text)?.let { locations.add(it) }
                    } catch (e: SocketTimeoutException) {
                        // 继续等待其它设备响应
                    } catch (t: Throwable) {
                        break
                    }
                }
                locations.mapNotNull { loc ->
                    runCatching { fromLocation(loc) }.getOrNull()
                }.distinctBy { it.controlUrl }
            } finally {
                runCatching { socket.close() }
                runCatching { lock?.release() }
            }
        }

    /** 成功返回 null，失败返回可读原因 */
    suspend fun cast(device: CastDevice, url: String, title: String): String? =
        withContext(Dispatchers.IO) {
            post(device, "SetAVTransportURI", setUriBody(url, title))
                ?: post(device, "Play", playBody())
        }

    suspend fun stop(device: CastDevice): String? = withContext(Dispatchers.IO) {
        post(device, "Stop", stopBody())
    }

    private fun post(device: CastDevice, action: String, inner: String): String? = try {
        val conn = URL(device.controlUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
            conn.setRequestProperty("User-Agent", SiteHttpClient.USER_AGENT)
            conn.setRequestProperty("Connection", "close")
            val body = soap(action, inner).toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                runCatching { conn.inputStream?.close() }
                null
            } else {
                val detail = runCatching {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty().take(100)
                "设备返回 HTTP $code $detail"
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    } catch (t: Throwable) {
        t.message?.take(100) ?: "无法连接投屏设备"
    }
}
