package com.malekplay.network

import android.net.Network
import kotlinx.coroutines.CancellationException
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

/**
 * Discovers devices advertising TV, phone, and file-sharing services over mDNS/DNS-SD
 * across both IPv4 (`224.0.0.251`) and IPv6 (`ff02::fb`) multicast on all active LAN interfaces.
 */
internal class MdnsDiscoveryClient(
    private val socketFactory: () -> MulticastSocket = { MulticastSocket(null) }
) {
    private data class RecordedAddress(
        val host: String,
        val isIpv4: Boolean,
        val isLinkLocal: Boolean
    )

    suspend fun discover(
        network: Network?,
        interfaces: List<NetworkInterface> = findMulticastInterfaces(),
        onSocketOpened: ((MulticastSocket) -> Unit)? = null
    ): List<DiscoveredNetworkDevice> = withContext(Dispatchers.IO) {
        val rawRecords = ArrayList<Pair<DnsRecord, InetAddress>>()
        val interfacesByZone = interfaces.associateBy { it.name.lowercase(Locale.ROOT) }
        val joinedV4Interfaces = ArrayList<NetworkInterface>()
        val joinedV6Interfaces = ArrayList<NetworkInterface>()
        var joinedLegacyV4Group = false
        var joinedLegacyV6Group = false

        val socket = socketFactory()
        try {
            onSocketOpened?.invoke(socket)
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

            if (interfaces.isNotEmpty()) {
                interfaces.take(4).forEach { iface ->
                    val addrs = runCatching { iface.inetAddresses.toList() }.getOrDefault(emptyList())
                    val hasV4 = addrs.any { it is Inet4Address && !it.isLoopbackAddress }
                    val hasV6 = addrs.any { it is Inet6Address && !it.isLoopbackAddress }
                    if (hasV4) {
                        val ok = runCatching {
                            socket.joinGroup(InetSocketAddress(groupV4, MDNS_PORT), iface)
                        }.isSuccess
                        if (ok) joinedV4Interfaces += iface
                    }
                    if (hasV6 && groupV6 != null) {
                        val ok = runCatching {
                            socket.joinGroup(InetSocketAddress(groupV6, MDNS_PORT), iface)
                        }.isSuccess
                        if (ok) joinedV6Interfaces += iface
                    }
                }
            }
            if (joinedV4Interfaces.isEmpty()) {
                joinedLegacyV4Group = runCatching { socket.joinGroup(groupV4) }.isSuccess
            }
            if (joinedV6Interfaces.isEmpty() && groupV6 != null) {
                joinedLegacyV6Group = runCatching { socket.joinGroup(groupV6) }.isSuccess
            }

            SERVICE_TYPES.forEach { service ->
                if (!currentCoroutineContext().isActive) return@forEach
                val query = buildPtrQuery(service)
                if (joinedV4Interfaces.isNotEmpty()) {
                    joinedV4Interfaces.forEach { iface ->
                        runCatching {
                            socket.networkInterface = iface
                            socket.send(DatagramPacket(query, query.size, groupV4, MDNS_PORT))
                        }
                    }
                } else if (joinedLegacyV4Group) {
                    runCatching { socket.send(DatagramPacket(query, query.size, groupV4, MDNS_PORT)) }
                }

                if (groupV6 != null) {
                    if (joinedV6Interfaces.isNotEmpty()) {
                        joinedV6Interfaces.forEach { iface ->
                            runCatching {
                                socket.networkInterface = iface
                                val scopedV6 = runCatching {
                                    Inet6Address.getByAddress(null, groupV6.address, iface)
                                }.getOrElse { groupV6 }
                                socket.send(DatagramPacket(query, query.size, scopedV6, MDNS_PORT))
                            }
                        }
                    } else if (joinedLegacyV6Group) {
                        runCatching { socket.send(DatagramPacket(query, query.size, groupV6, MDNS_PORT)) }
                    }
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
                } catch (_: Exception) {
                    // Socket closed externally (e.g., network loss or discovery cancellation).
                    break
                }
                val packetAddr = packet.address ?: continue
                if (!LanNetworkSupport.isAllowedLanPeerAddress(packetAddr)) continue

                val packetInterface = resolvePacketInterface(packetAddr, interfacesByZone)
                val parsed = runCatching {
                    parseRecords(
                        data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                        sourceInterface = packetInterface
                    )
                }.getOrNull() ?: continue
                parsed.forEach { record -> rawRecords += record to packetAddr }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            val groupV4 = runCatching { InetAddress.getByName(MDNS_GROUP_V4) }.getOrNull()
            val groupV6 = runCatching { InetAddress.getByName(MDNS_GROUP_V6) }.getOrNull()
            if (groupV4 != null) {
                joinedV4Interfaces.forEach { iface ->
                    runCatching { socket.leaveGroup(InetSocketAddress(groupV4, MDNS_PORT), iface) }
                }
                if (joinedLegacyV4Group) runCatching { socket.leaveGroup(groupV4) }
            }
            if (groupV6 != null) {
                joinedV6Interfaces.forEach { iface ->
                    runCatching { socket.leaveGroup(InetSocketAddress(groupV6, MDNS_PORT), iface) }
                }
                if (joinedLegacyV6Group) runCatching { socket.leaveGroup(groupV6) }
            }
            runCatching { socket.close() }
        }

        buildDevicesFromRecords(rawRecords, interfacesByZone)
    }

    internal fun buildDevicesFromRecords(
        rawRecords: List<Pair<DnsRecord, InetAddress>>,
        interfacesByZone: Map<String, NetworkInterface> = emptyMap()
    ): List<DiscoveredNetworkDevice> {
        val instances = LinkedHashMap<String, String>()
        val servers = LinkedHashMap<String, Pair<String, Int>>()
        val addressesByHost = LinkedHashMap<String, LinkedHashSet<RecordedAddress>>()
        val fallbackHostsByKey = LinkedHashMap<String, LinkedHashSet<RecordedAddress>>()
        val friendlyNames = LinkedHashMap<String, String>()
        val modelNames = LinkedHashMap<String, String>()
        val supportedServices = SERVICE_TYPES.toSet()

        fun addFallback(key: String, packetAddr: InetAddress) {
            val formatted = LanNetworkSupport.formatHostPreservingScope(
                packetAddr,
                resolvePacketInterface(packetAddr, interfacesByZone)
            ) ?: return
            fallbackHostsByKey.getOrPut(key) { LinkedHashSet() }.add(
                RecordedAddress(
                    host = formatted,
                    isIpv4 = packetAddr is Inet4Address,
                    isLinkLocal = packetAddr.isLinkLocalAddress
                )
            )
        }

        rawRecords.forEach { (record, packetAddr) ->
            when (record.type) {
                TYPE_PTR -> record.target?.let { target ->
                    val service = record.name.lowercase(Locale.ROOT)
                    if (service in supportedServices) {
                        val instanceKey = target.lowercase(Locale.ROOT)
                        instances[instanceKey] = service
                        addFallback(instanceKey, packetAddr)
                    }
                }
                TYPE_SRV -> record.target?.let { target ->
                    val instanceKey = record.name.lowercase(Locale.ROOT)
                    servers[instanceKey] = target.lowercase(Locale.ROOT) to record.port
                    addFallback(instanceKey, packetAddr)
                }
                TYPE_A -> record.address?.let { addr ->
                    addressesByHost.getOrPut(record.name.lowercase(Locale.ROOT)) { LinkedHashSet() }
                        .add(RecordedAddress(host = addr, isIpv4 = true, isLinkLocal = false))
                }
                TYPE_AAAA -> record.address?.let { addr ->
                    val resolvedAddr = attachPacketScopeIfLinkLocal(addr, packetAddr, interfacesByZone)
                    val bare = LanNetworkSupport.stripZone(resolvedAddr)
                    val isLinkLocal = bare.lowercase(Locale.ROOT).startsWith("fe80:")
                    addressesByHost.getOrPut(record.name.lowercase(Locale.ROOT)) { LinkedHashSet() }
                        .add(RecordedAddress(host = resolvedAddr, isIpv4 = false, isLinkLocal = isLinkLocal))
                }
                TYPE_TXT -> {
                    val key = record.name.lowercase(Locale.ROOT)
                    record.text?.let { friendlyNames[key] = it }
                    record.model?.let { modelNames[key] = it }
                }
            }
        }

        val devices = instances.mapNotNull { (instance, service) ->
            val server = servers[instance]
            val targetHostname = server?.first
            val hostRecords = targetHostname?.let { addressesByHost[it] }.orEmpty()
            val fallbackRecords = fallbackHostsByKey[instance].orEmpty()
            val allCandidateRecords = (hostRecords + fallbackRecords).toList()
            val orderedHosts = selectPreferredAndAlternateHosts(allCandidateRecords)
            val primaryHost = orderedHosts.firstOrNull() ?: return@mapNotNull null
            val alternates = orderedHosts.drop(1).toSet()

            val kind = classifyMdnsService(service)
            val serviceLabel = service.removeSuffix(".local")
            val rawLabel = friendlyNames[instance]
                ?: instance.substringBefore("." + service.removeSuffix(".local"))
                    .replace("\\032", " ")
            val label = rawLabel.filterNot { it.isISOControl() }.trim().take(70).ifBlank { "جهاز شبكة" }
            val model = modelNames[instance]?.filterNot { it.isISOControl() }?.trim()?.take(60)
            DiscoveredNetworkDevice(
                id = "${LanNetworkSupport.stripZone(primaryHost).lowercase(Locale.ROOT)}:MDNS:$serviceLabel",
                name = label,
                host = primaryHost,
                kind = kind,
                services = listOfNotNull(
                    "mDNS/DNS-SD",
                    serviceLabel,
                    server?.second?.takeIf { it in 1..65535 }?.let { "Port $it" }
                ).toSet(),
                modelName = model,
                castProtocols = protocolsForMdnsService(service),
                playbackVerified = false,
                alternateHosts = alternates
            )
        }

        return deduplicateMultiAddressDevices(devices)
    }

    private fun selectPreferredAndAlternateHosts(records: List<RecordedAddress>): List<String> {
        // Prefer IPv4 for broad legacy TV HTTP compatibility, then ULA IPv6, then scoped link-local IPv6.
        return records
            .sortedWith(
                compareBy<RecordedAddress>(
                    { if (it.isIpv4) 0 else 1 },
                    { if (it.isLinkLocal) 1 else 0 }
                )
            )
            .map { it.host }
            .distinctBy { LanNetworkSupport.decodeScopedHost(it).lowercase(Locale.ROOT) }
    }

    private fun deduplicateMultiAddressDevices(
        devices: List<DiscoveredNetworkDevice>
    ): List<DiscoveredNetworkDevice> {
        val merged = LinkedHashMap<String, DiscoveredNetworkDevice>()
        for (device in devices) {
            val serviceTag = device.services.firstOrNull { it.startsWith("_") } ?: device.services.joinToString(",")
            val allHosts = (listOf(device.host) + device.alternateHosts)
                .map { LanNetworkSupport.stripZone(it).lowercase(Locale.ROOT) }
                .toSet()

            val existingKey = merged.entries.firstOrNull { (_, existing) ->
                val existingService = existing.services.firstOrNull { it.startsWith("_") } ?: existing.services.joinToString(",")
                if (!existingService.equals(serviceTag, ignoreCase = true)) return@firstOrNull false
                val existingHosts = (listOf(existing.host) + existing.alternateHosts)
                    .map { LanNetworkSupport.stripZone(it).lowercase(Locale.ROOT) }
                    .toSet()
                allHosts.any { it in existingHosts }
            }?.key

            if (existingKey == null) {
                merged[device.id] = device
            } else {
                val existing = merged.getValue(existingKey)
                val combinedHosts = (listOf(existing.host) + existing.alternateHosts + device.host + device.alternateHosts)
                    .distinctBy { LanNetworkSupport.decodeScopedHost(it).lowercase(Locale.ROOT) }
                val primary = combinedHosts.firstOrNull { !it.contains(':') } ?: combinedHosts.first()
                val alternates = combinedHosts.filterNot { it.equals(primary, ignoreCase = true) }.toSet()
                merged[existingKey] = existing.copy(
                    host = primary,
                    services = existing.services + device.services,
                    castProtocols = existing.castProtocols + device.castProtocols,
                    modelName = existing.modelName ?: device.modelName,
                    alternateHosts = alternates
                )
            }
        }
        return merged.values.toList()
    }

    private fun resolvePacketInterface(
        packetAddr: InetAddress,
        interfacesByZone: Map<String, NetworkInterface>
    ): NetworkInterface? {
        if (packetAddr is Inet6Address) {
            packetAddr.scopedInterface?.let { return it }
            val scopeId = packetAddr.scopeId
            if (scopeId > 0) {
                runCatching { NetworkInterface.getByIndex(scopeId) }.getOrNull()?.let { return it }
            }
            val zone = packetAddr.hostAddress?.substringAfter('%', "")
            if (!zone.isNullOrBlank()) {
                interfacesByZone[zone.lowercase(Locale.ROOT)]?.let { return it }
            }
        }
        return null
    }

    private fun attachPacketScopeIfLinkLocal(
        recordedAddress: String,
        packetAddr: InetAddress,
        interfacesByZone: Map<String, NetworkInterface>
    ): String {
        if ('%' in recordedAddress) return recordedAddress
        val bare = LanNetworkSupport.stripZone(recordedAddress)
        if (!bare.lowercase(Locale.ROOT).startsWith("fe80:")) return bare
        val fallbackIface = resolvePacketInterface(packetAddr, interfacesByZone)
        val packetZone = LanNetworkSupport.formatHostPreservingScope(packetAddr, fallbackIface)
            ?.substringAfter('%', "")
            ?.takeIf { it.isNotBlank() }
            ?: fallbackIface?.name
        return if (!packetZone.isNullOrBlank()) "$bare%$packetZone" else bare
    }

    internal fun buildPtrQuery(service: String): ByteArray {
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

    internal fun parseRecords(
        data: ByteArray,
        sourceInterface: NetworkInterface? = null
    ): List<DnsRecord> {
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
                        address = LanNetworkSupport.formatHostPreservingScope(parsed)
                    }
                }
                TYPE_AAAA -> if (length == 16) {
                    val rawAddr = data.copyOfRange(rdata, rdata + 16)
                    val parsed = if (sourceInterface != null) {
                        runCatching { Inet6Address.getByAddress(null, rawAddr, sourceInterface) }
                            .getOrElse { InetAddress.getByAddress(rawAddr) }
                    } else {
                        InetAddress.getByAddress(rawAddr)
                    }
                    if (LanNetworkSupport.isAllowedLanPeerAddress(parsed)) {
                        address = LanNetworkSupport.formatHostPreservingScope(parsed, sourceInterface)
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

        internal fun findMulticastInterfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface ->
                    iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                        LanNetworkSupport.isLanInterfaceName(iface.name) &&
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
