package com.malekplay.network

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * UPnP AVTransport control point for discovered DLNA renderers.
 *
 * The renderer pulls media from a URL reachable on the same LAN; this class
 * never uploads media bytes to the television.
 */
class DlnaRendererClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000
) {
    suspend fun play(device: DiscoveredNetworkDevice, mediaUrl: String, title: String, mimeType: String) =
        withContext(Dispatchers.IO) {
            require(mediaUrl.startsWith("http://", true) || mediaUrl.startsWith("https://", true)) {
                "يجب أن يكون رابط الوسائط HTTP أو HTTPS ويمكن للتلفزيون الوصول إليه."
            }
            val endpoint = resolveAvTransportEndpoint(device)
            soap(endpoint, "SetAVTransportURI", """
                <InstanceID>0</InstanceID>
                <CurrentURI>${xmlEscape(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData>${xmlEscape(didlMetadata(title, mediaUrl, mimeType))}</CurrentURIMetaData>
            """.trimIndent())
            soap(endpoint, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        }

    suspend fun pause(device: DiscoveredNetworkDevice) = command(device, "Pause", "<InstanceID>0</InstanceID>")
    suspend fun resume(device: DiscoveredNetworkDevice) = command(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    suspend fun stop(device: DiscoveredNetworkDevice) = command(device, "Stop", "<InstanceID>0</InstanceID>")
    suspend fun seek(device: DiscoveredNetworkDevice, positionMs: Long) {
        require(positionMs >= 0)
        val seconds = positionMs / 1000
        val target = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        command(device, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")
    }

    private suspend fun command(device: DiscoveredNetworkDevice, action: String, arguments: String) =
        withContext(Dispatchers.IO) { soap(resolveAvTransportEndpoint(device), action, arguments) }

    private data class AvTransportEndpoint(val controlUrl: URL, val serviceType: String)

    private fun resolveAvTransportEndpoint(device: DiscoveredNetworkDevice): AvTransportEndpoint {
        require(device.kind == NetworkDeviceKind.MEDIA_RENDERER ||
            device.services.any { it.contains("MediaRenderer", true) }) {
            "الجهاز المكتشف لا يعلن خدمة DLNA MediaRenderer."
        }
        val description = device.descriptionUrl
            ?: throw IOException("لم يعلن التلفزيون رابط وصف UPnP؛ أعد اكتشاف الأجهزة.")
        val descriptionUrl = URL(description)
        require(descriptionUrl.protocol == "http" || descriptionUrl.protocol == "https")
        require(descriptionUrl.host.equals(device.host, ignoreCase = true)) {
            "تم رفض رابط وصف لا يطابق عنوان الجهاز المكتشف."
        }
        val xml = readUrl(descriptionUrl)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
        }
        var serviceType: String? = null
        var controlPath: String? = null
        var inService = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "service" -> { inService = true; serviceType = null; controlPath = null }
                        "serviceType" -> if (inService) serviceType = parser.nextText().trim()
                        "controlURL" -> if (inService) controlPath = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "service" && inService) {
                    if (serviceType?.matches(Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+", RegexOption.IGNORE_CASE)) == true && !controlPath.isNullOrBlank()) {
                        val resolved = URL(descriptionUrl, controlPath)
                        require(resolved.protocol == "http" || resolved.protocol == "https") {
                            "بروتوكول رابط تحكم DLNA غير مدعوم."
                        }
                        require(resolved.host.equals(device.host, ignoreCase = true)) {
                            "تم رفض عنوان تحكم DLNA خارج الجهاز المكتشف."
                        }
                        return AvTransportEndpoint(resolved, serviceType!!)
                    }
                    inService = false
                }
            }
            parser.next()
        }
        throw IOException("هذا الجهاز لا يعلن خدمة AVTransport؛ قد يكون خادم وسائط فقط.")
    }

    private fun readUrl(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("تعذر قراءة وصف DLNA: HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 1_000_000) throw IOException("ملف وصف DLNA أكبر من الحد المسموح.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return String(bytes, StandardCharsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun soap(endpoint: AvTransportEndpoint, action: String, arguments: String) {
        val url = endpoint.controlUrl
        val service = endpoint.serviceType
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body><u:$action xmlns:u="$service">$arguments</u:$action></s:Body>
            </s:Envelope>""".trimIndent()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", "\"$service#$action\"")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = runCatching {
                    (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText().take(1200) }
                }.getOrDefault("")
                throw IOException("فشل أمر DLNA $action (HTTP $status): ${errorBody.take(300)}")
            }
            connection.inputStream?.close()
        } finally { connection.disconnect() }
    }

    private fun didlMetadata(title: String, url: String, mime: String): String {
        val safeTitle = xmlEscape(title.ifBlank { "MalekPlay media" })
        val safeUrl = xmlEscape(url)
        val safeMime = xmlEscape(mime.ifBlank { "application/octet-stream" })
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$safeTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$safeMime:*">$safeUrl</res></item></DIDL-Lite>"""
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
