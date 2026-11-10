package com.malekplay.network

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
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
    private val endpointCache = ConcurrentHashMap<String, AvTransportEndpoint>()

    data class PlaybackPosition(val positionMs: Long, val durationMs: Long)

    suspend fun play(device: DiscoveredNetworkDevice, mediaUrl: String, title: String, mimeType: String) =
        withContext(Dispatchers.IO) {
            val mediaUri = URL(mediaUrl)
            require(mediaUri.protocol.equals("http", true) || mediaUri.protocol.equals("https", true)) {
                "يجب أن يكون رابط الوسائط HTTP أو HTTPS ويمكن للتلفزيون الوصول إليه."
            }
            if (mediaUri.protocol.equals("http", true)) {
                require(isPrivateLanHost(mediaUri.host)) {
                    "روابط HTTP مسموحة فقط لخادم البث المحلي على الشبكة الخاصة."
                }
            }
            val endpoint = resolveAvTransportEndpoint(device)
            soap(endpoint, "SetAVTransportURI", """
                <InstanceID>0</InstanceID>
                <CurrentURI>${escapeDlnaXml(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData>${escapeDlnaXml(buildDlnaDidlMetadata(title, mediaUrl, mimeType))}</CurrentURIMetaData>
            """.trimIndent())
            try {
                soap(endpoint, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            } catch (failure: Exception) {
                // Best-effort rollback: retain the original Play failure even if Stop also fails.
                if (failure is CancellationException) throw failure
                runCatching {
                    soap(endpoint, "Stop", "<InstanceID>0</InstanceID>")
                }
                throw failure
            }
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

    suspend fun getPosition(device: DiscoveredNetworkDevice): PlaybackPosition? =
        withContext(Dispatchers.IO) {
            val response = soap(
                resolveAvTransportEndpoint(device),
                "GetPositionInfo",
                "<InstanceID>0</InstanceID>"
            )
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(ByteArrayInputStream(response.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
            }
            var relativeTime: String? = null
            var duration: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "RelTime" -> relativeTime = parser.nextText()
                        "TrackDuration" -> duration = parser.nextText()
                    }
                }
                parser.next()
            }
            val positionMs = parseDlnaClockTime(relativeTime) ?: return@withContext null
            val durationMs = parseDlnaClockTime(duration) ?: 0L
            PlaybackPosition(positionMs, durationMs)
        }

    private suspend fun command(device: DiscoveredNetworkDevice, action: String, arguments: String) =
        withContext(Dispatchers.IO) { soap(resolveAvTransportEndpoint(device), action, arguments) }

    private data class AvTransportEndpoint(val controlUrl: URL, val serviceType: String)

    private fun resolveAvTransportEndpoint(device: DiscoveredNetworkDevice): AvTransportEndpoint {
        require(device.kind == NetworkDeviceKind.MEDIA_RENDERER ||
            device.services.any { it.contains("MediaRenderer", true) }) {
            "الجهاز المكتشف لا يعلن خدمة DLNA MediaRenderer."
        }
        require(isPrivateLanHost(device.host)) {
            "أوامر DLNA مسموحة لأجهزة الشبكة المحلية فقط."
        }
        val description = device.descriptionUrl
            ?: throw IOException("لم يعلن التلفزيون رابط وصف UPnP؛ أعد اكتشاف الأجهزة.")
        // A renderer may keep the same discovery identity while its DHCP address
        // or description endpoint changes. Never reuse a control URL across hosts.
        val cacheKey = dlnaEndpointCacheKey(device.id, device.host, description)
        endpointCache[cacheKey]?.let { return it }
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
                        val endpoint = AvTransportEndpoint(resolved, serviceType!!)
                        endpointCache[cacheKey] = endpoint
                        return endpoint
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
                readBounded(
                    input,
                    maxBytes = 1_000_000,
                    tooLargeMessage = "ملف وصف DLNA أكبر من الحد المسموح."
                )
            }
            return String(bytes, StandardCharsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun soap(endpoint: AvTransportEndpoint, action: String, arguments: String): String {
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
                    String(
                        readBounded(
                            connection.errorStream ?: connection.inputStream,
                            maxBytes = 16_384,
                            tooLargeMessage = "رد خطأ DLNA تجاوز الحد المسموح."
                        ),
                        StandardCharsets.UTF_8
                    ).take(1200)
                }.getOrDefault("")
                throw IOException("فشل أمر DLNA $action (HTTP $status): ${errorBody.take(300)}")
            }
            return connection.inputStream?.use { input ->
                String(
                    readBounded(
                        input,
                        maxBytes = 256_000,
                        tooLargeMessage = "رد SOAP من DLNA تجاوز الحد المسموح."
                    ),
                    StandardCharsets.UTF_8
                )
            }.orEmpty()
        } finally { connection.disconnect() }
    }

}


/**
 * Reads at most [maxBytes] from an untrusted renderer response.
 * The extra read detects an oversized body without buffering it in memory.
 */
internal fun readBounded(
    input: java.io.InputStream,
    maxBytes: Int,
    tooLargeMessage: String
): ByteArray {
    require(maxBytes > 0) { "حد قراءة DLNA غير صالح." }
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8_192))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count > maxBytes - total) throw IOException(tooLargeMessage)
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}

/** Maps media MIME types to the most specific standard UPnP item class. */
internal fun dlnaUpnpClassForMime(mime: String): String {
    val normalized = mime.substringBefore(';').trim().lowercase()
    return when {
        normalized.startsWith("audio/") -> "object.item.audioItem.musicTrack"
        normalized.startsWith("image/") -> "object.item.imageItem.photo"
        normalized.startsWith("video/") -> "object.item.videoItem"
        else -> "object.item"
    }
}

/** Builds escaped DIDL-Lite metadata without assuming every shared item is video. */
internal fun buildDlnaDidlMetadata(title: String, url: String, mime: String): String {
    val safeTitle = escapeDlnaXml(title.ifBlank { "MalekPlay media" })
    val safeUrl = escapeDlnaXml(url)
    val normalizedMime = mime.substringBefore(';').trim().ifBlank { "application/octet-stream" }
    val safeMime = escapeDlnaXml(normalizedMime)
    val upnpClass = dlnaUpnpClassForMime(normalizedMime)
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$safeTitle</dc:title><upnp:class>$upnpClass</upnp:class><res protocolInfo="http-get:*:$safeMime:*">$safeUrl</res></item></DIDL-Lite>"""
}

private fun escapeDlnaXml(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

/**
 * Builds a cache key from the complete discovered endpoint identity, not only
 * the device UUID. This prevents stale UPnP control URLs after DHCP changes.
 */
internal fun dlnaEndpointCacheKey(deviceId: String, host: String, descriptionUrl: String): String =
    listOf(deviceId, host.lowercase(), descriptionUrl).joinToString("\u0000")

/**
 * Parses the UPnP HH:MM:SS[.fraction] clock format without allowing malformed
 * or oversized remote values to overflow Long arithmetic.
 */
internal fun parseDlnaClockTime(value: String?): Long? {
    if (value.isNullOrBlank() || value.trim().equals("NOT_IMPLEMENTED", ignoreCase = true)) {
        return null
    }
    val parts = value.trim().split(':')
    if (parts.size != 3) return null

    return try {
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        val seconds = java.math.BigDecimal(parts[2])
        if (hours < 0L || minutes !in 0L..59L ||
            seconds < java.math.BigDecimal.ZERO ||
            seconds >= java.math.BigDecimal.valueOf(60L)
        ) return null

        val secondMillis = seconds
            .multiply(java.math.BigDecimal.valueOf(1_000L))
            .setScale(0, java.math.RoundingMode.DOWN)
            .longValueExact()
        val hourMillis = Math.multiplyExact(hours, 3_600_000L)
        val minuteMillis = Math.multiplyExact(minutes, 60_000L)
        Math.addExact(Math.addExact(hourMillis, minuteMillis), secondMillis)
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
