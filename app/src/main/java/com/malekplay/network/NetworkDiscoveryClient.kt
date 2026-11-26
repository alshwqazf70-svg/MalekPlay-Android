package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

internal fun isSameDiscoveryHost(endpointHost: String?, packetHost: String): Boolean =
    !endpointHost.isNullOrBlank() && endpointHost.equals(packetHost, ignoreCase = true)

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

/** A service advertised on the current Wi-Fi / LAN / Hotspot. It is a discovery hint, not proof of file access. */
data class DiscoveredNetworkDevice(
    val id: String,
    val name: String,
    val host: String,
    val kind: NetworkDeviceKind,
    val services: Set<String>,
    val descriptionUrl: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val castProtocols: Set<TvCastProtocol> = emptySet()
)

enum class NetworkDeviceKind { MEDIA_SERVER, MEDIA_RENDERER, WINDOWS_COMPUTER, NETWORK_DEVICE }

/**
 * Passive-scope LAN discovery using standard multicast advertisements:
 * SSDP/UPnP (including DLNA, DIAL, and Roku ECP) for media devices,
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

            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("MalekPlay:LAN-discovery")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
            try {
                // Run independent multicast protocols together to keep the initial scan responsive.
                val (ssdpDevices, wsDiscoveryDevices, mdnsDevices) = coroutineScope {
                    val ssdp = async(Dispatchers.IO) {
                        collectProtocolResults { results -> discoverSsdp(results, lanNetwork) }
                    }
                    val wsDiscovery = async(Dispatchers.IO) {
                        collectProtocolResults { results -> discoverWsDiscovery(results, lanNetwork) }
                    }
                    val mdns = async(Dispatchers.IO) {
                        try {
                            MdnsDiscoveryClient().discover(lanNetwork)
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
                        device.host.lowercase(Locale.ROOT),
                        MutableDevice(
                            name = device.name,
                            host = device.host,
                            kind = device.kind,
                            services = device.services.toMutableSet(),
                            descriptionUrl = device.descriptionUrl,
                            manufacturer = device.manufacturer,
                            modelName = device.modelName,
                            castProtocols = device.castProtocols.toMutableSet()
                        )
                    )
                }

                enrichDiscoveredDevices(found.values.toList())

                found.values
                    .filter { it.host.isNotBlank() }
                    .map { it.toPublic() }
                    .sortedWith(
                        compareBy<DiscoveredNetworkDevice> { it.kind.ordinal }
                            .thenByDescending { it.castProtocols.size }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    )
            } finally {
                if (lock?.isHeld == true) runCatching { lock.release() }
            }
        }

    /**
     * Probes a single user-entered LAN IPv4 address for common Smart TV / DLNA / Roku / DIAL
     * endpoints when router AP isolation or multicast filtering blocks SSDP discovery.
     */
    suspend fun probeDeviceByIp(rawHost: String): DiscoveredNetworkDevice? =
        withContext(Dispatchers.IO) {
            val host = rawHost.trim()
            if (!isPrivateLanHost(host)) {
                throw IllegalArgumentException("أدخل عنوان IPv4 خاص على نفس الشبكة المحلية (مثل 192.168.1.50).")
            }
            val candidateUrls = listOf(
                "http://$host:8060/query/device-info", // Roku TV / Roku Stick ECP
                "http://$host:9197/dmr",               // Samsung Tizen / Smart TV DMR
                "http://$host:7676/smp_7_",            // Samsung Legacy AllShare
                "http://$host:52235/dmr/SamsungMRDesc.xml",
                "http://$host:1990/",                  // LG webOS / NetCast DLNA
                "http://$host:1567/",                  // LG Smart TV
                "http://$host:36866/",                 // LG webOS second-screen / DMR
                "http://$host:8008/ssdp/device-desc.xml", // Android TV / Chromecast DIAL
                "http://$host:49152/description.xml",  // Generic UPnP / Smart TV
                "http://$host:49153/description.xml",
                "http://$host:38400/description.xml",  // Sony / Panasonic / Philips
                "http://$host:1648/",                  // Kodi UPnP renderer
                "http://$host:8200/rootDesc.xml",      // MiniDLNA / ReadyMedia
                "http://$host:50001/desc.xml"
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
                val results = candidateUrls.map { urlString ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val body = readSafeLocalXml(urlString, host, timeoutMs = 1_100)
                            urlString to body
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()

                for ((urlString, xml) in results) {
                    if (urlString.contains(":8060/query/device-info") && xml.contains("<device-info", true)) {
                        foundAny = true
                        discovered.kind = NetworkDeviceKind.MEDIA_RENDERER
                        discovered.services += "Roku ECP"
                        discovered.castProtocols += TvCastProtocol.ROKU_ECP
                        val friendly = extractXmlTag(xml, "friendly-device-name")
                            ?: extractXmlTag(xml, "user-device-name")
                            ?: extractXmlTag(xml, "model-name")
                        val vendor = extractXmlTag(xml, "vendor-name")
                        val model = extractXmlTag(xml, "model-name")
                        if (!friendly.isNullOrBlank()) discovered.name = friendly
                        if (!vendor.isNullOrBlank()) discovered.manufacturer = vendor
                        if (!model.isNullOrBlank()) discovered.modelName = model
                    } else if (xml.contains("<root", true) || xml.contains("AVTransport", true) || xml.contains("MediaRenderer", true) || xml.contains("ContentDirectory", true)) {
                        foundAny = true
                        applyDescriptionXml(discovered, urlString, xml)
                    }
                }
            }
            if (!foundAny) null else discovered.toPublic()
        }

    private suspend fun enrichDiscoveredDevices(devices: List<MutableDevice>) {
        val candidates = devices.filter {
            it.descriptionUrl != null ||
                "roku:ecp" in it.services ||
                it.services.any { s -> s.contains("Port 8060") || s.contains("dial", true) }
        }
        if (candidates.isEmpty()) return

        withTimeoutOrNull(ENRICH_TOTAL_TIMEOUT_MS) {
            coroutineScope {
                candidates.map { device ->
                    async(Dispatchers.IO) {
                        val desc = device.descriptionUrl
                        if (desc != null) {
                            runCatching {
                                val xml = readSafeLocalXml(desc, device.host, timeoutMs = 1_100)
                                applyDescriptionXml(device, desc, xml)
                            }
                        }
                        if ("roku:ecp" in device.services || TvCastProtocol.ROKU_ECP in device.castProtocols) {
                            runCatching {
                                val rokuInfo = readSafeLocalXml("http://${device.host}:8060/query/device-info", device.host, timeoutMs = 900)
                                val friendly = extractXmlTag(rokuInfo, "friendly-device-name")
                                    ?: extractXmlTag(rokuInfo, "user-device-name")
                                val vendor = extractXmlTag(rokuInfo, "vendor-name")
                                val model = extractXmlTag(rokuInfo, "model-name")
                                synchronized(device) {
                                    if (!friendly.isNullOrBlank()) device.name = friendly
                                    if (!vendor.isNullOrBlank()) device.manufacturer = vendor
                                    if (!model.isNullOrBlank()) device.modelName = model
                                    device.kind = NetworkDeviceKind.MEDIA_RENDERER
                                    device.castProtocols += TvCastProtocol.ROKU_ECP
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

    private fun readSafeLocalXml(rawUrl: String, expectedHost: String, timeoutMs: Int): String {
        val url: URL = validateDlnaRendererEndpointUrl(rawUrl, expectedHost)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = false
            useCaches = false
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

    private fun discoverSsdp(found: MutableMap<String, MutableDevice>, network: Network?) {
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
        MulticastSocket().use { socket ->
            if (network != null) runCatching { network.bindSocket(socket) }
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket.timeToLive = 3
            val target = InetAddress.getByName(SSDP_GROUP)
            val interfaces = findActivePrivateMulticastInterfaces()
            requests.forEach { searchTarget ->
                val message = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_GROUP:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $searchTarget\r\n\r\n"
                val bytes = message.toByteArray(Charsets.US_ASCII)
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, SSDP_PORT)) }
                interfaces.take(3).forEach { iface ->
                    runCatching {
                        socket.networkInterface = iface
                        socket.send(DatagramPacket(bytes, bytes.size, target, SSDP_PORT))
                    }
                }
            }
            receiveUntilQuiet(socket, SSDP_WINDOW_MS) { payload, source ->
                val headers = parseHeaders(payload)
                val location = headers["location"]
                val usn = headers["usn"].orEmpty()
                val st = headers["st"].orEmpty()
                val server = headers["server"].orEmpty()
                val locationUri = location?.let { runCatching { URI(it) }.getOrNull() }
                val host = source.hostAddress ?: return@receiveUntilQuiet
                // Never trust a LOCATION host that differs from the actual LAN packet sender.
                val description = locationUri?.takeIf {
                    (it.scheme == "http" || it.scheme == "https") &&
                        isSameDiscoveryHost(it.host, host)
                }?.toString()
                val identity = host.lowercase(Locale.ROOT)
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
                        castProtocols = protocols
                    )
                )
            }
        }
    }

    private fun discoverWsDiscovery(found: MutableMap<String, MutableDevice>, network: Network?) {
        val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
<e:Header><w:MessageID>urn:uuid:malekplay-discovery</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe/></e:Body></e:Envelope>"""
        MulticastSocket().use { socket ->
            if (network != null) runCatching { network.bindSocket(socket) }
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket.timeToLive = 2
            val bytes = probe.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_GROUP), WSD_PORT))
            receiveUntilQuiet(socket, WSD_WINDOW_MS) { payload, source ->
                val xml = payload.take(MAX_RESPONSE_CHARS)
                if (!xml.contains("ProbeMatch", true)) return@receiveUntilQuiet
                val xaddr = XML_XADDR.find(xml)?.groupValues?.getOrNull(1)?.trim()
                val uri = xaddr?.split(Regex("\\s+"))?.firstOrNull()?.let { runCatching { URI(it) }.getOrNull() }
                val host = source.hostAddress ?: return@receiveUntilQuiet
                // Accept service endpoints only when they point back to the responding LAN host.
                val safeXAddr = uri?.takeIf {
                    (it.scheme == "http" || it.scheme == "https") &&
                        it.host.equals(host, ignoreCase = true)
                }?.toString()
                val types = XML_TYPES.find(xml)?.groupValues?.getOrNull(1).orEmpty()
                val scopes = XML_SCOPES.find(xml)?.groupValues?.getOrNull(1).orEmpty()
                val computer = classifyWsDiscoveryDevice(types, scopes) == NetworkDeviceKind.WINDOWS_COMPUTER
                val identity = host.lowercase(Locale.ROOT)
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
        }
    }

    private fun findActivePrivateMulticastInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface ->
                iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                    iface.inetAddresses.toList().any { addr ->
                        addr is Inet4Address && !addr.isLoopbackAddress && isPrivateLanHost(addr.hostAddress.orEmpty())
                    }
            }
            .sortedBy { iface ->
                val name = iface.name.lowercase(Locale.ROOT)
                when {
                    name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
                    name.startsWith("eth") || name.startsWith("p2p") -> 1
                    else -> 2
                }
            }
    }.getOrDefault(emptyList())

    private fun receiveUntilQuiet(
        socket: MulticastSocket,
        windowMs: Long,
        onPacket: (String, InetAddress) -> Unit
    ) {
        val started = System.nanoTime()
        var lastPacket = started
        val buffer = ByteArray(8192)
        while ((System.nanoTime() - started) / 1_000_000 < windowMs &&
            (System.nanoTime() - lastPacket) / 1_000_000 < QUIET_WINDOW_MS
        ) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                lastPacket = System.nanoTime()
                val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                runCatching { onPacket(payload, packet.address) }
            } catch (_: java.net.SocketTimeoutException) {
                // Keep listening until the full discovery window expires; SSDP MX responses may be delayed.
                continue
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
        val existing = found[key]
        if (existing == null) found[key] = device
        else {
            existing.services.addAll(device.services)
            existing.castProtocols.addAll(device.castProtocols)
            if (existing.descriptionUrl == null) existing.descriptionUrl = device.descriptionUrl
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
                // Smart TVs often expose both MediaServer and MediaRenderer; retain MediaRenderer capability in services & protocols.
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
        val host: String,
        var kind: NetworkDeviceKind,
        val services: MutableSet<String>,
        var descriptionUrl: String?,
        var manufacturer: String? = null,
        var modelName: String? = null,
        val castProtocols: MutableSet<TvCastProtocol> = mutableSetOf()
    ) {
        fun toPublic() = DiscoveredNetworkDevice(
            id = "$host:${kind.name}:${services.sorted().joinToString()}",
            name = name,
            host = host,
            kind = kind,
            services = services.toSet(),
            descriptionUrl = descriptionUrl,
            manufacturer = manufacturer,
            modelName = modelName,
            castProtocols = castProtocols.toSet()
        )
    }

    private companion object {
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val WSD_PORT = 3702
        const val RECEIVE_TIMEOUT_MS = 650
        const val SSDP_WINDOW_MS = 3_400L
        const val WSD_WINDOW_MS = 2_400L
        const val QUIET_WINDOW_MS = 2_100L
        const val ENRICH_TOTAL_TIMEOUT_MS = 1_800L
        const val MAX_RESPONSE_CHARS = 16_384
        val XML_XADDR = Regex("<(?:[\\w.-]+:)?XAddrs[^>]*>(.*?)</(?:[\\w.-]+:)?XAddrs>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val XML_TYPES = Regex("<(?:[\\w.-]+:)?Types[^>]*>(.*?)</(?:[\\w.-]+:)?Types>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val XML_SCOPES = Regex("<(?:[\\w.-]+:)?Scopes[^>]*>(.*?)</(?:[\\w.-]+:)?Scopes>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
