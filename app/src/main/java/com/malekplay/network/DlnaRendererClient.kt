package com.malekplay.network

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Validate renderer-pulled media URLs before embedding them in SOAP/DIDL.
 * Plain HTTP is permitted only for numeric private-LAN endpoints; HTTPS keeps
 * normal platform certificate validation. Credentials and fragments are never
 * forwarded to a renderer.
 */
internal fun validateDlnaRendererMediaUrl(raw: String): URL {
    require(raw.length in 1..4096) { "رابط وسائط DLNA غير صالح." }
    val uri = URI(raw).normalize()
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "يجب أن يكون رابط الوسائط HTTP أو HTTPS."
    }
    require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
        "رابط وسائط DLNA يحتوي على بيانات أو مكونات غير مسموحة."
    }
    require(uri.port == -1 || uri.port in 1..65535) { "منفذ وسائط DLNA غير صالح." }
    if (uri.scheme.equals("http", true)) {
        require(isPrivateLanHost(uri.host)) {
            "روابط HTTP مسموحة فقط لخادم البث المحلي على الشبكة الخاصة."
        }
    }
    return uri.toURL()
}

/** Restrict UPnP description/control endpoints to the exact discovered LAN peer. */
internal fun validateDlnaRendererEndpointUrl(raw: String, deviceHost: String): URL {
    require(raw.length in 1..4096) { "رابط جهاز DLNA غير صالح." }
    require(isPrivateLanHost(deviceHost)) { "عنوان جهاز DLNA ليس عنوان شبكة محلية." }
    val uri = URI(raw).normalize()
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "بروتوكول رابط DLNA غير مدعوم."
    }
    require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
        "رابط DLNA يحتوي على بيانات أو مكونات غير مسموحة."
    }
    require(uri.port == -1 || uri.port in 1..65535) { "منفذ DLNA غير صالح." }
    require(isPrivateLanHost(uri.host) && uri.host.equals(deviceHost, ignoreCase = true)) {
        "تم رفض رابط DLNA خارج الجهاز المكتشف."
    }
    return uri.toURL()
}

/**
 * Universal UPnP/DLNA AVTransport + RenderingControl + Roku ECP control point.
 *
 * Designed for maximum smart TV compatibility (Samsung Tizen, LG webOS, Sony Bravia,
 * Hisense, TCL, Philips, Panasonic, Android TV DLNA apps, Kodi, Xbox, PlayStation,
 * and Roku TVs) with automatic DIDL-Lite metadata fallbacks and REL_TIME/ABS_TIME seek.
 */
class DlnaRendererClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000
) {
    private val endpointCache = ConcurrentHashMap<String, RendererEndpoints>()

    data class PlaybackPosition(
        val positionMs: Long,
        val durationMs: Long,
        val transportState: String? = null
    )

    private data class ServiceEndpoint(val controlUrl: URL, val serviceType: String)
    private data class RendererEndpoints(
        val avTransport: ServiceEndpoint,
        val renderingControl: ServiceEndpoint?
    )

    suspend fun play(
        device: DiscoveredNetworkDevice,
        mediaUrl: String,
        title: String,
        mimeType: String,
        subtitleUrl: String? = null,
        sizeBytes: Long = -1L
    ) = withContext(Dispatchers.IO) {
        validateDlnaRendererMediaUrl(mediaUrl)
        subtitleUrl?.let { validateDlnaRendererMediaUrl(it) }

        val isRokuOnly = TvCastProtocol.ROKU_ECP in device.castProtocols &&
            device.descriptionUrl == null &&
            TvCastProtocol.DLNA !in device.castProtocols
        if (isRokuOnly) {
            playViaRokuEcp(device, mediaUrl, title, mimeType)
            return@withContext
        }

        val endpoint = try {
            resolveRendererEndpoints(device).avTransport
        } catch (dlnaMissing: Exception) {
            if (TvCastProtocol.ROKU_ECP in device.castProtocols) {
                playViaRokuEcp(device, mediaUrl, title, mimeType)
                return@withContext
            }
            throw dlnaMissing
        }

        val normalizedMime = mimeType.substringBefore(';').trim().ifBlank { "video/mp4" }
        val metadataCandidates = buildList {
            // 1) Rich DLNA metadata with OP=01 flags, size, and companion subtitles (Samsung/LG/Sony/Kodi).
            add(buildEnhancedDlnaDidlMetadata(title, mediaUrl, normalizedMime, subtitleUrl, sizeBytes))
            // 2) Standard minimal DIDL-Lite without DLNA flags/subtitles (strict older renderers).
            add(buildDlnaDidlMetadata(title, mediaUrl, normalizedMime))
            // 3) Generic video/mp4 fallback when a TV rejects container-specific MIME strings (e.g. x-matroska).
            if (normalizedMime.startsWith("video/", true) && !normalizedMime.equals("video/mp4", true)) {
                add(buildDlnaDidlMetadata(title, mediaUrl, "video/mp4"))
            }
            // 4) Empty metadata fallback for basic / budget smart TVs that reject DIDL-Lite XML.
            add("")
        }.distinct()

        // Some TVs (notably older Samsung/LG models) reject SetAVTransportURI while previous media is still active.
        runCatching { soap(endpoint, "Stop", "<InstanceID>0</InstanceID>") }

        var lastFailure: Exception? = null
        for (metadata in metadataCandidates) {
            try {
                soap(
                    endpoint,
                    "SetAVTransportURI",
                    """
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${escapeDlnaXml(mediaUrl)}</CurrentURI>
                    <CurrentURIMetaData>${escapeDlnaXml(metadata)}</CurrentURIMetaData>
                    """.trimIndent()
                )
                try {
                    soap(endpoint, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                    return@withContext
                } catch (playFailure: Exception) {
                    if (playFailure is CancellationException) throw playFailure
                    runCatching { soap(endpoint, "Stop", "<InstanceID>0</InstanceID>") }
                    throw playFailure
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                lastFailure = failure
            }
        }
        throw lastFailure ?: IOException("تعذر بدء التشغيل على التلفزيون.")
    }

    suspend fun pause(device: DiscoveredNetworkDevice) {
        if (isRokuPrimary(device)) {
            sendRokuKeypress(device, "Play")
            return
        }
        command(device, "Pause", "<InstanceID>0</InstanceID>")
    }

    suspend fun resume(device: DiscoveredNetworkDevice) {
        if (isRokuPrimary(device)) {
            sendRokuKeypress(device, "Play")
            return
        }
        command(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    suspend fun stop(device: DiscoveredNetworkDevice) {
        if (isRokuPrimary(device)) {
            sendRokuKeypress(device, "Home")
            return
        }
        command(device, "Stop", "<InstanceID>0</InstanceID>")
    }

    suspend fun seek(device: DiscoveredNetworkDevice, positionMs: Long) {
        require(positionMs >= 0)
        if (isRokuPrimary(device)) return
        val seconds = positionMs / 1000
        val target = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        withContext(Dispatchers.IO) {
            val endpoint = resolveRendererEndpoints(device).avTransport
            try {
                soap(endpoint, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // Some LG/Sony/Philips TVs only accept ABS_TIME instead of REL_TIME.
                soap(endpoint, "Seek", "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>$target</Target>")
            }
        }
    }

    suspend fun getVolume(device: DiscoveredNetworkDevice): Int? = withContext(Dispatchers.IO) {
        val rendering = runCatching { resolveRendererEndpoints(device).renderingControl }.getOrNull()
            ?: return@withContext null
        val response = runCatching {
            soap(rendering, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        }.getOrNull() ?: return@withContext null
        extractSoapTag(response, "CurrentVolume")?.toIntOrNull()?.coerceIn(0, 100)
    }

    suspend fun setVolume(device: DiscoveredNetworkDevice, volumePercent: Int) = withContext(Dispatchers.IO) {
        val clamped = volumePercent.coerceIn(0, 100)
        if (isRokuPrimary(device)) {
            // Roku ECP uses relative key presses; caller adjusts via stepVolume on Roku.
            return@withContext
        }
        val rendering = resolveRendererEndpoints(device).renderingControl
            ?: throw IOException("التلفزيون لا يدعم التحكم بالصوت عبر DLNA.")
        soap(
            rendering,
            "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$clamped</DesiredVolume>"
        )
        Unit
    }

    suspend fun stepVolume(device: DiscoveredNetworkDevice, up: Boolean) {
        if (isRokuPrimary(device)) {
            sendRokuKeypress(device, if (up) "VolumeUp" else "VolumeDown")
            return
        }
        val current = getVolume(device) ?: 25
        setVolume(device, (current + if (up) 5 else -5).coerceIn(0, 100))
    }

    suspend fun setMute(device: DiscoveredNetworkDevice, muted: Boolean) = withContext(Dispatchers.IO) {
        if (isRokuPrimary(device)) {
            sendRokuKeypress(device, "VolumeMute")
            return@withContext
        }
        val rendering = resolveRendererEndpoints(device).renderingControl
            ?: throw IOException("التلفزيون لا يدعم كتم الصوت عبر DLNA.")
        soap(
            rendering,
            "SetMute",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (muted) 1 else 0}</DesiredMute>"
        )
        Unit
    }

    suspend fun getPosition(device: DiscoveredNetworkDevice): PlaybackPosition? =
        withContext(Dispatchers.IO) {
            if (isRokuPrimary(device)) return@withContext null
            val endpoint = resolveRendererEndpoints(device).avTransport
            val response = soap(
                endpoint,
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
            val transportState = runCatching {
                val info = soap(endpoint, "GetTransportInfo", "<InstanceID>0</InstanceID>")
                extractSoapTag(info, "CurrentTransportState")
            }.getOrNull()
            val positionMs = parseDlnaClockTime(relativeTime) ?: return@withContext null
            val durationMs = parseDlnaClockTime(duration) ?: 0L
            PlaybackPosition(positionMs, durationMs, transportState)
        }

    private fun isRokuPrimary(device: DiscoveredNetworkDevice): Boolean =
        TvCastProtocol.ROKU_ECP in device.castProtocols &&
            device.descriptionUrl == null &&
            TvCastProtocol.DLNA !in device.castProtocols

    private suspend fun playViaRokuEcp(
        device: DiscoveredNetworkDevice,
        mediaUrl: String,
        title: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        require(isPrivateLanHost(device.host)) { "عنوان Roku خارج الشبكة المحلية." }
        val isAudio = mimeType.startsWith("audio/", true)
        val isImage = mimeType.startsWith("image/", true)
        val mediaType = when {
            isAudio -> "a"
            isImage -> "p"
            else -> "v"
        }
        val encodedUrl = URLEncoder.encode(mediaUrl, "UTF-8")
        val encodedTitle = URLEncoder.encode(title.ifBlank { "MalekPlay" }, "UTF-8")
        // PlayOnRoku / Roku Media Player ECP input endpoint (15985 = PlayOnRoku).
        val target = URL("http://${device.host}:8060/input/15985?t=$mediaType&u=$encodedUrl&videoName=$encodedTitle&videoFormat=${guessRokuFormat(mediaUrl, mimeType)}")
        val safeUrl = validateDlnaRendererEndpointUrl(target.toExternalForm(), device.host)
        postEmpty(safeUrl, "تعذر إرسال الوسائط إلى Roku TV. تأكد من تثبيت Roku Media Player أو تفعيل ECP.")
    }

    private suspend fun sendRokuKeypress(device: DiscoveredNetworkDevice, key: String) = withContext(Dispatchers.IO) {
        require(isPrivateLanHost(device.host)) { "عنوان Roku خارج الشبكة المحلية." }
        val safeUrl = validateDlnaRendererEndpointUrl("http://${device.host}:8060/keypress/$key", device.host)
        postEmpty(safeUrl, "تعذر إرسال أمر التحكم إلى Roku TV.")
    }

    private fun postEmpty(url: URL, failurePrefix: String) {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            setFixedLengthStreamingMode(0)
        }
        try {
            connection.outputStream.use { }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("$failurePrefix (HTTP $status)")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun guessRokuFormat(mediaUrl: String, mimeType: String): String = when {
        mediaUrl.endsWith(".mkv", true) || mimeType.contains("matroska", true) -> "mkv"
        mediaUrl.endsWith(".webm", true) || mimeType.contains("webm", true) -> "webm"
        mediaUrl.endsWith(".mp3", true) || mimeType.contains("mpeg", true) -> "mp3"
        else -> "mp4"
    }

    private suspend fun command(device: DiscoveredNetworkDevice, action: String, arguments: String) =
        withContext(Dispatchers.IO) { soap(resolveRendererEndpoints(device).avTransport, action, arguments) }

    private fun resolveRendererEndpoints(device: DiscoveredNetworkDevice): RendererEndpoints {
        require(
            device.kind == NetworkDeviceKind.MEDIA_RENDERER ||
                device.services.any { it.contains("MediaRenderer", true) || it.contains("AVTransport", true) }
        ) {
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
        val descriptionUrl = validateDlnaRendererEndpointUrl(description, device.host)
        val xml = readUrl(descriptionUrl)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
        }
        var serviceType: String? = null
        var controlPath: String? = null
        var inService = false
        var avTransport: ServiceEndpoint? = null
        var renderingControl: ServiceEndpoint? = null

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
                    val type = serviceType
                    val path = controlPath
                    if (!type.isNullOrBlank() && !path.isNullOrBlank()) {
                        val resolved = URL(descriptionUrl, path)
                        val safeControlUrl = runCatching {
                            validateDlnaRendererEndpointUrl(resolved.toExternalForm(), device.host)
                        }.getOrNull()
                        if (safeControlUrl != null) {
                            if (avTransport == null && type.matches(Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+", RegexOption.IGNORE_CASE))) {
                                avTransport = ServiceEndpoint(safeControlUrl, type)
                            } else if (renderingControl == null && type.matches(Regex("urn:schemas-upnp-org:service:RenderingControl:[0-9]+", RegexOption.IGNORE_CASE))) {
                                renderingControl = ServiceEndpoint(safeControlUrl, type)
                            }
                        }
                    }
                    inService = false
                }
            }
            parser.next()
        }
        val resolvedAv = avTransport
            ?: throw IOException("هذا الجهاز لا يعلن خدمة AVTransport؛ قد يكون خادم وسائط فقط.")
        val endpoints = RendererEndpoints(resolvedAv, renderingControl)
        endpointCache[cacheKey] = endpoints
        return endpoints
    }

    private fun extractSoapTag(xml: String, tagName: String): String? {
        val regex = Regex("<(?:[\\w.-]+:)?$tagName[^>]*>(.*?)</(?:[\\w.-]+:)?$tagName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
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

    private fun soap(endpoint: ServiceEndpoint, action: String, arguments: String): String {
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
            instanceFollowRedirects = false
            useCaches = false
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

/**
 * Rich DIDL-Lite metadata including DLNA OP=01 flags (enabling HTTP byte-range seeking
 * on Samsung/LG/Sony/Hisense/TCL TVs), optional byte size, and companion subtitle tags.
 */
internal fun buildEnhancedDlnaDidlMetadata(
    title: String,
    url: String,
    mime: String,
    subtitleUrl: String? = null,
    sizeBytes: Long = -1L
): String {
    val safeTitle = escapeDlnaXml(title.ifBlank { "MalekPlay media" })
    val safeUrl = escapeDlnaXml(url)
    val normalizedMime = mime.substringBefore(';').trim().ifBlank { "application/octet-stream" }
    val safeMime = escapeDlnaXml(normalizedMime)
    val upnpClass = dlnaUpnpClassForMime(normalizedMime)
    val sizeAttr = if (sizeBytes > 0L) """ size="$sizeBytes"""" else ""
    val dlnaInfo = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    val subTag = subtitleUrl?.takeIf { it.isNotBlank() }?.let { sub ->
        val safeSub = escapeDlnaXml(sub)
        """<res protocolInfo="http-get:*:text/srt:*">$safeSub</res><sec:CaptionInfoEx sec:type="srt">$safeSub</sec:CaptionInfoEx>"""
    }.orEmpty()
    val secNs = if (subtitleUrl.isNullOrBlank()) "" else """ xmlns:sec="http://www.sec.co.kr/""""
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"$secNs><item id="0" parentID="-1" restricted="1"><dc:title>$safeTitle</dc:title><upnp:class>$upnpClass</upnp:class><res$sizeAttr protocolInfo="http-get:*:$safeMime:$dlnaInfo">$safeUrl</res>$subTag</item></DIDL-Lite>"""
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
