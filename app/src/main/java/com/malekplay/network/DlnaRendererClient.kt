package com.malekplay.network

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
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
internal fun validateDlnaRendererMediaUrl(raw: String, allowLoopback: Boolean = false): URL {
    require(raw.length in 1..4096) { "رابط وسائط DLNA غير صالح." }
    val uri = URI(raw).normalize()
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "يجب أن يكون رابط الوسائط HTTP أو HTTPS."
    }
    require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
        "رابط وسائط DLNA يحتوي على بيانات أو مكونات غير مسموحة."
    }
    require(uri.port == -1 || uri.port in 1..65535) { "منفذ وسائط DLNA غير صالح." }
    val cleanHost = LanNetworkSupport.stripZone(uri.host)
    if (uri.scheme.equals("http", true)) {
        require(isPrivateLanHost(cleanHost) || (allowLoopback && cleanHost == "127.0.0.1")) {
            "روابط HTTP مسموحة فقط لخادم البث المحلي على الشبكة الخاصة."
        }
    }
    return uri.toURL()
}

/** Restrict UPnP description/control endpoints to the exact discovered LAN peer. */
internal fun validateDlnaRendererEndpointUrl(
    raw: String,
    deviceHost: String,
    allowLoopback: Boolean = false
): URL {
    require(raw.length in 1..4096) { "رابط جهاز DLNA غير صالح." }
    val cleanDeviceHost = LanNetworkSupport.stripZone(deviceHost)
    require(isPrivateLanHost(cleanDeviceHost) || (allowLoopback && cleanDeviceHost == "127.0.0.1")) {
        "عنوان جهاز DLNA ليس عنوان شبكة محلية."
    }
    val uri = URI(raw).normalize()
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "بروتوكول رابط DLNA غير مدعوم."
    }
    require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
        "رابط DLNA يحتوي على بيانات أو مكونات غير مسموحة."
    }
    require(uri.port == -1 || uri.port in 1..65535) { "منفذ DLNA غير صالح." }
    val cleanUriHost = LanNetworkSupport.stripZone(uri.host)
    require(
        (isPrivateLanHost(cleanUriHost) || (allowLoopback && cleanUriHost == "127.0.0.1")) &&
            cleanUriHost.equals(cleanDeviceHost, ignoreCase = true)
    ) {
        "تم رفض رابط DLNA خارج الجهاز المكتشف."
    }
    return uri.toURL()
}

/**
 * Universal UPnP/DLNA AVTransport + RenderingControl + Roku ECP control point.
 *
 * Designed for maximum smart TV compatibility (Samsung Tizen, LG webOS, Sony Bravia,
 * Hisense, TCL, Philips, Panasonic, Android TV DLNA apps, Kodi, Xbox, PlayStation,
 * and Roku TVs) with automatic DIDL-Lite metadata fallbacks, safe non-DOCDECL XML parsing,
 * explicit LAN Network binding, and non-toggle Roku state verification.
 */
class DlnaRendererClient internal constructor(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000,
    context: Context? = null,
    private val rokuPort: Int = 8060,
    private val allowLoopbackForTest: Boolean = false
) {
    constructor(
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 8_000,
        context: Context? = null
    ) : this(connectTimeoutMs, readTimeoutMs, context, 8060, false)
    private val appContext: Context? = context?.applicationContext
    private val endpointCache = ConcurrentHashMap<String, RendererEndpoints>()
    private val capabilityCache = ConcurrentHashMap<String, RendererCapabilities>()

    enum class CapabilitySupport {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN
    }

    data class PlaybackPosition(
        val positionMs: Long,
        val durationMs: Long,
        val transportState: String? = null
    )

    data class RendererCapabilities(
        val seek: CapabilitySupport = CapabilitySupport.UNKNOWN,
        val positionReporting: CapabilitySupport = CapabilitySupport.UNKNOWN,
        val absoluteVolume: CapabilitySupport = CapabilitySupport.UNKNOWN,
        val explicitMute: CapabilitySupport = CapabilitySupport.UNKNOWN,
        val protocolInfoVerified: Boolean = false
    ) {
        val supportsSeek: Boolean get() = seek == CapabilitySupport.SUPPORTED
        val supportsAbsoluteVolume: Boolean get() = absoluteVolume == CapabilitySupport.SUPPORTED
        val supportsExplicitMute: Boolean get() = explicitMute == CapabilitySupport.SUPPORTED
        val supportsPositionReporting: Boolean get() = positionReporting == CapabilitySupport.SUPPORTED
    }

    private data class RokuMediaPlayerState(
        val state: String,
        val positionMs: Long,
        val durationMs: Long
    )

    private class SoapFaultException(
        val errorCode: Int?,
        val errorDescription: String,
        message: String
    ) : IOException(message)

    private data class ServiceEndpoint(
        val controlUrl: URL,
        val serviceType: String,
        val scpdUrl: URL? = null
    )

    private data class RendererEndpoints(
        val avTransport: ServiceEndpoint,
        val renderingControl: ServiceEndpoint?,
        val connectionManager: ServiceEndpoint?
    )

    fun capabilitiesFor(device: DiscoveredNetworkDevice): RendererCapabilities {
        if (isRokuPrimary(device)) {
            return RendererCapabilities(
                seek = CapabilitySupport.UNSUPPORTED,
                positionReporting = if (device.supportsRokuControlOnly()) {
                    CapabilitySupport.UNSUPPORTED
                } else {
                    CapabilitySupport.SUPPORTED
                },
                absoluteVolume = CapabilitySupport.UNSUPPORTED,
                explicitMute = CapabilitySupport.UNSUPPORTED,
                protocolInfoVerified = false
            )
        }
        val desc = device.descriptionUrl ?: return RendererCapabilities()
        val key = dlnaEndpointCacheKey(device.id, device.host, desc)
        capabilityCache[key]?.let { return it }
        val cached = endpointCache[key] ?: return RendererCapabilities()
        return RendererCapabilities(
            seek = CapabilitySupport.UNKNOWN,
            positionReporting = CapabilitySupport.UNKNOWN,
            absoluteVolume = if (cached.renderingControl == null) CapabilitySupport.UNSUPPORTED else CapabilitySupport.UNKNOWN,
            explicitMute = if (cached.renderingControl == null) CapabilitySupport.UNSUPPORTED else CapabilitySupport.UNKNOWN,
            protocolInfoVerified = false
        )
    }

    /**
     * Queries UPnP device description, service SCPD XMLs, and ConnectionManager#GetProtocolInfo
     * to determine actual supported capabilities without guessing.
     */
    suspend fun inspectCapabilities(device: DiscoveredNetworkDevice): RendererCapabilities =
        withContext(Dispatchers.IO) {
            if (isRokuPrimary(device)) {
                return@withContext capabilitiesFor(device)
            }
            val desc = device.descriptionUrl ?: return@withContext RendererCapabilities()
            val key = dlnaEndpointCacheKey(device.id, device.host, desc)
            val endpoints = runCatching { resolveRendererEndpoints(device) }.getOrNull()
                ?: return@withContext capabilitiesFor(device)
            val cm = endpoints.connectionManager
            if (cm != null && capabilityCache[key]?.protocolInfoVerified != true) {
                val ok = runCatching {
                    soap(cm, "GetProtocolInfo", "")
                }.isSuccess
                if (ok) {
                    updateCapabilities(key) { it.copy(protocolInfoVerified = true) }
                }
            }
            capabilityCache[key] ?: capabilitiesFor(device)
        }

    private fun updateCapabilities(
        cacheKey: String?,
        transform: (RendererCapabilities) -> RendererCapabilities
    ) {
        if (cacheKey == null) return
        capabilityCache.compute(cacheKey) { _, existing ->
            transform(existing ?: RendererCapabilities())
        }
    }

    private fun isUnsupportedFault(error: Throwable): Boolean =
        error is SoapFaultException && error.errorCode in setOf(401, 602, 710)

    suspend fun play(
        device: DiscoveredNetworkDevice,
        mediaUrl: String,
        title: String,
        mimeType: String,
        subtitleUrl: String? = null,
        sizeBytes: Long = -1L
    ) = withContext(Dispatchers.IO) {
        validateDlnaRendererMediaUrl(mediaUrl, allowLoopbackForTest)
        subtitleUrl?.let { validateDlnaRendererMediaUrl(it, allowLoopbackForTest) }

        val isRoku = TvCastProtocol.ROKU_ECP in device.castProtocols
        val hasDlnaDescription = device.descriptionUrl != null &&
            (TvCastProtocol.DLNA in device.castProtocols ||
                device.services.any { it.contains("MediaRenderer", true) || it.contains("AVTransport", true) })

        if (isRoku && !hasDlnaDescription) {
            playViaRokuEcp(device, mediaUrl, title, mimeType)
            return@withContext
        }
        if (!hasDlnaDescription && !isRoku) {
            throw UnsupportedOperationException(
                "هذا الجهاز لا يدعم البث المباشر عبر DLNA أو Roku؛ استخدم البث للمتصفح (Web Cast) أو Google Cast."
            )
        }

        val endpoint = try {
            resolveRendererEndpoints(device).avTransport
        } catch (dlnaMissing: Exception) {
            if (isRoku) {
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

        var didStopBeforeRetry = false
        var lastFailure: Exception? = null
        for (metadata in metadataCandidates) {
            try {
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
                } catch (firstSetFailure: Exception) {
                    if (firstSetFailure is CancellationException) throw firstSetFailure
                    if (!didStopBeforeRetry) {
                        didStopBeforeRetry = true
                        runCatching { soap(endpoint, "Stop", "<InstanceID>0</InstanceID>") }
                        runCatching { Thread.sleep(150L) }
                        soap(
                            endpoint,
                            "SetAVTransportURI",
                            """
                            <InstanceID>0</InstanceID>
                            <CurrentURI>${escapeDlnaXml(mediaUrl)}</CurrentURI>
                            <CurrentURIMetaData>${escapeDlnaXml(metadata)}</CurrentURIMetaData>
                            """.trimIndent()
                        )
                    } else {
                        throw firstSetFailure
                    }
                }
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
            val current = queryRokuMediaPlayer(device)
                ?: throw IOException("تعذر التحقق من حالة مشغل Roku قبل الإيقاف المؤقت.")
            val state = current.state.lowercase()
            if (state == "pause" || state == "stop" || state == "close") return
            if (state == "play" || state == "buffer" || state == "startup") {
                sendRokuKeypress(device, "Play")
            }
            return
        }
        command(device, "Pause", "<InstanceID>0</InstanceID>")
    }

    suspend fun resume(device: DiscoveredNetworkDevice) {
        if (isRokuPrimary(device)) {
            val current = queryRokuMediaPlayer(device)
                ?: throw IOException("تعذر التحقق من حالة مشغل Roku قبل الاستئناف.")
            val state = current.state.lowercase()
            if (state == "play" || state == "buffer" || state == "startup") return
            if (state == "close" || state == "stop") {
                throw IOException("انتهت جلسة التشغيل على Roku؛ أعد إرسال الفيديو.")
            }
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
        if (isRokuPrimary(device)) {
            throw UnsupportedOperationException("جهاز Roku لا يدعم الانتقال المباشر إلى موضع زمني عبر الشبكة.")
        }
        val seconds = positionMs / 1000
        val target = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        withContext(Dispatchers.IO) {
            val endpoint = resolveRendererEndpoints(device).avTransport
            val cacheKey = device.descriptionUrl?.let { dlnaEndpointCacheKey(device.id, device.host, it) }
            if (capabilitiesFor(device).seek == CapabilitySupport.UNSUPPORTED) {
                throw UnsupportedOperationException("هذا التلفزيون لا يدعم الانتقال الزمني (Seek) عبر DLNA.")
            }
            try {
                soap(endpoint, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")
                updateCapabilities(cacheKey) { it.copy(seek = CapabilitySupport.SUPPORTED) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (isUnsupportedFault(failure)) {
                    // Try ABS_TIME fallback before declaring Seek unsupported; some TVs reject REL_TIME with 710.
                    try {
                        soap(endpoint, "Seek", "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>$target</Target>")
                        updateCapabilities(cacheKey) { it.copy(seek = CapabilitySupport.SUPPORTED) }
                        return@withContext
                    } catch (absFailure: Exception) {
                        if (absFailure is CancellationException) throw absFailure
                        if (isUnsupportedFault(absFailure)) {
                            updateCapabilities(cacheKey) { it.copy(seek = CapabilitySupport.UNSUPPORTED) }
                            throw UnsupportedOperationException("هذا التلفزيون لا يدعم الانتقال الزمني (Seek) عبر DLNA.")
                        }
                        throw absFailure
                    }
                }
                // Some LG/Sony/Philips TVs only accept ABS_TIME instead of REL_TIME.
                try {
                    soap(endpoint, "Seek", "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>$target</Target>")
                    updateCapabilities(cacheKey) { it.copy(seek = CapabilitySupport.SUPPORTED) }
                } catch (absFailure: Exception) {
                    if (absFailure is CancellationException) throw absFailure
                    if (isUnsupportedFault(absFailure)) {
                        updateCapabilities(cacheKey) { it.copy(seek = CapabilitySupport.UNSUPPORTED) }
                        throw UnsupportedOperationException("هذا التلفزيون لا يدعم الانتقال الزمني (Seek) عبر DLNA.")
                    }
                    throw absFailure
                }
            }
        }
    }

    suspend fun getVolume(device: DiscoveredNetworkDevice): Int? = withContext(Dispatchers.IO) {
        if (isRokuPrimary(device)) return@withContext null
        val rendering = runCatching { resolveRendererEndpoints(device).renderingControl }.getOrNull()
            ?: return@withContext null
        val cacheKey = device.descriptionUrl?.let { dlnaEndpointCacheKey(device.id, device.host, it) }
        if (capabilitiesFor(device).absoluteVolume == CapabilitySupport.UNSUPPORTED) {
            return@withContext null
        }
        val response = try {
            soap(rendering, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            if (isUnsupportedFault(err)) {
                updateCapabilities(cacheKey) { it.copy(absoluteVolume = CapabilitySupport.UNSUPPORTED) }
            }
            return@withContext null
        }
        val parsed = extractSoapTag(response, "CurrentVolume")?.toIntOrNull()?.coerceIn(0, 100)
        if (parsed != null) {
            updateCapabilities(cacheKey) { it.copy(absoluteVolume = CapabilitySupport.SUPPORTED) }
        }
        parsed
    }

    suspend fun setVolume(device: DiscoveredNetworkDevice, volumePercent: Int) = withContext(Dispatchers.IO) {
        val clamped = volumePercent.coerceIn(0, 100)
        if (isRokuPrimary(device)) {
            throw UnsupportedOperationException("جهاز Roku يدعم رفع أو خفض الصوت تدريجياً ولا يدعم ضبط نسبة محددة.")
        }
        val rendering = resolveRendererEndpoints(device).renderingControl
            ?: throw UnsupportedOperationException("التلفزيون لا يدعم التحكم بالصوت عبر DLNA.")
        val cacheKey = device.descriptionUrl?.let { dlnaEndpointCacheKey(device.id, device.host, it) }
        if (capabilitiesFor(device).absoluteVolume == CapabilitySupport.UNSUPPORTED) {
            throw UnsupportedOperationException("التلفزيون لا يدعم ضبط مستوى الصوت المطلق عبر DLNA.")
        }
        try {
            soap(
                rendering,
                "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$clamped</DesiredVolume>"
            )
            updateCapabilities(cacheKey) { it.copy(absoluteVolume = CapabilitySupport.SUPPORTED) }
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            if (isUnsupportedFault(err)) {
                updateCapabilities(cacheKey) { it.copy(absoluteVolume = CapabilitySupport.UNSUPPORTED) }
                throw UnsupportedOperationException("التلفزيون لا يدعم ضبط مستوى الصوت المطلق عبر DLNA.")
            }
            throw err
        }
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
            throw UnsupportedOperationException(
                "جهاز Roku لا يدعم كتم الصوت الصريح (تفعيل/إلغاء) عبر الشبكة؛ يدعم التبديل من ريموت التلفزيون فقط."
            )
        }
        val rendering = resolveRendererEndpoints(device).renderingControl
            ?: throw UnsupportedOperationException("التلفزيون لا يدعم كتم الصوت عبر DLNA.")
        val cacheKey = device.descriptionUrl?.let { dlnaEndpointCacheKey(device.id, device.host, it) }
        if (capabilitiesFor(device).explicitMute == CapabilitySupport.UNSUPPORTED) {
            throw UnsupportedOperationException("التلفزيون لا يدعم كتم الصوت الصريح عبر DLNA.")
        }
        try {
            soap(
                rendering,
                "SetMute",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (muted) 1 else 0}</DesiredMute>"
            )
            updateCapabilities(cacheKey) { it.copy(explicitMute = CapabilitySupport.SUPPORTED) }
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            if (isUnsupportedFault(err)) {
                updateCapabilities(cacheKey) { it.copy(explicitMute = CapabilitySupport.UNSUPPORTED) }
                throw UnsupportedOperationException("التلفزيون لا يدعم كتم الصوت الصريح عبر DLNA.")
            }
            throw err
        }
        Unit
    }

    suspend fun getMute(device: DiscoveredNetworkDevice): Boolean? = withContext(Dispatchers.IO) {
        if (isRokuPrimary(device)) return@withContext null
        val rendering = runCatching { resolveRendererEndpoints(device).renderingControl }.getOrNull()
            ?: return@withContext null
        val cacheKey = device.descriptionUrl?.let { dlnaEndpointCacheKey(device.id, device.host, it) }
        if (capabilitiesFor(device).explicitMute == CapabilitySupport.UNSUPPORTED) {
            return@withContext null
        }
        val response = try {
            soap(rendering, "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            if (isUnsupportedFault(err)) {
                updateCapabilities(cacheKey) { it.copy(explicitMute = CapabilitySupport.UNSUPPORTED) }
            }
            return@withContext null
        }
        val parsed = when (extractSoapTag(response, "CurrentMute")?.trim()?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        if (parsed != null) {
            updateCapabilities(cacheKey) { it.copy(explicitMute = CapabilitySupport.SUPPORTED) }
        }
        parsed
    }

    suspend fun getPosition(device: DiscoveredNetworkDevice): PlaybackPosition? =
        withContext(Dispatchers.IO) {
            if (isRokuPrimary(device)) {
                val roku = queryRokuMediaPlayer(device) ?: return@withContext null
                val mappedState = when (roku.state.lowercase()) {
                    "play", "buffer", "startup" -> "PLAYING"
                    "pause" -> "PAUSED_PLAYBACK"
                    "stop", "close" -> "STOPPED"
                    else -> roku.state.uppercase()
                }
                return@withContext PlaybackPosition(roku.positionMs, roku.durationMs, mappedState)
            }
            val endpoint = resolveRendererEndpoints(device).avTransport
            val cacheKey = device.descriptionUrl?.let { dlnaEndpointCacheKey(device.id, device.host, it) }
            if (capabilitiesFor(device).positionReporting == CapabilitySupport.UNSUPPORTED) {
                return@withContext null
            }
            val response = try {
                soap(
                    endpoint,
                    "GetPositionInfo",
                    "<InstanceID>0</InstanceID>"
                )
            } catch (err: Exception) {
                if (err is CancellationException) throw err
                if (isUnsupportedFault(err)) {
                    updateCapabilities(cacheKey) { it.copy(positionReporting = CapabilitySupport.UNSUPPORTED) }
                    return@withContext null
                }
                throw err
            }
            val parser = newSafePullParser(response)
            var relativeTime: String? = null
            var duration: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
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
            val positionMs = parseDlnaClockTime(relativeTime)
            if (positionMs == null) {
                updateCapabilities(cacheKey) { it.copy(positionReporting = CapabilitySupport.UNSUPPORTED) }
                return@withContext null
            }
            updateCapabilities(cacheKey) { it.copy(positionReporting = CapabilitySupport.SUPPORTED) }
            val durationMs = parseDlnaClockTime(duration) ?: 0L
            PlaybackPosition(positionMs, durationMs, transportState)
        }

    private fun isRokuPrimary(device: DiscoveredNetworkDevice): Boolean =
        TvCastProtocol.ROKU_ECP in device.castProtocols &&
            device.descriptionUrl == null &&
            TvCastProtocol.DLNA !in device.castProtocols

    private fun rokuUrlHost(device: DiscoveredNetworkDevice): String =
        LanNetworkSupport.formatUriAuthorityHost(device.host)

    private fun isAllowedTargetHost(host: String): Boolean {
        val clean = LanNetworkSupport.stripZone(host)
        return isPrivateLanHost(clean) || (allowLoopbackForTest && clean == "127.0.0.1")
    }

    private suspend fun playViaRokuEcp(
        device: DiscoveredNetworkDevice,
        mediaUrl: String,
        title: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        require(isAllowedTargetHost(device.host)) { "عنوان Roku خارج الشبكة المحلية." }
        if (device.supportsRokuControlOnly()) {
            throw UnsupportedOperationException(
                "جهاز Roku يدعم التحكم عن بُعد فقط ولا يتوفر فيه تطبيق Roku Media Player (PlayOnRoku) لاستقبال البث المباشر."
            )
        }
        val isAudio = mimeType.startsWith("audio/", true)
        val isImage = mimeType.startsWith("image/", true)
        val mediaType = when {
            isAudio -> "a"
            isImage -> "p"
            else -> "v"
        }
        val encodedUrl = URLEncoder.encode(mediaUrl, "UTF-8")
        val encodedTitle = URLEncoder.encode(title.ifBlank { "MalekPlay" }, "UTF-8")
        val urlHost = rokuUrlHost(device)
        // PlayOnRoku / Roku Media Player ECP input endpoint (15985 = PlayOnRoku).
        val target = URL("http://$urlHost:$rokuPort/input/15985?t=$mediaType&u=$encodedUrl&videoName=$encodedTitle&videoFormat=${guessRokuFormat(mediaUrl, mimeType)}")
        val safeUrl = validateDlnaRendererEndpointUrl(target.toExternalForm(), device.host, allowLoopbackForTest)
        postEmpty(safeUrl, "تعذر إرسال الوسائط إلى Roku TV. تأكد من تثبيت Roku Media Player أو تفعيل ECP.")
    }

    private suspend fun sendRokuKeypress(device: DiscoveredNetworkDevice, key: String) = withContext(Dispatchers.IO) {
        require(isAllowedTargetHost(device.host)) { "عنوان Roku خارج الشبكة المحلية." }
        val urlHost = rokuUrlHost(device)
        val safeUrl = validateDlnaRendererEndpointUrl("http://$urlHost:$rokuPort/keypress/$key", device.host, allowLoopbackForTest)
        postEmpty(safeUrl, "تعذر إرسال أمر التحكم إلى Roku TV.")
    }

    private fun queryRokuMediaPlayer(device: DiscoveredNetworkDevice): RokuMediaPlayerState? {
        if (!isAllowedTargetHost(device.host)) return null
        val urlHost = rokuUrlHost(device)
        val safeUrl = runCatching {
            validateDlnaRendererEndpointUrl("http://$urlHost:$rokuPort/query/media-player", device.host, allowLoopbackForTest)
        }.getOrNull() ?: return null
        val xml = runCatching { readUrl(safeUrl) }.getOrNull() ?: return null
        val parser = runCatching { newSafePullParser(xml) }.getOrNull() ?: return null
        var state: String? = null
        var positionText: String? = null
        var durationText: String? = null
        runCatching {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "player" -> state = parser.getAttributeValue(null, "state")
                        "position" -> positionText = parser.nextText()
                        "duration" -> durationText = parser.nextText()
                    }
                }
                parser.next()
            }
        }
        val resolvedState = state?.takeIf { it.isNotBlank() } ?: return null
        return RokuMediaPlayerState(
            state = resolvedState,
            positionMs = parseRokuTimeMs(positionText),
            durationMs = parseRokuTimeMs(durationText)
        )
    }

    private fun parseRokuTimeMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val digits = raw.trim().substringBefore(' ').trim()
        return digits.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    private fun openLanConnection(url: URL): java.net.HttpURLConnection {
        val network = LanNetworkSupport.findLanNetworkForHost(appContext, url.host)
        return LanNetworkSupport.openLanHttpConnection(
            url = url,
            network = network,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
    }

    private fun postEmpty(url: URL, failurePrefix: String) {
        val connection = openLanConnection(url).apply {
            requestMethod = "POST"
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

    private fun newSafePullParser(xml: String): XmlPullParser {
        require(xml.length <= MAX_XML_BYTES) { "ملف XML أكبر من الحد المسموح." }
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(StringReader(xml))
        }
    }

    private fun resolveRendererEndpoints(device: DiscoveredNetworkDevice): RendererEndpoints {
        require(
            device.kind == NetworkDeviceKind.MEDIA_RENDERER ||
                device.services.any { it.contains("MediaRenderer", true) || it.contains("AVTransport", true) }
        ) {
            "الجهاز المكتشف لا يعلن خدمة DLNA MediaRenderer."
        }
        require(isAllowedTargetHost(device.host)) {
            "أوامر DLNA مسموحة لأجهزة الشبكة المحلية فقط."
        }
        val description = device.descriptionUrl
            ?: throw IOException("لم يعلن التلفزيون رابط وصف UPnP؛ أعد اكتشاف الأجهزة.")
        // A renderer may keep the same discovery identity while its DHCP address
        // or description endpoint changes. Never reuse a control URL across hosts.
        val cacheKey = dlnaEndpointCacheKey(device.id, device.host, description)
        endpointCache[cacheKey]?.let { return it }
        val descriptionUrl = validateDlnaRendererEndpointUrl(description, device.host, allowLoopbackForTest)
        val xml = readUrl(descriptionUrl)
        val parser = newSafePullParser(xml)
        var serviceType: String? = null
        var controlPath: String? = null
        var scpdPath: String? = null
        var inService = false
        var avTransport: ServiceEndpoint? = null
        var renderingControl: ServiceEndpoint? = null
        var connectionManager: ServiceEndpoint? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "service" -> {
                            inService = true
                            serviceType = null
                            controlPath = null
                            scpdPath = null
                        }
                        "serviceType" -> if (inService) serviceType = parser.nextText().trim()
                        "controlURL" -> if (inService) controlPath = parser.nextText().trim()
                        "SCPDURL" -> if (inService) scpdPath = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "service" && inService) {
                    val type = serviceType
                    val path = controlPath
                    if (!type.isNullOrBlank() && !path.isNullOrBlank()) {
                        val resolved = URL(descriptionUrl, path)
                        val safeControlUrl = runCatching {
                            validateDlnaRendererEndpointUrl(resolved.toExternalForm(), device.host, allowLoopbackForTest)
                        }.getOrNull()
                        val safeScpdUrl = scpdPath?.takeIf { it.isNotBlank() }?.let { rawScpd ->
                            runCatching {
                                validateDlnaRendererEndpointUrl(
                                    URL(descriptionUrl, rawScpd).toExternalForm(),
                                    device.host,
                                    allowLoopbackForTest
                                )
                            }.getOrNull()
                        }
                        if (safeControlUrl != null) {
                            when {
                                avTransport == null && type.matches(Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+", RegexOption.IGNORE_CASE)) ->
                                    avTransport = ServiceEndpoint(safeControlUrl, type, safeScpdUrl)
                                renderingControl == null && type.matches(Regex("urn:schemas-upnp-org:service:RenderingControl:[0-9]+", RegexOption.IGNORE_CASE)) ->
                                    renderingControl = ServiceEndpoint(safeControlUrl, type, safeScpdUrl)
                                connectionManager == null && type.matches(Regex("urn:schemas-upnp-org:service:ConnectionManager:[0-9]+", RegexOption.IGNORE_CASE)) ->
                                    connectionManager = ServiceEndpoint(safeControlUrl, type, safeScpdUrl)
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
        val endpoints = RendererEndpoints(resolvedAv, renderingControl, connectionManager)
        endpointCache[cacheKey] = endpoints

        val avActions = resolvedAv.scpdUrl?.let { scpdUrl ->
            runCatching { parseScpdActions(readUrl(scpdUrl)) }.getOrNull()
        }
        val rcActions = renderingControl?.scpdUrl?.let { scpdUrl ->
            runCatching { parseScpdActions(readUrl(scpdUrl)) }.getOrNull()
        }
        capabilityCache.putIfAbsent(
            cacheKey,
            RendererCapabilities(
                seek = when {
                    avActions == null -> CapabilitySupport.UNKNOWN
                    "Seek" in avActions -> CapabilitySupport.SUPPORTED
                    else -> CapabilitySupport.UNSUPPORTED
                },
                positionReporting = when {
                    avActions == null -> CapabilitySupport.UNKNOWN
                    "GetPositionInfo" in avActions -> CapabilitySupport.SUPPORTED
                    else -> CapabilitySupport.UNSUPPORTED
                },
                absoluteVolume = when {
                    renderingControl == null -> CapabilitySupport.UNSUPPORTED
                    rcActions == null -> CapabilitySupport.UNKNOWN
                    "GetVolume" in rcActions || "SetVolume" in rcActions -> CapabilitySupport.SUPPORTED
                    else -> CapabilitySupport.UNSUPPORTED
                },
                explicitMute = when {
                    renderingControl == null -> CapabilitySupport.UNSUPPORTED
                    rcActions == null -> CapabilitySupport.UNKNOWN
                    "GetMute" in rcActions || "SetMute" in rcActions -> CapabilitySupport.SUPPORTED
                    else -> CapabilitySupport.UNSUPPORTED
                },
                protocolInfoVerified = false
            )
        )
        return endpoints
    }

    private fun parseScpdActions(xml: String): Set<String> {
        val parser = newSafePullParser(xml)
        val actions = linkedSetOf<String>()
        var inActionList = false
        var inAction = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "actionList" -> inActionList = true
                        "action" -> if (inActionList) inAction = true
                        "name" -> if (inAction) {
                            val name = parser.nextText().trim()
                            if (name.isNotEmpty()) actions += name
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "action" -> inAction = false
                        "actionList" -> inActionList = false
                    }
                }
            }
            parser.next()
        }
        return actions
    }

    private fun extractSoapTag(xml: String, tagName: String): String? {
        val parser = runCatching { newSafePullParser(xml) }.getOrNull()
        if (parser != null) {
            val target = tagName.lowercase()
            val found = runCatching {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG &&
                        parser.name.substringAfter(':').equals(target, ignoreCase = true)
                    ) {
                        return@runCatching parser.nextText().trim()
                    }
                    parser.next()
                }
                null
            }.getOrNull()
            if (found != null) return found
        }
        val regex = Regex("<(?:[\\w.-]+:)?$tagName[^>]*>(.*?)</(?:[\\w.-]+:)?$tagName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun readUrl(url: URL): String {
        val connection = openLanConnection(url).apply {
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("تعذر قراءة وصف DLNA: HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                readBounded(
                    input,
                    maxBytes = MAX_XML_BYTES,
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
        val connection = openLanConnection(url).apply {
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
                if (errorBody.contains(":Fault", ignoreCase = true) || errorBody.contains("<Fault", ignoreCase = true)) {
                    val code = extractSoapTag(errorBody, "errorCode")?.toIntOrNull()
                    val desc = extractSoapTag(errorBody, "errorDescription")
                        ?: extractSoapTag(errorBody, "faultstring")
                        ?: "SOAP Fault"
                    throw SoapFaultException(code, desc, "رفض التلفزيون أمر $action: ${desc.take(200)}")
                }
                throw IOException("فشل أمر DLNA $action (HTTP $status): ${errorBody.take(300)}")
            }
            val responseXml = connection.inputStream?.use { input ->
                String(
                    readBounded(
                        input,
                        maxBytes = 256_000,
                        tooLargeMessage = "رد SOAP من DLNA تجاوز الحد المسموح."
                    ),
                    StandardCharsets.UTF_8
                )
            }.orEmpty()
            if (responseXml.contains(":Fault", ignoreCase = true) || responseXml.contains("<Fault", ignoreCase = true)) {
                val code = extractSoapTag(responseXml, "errorCode")?.toIntOrNull()
                val desc = extractSoapTag(responseXml, "errorDescription")
                    ?: extractSoapTag(responseXml, "faultstring")
                    ?: "SOAP Fault"
                throw SoapFaultException(code, desc, "رفض التلفزيون أمر $action: ${desc.take(200)}")
            }
            return responseXml
        } finally { connection.disconnect() }
    }

    private companion object {
        const val MAX_XML_BYTES = 1_000_000
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
        val isVtt = sub.contains(".vtt", ignoreCase = true)
        val subMime = if (isVtt) "text/vtt" else "text/srt"
        val secType = if (isVtt) "vtt" else "srt"
        """<res protocolInfo="http-get:*:$subMime:*">$safeSub</res><sec:CaptionInfoEx sec:type="$secType">$safeSub</sec:CaptionInfoEx>"""
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
    listOf(deviceId, LanNetworkSupport.decodeScopedHost(host).lowercase(), descriptionUrl).joinToString("\u0000")

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
