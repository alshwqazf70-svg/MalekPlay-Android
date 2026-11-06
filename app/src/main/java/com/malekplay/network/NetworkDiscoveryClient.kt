package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URI
import java.util.Locale

internal fun isSameDiscoveryHost(endpointHost: String?, packetHost: String): Boolean =
    !endpointHost.isNullOrBlank() && endpointHost.equals(packetHost, ignoreCase = true)

internal fun classifySsdpDevice(st: String, usn: String): NetworkDeviceKind = when {
    st.contains("mediaserver", true) || usn.contains("mediaserver", true) ->
        NetworkDeviceKind.MEDIA_SERVER
    st.contains("mediarenderer", true) || usn.contains("mediarenderer", true) ->
        NetworkDeviceKind.MEDIA_RENDERER
    else -> NetworkDeviceKind.NETWORK_DEVICE
}

internal fun classifyWsDiscoveryDevice(types: String, scopes: String): NetworkDeviceKind {
    val advertised = "$types $scopes"
    return if (advertised.contains("computer", true) || advertised.contains("windows", true)) {
        NetworkDeviceKind.WINDOWS_COMPUTER
    } else NetworkDeviceKind.NETWORK_DEVICE
}

/** A service advertised on the current Wi-Fi LAN. It is a discovery hint, not proof of file access. */
data class DiscoveredNetworkDevice(
    val id: String,
    val name: String,
    val host: String,
    val kind: NetworkDeviceKind,
    val services: Set<String>,
    val descriptionUrl: String? = null
)

enum class NetworkDeviceKind { MEDIA_SERVER, MEDIA_RENDERER, WINDOWS_COMPUTER, NETWORK_DEVICE }

/**
 * Passive-scope LAN discovery using standard multicast advertisements:
 * SSDP/UPnP for media devices, WS-Discovery for Windows, and mDNS/DNS-SD for
 * common TV, Apple-device and SMB-sharing advertisements.
 *
 * No subnet-wide port scan is performed. Only devices answering the multicast
 * discovery requests are returned.
 */
class NetworkDiscoveryClient {
    suspend fun discover(context: Context): List<DiscoveredNetworkDevice> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiNetwork = connectivity.allNetworks.firstOrNull { candidate ->
                connectivity.getNetworkCapabilities(candidate)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: throw IllegalStateException("اتصل بشبكة Wi-Fi أولاً لاكتشاف الأجهزة المحلية.")

            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("MalekPlay:LAN-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                // Run independent multicast protocols together to keep the initial scan responsive.
                val (ssdpDevices, wsDiscoveryDevices, mdnsDevices) = coroutineScope {
                    val ssdp = async(Dispatchers.IO) {
                        collectProtocolResults { results -> discoverSsdp(results, wifiNetwork) }
                    }
                    val wsDiscovery = async(Dispatchers.IO) {
                        collectProtocolResults { results -> discoverWsDiscovery(results, wifiNetwork) }
                    }
                    val mdns = async(Dispatchers.IO) {
                        try {
                            MdnsDiscoveryClient().discover(wifiNetwork)
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
                        MutableDevice(device.name, device.host, device.kind, device.services.toMutableSet(), device.descriptionUrl)
                    )
                }
                found.values
                    .filter { it.host.isNotBlank() }
                    .map { it.toPublic() }
                    .sortedWith(compareBy<DiscoveredNetworkDevice> { it.kind.ordinal }.thenBy { it.name.lowercase(Locale.ROOT) })
            } finally {
                if (lock.isHeld) lock.release()
            }
        }

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

    private fun discoverSsdp(found: MutableMap<String, MutableDevice>, network: Network) {
        val requests = listOf("ssdp:all", "urn:schemas-upnp-org:device:MediaServer:1", "urn:schemas-upnp-org:device:MediaRenderer:1")
        MulticastSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket.timeToLive = 2
            val target = InetAddress.getByName(SSDP_GROUP)
            requests.forEach { searchTarget ->
                val message = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_GROUP:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $searchTarget\r\n\r\n"
                val bytes = message.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(bytes, bytes.size, target, SSDP_PORT))
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
                val label = when (kind) {
                    NetworkDeviceKind.MEDIA_SERVER -> "خادم وسائط UPnP"
                    NetworkDeviceKind.MEDIA_RENDERER -> "مشغل وسائط UPnP"
                    else -> server.take(70).ifBlank { "جهاز UPnP" }
                }
                merge(found, identity, MutableDevice(label, host, kind, mutableSetOf("SSDP/UPnP", st).filter { it.isNotBlank() }.toMutableSet(), description))
            }
        }
    }

    private fun discoverWsDiscovery(found: MutableMap<String, MutableDevice>, network: Network) {
        val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
<e:Header><w:MessageID>urn:uuid:malekplay-discovery</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe/></e:Body></e:Envelope>"""
        MulticastSocket().use { socket ->
            network.bindSocket(socket)
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
                merge(found, identity, MutableDevice(label, host,
                    if (computer) NetworkDeviceKind.WINDOWS_COMPUTER else NetworkDeviceKind.NETWORK_DEVICE,
                    mutableSetOf("WS-Discovery"), safeXAddr))
            }
        }
    }

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
            if (existing.descriptionUrl == null) existing.descriptionUrl = device.descriptionUrl
            if (existing.kind == NetworkDeviceKind.NETWORK_DEVICE && device.kind != NetworkDeviceKind.NETWORK_DEVICE) {
                existing.kind = device.kind
                existing.name = device.name
            }
        }
    }

    private data class MutableDevice(
        var name: String,
        val host: String,
        var kind: NetworkDeviceKind,
        val services: MutableSet<String>,
        var descriptionUrl: String?
    ) {
        fun toPublic() = DiscoveredNetworkDevice(
            id = "$host:${kind.name}:${services.sorted().joinToString()}",
            name = name,
            host = host,
            kind = kind,
            services = services.toSet(),
            descriptionUrl = descriptionUrl
        )
    }

    private companion object {
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val WSD_PORT = 3702
        const val RECEIVE_TIMEOUT_MS = 700
        const val SSDP_WINDOW_MS = 3_500L
        const val WSD_WINDOW_MS = 2_500L
        const val QUIET_WINDOW_MS = 2_200L
        const val MAX_RESPONSE_CHARS = 16_384
        val XML_XADDR = Regex("<(?:[\\w.-]+:)?XAddrs[^>]*>(.*?)</(?:[\\w.-]+:)?XAddrs>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val XML_TYPES = Regex("<(?:[\\w.-]+:)?Types[^>]*>(.*?)</(?:[\\w.-]+:)?Types>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val XML_SCOPES = Regex("<(?:[\\w.-]+:)?Scopes[^>]*>(.*?)</(?:[\\w.-]+:)?Scopes>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val XML_EPR = Regex("<(?:[\\w.-]+:)?Address[^>]*>(.*?)</(?:[\\w.-]+:)?Address>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
