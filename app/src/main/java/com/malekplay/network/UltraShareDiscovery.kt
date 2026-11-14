package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Bounded, expiring cache for NSTACKX display-name hints. Native discovery can
 * report many transient IDs over time; never let those broadcasts grow memory
 * for the lifetime of a foreground sharing session.
 */
internal class NstackxPeerNameCache(
    private val maxEntries: Int = 128,
    private val ttlMillis: Long = 60_000L
) {
    private data class Entry(val name: String, val seenAtMillis: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    init {
        require(maxEntries > 0)
        require(ttlMillis >= 0L)
    }

    @Synchronized
    fun put(peerId: String, displayName: String, nowMillis: Long = System.currentTimeMillis()) {
        val key = peerId.trim().lowercase(java.util.Locale.ROOT).take(128)
        val name = displayName.trim().take(64)
        if (key.isBlank() || name.isBlank()) return
        prune(nowMillis)
        entries[key] = Entry(name, nowMillis)
        while (entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun get(peerId: String, nowMillis: Long = System.currentTimeMillis()): String? {
        val key = peerId.trim().lowercase(java.util.Locale.ROOT).take(128)
        if (key.isBlank()) return null
        prune(nowMillis)
        return entries[key]?.name
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    private fun prune(nowMillis: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val seenAt = iterator.next().value.seenAtMillis
            if (seenAt > nowMillis || nowMillis - seenAt > ttlMillis) iterator.remove()
        }
    }
}

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
        peer.peerId?.takeIf { it.isNotBlank() }?.lowercase(java.util.Locale.ROOT)?.let { "id:$it" } ?: "host:${peer.host}"

    /**
     * Join the native NSTACKX identity/name signal to the routable LAN offer.
     * NSTACKX's current JNI callback does not expose a remote IPv4 endpoint, so
     * it must never invent a host or make a name-only peer connectable.
     */
    internal fun mergeNativePeerName(
        peer: Peer,
        nativePeerId: String,
        nativeDisplayName: String
    ): Peer {
        if (nativePeerId.isBlank() || nativeDisplayName.isBlank()) return peer
        return if (peer.peerId?.equals(nativePeerId, ignoreCase = true) == true) {
            peer.copy(name = nativeDisplayName.trim().take(64))
        } else peer
    }

    /**
     * Merge repeated offers from the same physical device across WLAN, hotspot and
     * Wi-Fi Direct. A later broadcast must not erase already-probed working routes.
     */
    internal fun mergePeerSightings(
        first: Peer,
        second: Peer
    ): Peer {
        val firstId = first.peerId?.lowercase(java.util.Locale.ROOT)
        val secondId = second.peerId?.lowercase(java.util.Locale.ROOT)
        if (firstId.isNullOrBlank() || firstId != secondId) return second
        val routes = UltraShareNegotiation.orderedCandidates(first.candidates + second.candidates)
        val selected = UltraShareNegotiation.selectCandidate(routes)
        return Peer(
            host = selected?.host ?: second.host,
            port = selected?.port ?: second.port,
            name = second.name.takeIf { it.isNotBlank() } ?: first.name,
            peerId = first.peerId,
            candidates = routes
        )
    }

    /** A peer remains visible briefly to absorb small UDP discovery gaps, then expires. */
    internal fun isSightingFresh(lastSeenMillis: Long, nowMillis: Long, ttlMillis: Long): Boolean =
        ttlMillis >= 0L && lastSeenMillis <= nowMillis && nowMillis - lastSeenMillis <= ttlMillis

    private data class BroadcastRoute(val network: Network?, val address: InetAddress)

    private fun findBroadcastRoutes(context: Context?): List<BroadcastRoute> = runCatching {
        val manager = context?.applicationContext
            ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkByInterface = manager?.allNetworks.orEmpty().mapNotNull { network ->
            val iface = manager?.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
            iface to network
        }.toMap()
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { iface ->
                val name = iface.name.lowercase()
                iface.isUp && !iface.isLoopback &&
                    (name.startsWith("wlan") || UltraShareNetworkRouting.isHotspotInterfaceName(name) ||
                        name.startsWith("eth") || name.startsWith("p2p"))
            }
            .flatMap { iface ->
                val network = networkByInterface[iface.name]
                iface.interfaceAddresses.mapNotNull { item ->
                    val broadcast = item.broadcast as? Inet4Address ?: return@mapNotNull null
                    BroadcastRoute(network, broadcast)
                }
            }
            .distinctBy { "${it.network}:${it.address.hostAddress}" }
    }.getOrDefault(emptyList()).ifEmpty {
        listOf(BroadcastRoute(null, InetAddress.getByName("255.255.255.255")))
    }

    private fun createDiscoverySocket(network: Network?, port: Int): DatagramSocket =
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            network?.bindSocket(this)
            bind(InetSocketAddress(port))
            soTimeout = 120
        }

    suspend fun advertiseWhileReceiving(
        transferPort: Int = UltraShareBatchTransport.DEFAULT_PORT,
        legacyTransferPort: Int = UltraShareTransport.DEFAULT_PORT,
        context: Context? = null,
        displayName: String = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" } ?: "MalekPlayer Android",
        transferPortProvider: (() -> Int)? = null
    ) = withContext(Dispatchers.IO) {
        require(transferPort in 1..65535)
        val peerId = context?.let(::stablePeerId) ?: UUID.randomUUID().toString()
        val safeDisplayName = displayName.trim().take(64).ifBlank { "MalekPlayer Android" }
        val networks: List<Network?> = findBroadcastRoutes(context).map { it.network }.distinct()
        val sockets = networks.mapNotNull { network ->
            runCatching { createDiscoverySocket(network, DISCOVERY_PORT) }.getOrNull()
        }.ifEmpty { listOf(createDiscoverySocket(null, DISCOVERY_PORT)) }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                sockets.forEach { socket ->
                    try {
                        val packet = DatagramPacket(ByteArray(MAX_PACKET_BYTES), MAX_PACKET_BYTES)
                        socket.receive(packet)
                        val request = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            .toString(Charsets.UTF_8)
                        when (request) {
                            PROBE_V1 -> {
                                val response = "$OFFER_PREFIX_V1$legacyTransferPort|MalekPlayer".toByteArray(Charsets.UTF_8)
                                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                            }
                            PROBE_V2 -> {
                                val advertisedPort = transferPortProvider?.invoke() ?: transferPort
                                if (advertisedPort in 1..65535) {
                                    val offer = UltraShareNegotiation.Offer(
                                        peerId = peerId,
                                        displayName = safeDisplayName,
                                        candidates = localCandidates(advertisedPort)
                                    )
                                    val response = UltraShareNegotiation.encode(offer).toByteArray(Charsets.UTF_8)
                                    socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Poll each active interface and observe cancellation promptly.
                    } catch (_: java.net.SocketException) {
                        // A single OEM network can disappear while another remains usable.
                    }
                }
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    suspend fun discoverPeers(
        scanWindowMs: Long = SCAN_WINDOW_MS,
        context: Context? = null
    ): List<Peer> = withContext(Dispatchers.IO) {
        require(scanWindowMs in 500L..15_000L)
        val routes = findBroadcastRoutes(context)
        require(routes.isNotEmpty()) { "تعذر تحديد عنوان البث لشبكة Wi-Fi المحلية." }
        val activeSockets = routes.groupBy { it.network }.mapNotNull { (network, networkRoutes) ->
            runCatching {
                Triple(network, createDiscoverySocket(network, 0), networkRoutes)
            }.getOrNull()
        }.ifEmpty {
            listOf(Triple(null, createDiscoverySocket(null, 0), routes))
        }
        try {
            val probes = listOf(PROBE_V2, PROBE_V1).map { it.toByteArray(Charsets.UTF_8) }
            activeSockets.forEach { (_, socket, networkRoutes) ->
                networkRoutes.forEach { route ->
                    probes.forEach { probe ->
                        runCatching { socket.send(DatagramPacket(probe, probe.size, route.address, DISCOVERY_PORT)) }
                    }
                }
            }
            val peers = LinkedHashMap<String, Peer>()
            val v2SourceHosts = HashSet<String>()
            val deadline = System.nanoTime() + scanWindowMs * 1_000_000L
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                activeSockets.forEach { (_, socket, _) ->
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val source = packet.address
                        if (source !is Inet4Address || !isPrivateLanAddress(source)) return@forEach
                        val message = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            .toString(Charsets.UTF_8)
                        val host = source.hostAddress ?: return@forEach
                        val v2 = UltraShareNegotiation.decode(message)
                        if (v2 != null) {
                            val testedCandidates = probeCandidates(
                                v2.candidates.filter {
                                    it.connected && UltraShareTransport.isValidPrivateLanHost(it.host)
                                }, context
                            )
                            val endpoint = UltraShareNegotiation.selectCandidate(testedCandidates)
                                ?: return@forEach
                            v2SourceHosts += host
                            peers.remove("host:$host")
                            val incoming = Peer(endpoint.host, endpoint.port, v2.displayName, v2.peerId, testedCandidates)
                            val identity = "id:${v2.peerId.lowercase(java.util.Locale.ROOT)}"
                            peers[identity] = peers[identity]?.let { mergePeerSightings(it, incoming) } ?: incoming
                            return@forEach
                        }
                        if (!message.startsWith(OFFER_PREFIX_V1)) return@forEach
                        val parts = message.removePrefix(OFFER_PREFIX_V1).split('|', limit = 2)
                        val port = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
                        val name = parts.getOrNull(1)?.take(64)?.takeIf { it.isNotBlank() } ?: "MalekPlayer"
                        if (port !in 1..65535) return@forEach
                        if (host !in v2SourceHosts && peers["host:$host"] == null) {
                            peers["host:$host"] = Peer(host, port, name)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue polling the remaining network-bound sockets.
                    } catch (_: java.net.SocketException) {
                        // Ignore a route removed during this bounded discovery pass.
                    }
                }
            }
            peers.values.toList()
        } finally {
            activeSockets.forEach { (_, socket, _) -> runCatching { socket.close() } }
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
                        UltraShareNetworkRouting.isHotspotInterfaceName(name) ->
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

    internal fun localPeerId(context: Context): String = stablePeerId(context)

    internal fun isValidStablePeerId(value: String): Boolean =
        value.length == 36 && runCatching {
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        }.getOrDefault(false)

    private fun stablePeerId(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(
            "ultra_share_identity",
            Context.MODE_PRIVATE
        )
        val existing = preferences.getString("peer_id", null)
            ?.takeIf(::isValidStablePeerId)
        if (existing != null) return existing.lowercase()

        val generated = UUID.randomUUID().toString()
        // Never advertise a transient identity: trusted-device authorization and
        // deduplication depend on this ID surviving process restarts.
        check(preferences.edit().putString("peer_id", generated).commit()) {
            "تعذر حفظ معرّف الجهاز؛ أعد المحاولة بعد التحقق من مساحة التخزين."
        }
        return generated
    }

    /**
     * Probe up to the protocol's eight advertised paths concurrently. Sequential
     * 700ms control/data timeouts could otherwise stall discovery for many seconds
     * and overrun the caller's scan window.
     */
    private suspend fun probeCandidates(
        candidates: List<UltraShareNegotiation.Candidate>,
        context: Context?
    ): List<UltraShareNegotiation.Candidate> = coroutineScope {
        candidates.map { candidate ->
            async(Dispatchers.IO) {
                candidate.copy(reachable = probeCandidate(candidate, context))
            }
        }.awaitAll()
    }

    private fun probeCandidate(candidate: UltraShareNegotiation.Candidate, context: Context?): Boolean {
        val routes = context?.let {
            UltraShareNetworkRouting.findNetworksForHost(it, candidate.host)
        } ?: emptyList()
        // When a Context is supplied, never probe through an unrelated process-default route.
        if (context != null && routes.isEmpty()) return false
        val attempts: List<android.net.Network?> = if (context == null) listOf(null) else routes
        return attempts.any { network ->
            if (!probeEndpoint(candidate.host, UltraShareControlChannel.DEFAULT_PORT, network)) {
                false
            } else {
                // Verify the actual V2 data listener on the same route as control.
                probeV2DataEndpoint(candidate.host, candidate.port, network)
            }
        }
    }

    /**
     * Performs the V2 data-plane challenge on the already authenticated peer route.
     * A successful MPC2 control handshake alone is not evidence that file transfer
     * can start; this probe verifies the actual batch listener and is ignored by it.
     */
    internal fun probeDataEndpoint(
        host: String,
        port: Int,
        networks: List<android.net.Network>
    ): Boolean {
        if (!UltraShareTransport.isValidPrivateLanHost(host) || port !in 1..65535) return false
        // Exhaust every peer-specific Android Network first, then retain the
        // system default route as a final compatibility fallback (some OEMs do
        // not expose the P2P/SoftAP Network object to ConnectivityManager).
        val routes: List<android.net.Network?> =
            networks.distinct().map { it } + listOf(null)
        return routes.distinct().any { network -> probeV2DataEndpoint(host, port, network) }
    }

    private fun probeEndpoint(host: String, port: Int, network: android.net.Network?): Boolean = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), 700)
            true
        }
    }.onFailure { failure ->
        UltraShareDiagnostics.failure("DISCOVERY_CONTROL_PROBE", failure, "host=" + host + ";port=" + port + ";networkHandle=" + (network?.networkHandle ?: 0L))
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
    }.onFailure { failure ->
        UltraShareDiagnostics.failure("DISCOVERY_DATA_PROBE", failure, "host=" + host + ";port=" + port + ";networkHandle=" + (network?.networkHandle ?: 0L))
    }.getOrDefault(false)

    private fun findBroadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    (name.startsWith("wlan") || UltraShareNetworkRouting.isHotspotInterfaceName(name) ||
                        name.startsWith("eth") || name.startsWith("p2p"))
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
