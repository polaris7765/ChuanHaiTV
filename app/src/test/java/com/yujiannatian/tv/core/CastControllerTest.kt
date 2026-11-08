package com.yujiannatian.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DLNA 设备描述解析与 SOAP 报文构造 */
class CastControllerTest {

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <URLBase>http://192.168.1.8:8200/</URLBase>
          <device>
            <friendlyName>客厅电视</friendlyName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/RenderingControl/ctl</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/AVTransport/ctl</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun findsAvTransportControlUrl() {
        assertEquals(
            "http://192.168.1.8:8200/AVTransport/ctl",
            CastController.parseControlUrl(description, "http://192.168.1.8:8200/rootDesc.xml")
        )
        assertEquals("客厅电视", CastController.friendlyName(description, "fallback"))
    }

    @Test
    fun parsesSsdpLocationHeader() {
        val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n" +
            "location: http://192.168.1.9:1900/desc.xml\r\nSERVER: Linux UPnP/1.0\r\n"
        assertEquals("http://192.168.1.9:1900/desc.xml", CastController.parseLocation(response))
        assertTrue(CastController.buildSearch("ssdp:all").contains("ST: ssdp:all"))
    }

    @Test
    fun buildsEscapedSoapEnvelope() {
        val url = "https://cdn.example.com/a/b.m3u8?token=1&x=2"
        val body = CastController.setUriBody(url, "似锦 & 第1集")
        assertTrue(body.contains("https://cdn.example.com/a/b.m3u8?token=1&amp;x=2"))
        assertTrue(body.contains("&lt;DIDL-Lite"))
        // DIDL 本身是 XML，作为 CurrentURIMetaData 文本会被再转义一层
        assertTrue(CastController.didl("似锦 & 第1集", url).contains("似锦 &amp; 第1集"))
        assertTrue(body.contains("似锦 &amp;amp; 第1集"))

        val soap = CastController.soap("SetAVTransportURI", body)
        assertTrue(soap.contains("<u:SetAVTransportURI"))
        assertTrue(soap.contains(CastController.AV_TRANSPORT))
        assertTrue(CastController.soap("Play", CastController.playBody()).contains("<u:Play"))
    }

    @Test
    fun escapesXmlSpecials() {
        assertEquals("&lt;&amp;&gt;&quot;&apos;", CastController.escapeXml("<&>\"'"))
    }
}
