package com.malekplay.network

import android.content.Context
import android.os.Build
import java.net.Socket
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** LAN discovery with v2 candidate offers and a v1 compatibility path. */
object UltraShareDiscovery {
    const val DISCOVERY_PORT = 38742
    private const val PROBE_V1 = "MPULTRA_DISCOVER|1"
    private const val PROBE_V2 = "MPULTRA_DISCOVER|2"
    private const val OFFER_PREFIX_V1 = "MPULTRA_OFFER|1|"
    private const val RECEIVE_TIMEOUT_MS = 500
    private const val SCAN_WINDOW_MS = 4_000L
    private const val MAX_PACKET_BYTES = 2048

    data class Peer(
        val host: String,
        val port: Int,
        val name: String,
        val peerId: String? = null,
        val candidates: List<UltraShareNegotiation.Candidate> = emptyList()
    )

    /** Stable identity prevents one physical phone appearing once per network interface. */
    internal fun identityKey(peer: Peer): String =
        peer.peerId?.takeIf { it.isNotBlank() }?.let { "id:$it" } ?: "host:${peer.host}"

    suspend fun advertiseWhileReceiving(
        transferPort: Int = UltraShareBatchTransport.DEFAULT_PORT,
        legacyTransferPort: Int = UltraShareTransport.DEFAULT_PORT,
        context: Context? = null,
        displayName: String = Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android"
    ) = withContext(Dispatchers.IO) {
        require(transferPort in 1..65535)
        val peerId = context?.let(::stablePeerId) ?: UUID.randomUUID().toString()
        val safeDisplayName = displayName.trim().take(64).ifBlank { "MalekPlay Android" }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(DISCOVERY_PORT))
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val request = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        .toString(Charsets.UTF_8)
                    when (request) {
                        PROBE_V1 -> {
                            val response = "$OFFER_PREFIX_V1$legacyTransferPort|MalekPlay".toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        }
                        PROBE_V2 -> {
                            val offer = UltraShareNegotiation.Offer(
                                peerId = peerId,
                                displayName = safeDisplayName,
                                candidates = localCandidates(transferPort)
                            )
                            val response = UltraShareNegotiation.encode(offer).toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Periodically check coroutine cancellation.
                }
            }
        }
    }

    suspend fun discoverPeers(
        scanWindowMs: Long = SCAN_WINDOW_MS,
        context: Context? = null
    ): List<Peer> = withContext(Dispatchers.IO) {
        require(scanWindowMs in 500L..15_000L)
        val broadcasts = findBroadcastAddresses()
        require(broadcasts.isNotEmpty()) { "تعذر تحديد عنوان البث لشبكة Wi-Fi المحلية." }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            val probes = listOf(PROBE_V2, PROBE_V1).map { it.toByteArray(Charsets.UTF_8) }
            broadcasts.forEach { address ->
                probes.forEach { probe ->
                    runCatching { socket.send(DatagramPacket(probe, probe.size, address, DISCOVERY_PORT)) }
                }
            }

            val peers = LinkedHashMap<String, Peer>()
            val v2SourceHosts = HashSet<String>()
            val deadline = System.nanoTime() + scanWindowMs * 1_000_000L
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val source = packet.address
                    if (source !is Inet4Address || !isPrivateLanAddress(source)) continue
                    val message = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        .toString(Charsets.UTF_8)
                    val host = source.hostAddress ?: continue
                    val v2 = UltraShareNegotiation.decode(message)
                    if (v2 != null) {
                        v2SourceHosts += host
                        // Verify both the authenticated control listener and the actual V2
                        // data listener on every advertised interface. A reachable control
                        // socket alone is not enough to prove that file transfer can use a path.
                        // Keep all verified candidates so the selected path can be inspected and
                        // a lower-priority path remains available to the caller.
                        val testedCandidates = v2.candidates
                            .filter { it.connected && UltraShareTransport.isValidPrivateLanHost(it.host) }
                            .map { candidate ->
                                candidate.copy(reachable = probeCandidate(candidate, context))
                            }
                        val endpoint = UltraShareNegotiation.selectCandidate(testedCandidates)
                            ?: continue
                        peers.remove("host:$host") // Upgrade an earlier V1 sighting to its stable V2 identity.
                        peers["id:${v2.peerId}"] = Peer(
                            host = endpoint.host,
                            port = endpoint.port,
                            name = v2.displayName,
                            peerId = v2.peerId,
                            candidates = testedCandidates
                        )
                        continue
                    }
                    if (!message.startsWith(OFFER_PREFIX_V1)) continue
                    val parts = message.removePrefix(OFFER_PREFIX_V1).split('|', limit = 2)
                    val port = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val name = parts.getOrNull(1)?.take(64)?.takeIf { it.isNotBlank() } ?: "MalekPlay"
                    if (port !in 1..65535) continue
                    // Do not downgrade a v2 peer when its v1 response arrives later.
                    if (host !in v2SourceHosts && peers["host:$host"] == null) {
                        peers["host:$host"] = Peer(host, port, name)
                    }
                } catch (_: SocketTimeoutException) {
                    // Continue until the bounded scan window expires.
                }
            }
            peers.values.toList()
        }
    }

    private fun localCandidates(port: Int): List<UltraShareNegotiation.Candidate> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { network ->
                    val name = network.name.lowercase()
                    val transport = when {
                        name.startsWith("p2p") -> UltraShareNegotiation.Transport.WIFI_DIRECT
                        name.startsWith("ap") || name.startsWith("swlan") ->
                            UltraShareNegotiation.Transport.HOTSPOT
                        name.startsWith("wlan") || name.startsWith("eth") ->
                            UltraShareNegotiation.Transport.WLAN
                        else -> null
                    } ?: return@flatMap emptyList()
                    network.interfaceAddresses.mapNotNull { item ->
                        val address = item.address as? Inet4Address ?: return@mapNotNull null
                        if (!isPrivateLanAddress(address)) return@mapNotNull null
                        UltraShareNegotiation.Candidate(
                            transport = transport,
                            host = address.hostAddress ?: return@mapNotNull null,
                            port = port,
                            connected = true,
                            // A visible P2P/AP interface is not proof that the scanning
                            // peer joined that group. Keep it as a candidate, but do not
                            // auto-select it until a later connection probe confirms reachability.
                            reachable = transport == UltraShareNegotiation.Transport.WLAN,
                            priority = when (transport) {
                                UltraShareNegotiation.Transport.WIFI_DIRECT -> 30
                                UltraShareNegotiation.Transport.WLAN -> 20
                                UltraShareNegotiation.Transport.HOTSPOT -> 10
                            }
                        )
                    }
                }
                .distinctBy { "${it.transport}:${it.host}:${it.port}" }
                .take(8)
        }.getOrDefault(emptyList())

    private fun stablePeerId(context: Context): String {
        val preferences = context.getSharedPreferences("ultra_share_identity", Context.MODE_PRIVATE)
        val existing = preferences.getString("peer_id", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString("peer_id", generated).commit()
        return preferences.getString("peer_id", generated) ?: generated
    }

    private fun probeCandidate(candidate: UltraShareNegotiation.Candidate, context: Context?): Boolean {
        val network = context?.let { UltraShareNetworkRouting.findNetworkForHost(it, candidate.host) }
        if (!probeEndpoint(candidate.host, UltraShareControlChannel.DEFAULT_PORT, network)) return false
        // Candidate.port is the V2 batch-data port. Complete its explicit probe
        // preamble so the receiver can distinguish discovery from a broken transfer.
        return probeV2DataEndpoint(candidate.host, candidate.port, network)
    }

    private fun probeEndpoint(host: String, port: Int, network: android.net.Network?): Boolean = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), 700)
            true
        }
    }.getOrDefault(false)

    private fun probeV2DataEndpoint(host: String, port: Int, network: android.net.Network?): Boolean = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 700
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), 700)
            val input = DataInputStream(socket.getInputStream())
            check(input.readInt() == 0x4D505532) { "V2 data listener handshake is unavailable." }
            val challenge = ByteArray(32)
            input.readFully(challenge)
            socket.getOutputStream().write(UltraShareBatchTransport.discoveryProbeProof())
            socket.getOutputStream().flush()
            true
        }
    }.getOrDefault(false)

    private fun findBroadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    (name.startsWith("wlan") || name.startsWith("swlan") ||
                        name.startsWith("ap") || name.startsWith("eth") || name.startsWith("p2p"))
            }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .filterIsInstance<Inet4Address>()
            .distinctBy { it.hostAddress }
    }.getOrDefault(emptyList()).ifEmpty {
        listOf(InetAddress.getByName("255.255.255.255"))
    }

    private fun isPrivateLanAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isMulticastAddress &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress)
}
