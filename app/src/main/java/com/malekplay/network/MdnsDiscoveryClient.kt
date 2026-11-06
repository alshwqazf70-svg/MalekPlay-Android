package com.malekplay.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Network
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Locale

internal fun classifyMdnsService(service: String): NetworkDeviceKind = when {
    service.contains("_smb._tcp", true) -> NetworkDeviceKind.NETWORK_DEVICE
    service.contains("_googlecast._tcp", true) ||
        service.contains("_androidtvremote2._tcp", true) ||
        service.contains("_mediaremotetv._tcp", true) ||
        service.contains("_airplay._tcp", true) ||
        service.contains("_raop._tcp", true) -> NetworkDeviceKind.MEDIA_RENDERER
    else -> NetworkDeviceKind.NETWORK_DEVICE
}

/** Discovers devices advertising common TV, phone and file-sharing services over mDNS/DNS-SD. */
internal class MdnsDiscoveryClient {
    suspend fun discover(network: Network): List<DiscoveredNetworkDevice> = withContext(Dispatchers.IO) {
        val instances = LinkedHashMap<String, String>()
        val servers = LinkedHashMap<String, Pair<String, Int>>()
        val addresses = LinkedHashMap<String, String>()
        val fallbackHosts = LinkedHashMap<String, String>()
        val friendlyNames = LinkedHashMap<String, String>()
        val supportedServices = SERVICE_TYPES.toSet()
        MulticastSocket(null).use { socket ->
            network.bindSocket(socket)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(MDNS_PORT))
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket.timeToLive = 1
            val group = InetAddress.getByName(MDNS_GROUP)
            socket.joinGroup(group)
            SERVICE_TYPES.forEach { service ->
                val query = buildPtrQuery(service)
                socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
            }
            val deadline = System.nanoTime() + DISCOVERY_WINDOW_MS * 1_000_000L
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (System.nanoTime() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val sourceHost = packet.address.hostAddress ?: continue
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
                        TYPE_A -> record.address?.let { addresses[record.name.lowercase(Locale.ROOT)] = it }
                        TYPE_TXT -> record.text?.let { friendlyNames[record.name.lowercase(Locale.ROOT)] = it }
                    }
                }
            }
        }

        instances.mapNotNull { (instance, service) ->
            val server = servers[instance]
            val hostName = server?.first
            val host = hostName?.let { addresses[it] } ?: fallbackHosts[instance] ?: return@mapNotNull null
            val kind = classifyMdnsService(service)
            val serviceLabel = service.removeSuffix(".local")
            val rawLabel = friendlyNames[instance]
                ?: instance.substringBefore("." + service.removeSuffix(".local"))
                    .replace("\\032", " ")
            val label = rawLabel.filterNot { it.isISOControl() }.trim().take(70).ifBlank { "جهاز شبكة" }
            DiscoveredNetworkDevice(
                id = "$host:MDNS:$serviceLabel",
                name = label,
                host = host,
                kind = kind,
                services = listOfNotNull(
                    "mDNS/DNS-SD",
                    serviceLabel,
                    server?.second?.takeIf { it in 1..65535 }?.let { "Port " + it }
                ).toSet()
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

    private fun parseRecords(data: ByteArray): List<DnsRecord> {
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
                    address = InetAddress.getByAddress(data.copyOfRange(rdata, rdata + 4)).hostAddress
                }
                TYPE_TXT -> {
                    var cursor = rdata
                    val end = rdata + length
                    while (cursor < end) {
                        val partLength = data[cursor++].toInt() and 0xFF
                        if (cursor + partLength > end) break
                        val part = String(data, cursor, partLength, Charsets.UTF_8)
                        if (part.startsWith("fn=") || part.startsWith("name=")) {
                            text = part.substringAfter('=').take(70)
                            break
                        }
                        cursor += partLength
                    }
                }
            }
            result += DnsRecord(name.lowercase(Locale.ROOT), type, target, address, port, text)
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

    private data class DnsRecord(
        val name: String,
        val type: Int,
        val target: String? = null,
        val address: String? = null,
        val port: Int = 0,
        val text: String? = null
    )

    private companion object {
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_SRV = 33
        const val RECEIVE_TIMEOUT_MS = 350
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
            "_companion-link._tcp.local",
            "_smb._tcp.local"
        )
    }
}
