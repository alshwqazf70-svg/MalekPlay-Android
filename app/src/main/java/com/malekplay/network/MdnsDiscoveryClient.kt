package com.malekplay.network

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Locale

internal fun classifyMdnsService(service: String): NetworkDeviceKind = when {
    service.contains("_smb._tcp", true) -> NetworkDeviceKind.NETWORK_DEVICE
    service.contains("_workstation._tcp", true) ||
        service.contains("_afpovertcp._tcp", true) ||
        service.contains("_sftp-ssh._tcp", true) -> NetworkDeviceKind.WINDOWS_COMPUTER
    service.contains("_googlecast._tcp", true) ||
        service.contains("_androidtvremote2._tcp", true) ||
        service.contains("_mediaremotetv._tcp", true) ||
        service.contains("_airplay._tcp", true) ||
        service.contains("_raop._tcp", true) ||
        service.contains("_samsungmsf._tcp", true) ||
        service.contains("_lge-smarttv._tcp", true) ||
        service.contains("_roku._tcp", true) ||
        service.contains("_roku-ecp._tcp", true) ||
        service.contains("_dial._tcp", true) ||
        service.contains("_dlna._tcp", true) -> NetworkDeviceKind.MEDIA_RENDERER
    else -> NetworkDeviceKind.NETWORK_DEVICE
}

internal fun protocolsForMdnsService(service: String): Set<TvCastProtocol> = when {
    service.contains("_googlecast._tcp", true) ||
        service.contains("_androidtvremote2._tcp", true) -> setOf(TvCastProtocol.CHROMECAST)
    service.contains("_airplay._tcp", true) ||
        service.contains("_raop._tcp", true) -> setOf(TvCastProtocol.AIRPLAY)
    service.contains("_roku._tcp", true) ||
        service.contains("_roku-ecp._tcp", true) -> setOf(TvCastProtocol.ROKU_ECP)
    service.contains("_dial._tcp", true) -> setOf(TvCastProtocol.DIAL)
    service.contains("_dlna._tcp", true) ||
        service.contains("_samsungmsf._tcp", true) ||
        service.contains("_lge-smarttv._tcp", true) -> setOf(TvCastProtocol.DLNA)
    else -> emptySet()
}

/** Discovers devices advertising common TV, phone and file-sharing services over mDNS/DNS-SD. */
internal class MdnsDiscoveryClient {
    suspend fun discover(
        network: Network?,
        interfaces: List<NetworkInterface> = findMulticastInterfaces()
    ): List<DiscoveredNetworkDevice> = withContext(Dispatchers.IO) {
        val instances = LinkedHashMap<String, String>()
        val servers = LinkedHashMap<String, Pair<String, Int>>()
        val ipv4Addresses = LinkedHashMap<String, String>()
        val ipv6Addresses = LinkedHashMap<String, String>()
        val fallbackHosts = LinkedHashMap<String, String>()
        val friendlyNames = LinkedHashMap<String, String>()
        val modelNames = LinkedHashMap<String, String>()
        val supportedServices = SERVICE_TYPES.toSet()

        val joinedInterfaces = ArrayList<NetworkInterface>()
        MulticastSocket(null).use { socket ->
            if (network != null) runCatching { network.bindSocket(socket) }
            socket.reuseAddress = true
            try {
                socket.bind(InetSocketAddress(MDNS_PORT))
            } catch (_: Exception) {
                socket.bind(InetSocketAddress(0))
            }
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket.timeToLive = 1
            val groupV4 = InetAddress.getByName(MDNS_GROUP_V4)
            val groupV6 = runCatching { InetAddress.getByName(MDNS_GROUP_V6) }.getOrNull()

            try {
                if (interfaces.isNotEmpty()) {
                    interfaces.take(4).forEach { iface ->
                        val joinedV4 = runCatching {
                            socket.joinGroup(InetSocketAddress(groupV4, MDNS_PORT), iface)
                        }.isSuccess
                        if (joinedV4) joinedInterfaces += iface
                        if (groupV6 != null && iface.inetAddresses.toList().any { it is Inet6Address }) {
                            runCatching { socket.joinGroup(InetSocketAddress(groupV6, MDNS_PORT), iface) }
                        }
                    }
                }
                if (joinedInterfaces.isEmpty()) {
                    runCatching { socket.joinGroup(groupV4) }
                }

                SERVICE_TYPES.forEach { service ->
                    if (!currentCoroutineContext().isActive) return@forEach
                    val query = buildPtrQuery(service)
                    if (joinedInterfaces.isNotEmpty()) {
                        joinedInterfaces.forEach { iface ->
                            runCatching {
                                socket.networkInterface = iface
                                socket.send(DatagramPacket(query, query.size, groupV4, MDNS_PORT))
                            }
                        }
                    } else {
                        runCatching { socket.send(DatagramPacket(query, query.size, groupV4, MDNS_PORT)) }
                    }
                }

                val deadline = System.nanoTime() + DISCOVERY_WINDOW_MS * 1_000_000L
                val buffer = ByteArray(MAX_PACKET_BYTES)
                while (currentCoroutineContext().isActive && System.nanoTime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val packetAddr = packet.address
                    if (!LanNetworkSupport.isAllowedLanPeerAddress(packetAddr)) continue
                    val sourceHost = normalizeHostAddress(packetAddr) ?: continue
                    val parsed = runCatching {
                        parseRecords(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                    }.getOrNull() ?: continue
                    parsed.forEach { record ->
                        when (record.type) {
                            TYPE_PTR -> record.target?.let { target ->
                                val service = record.name.lowercase(Locale.ROOT)
                                if (service in supportedServices) {
                                    instances[target.lowercase(Locale.ROOT)] = service
                                    fallbackHosts[target.lowercase(Locale.ROOT)] = sourceHost
                                }
                            }
                            TYPE_SRV -> record.target?.let { target ->
                                servers[record.name.lowercase(Locale.ROOT)] = target.lowercase(Locale.ROOT) to record.port
                                fallbackHosts[record.name.lowercase(Locale.ROOT)] = sourceHost
                            }
                            TYPE_A -> record.address?.let { addr ->
                                ipv4Addresses[record.name.lowercase(Locale.ROOT)] = addr
                            }
                            TYPE_AAAA -> record.address?.let { addr ->
                                ipv6Addresses[record.name.lowercase(Locale.ROOT)] = addr
                            }
                            TYPE_TXT -> {
                                record.text?.let { friendlyNames[record.name.lowercase(Locale.ROOT)] = it }
                                record.model?.let { modelNames[record.name.lowercase(Locale.ROOT)] = it }
                            }
                        }
                    }
                }
            } finally {
                joinedInterfaces.forEach { iface ->
                    runCatching { socket.leaveGroup(InetSocketAddress(groupV4, MDNS_PORT), iface) }
                    if (groupV6 != null) {
                        runCatching { socket.leaveGroup(InetSocketAddress(groupV6, MDNS_PORT), iface) }
                    }
                }
                if (joinedInterfaces.isEmpty()) {
                    runCatching { socket.leaveGroup(groupV4) }
                }
            }
        }

        instances.mapNotNull { (instance, service) ->
            val server = servers[instance]
            val hostName = server?.first
            val host = hostName?.let { ipv4Addresses[it] ?: ipv6Addresses[it] }
                ?: fallbackHosts[instance]
                ?: return@mapNotNull null
            val kind = classifyMdnsService(service)
            val serviceLabel = service.removeSuffix(".local")
            val rawLabel = friendlyNames[instance]
                ?: instance.substringBefore("." + service.removeSuffix(".local"))
                    .replace("\\032", " ")
            val label = rawLabel.filterNot { it.isISOControl() }.trim().take(70).ifBlank { "جهاز شبكة" }
            val model = modelNames[instance]?.filterNot { it.isISOControl() }?.trim()?.take(60)
            DiscoveredNetworkDevice(
                id = "$host:MDNS:$serviceLabel",
                name = label,
                host = host,
                kind = kind,
                services = listOfNotNull(
                    "mDNS/DNS-SD",
                    serviceLabel,
                    server?.second?.takeIf { it in 1..65535 }?.let { "Port " + it }
                ).toSet(),
                modelName = model,
                castProtocols = protocolsForMdnsService(service),
                playbackVerified = false
            )
        }.distinctBy { it.host + ":" + it.services.elementAtOrNull(1) }
    }

    private fun buildPtrQuery(service: String): ByteArray {
        val out = ArrayList<Byte>()
        fun put(value: Int) { out.add(value.toByte()) }
        repeat(2) { put(0) }
        put(0); put(0)
        put(0); put(1)
        repeat(6) { put(0) }
        service.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            put(bytes.size)
            bytes.forEach { out.add(it) }
        }
        put(0)
        put(0); put(TYPE_PTR)
        put(0); put(1)
        return out.toByteArray()
    }

    internal fun parseRecords(data: ByteArray): List<DnsRecord> {
        require(data.size in 12..MAX_PACKET_BYTES)
        fun u16(offset: Int): Int {
            require(offset >= 0 && offset + 1 < data.size)
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }
        val questions = u16(4)
        val recordCount = u16(6) + u16(8) + u16(10)
        require(recordCount <= MAX_RECORDS && questions <= MAX_QUESTIONS)
        var offset = 12
        repeat(questions) {
            offset = decodeName(data, offset).second
            offset += 4
            require(offset <= data.size)
        }
        val result = ArrayList<DnsRecord>()
        repeat(recordCount) {
            val (name, afterName) = decodeName(data, offset)
            offset = afterName
            val type = u16(offset)
            val length = u16(offset + 8)
            val rdata = offset + 10
            require(rdata + length <= data.size)
            var target: String? = null
            var address: String? = null
            var port = 0
            var text: String? = null
            var model: String? = null
            when (type) {
                TYPE_PTR -> {
                    val decoded = decodeName(data, rdata)
                    require(decoded.second <= rdata + length)
                    target = decoded.first
                }
                TYPE_SRV -> if (length >= 7) {
                    port = u16(rdata + 4)
                    val decoded = decodeName(data, rdata + 6)
                    require(decoded.second <= rdata + length)
                    target = decoded.first
                }
                TYPE_A -> if (length == 4) {
                    val parsed = InetAddress.getByAddress(data.copyOfRange(rdata, rdata + 4))
                    if (LanNetworkSupport.isAllowedLanPeerAddress(parsed)) {
                        address = normalizeHostAddress(parsed)
                    }
                }
                TYPE_AAAA -> if (length == 16) {
                    val parsed = InetAddress.getByAddress(data.copyOfRange(rdata, rdata + 16))
                    if (LanNetworkSupport.isAllowedLanPeerAddress(parsed)) {
                        address = normalizeHostAddress(parsed)
                    }
                }
                TYPE_TXT -> {
                    var cursor = rdata
                    val end = rdata + length
                    while (cursor < end) {
                        val partLength = data[cursor++].toInt() and 0xFF
                        if (cursor + partLength > end) break
                        val part = String(data, cursor, partLength, Charsets.UTF_8)
                        when {
                            (part.startsWith("fn=") || part.startsWith("name=") || part.startsWith("n=")) && text == null ->
                                text = part.substringAfter('=').take(70)
                            (part.startsWith("md=") || part.startsWith("model=")) && model == null ->
                                model = part.substringAfter('=').take(60)
                        }
                        cursor += partLength
                    }
                }
            }
            result += DnsRecord(name.lowercase(Locale.ROOT), type, target, address, port, text, model)
            offset = rdata + length
        }
        return result
    }

    private fun decodeName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var cursor = start
        var nextOffset = -1
        var jumps = 0
        while (true) {
            require(cursor < data.size && jumps <= MAX_POINTER_JUMPS)
            val length = data[cursor].toInt() and 0xFF
            if (length == 0) {
                if (nextOffset < 0) nextOffset = cursor + 1
                break
            }
            if ((length and 0xC0) == 0xC0) {
                require(cursor + 1 < data.size)
                val pointer = ((length and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                require(pointer < data.size)
                if (nextOffset < 0) nextOffset = cursor + 2
                cursor = pointer
                jumps++
            } else {
                require(length <= 63 && cursor + 1 + length <= data.size)
                labels += String(data, cursor + 1, length, Charsets.UTF_8)
                cursor += length + 1
            }
        }
        return labels.joinToString(".") to nextOffset
    }

    internal data class DnsRecord(
        val name: String,
        val type: Int,
        val target: String? = null,
        val address: String? = null,
        val port: Int = 0,
        val text: String? = null,
        val model: String? = null
    )

    internal companion object {
        const val MDNS_GROUP_V4 = "224.0.0.251"
        const val MDNS_GROUP_V6 = "ff02::fb"
        const val MDNS_PORT = 5353
        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_AAAA = 28
        const val TYPE_SRV = 33
        const val RECEIVE_TIMEOUT_MS = 300
        const val DISCOVERY_WINDOW_MS = 2_600L
        const val MAX_PACKET_BYTES = 9_000
        const val MAX_RECORDS = 512
        const val MAX_QUESTIONS = 64
        const val MAX_POINTER_JUMPS = 12
        val SERVICE_TYPES = listOf(
            "_googlecast._tcp.local",
            "_androidtvremote2._tcp.local",
            "_mediaremotetv._tcp.local",
            "_airplay._tcp.local",
            "_raop._tcp.local",
            "_roku._tcp.local",
            "_roku-ecp._tcp.local",
            "_dial._tcp.local",
            "_dlna._tcp.local",
            "_samsungmsf._tcp.local",
            "_lge-smarttv._tcp.local",
            "_companion-link._tcp.local",
            "_http._tcp.local",
            "_webdav._tcp.local",
            "_smb._tcp.local",
            "_workstation._tcp.local",
            "_sftp-ssh._tcp.local",
            "_afpovertcp._tcp.local"
        )

        private fun normalizeHostAddress(addr: InetAddress?): String? {
            val raw = addr?.hostAddress ?: return null
            return raw.substringBefore('%').takeIf { it.isNotBlank() }
        }

        internal fun findMulticastInterfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface ->
                    iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                        iface.inetAddresses.toList().any { addr ->
                            !addr.isLoopbackAddress && LanNetworkSupport.isAllowedLanPeerAddress(addr)
                        }
                }
                .sortedBy { iface ->
                    val name = iface.name.lowercase(Locale.ROOT)
                    when {
                        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
                        name.startsWith("eth") || name.startsWith("p2p") || name.startsWith("rndis") -> 1
                        else -> 2
                    }
                }
        }.getOrDefault(emptyList())
    }
}
