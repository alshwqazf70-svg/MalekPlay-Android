package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal fun isSameDiscoveryHost(endpointHost: String?, packetHost: String): Boolean {
    val cleanEndpoint = LanNetworkSupport.stripZone(endpointHost)
    val cleanPacket = LanNetworkSupport.stripZone(packetHost)
    return cleanEndpoint.isNotBlank() && cleanEndpoint.equals(cleanPacket, ignoreCase = true)
}

internal fun classifySsdpDevice(st: String, usn: String): NetworkDeviceKind = when {
    st.contains("mediaserver", true) || usn.contains("mediaserver", true) ||
        st.contains("contentdirectory", true) || usn.contains("contentdirectory", true) ->
        NetworkDeviceKind.MEDIA_SERVER
    st.contains("mediarenderer", true) || usn.contains("mediarenderer", true) ||
        st.contains("avtransport", true) || usn.contains("avtransport", true) ||
        st.contains("renderingcontrol", true) || usn.contains("renderingcontrol", true) ||
        st.contains("roku:ecp", true) || usn.contains("roku:ecp", true) ->
        NetworkDeviceKind.MEDIA_RENDERER
    else -> NetworkDeviceKind.NETWORK_DEVICE
}

internal fun classifyWsDiscoveryDevice(types: String, scopes: String): NetworkDeviceKind {
    val advertised = "$types $scopes"
    return if (advertised.contains("computer", true) || advertised.contains("windows", true)) {
        NetworkDeviceKind.WINDOWS_COMPUTER
    } else NetworkDeviceKind.NETWORK_DEVICE
}

enum class TvCastProtocol {
    DLNA,
    CHROMECAST,
    ROKU_ECP,
    AIRPLAY,
    DIAL
}

/** A service advertised on the current Wi-Fi / LAN / Hotspot. It is a discovery hint, not proof of playback capability. */
data class DiscoveredNetworkDevice(
    val id: String,
    val name: String,
    val host: String,
    val kind: NetworkDeviceKind,
    val services: Set<String>,
    val descriptionUrl: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val castProtocols: Set<TvCastProtocol> = emptySet(),
    val playbackVerified: Boolean = false,
    val controlVerified: Boolean = false,
    val alternateHosts: Set<String> = emptySet()
) {
    /** True only when a concrete direct playback & control endpoint (DLNA AVTransport or verified Roku Media Player) is confirmed. */
    fun supportsDirectPlayback(): Boolean =
        playbackVerified && (TvCastProtocol.DLNA in castProtocols || TvCastProtocol.ROKU_ECP in castProtocols)

    /** True when Roku ECP control was verified, while media playback via Roku Media Player could not be confirmed. */
    fun supportsRokuControlOnly(): Boolean =
        TvCastProtocol.ROKU_ECP in castProtocols && controlVerified && !playbackVerified

    /** True when the device advertises Google Cast / Android TV Remote routing via Cast SDK. */
    fun hasGoogleCastRoute(): Boolean =
        TvCastProtocol.CHROMECAST in castProtocols

    /** True for discovered smart devices that lack a verified direct playback endpoint in this app. */
    fun isDiscoveredWithoutDirectPlayback(): Boolean =
        !supportsDirectPlayback() && !hasGoogleCastRoute() &&
            (kind == NetworkDeviceKind.MEDIA_RENDERER ||
                TvCastProtocol.AIRPLAY in castProtocols ||
                TvCastProtocol.DIAL in castProtocols ||
                TvCastProtocol.DLNA in castProtocols ||
                TvCastProtocol.ROKU_ECP in castProtocols)
}

enum class NetworkDeviceKind { MEDIA_SERVER, MEDIA_RENDERER, WINDOWS_COMPUTER, NETWORK_DEVICE }

/**
 * Result of staged Roku verification separating discovery, ECP control readiness,
 * and Roku Media Player (`/input/15985`) media playback readiness.
 */
internal data class RokuReadinessResult(
    val reachable: Boolean,
    val controlVerified: Boolean,
    val playbackVerified: Boolean,
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null
)

/**
 * Passive-scope LAN discovery using standard multicast advertisements:
 * SSDP/UPnP (IPv4 & IPv6, including DLNA, DIAL, and Roku ECP) for media devices,
 * WS-Discovery for Windows, and mDNS/DNS-SD for TVs, Apple devices, and file shares.
 *
 * Also supports Wi-Fi Hotspot / AP / Ethernet local interfaces and explicit
 * single-IP TV probing when a router blocks multicast discovery.
 */
class NetworkDiscoveryClient {
    suspend fun discover(context: Context): List<DiscoveredNetworkDevice> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivity.activeNetwork
            val lanNetwork = activeNetwork?.takeIf { candidate ->
                connectivity.getNetworkCapabilities(candidate)?.let { caps ->
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            } ?: connectivity.allNetworks.firstOrNull { candidate ->
                connectivity.getNetworkCapabilities(candidate)?.let { caps ->
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }

            val hasLocalInterface = findActivePrivateMulticastInterfaces().isNotEmpty()
            if (lanNetwork == null && !hasLocalInterface) {
                throw IllegalStateException("اتصل بشبكة Wi-Fi أو فعّل نقطة الاتصال أولاً لاكتشاف الأجهزة المحلية.")
            }

            val activeDiscoverySockets = Collections.newSetFromMap(ConcurrentHashMap<MulticastSocket, Boolean>())
            @Volatile var networkChanged = false
            val networkCallback = if (lanNetwork != null) {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        if (network == lanNetwork) {
                            networkChanged = true
                            activeDiscoverySockets.toList().forEach { sock -> runCatching { sock.close() } }
                        }
                    }
                }
            } else null

            if (networkCallback != null) {
                runCatching {
                    val req = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build()
                    connectivity.registerNetworkCallback(req, networkCallback)
                }
            }

            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("MalekPlay:LAN-discovery")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
            try {
                // Run independent multicast protocols together to keep the initial scan responsive.
                val (ssdpDevices, wsDiscoveryDevices, mdnsDevices) = coroutineScope {
                    val ssdp = async(Dispatchers.IO) {
                        val activeCheck = { isActive && !networkChanged }
                        collectProtocolResults { results ->
                            discoverSsdp(results, lanNetwork, activeDiscoverySockets, activeCheck)
                        }
                    }
                    val wsDiscovery = async(Dispatchers.IO) {
                        val activeCheck = { isActive && !networkChanged }
                        collectProtocolResults { results ->
                            discoverWsDiscovery(results, lanNetwork, activeDiscoverySockets, activeCheck)
                        }
                    }
                    val mdns = async(Dispatchers.IO) {
                        try {
                            MdnsDiscoveryClient().discover(
                                network = lanNetwork,
                                onSocketOpened = { sock -> activeDiscoverySockets += sock }
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    Triple(ssdp.await(), wsDiscovery.await(), mdns.await())
                }
                val found = LinkedHashMap<String, MutableDevice>()
                (ssdpDevices + wsDiscoveryDevices + mdnsDevices).forEach { device ->
                    merge(
                        found,
                        LanNetworkSupport.stripZone(device.host).lowercase(Locale.ROOT),
                        MutableDevice(
                            name = device.name,
                            host = device.host,
                            kind = device.kind,
                            services = device.services.toMutableSet(),
                            descriptionUrl = device.descriptionUrl,
                            manufacturer = device.manufacturer,
                            modelName = device.modelName,
                            castProtocols = device.castProtocols.toMutableSet(),
                            candidateDescriptionUrls = listOfNotNull(device.descriptionUrl).toMutableSet(),
                            playbackVerified = device.playbackVerified,
                            controlVerified = device.controlVerified,
                            alternateHosts = device.alternateHosts.toMutableSet()
                        )
                    )
                }

                if (!networkChanged) {
                    enrichDiscoveredDevices(found.values.toList(), app, lanNetwork)
                }

                found.values
                    .filter { it.host.isNotBlank() }
                    .map { it.toPublic() }
                    .sortedWith(
                        compareByDescending<DiscoveredNetworkDevice> { it.supportsDirectPlayback() }
                            .thenByDescending { it.hasGoogleCastRoute() }
                            .thenByDescending { it.controlVerified }
                            .thenBy { it.kind.ordinal }
                            .thenByDescending { it.castProtocols.size }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    )
            } finally {
                activeDiscoverySockets.toList().forEach { sock -> runCatching { sock.close() } }
                activeDiscoverySockets.clear()
                if (networkCallback != null) {
                    runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
                }
                if (lock?.isHeld == true) runCatching { lock.release() }
            }
        }

    /**
     * Probes a single user-entered LAN IPv4/IPv6 address (preserving any IPv6 link-local Scope ID)
     * for Smart TV / DLNA / Roku / DIAL endpoints when router AP isolation or multicast filtering blocks SSDP.
     */
    suspend fun probeDeviceByIp(rawHost: String, context: Context? = null): DiscoveredNetworkDevice? =
        withContext(Dispatchers.IO) {
            val host = LanNetworkSupport.decodeScopedHost(rawHost)
            if (!isPrivateLanHost(host)) {
                throw IllegalArgumentException("أدخل عنوان IP خاص على نفس الشبكة المحلية (مثل 192.168.1.50).")
            }
            val urlHost = LanNetworkSupport.formatUriAuthorityHost(host)
            val boundNetwork = LanNetworkSupport.findLanNetworkForHost(context, host)
            val candidateUrls = listOf(
                "http://$urlHost:9197/dmr",               // Samsung Tizen / Smart TV DMR
                "http://$urlHost:7676/smp_7_",            // Samsung Legacy AllShare
                "http://$urlHost:52235/dmr/SamsungMRDesc.xml",
                "http://$urlHost:1990/",                  // LG webOS / NetCast DLNA
                "http://$urlHost:1567/",                  // LG Smart TV
                "http://$urlHost:36866/",                 // LG webOS second-screen / DMR
                "http://$urlHost:8008/ssdp/device-desc.xml", // Android TV / Chromecast DIAL
                "http://$urlHost:49152/description.xml",  // Generic UPnP / Smart TV
                "http://$urlHost:49153/description.xml",
                "http://$urlHost:38400/description.xml",  // Sony / Panasonic / Philips
                "http://$urlHost:1648/",                  // Kodi UPnP renderer
                "http://$urlHost:8200/rootDesc.xml",      // MiniDLNA / ReadyMedia
                "http://$urlHost:50001/desc.xml"
            )
            val discovered = MutableDevice(
                name = "تلفزيون ($host)",
                host = host,
                kind = NetworkDeviceKind.NETWORK_DEVICE,
                services = mutableSetOf(),
                descriptionUrl = null
            )
            var foundAny = false
            coroutineScope {
                val rokuDeferred = async(Dispatchers.IO) {
                    verifyRokuReadiness(host, rokuPort = 8060, network = boundNetwork)
                }
                val results = candidateUrls.map { urlString ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val body = readSafeLocalXml(urlString, host, timeoutMs = 1_100, network = boundNetwork)
                            urlString to body
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()

                val roku = rokuDeferred.await()
                if (roku.reachable) {
                    foundAny = true
                    discovered.kind = NetworkDeviceKind.MEDIA_RENDERER
                    discovered.services += "Roku ECP"
                    discovered.castProtocols += TvCastProtocol.ROKU_ECP
                    discovered.controlVerified = discovered.controlVerified || roku.controlVerified
                    discovered.playbackVerified = discovered.playbackVerified || roku.playbackVerified
                    if (!roku.friendlyName.isNullOrBlank()) discovered.name = roku.friendlyName
                    if (!roku.manufacturer.isNullOrBlank()) discovered.manufacturer = roku.manufacturer
                    if (!roku.modelName.isNullOrBlank()) discovered.modelName = roku.modelName
                }

                for ((urlString, xml) in results) {
                    if (xml.contains("<root", true) || xml.contains("AVTransport", true) || xml.contains("MediaRenderer", true) || xml.contains("ContentDirectory", true)) {
                        foundAny = true
                        applyDescriptionXml(discovered, urlString, xml)
                    }
                }
            }
            if (!foundAny) null else discovered.toPublic()
        }

    /**
     * Verifies Roku device readiness in explicit stages:
     * 1) `/query/device-info` checks whether ECP control is reachable and not disabled.
     * 2) `/query/apps` verifies whether Roku Media Player (channel id `2213` / PlayOnRoku) is installed
     *    before setting `playbackVerified = true`. A device responding only to `/query/device-info`
     *    remains `controlVerified = true, playbackVerified = false`.
     */
    internal fun verifyRokuReadiness(
        host: String,
        rokuPort: Int = 8060,
        network: Network? = null,
        allowLoopbackForTest: Boolean = false,
        httpGet: ((String) -> String?)? = null
    ): RokuReadinessResult {
        val urlHost = LanNetworkSupport.formatUriAuthorityHost(host)
        val fetch: (String) -> String? = httpGet ?: { rawUrl ->
            runCatching {
                readSafeLocalXml(
                    rawUrl = rawUrl,
                    expectedHost = host,
                    timeoutMs = 950,
                    network = network,
                    allowLoopbackForTest = allowLoopbackForTest
                )
            }.getOrNull()
        }

        val infoXml = fetch("http://$urlHost:$rokuPort/query/device-info")
        if (infoXml.isNullOrBlank() || !infoXml.contains("<device-info", ignoreCase = true)) {
            return RokuReadinessResult(reachable = false, controlVerified = false, playbackVerified = false)
        }

        val friendly = extractXmlTag(infoXml, "friendly-device-name")
            ?: extractXmlTag(infoXml, "user-device-name")
            ?: extractXmlTag(infoXml, "model-name")
        val vendor = extractXmlTag(infoXml, "vendor-name")
        val model = extractXmlTag(infoXml, "model-name")
        val ecpSettingMode = extractXmlTag(infoXml, "ecp-setting-mode")?.lowercase(Locale.ROOT)
        val controlAllowed = ecpSettingMode == null ||
            (ecpSettingMode != "disabled" && ecpSettingMode != "off")
        if (!controlAllowed) {
            return RokuReadinessResult(
                reachable = true,
                controlVerified = false,
                playbackVerified = false,
                friendlyName = friendly,
                manufacturer = vendor,
                modelName = model
            )
        }

        val appsXml = fetch("http://$urlHost:$rokuPort/query/apps")
        val playbackSupported = if (!appsXml.isNullOrBlank() && appsXml.contains("<app", ignoreCase = true)) {
            ROKU_MEDIA_PLAYER_APP_REGEX.containsMatchIn(appsXml) ||
                appsXml.contains("Roku Media Player", ignoreCase = true) ||
                appsXml.contains("PlayOnRoku", ignoreCase = true)
        } else {
            false
        }

        return RokuReadinessResult(
            reachable = true,
            controlVerified = true,
            playbackVerified = playbackSupported,
            friendlyName = friendly,
            manufacturer = vendor,
            modelName = model
        )
    }

    private suspend fun enrichDiscoveredDevices(
        devices: List<MutableDevice>,
        context: Context,
        lanNetwork: Network?
    ) {
        val candidates = devices.filter {
            it.descriptionUrl != null ||
                it.candidateDescriptionUrls.isNotEmpty() ||
                "roku:ecp" in it.services ||
                TvCastProtocol.ROKU_ECP in it.castProtocols ||
                TvCastProtocol.DLNA in it.castProtocols ||
                it.services.any { s -> s.contains("Port 8060") || s.contains("dial", true) }
        }
        if (candidates.isEmpty()) return

        withTimeoutOrNull(ENRICH_TOTAL_TIMEOUT_MS) {
            coroutineScope {
                candidates.map { device ->
                    async(Dispatchers.IO) {
                        val deviceNetwork = LanNetworkSupport.findLanNetworkForHost(context, device.host) ?: lanNetwork
                        val urlHost = LanNetworkSupport.formatUriAuthorityHost(device.host)
                        val urlsToInspect = synchronized(device) {
                            val existing = (device.candidateDescriptionUrls + listOfNotNull(device.descriptionUrl))
                                .distinct()
                            if (existing.isNotEmpty()) {
                                existing.take(5)
                            } else if (TvCastProtocol.DLNA in device.castProtocols) {
                                listOf(
                                    "http://$urlHost:9197/dmr",
                                    "http://$urlHost:7676/smp_7_",
                                    "http://$urlHost:52235/dmr/SamsungMRDesc.xml",
                                    "http://$urlHost:1990/",
                                    "http://$urlHost:36866/",
                                    "http://$urlHost:49152/description.xml"
                                )
                            } else emptyList()
                        }
                        for (desc in urlsToInspect) {
                            runCatching {
                                val xml = readSafeLocalXml(desc, device.host, timeoutMs = 1_100, network = deviceNetwork)
                                applyDescriptionXml(device, desc, xml)
                            }
                        }
                        if ("roku:ecp" in device.services || TvCastProtocol.ROKU_ECP in device.castProtocols) {
                            val roku = verifyRokuReadiness(device.host, rokuPort = 8060, network = deviceNetwork)
                            if (roku.reachable) {
                                synchronized(device) {
                                    if (!roku.friendlyName.isNullOrBlank()) device.name = roku.friendlyName
                                    if (!roku.manufacturer.isNullOrBlank()) device.manufacturer = roku.manufacturer
                                    if (!roku.modelName.isNullOrBlank()) device.modelName = roku.modelName
                                    device.kind = NetworkDeviceKind.MEDIA_RENDERER
                                    device.castProtocols += TvCastProtocol.ROKU_ECP
                                    device.controlVerified = device.controlVerified || roku.controlVerified
                                    device.playbackVerified = device.playbackVerified || roku.playbackVerified
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun applyDescriptionXml(device: MutableDevice, urlString: String, xml: String) {
        val friendlyName = extractXmlTag(xml, "friendlyName")
        val manufacturer = extractXmlTag(xml, "manufacturer")
        val modelName = extractXmlTag(xml, "modelName")
        val hasAvTransport = xml.contains("AVTransport", ignoreCase = true)
        val hasMediaRenderer = xml.contains("MediaRenderer", ignoreCase = true)
        val hasContentDirectory = xml.contains("ContentDirectory", ignoreCase = true)
        val hasDial = xml.contains("dial-multiscreen", ignoreCase = true)

        synchronized(device) {
            if (hasAvTransport || hasMediaRenderer) {
                device.kind = NetworkDeviceKind.MEDIA_RENDERER
                device.services += "DLNA MediaRenderer"
                device.castProtocols += TvCastProtocol.DLNA
                if (hasAvTransport) {
                    device.playbackVerified = true
                    device.controlVerified = true
                }
                if (device.descriptionUrl == null || !urlString.contains(":8008/")) {
                    device.descriptionUrl = urlString
                }
            } else if (hasContentDirectory && device.kind == NetworkDeviceKind.NETWORK_DEVICE) {
                device.kind = NetworkDeviceKind.MEDIA_SERVER
                device.services += "DLNA MediaServer"
                if (device.descriptionUrl == null) device.descriptionUrl = urlString
            }
            if (hasDial) {
                device.castProtocols += TvCastProtocol.DIAL
                device.services += "DIAL"
            }
            if (!friendlyName.isNullOrBlank()) {
                val cleaned = friendlyName.trim().take(70)
                if (device.name.isBlank() || isGenericDiscoveryName(device.name) || hasAvTransport || hasMediaRenderer) {
                    device.name = cleaned
                }
            }
            if (!manufacturer.isNullOrBlank()) device.manufacturer = manufacturer.trim().take(50)
            if (!modelName.isNullOrBlank()) device.modelName = modelName.trim().take(60)
        }
    }

    private fun readSafeLocalXml(
        rawUrl: String,
        expectedHost: String,
        timeoutMs: Int,
        network: Network? = null,
        allowLoopbackForTest: Boolean = false
    ): String {
        val url: URL = validateDlnaRendererEndpointUrl(rawUrl, expectedHost, allowLoopbackForTest)
        val connection = LanNetworkSupport.openLanHttpConnection(
            url = url,
            network = network,
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs
        ).apply {
            requestMethod = "GET"
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use { input ->
                readBounded(input, maxBytes = 512_000, tooLargeMessage = "XML too large")
            }
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractXmlTag(xml: String, tagName: String): String? {
        val pattern = Regex(
            "<(?:[\\w.-]+:)?$tagName[^>]*>(.*?)</(?:[\\w.-]+:)?$tagName>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val raw = pattern.find(xml)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .filterNot { it.isISOControl() }
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun isGenericDiscoveryName(name: String): Boolean =
        name == "جهاز UPnP" ||
            name == "مشغل وسائط UPnP" ||
            name == "خادم وسائط UPnP" ||
            name == "جهاز WS-Discovery" ||
            name == "كمبيوتر على الشبكة" ||
            name.startsWith("تلفزيون (")

    private fun collectProtocolResults(
        discover: (MutableMap<String, MutableDevice>) -> Unit
    ): List<DiscoveredNetworkDevice> {
        val results = LinkedHashMap<String, MutableDevice>()
        try {
            discover(results)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        return results.values.map { it.toPublic() }
    }

    private fun discoverSsdp(
        found: MutableMap<String, MutableDevice>,
        network: Network?,
        activeSockets: MutableSet<MulticastSocket>,
        shouldContinue: () -> Boolean
    ) {
        val requests = listOf(
            "ssdp:all",
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:device:MediaRenderer:2",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "roku:ecp",
            "urn:schemas-upnp-org:device:MediaServer:1"
        )
        val socket = MulticastSocket()
        activeSockets += socket
        try {
            if (network != null) runCatching { network.bindSocket(socket) }
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket.timeToLive = 3
            val targetV4 = InetAddress.getByName(SSDP_GROUP)
            val targetV6 = runCatching { InetAddress.getByName(SSDP_GROUP_V6) }.getOrNull()
            val interfaces = findActivePrivateMulticastInterfaces()
            requests.forEach { searchTarget ->
                if (!shouldContinue()) return@forEach
                val messageV4 = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_GROUP:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $searchTarget\r\n\r\n"
                val bytesV4 = messageV4.toByteArray(Charsets.US_ASCII)
                val messageV6 = "M-SEARCH * HTTP/1.1\r\nHOST: [$SSDP_GROUP_V6]:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $searchTarget\r\n\r\n"
                val bytesV6 = messageV6.toByteArray(Charsets.US_ASCII)
                if (interfaces.isNotEmpty()) {
                    interfaces.take(4).forEach { iface ->
                        val addrs = runCatching { iface.inetAddresses.toList() }.getOrDefault(emptyList())
                        if (addrs.any { it is Inet4Address && !it.isLoopbackAddress }) {
                            runCatching {
                                socket.networkInterface = iface
                                socket.send(DatagramPacket(bytesV4, bytesV4.size, targetV4, SSDP_PORT))
                            }
                        }
                        if (targetV6 != null && addrs.any { it is Inet6Address && !it.isLoopbackAddress }) {
                            runCatching {
                                socket.networkInterface = iface
                                val scopedTargetV6 = runCatching {
                                    Inet6Address.getByAddress(null, targetV6.address, iface)
                                }.getOrElse { targetV6 }
                                socket.send(DatagramPacket(bytesV6, bytesV6.size, scopedTargetV6, SSDP_PORT))
                            }
                        }
                    }
                } else {
                    runCatching { socket.send(DatagramPacket(bytesV4, bytesV4.size, targetV4, SSDP_PORT)) }
                }
            }
            receiveUntilQuiet(socket, SSDP_WINDOW_MS, shouldContinue) { payload, source ->
                if (!LanNetworkSupport.isAllowedLanPeerAddress(source)) return@receiveUntilQuiet
                val headers = parseHeaders(payload)
                val location = headers["location"]
                val usn = headers["usn"].orEmpty()
                val st = headers["st"].orEmpty()
                val server = headers["server"].orEmpty()
                val locationUri = location?.let { runCatching { URI(it) }.getOrNull() }
                val host = LanNetworkSupport.formatHostPreservingScope(source) ?: return@receiveUntilQuiet
                // Never trust a LOCATION host that differs from the actual LAN packet sender.
                // For link-local IPv6 packets, preserve our local Scope ID in the resolved URL authority.
                val description = locationUri?.takeIf {
                    (it.scheme == "http" || it.scheme == "https") &&
                        isSameDiscoveryHost(it.host, host)
                }?.let { validUri ->
                    if (host.contains('%')) {
                        val scopedAuthority = LanNetworkSupport.formatUriAuthorityHost(host)
                        val portPart = if (validUri.port in 1..65535) ":${validUri.port}" else ""
                        val pathPart = validUri.rawPath.orEmpty()
                        val queryPart = validUri.rawQuery?.let { "?$it" }.orEmpty()
                        "${validUri.scheme}://$scopedAuthority$portPart$pathPart$queryPart"
                    } else {
                        validUri.toString()
                    }
                }
                val identity = LanNetworkSupport.stripZone(host).lowercase(Locale.ROOT)
                val kind = classifySsdpDevice(st, usn)
                val protocols = mutableSetOf<TvCastProtocol>()
                if (kind == NetworkDeviceKind.MEDIA_RENDERER) {
                    if (st.contains("roku:ecp", true) || usn.contains("roku:ecp", true)) {
                        protocols += TvCastProtocol.ROKU_ECP
                    } else {
                        protocols += TvCastProtocol.DLNA
                    }
                }
                if (st.contains("dial", true) || usn.contains("dial", true)) {
                    protocols += TvCastProtocol.DIAL
                }
                val label = when (kind) {
                    NetworkDeviceKind.MEDIA_SERVER -> "خادم وسائط UPnP"
                    NetworkDeviceKind.MEDIA_RENDERER ->
                        if (TvCastProtocol.ROKU_ECP in protocols) "Roku TV" else "مشغل وسائط UPnP"
                    else -> server.take(70).ifBlank { "جهاز UPnP" }
                }
                merge(
                    found,
                    identity,
                    MutableDevice(
                        name = label,
                        host = host,
                        kind = kind,
                        services = mutableSetOf("SSDP/UPnP", st).filter { it.isNotBlank() }.toMutableSet(),
                        descriptionUrl = description,
                        castProtocols = protocols,
                        playbackVerified = false,
                        controlVerified = false
                    )
                )
            }
        } finally {
            activeSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun discoverWsDiscovery(
        found: MutableMap<String, MutableDevice>,
        network: Network?,
        activeSockets: MutableSet<MulticastSocket>,
        shouldContinue: () -> Boolean
    ) {
        val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
<e:Header><w:MessageID>urn:uuid:malekplay-discovery</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe/></e:Body></e:Envelope>"""
        val socket = MulticastSocket()
        activeSockets += socket
        try {
            if (network != null) runCatching { network.bindSocket(socket) }
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket.timeToLive = 2
            val bytes = probe.toByteArray(Charsets.UTF_8)
            val target = InetAddress.getByName(SSDP_GROUP)
            val interfaces = findActivePrivateMulticastInterfaces()
            if (interfaces.isNotEmpty()) {
                interfaces.take(3).forEach { iface ->
                    runCatching {
                        socket.networkInterface = iface
                        socket.send(DatagramPacket(bytes, bytes.size, target, WSD_PORT))
                    }
                }
            } else {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, WSD_PORT)) }
            }
            receiveUntilQuiet(socket, WSD_WINDOW_MS, shouldContinue) { payload, source ->
                if (!LanNetworkSupport.isAllowedLanPeerAddress(source)) return@receiveUntilQuiet
                val xml = payload.take(MAX_RESPONSE_CHARS)
                if (!xml.contains("ProbeMatch", true)) return@receiveUntilQuiet
                val xaddr = XML_XADDR.find(xml)?.groupValues?.getOrNull(1)?.trim()
                val uri = xaddr?.split(Regex("\\s+"))?.firstOrNull()?.let { runCatching { URI(it) }.getOrNull() }
                val host = LanNetworkSupport.formatHostPreservingScope(source) ?: return@receiveUntilQuiet
                // Accept service endpoints only when they point back to the responding LAN host.
                val safeXAddr = uri?.takeIf {
                    (it.scheme == "http" || it.scheme == "https") &&
                        isSameDiscoveryHost(it.host, host)
                }?.toString()
                val types = XML_TYPES.find(xml)?.groupValues?.getOrNull(1).orEmpty()
                val scopes = XML_SCOPES.find(xml)?.groupValues?.getOrNull(1).orEmpty()
                val computer = classifyWsDiscoveryDevice(types, scopes) == NetworkDeviceKind.WINDOWS_COMPUTER
                val identity = LanNetworkSupport.stripZone(host).lowercase(Locale.ROOT)
                val label = if (computer) "كمبيوتر على الشبكة" else "جهاز WS-Discovery"
                merge(
                    found,
                    identity,
                    MutableDevice(
                        name = label,
                        host = host,
                        kind = if (computer) NetworkDeviceKind.WINDOWS_COMPUTER else NetworkDeviceKind.NETWORK_DEVICE,
                        services = mutableSetOf("WS-Discovery"),
                        descriptionUrl = safeXAddr
                    )
                )
            }
        } finally {
            activeSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun findActivePrivateMulticastInterfaces(): List<NetworkInterface> =
        MdnsDiscoveryClient.findMulticastInterfaces()

    private fun receiveUntilQuiet(
        socket: MulticastSocket,
        windowMs: Long,
        shouldContinue: () -> Boolean,
        onPacket: (String, InetAddress) -> Unit
    ) {
        val started = System.nanoTime()
        var lastPacket = started
        val buffer = ByteArray(8192)
        while (shouldContinue() &&
            (System.nanoTime() - started) / 1_000_000 < windowMs &&
            (System.nanoTime() - lastPacket) / 1_000_000 < QUIET_WINDOW_MS
        ) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                lastPacket = System.nanoTime()
                val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                runCatching { onPacket(payload, packet.address) }
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun parseHeaders(payload: String): Map<String, String> =
        payload.lineSequence().drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null
            else line.substring(0, separator).trim().lowercase(Locale.ROOT) to line.substring(separator + 1).trim()
        }.toMap()

    private fun merge(found: MutableMap<String, MutableDevice>, key: String, device: MutableDevice) {
        val incomingHosts = (listOf(device.host) + device.alternateHosts)
            .map { LanNetworkSupport.stripZone(it).lowercase(Locale.ROOT) }
            .toSet()

        val matchedKey = if (found.containsKey(key)) {
            key
        } else {
            found.entries.firstOrNull { (_, existing) ->
                val existingHosts = (listOf(existing.host) + existing.alternateHosts)
                    .map { LanNetworkSupport.stripZone(it).lowercase(Locale.ROOT) }
                    .toSet()
                incomingHosts.any { it in existingHosts }
            }?.key ?: key
        }

        val existing = found[matchedKey]
        if (existing == null) {
            device.descriptionUrl?.let { device.candidateDescriptionUrls += it }
            found[matchedKey] = device
        } else {
            existing.services.addAll(device.services)
            existing.castProtocols.addAll(device.castProtocols)
            existing.playbackVerified = existing.playbackVerified || device.playbackVerified
            existing.controlVerified = existing.controlVerified || device.controlVerified
            if (!device.host.equals(existing.host, ignoreCase = true)) {
                existing.alternateHosts += device.host
            }
            existing.alternateHosts.addAll(device.alternateHosts)
            // Prefer IPv4 host as primary when an existing entry only had IPv6.
            if (existing.host.contains(':') && !device.host.contains(':')) {
                existing.alternateHosts += existing.host
                existing.host = device.host
                existing.alternateHosts.removeAll { it.equals(existing.host, ignoreCase = true) }
            }
            device.descriptionUrl?.let { incomingDesc ->
                existing.candidateDescriptionUrls += incomingDesc
                val existingIsDialOrGeneric = existing.descriptionUrl == null ||
                    existing.descriptionUrl?.contains(":8008/") == true
                val incomingIsRenderer = device.kind == NetworkDeviceKind.MEDIA_RENDERER &&
                    !incomingDesc.contains(":8008/")
                if (existing.descriptionUrl == null || (existingIsDialOrGeneric && incomingIsRenderer)) {
                    existing.descriptionUrl = incomingDesc
                }
            }
            if (existing.manufacturer == null) existing.manufacturer = device.manufacturer
            if (existing.modelName == null) existing.modelName = device.modelName
            if ("mDNS/DNS-SD" in device.services && device.name.isNotBlank() && isGenericDiscoveryName(existing.name)) {
                existing.name = device.name
            }
            val existingPriority = devicePriority(existing.kind)
            val incomingPriority = devicePriority(device.kind)
            if (incomingPriority > existingPriority) {
                existing.kind = device.kind
                if (existing.name.isBlank() || isGenericDiscoveryName(existing.name)) {
                    existing.name = device.name
                }
            } else if (device.kind == NetworkDeviceKind.MEDIA_RENDERER && existing.kind == NetworkDeviceKind.MEDIA_SERVER) {
                existing.services += "DLNA MediaRenderer"
                existing.castProtocols += device.castProtocols
            }
        }
    }

    private fun devicePriority(kind: NetworkDeviceKind): Int = when (kind) {
        NetworkDeviceKind.MEDIA_RENDERER -> 4
        NetworkDeviceKind.WINDOWS_COMPUTER -> 3
        NetworkDeviceKind.MEDIA_SERVER -> 2
        NetworkDeviceKind.NETWORK_DEVICE -> 1
    }

    private data class MutableDevice(
        var name: String,
        var host: String,
        var kind: NetworkDeviceKind,
        val services: MutableSet<String>,
        var descriptionUrl: String?,
        var manufacturer: String? = null,
        var modelName: String? = null,
        val castProtocols: MutableSet<TvCastProtocol> = mutableSetOf(),
        val candidateDescriptionUrls: MutableSet<String> = mutableSetOf(),
        var playbackVerified: Boolean = false,
        var controlVerified: Boolean = false,
        val alternateHosts: MutableSet<String> = mutableSetOf()
    ) {
        fun toPublic() = DiscoveredNetworkDevice(
            id = "${LanNetworkSupport.stripZone(host)}:${kind.name}:${services.sorted().joinToString()}",
            name = name,
            host = host,
            kind = kind,
            services = services.toSet(),
            descriptionUrl = descriptionUrl,
            manufacturer = manufacturer,
            modelName = modelName,
            castProtocols = castProtocols.toSet(),
            playbackVerified = playbackVerified,
            controlVerified = controlVerified,
            alternateHosts = alternateHosts.toSet()
        )
    }

    private companion object {
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_GROUP_V6 = "ff02::c"
        const val SSDP_PORT = 1900
        const val WSD_PORT = 3702
        const val RECEIVE_TIMEOUT_MS = 650
        const val SSDP_WINDOW_MS = 3_400L
        const val WSD_WINDOW_MS = 2_400L
        const val QUIET_WINDOW_MS = 2_100L
        const val ENRICH_TOTAL_TIMEOUT_MS = 1_800L
        const val MAX_RESPONSE_CHARS = 16_384
        val ROKU_MEDIA_PLAYER_APP_REGEX = Regex("""<app[^>]*\bid\s*=\s*["']2213["'][^>]*>""", RegexOption.IGNORE_CASE)
        val XML_XADDR = Regex("<(?:[\\w.-]+:)?XAddrs[^>]*>(.*?)</(?:[\\w.-]+:)?XAddrs>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val XML_TYPES = Regex("<(?:[\\w.-]+:)?Types[^>]*>(.*?)</(?:[\\w.-]+:)?Types>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val XML_SCOPES = Regex("<(?:[\\w.-]+:)?Scopes[^>]*>(.*?)</(?:[\\w.-]+:)?Scopes>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
