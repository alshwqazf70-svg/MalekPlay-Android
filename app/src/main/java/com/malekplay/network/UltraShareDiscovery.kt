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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** LAN discovery for the native UltraShare V2 peer and data-plane protocol. */
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
    }.onFailure { failure ->
        UltraShareDiagnostics.failure("DISCOVERY_BROADCAST_ROUTE_ENUMERATION", failure)
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
        context: Context? = null,
        displayName: String = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" } ?: "MalekPlayer Android",
        transferPortProvider: (() -> Int)? = null
    ) = withContext(Dispatchers.IO) {
        require(transferPort in 1..65535)
        val peerId = context?.let(::stablePeerId) ?: UUID.randomUUID().toString()
        val safeDisplayName = displayName.trim().take(64).ifBlank { "MalekPlayer Android" }
        fun routeFingerprint(routes: List<BroadcastRoute>): List<String> =
            routes.map { "${it.network?.networkHandle ?: 0L}:${it.address.hostAddress}" }.distinct().sorted()

        fun openSockets(routes: List<BroadcastRoute>): List<DatagramSocket> {
            val activeNetworks = routes.map { it.network }.distinct()
            return activeNetworks.mapNotNull { network ->
                runCatching { createDiscoverySocket(network, DISCOVERY_PORT) }
                    .onFailure { failure ->
                        UltraShareDiagnostics.failure(
                            "DISCOVERY_SOCKET_OPEN",
                            failure,
                            "networkHandle=${network?.networkHandle ?: 0L}"
                        )
                    }
                    .getOrNull()
            }.ifEmpty {
                runCatching { createDiscoverySocket(null, DISCOVERY_PORT) }
                    .onFailure { failure ->
                        UltraShareDiagnostics.failure("DISCOVERY_FALLBACK_SOCKET_OPEN", failure)
                    }
                    .getOrNull()
                    ?.let(::listOf)
                    ?: emptyList()
            }
        }

        val initialRoutes = findBroadcastRoutes(context)
        var activeFingerprint = routeFingerprint(initialRoutes)
        var sockets = openSockets(initialRoutes)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val latestRoutes = findBroadcastRoutes(context)
                val latestFingerprint = routeFingerprint(latestRoutes)
                if (latestFingerprint != activeFingerprint) {
                    UltraShareDiagnostics.event(
                        "DISCOVERY",
                        "NETWORK_ROUTES_CHANGED",
                        mapOf(
                            "previousRouteCount" to activeFingerprint.size,
                            "currentRouteCount" to latestFingerprint.size
                        )
                    )
                    sockets.forEach { runCatching { it.close() } }
                    activeFingerprint = latestFingerprint
                    sockets = openSockets(latestRoutes)
                    UltraShareDiagnostics.event(
                        "DISCOVERY",
                        "DISCOVERY_SOCKETS_REBOUND",
                        mapOf("socketCount" to sockets.size)
                    )
                }
                if (sockets.isEmpty()) {
                    sockets = openSockets(latestRoutes)
                    if (sockets.isEmpty()) {
                        delay(250L)
                        continue
                    }
                }
                var socketFailureDetected = false
                sockets.forEach { socket ->
                    try {
                        val packet = DatagramPacket(ByteArray(MAX_PACKET_BYTES), MAX_PACKET_BYTES)
                        socket.receive(packet)
                        val request = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            .toString(Charsets.UTF_8)
                        when (request) {
                            // Do not advertise a legacy data listener that this V2-only
                            // receiver no longer hosts. V1 discovery remains enabled on
                            // the scanning side so older receivers can still be paired.
                            PROBE_V1 -> Unit
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
                        // Poll routes regularly so address/network changes trigger a socket rebind.
                    } catch (failure: java.net.SocketException) {
                        socketFailureDetected = true
                        UltraShareDiagnostics.failure("DISCOVERY_SOCKET_RECEIVE", failure)
                    }
                }
                if (socketFailureDetected) {
                    val recoveredRoutes = findBroadcastRoutes(context)
                    val recoveredFingerprint = routeFingerprint(recoveredRoutes)
                    sockets.forEach { runCatching { it.close() } }
                    activeFingerprint = recoveredFingerprint
                    sockets = openSockets(recoveredRoutes)
                    UltraShareDiagnostics.event(
                        "DISCOVERY",
                        "DISCOVERY_SOCKETS_RECOVERED_AFTER_ERROR",
                        mapOf("socketCount" to sockets.size)
                    )
                    if (sockets.isEmpty()) delay(250L)
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
        // Discovery must remain best-effort: lack of an enumerated broadcast route
        // must not become a pairing prerequisite. findBroadcastRoutes normally
        // supplies the limited-broadcast fallback; retain it even on OEM failures.
        val effectiveRoutes = routes.ifEmpty { listOf(BroadcastRoute(null, InetAddress.getByName("255.255.255.255"))) }
        val activeSockets = effectiveRoutes.groupBy { it.network }.mapNotNull { (network, networkRoutes) ->
            runCatching {
                Triple(network, createDiscoverySocket(network, 0), networkRoutes)
            }.getOrNull()
        }.ifEmpty {
            listOf(Triple(null, createDiscoverySocket(null, 0), effectiveRoutes))
        }
        try {
            // Keep V1 discovery alive for older MalekPlay builds while preferring V2.
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
                        // Broadcast discovery can loop an offer back to the same process on
                        // Android 9/10, especially on Samsung P2P interfaces. Never treat
                        // one of this device's own interface addresses as a remote peer.
                        if (context != null && UltraShareNetworkRouting.isLocalAddress(context, host)) {
                            UltraShareDiagnostics.event(
                                "DISCOVERY",
                                "SELF_DISCOVERY_OFFER_IGNORED",
                                mapOf("host" to host)
                            )
                            return@forEach
                        }
                        val v2 = UltraShareNegotiation.decode(message)
                        if (v2 != null) {
                            // Test every advertised private path independently. A bad,
                            // stale or unavailable secondary route must not hide the peer
                            // or prevent the user from pairing through the UDP source IP.
                            val safeCandidates = v2.candidates.filter {
                                UltraShareCore.isValidPrivateLanHost(it.host) && it.port in 1..65535
                            }
                            val testedCandidates = probeCandidates(
                                safeCandidates.map { candidate ->
                                    if (candidate.connected) candidate else candidate.copy(reachable = false)
                                }, context
                            )
                            val incoming = peerFromOffer(host, v2, testedCandidates) ?: return@forEach
                            v2SourceHosts += host
                            peers.remove("host:$host")
                            val identity = "id:${v2.peerId.lowercase(java.util.Locale.ROOT)}"
                            peers[identity] = peers[identity]?.let { mergePeerSightings(it, incoming) } ?: incoming
                            return@forEach
                        }
                        // Legacy V1 peers have no stable identity or route list. Keep
                        // their observed private source address so MPC2 pairing can
                        // still be attempted; transfer compatibility is negotiated later.
                        val legacy = decodeLegacyOffer(message, host) ?: return@forEach
                        if (host !in v2SourceHosts) {
                            peers.putIfAbsent(identityKey(legacy), legacy)
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

    /** Build a visible peer even when every optional advertised route is offline. */
    internal fun peerFromOffer(
        sourceHost: String,
        offer: UltraShareNegotiation.Offer,
        testedCandidates: List<UltraShareNegotiation.Candidate>
    ): Peer? {
        if (!UltraShareCore.isValidPrivateLanHost(sourceHost)) return null
        val endpoint = UltraShareNegotiation.selectCandidate(testedCandidates)
        return Peer(
            host = endpoint?.host ?: sourceHost,
            port = endpoint?.port ?: UltraShareBatchTransport.DEFAULT_PORT,
            name = offer.displayName,
            peerId = offer.peerId,
            candidates = testedCandidates
        )
    }

    /** Decode the legacy discovery envelope without granting it any trust by name. */
    internal fun decodeLegacyOffer(message: String, sourceHost: String): Peer? {
        if (!UltraShareCore.isValidPrivateLanHost(sourceHost) ||
            !message.startsWith(OFFER_PREFIX_V1)
        ) return null
        val parts = message.removePrefix(OFFER_PREFIX_V1).split('|', limit = 2)
        val port = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = parts.getOrNull(1)?.trim()?.take(64)?.takeIf { it.isNotBlank() } ?: "MalekPlayer"
        // The legacy offer's TCP port is a V1 endpoint hint. The authenticated
        // MPC2 pairing step still validates the peer before any transfer is tried.
        val legacyRoute = UltraShareNegotiation.Candidate(
            transport = UltraShareNegotiation.Transport.WLAN,
            host = sourceHost,
            port = port,
            connected = true,
            reachable = true,
            priority = 0
        )
        return Peer(sourceHost, port, name, candidates = listOf(legacyRoute))
    }

    /**
     * Detect a legacy MPU1 listener without treating it as a V2 listener.
     * This is diagnostic/version negotiation only; it never sends file data.
     */
    internal fun probeLegacyDataEndpoint(
        host: String,
        port: Int,
        networks: List<android.net.Network>
    ): Boolean {
        if (!UltraShareCore.isValidPrivateLanHost(host) || port !in 1..65535) return false
        val routes: List<android.net.Network?> = networks.distinct().map { it } + listOf(null)
        return routes.distinct().any { network ->
            runCatching {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.soTimeout = 700
                    network?.bindSocket(socket)
                    socket.connect(InetSocketAddress(host, port), 700)
                    val input = DataInputStream(socket.getInputStream())
                    check(input.readInt() == 0x4D505531) { "Legacy MPU1 listener is unavailable." }
                    input.readFully(ByteArray(32))
                    true
                }
            }.getOrDefault(false)
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
                .sortedWith(
                    compareBy<UltraShareNegotiation.Candidate> {
                        when (it.transport) {
                            UltraShareNegotiation.Transport.WIFI_DIRECT -> 0
                            UltraShareNegotiation.Transport.WLAN -> 1
                            UltraShareNegotiation.Transport.HOTSPOT -> 2
                        }
                    }.thenByDescending { it.priority }
                )
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
                candidate.copy(
                    reachable = candidate.connected && probeCandidate(candidate, context)
                )
            }
        }.awaitAll()
    }

    private fun probeCandidate(candidate: UltraShareNegotiation.Candidate, context: Context?): Boolean {
        val routes = context?.let {
            UltraShareNetworkRouting.findNetworksForHost(it, candidate.host)
        } ?: emptyList()
        // Some Android 9/OEM Wi-Fi Direct implementations do not expose the P2P
        // Network through ConnectivityManager.allNetworks. The peer host is already
        // validated as a private LAN address, so try every explicit matching Network
        // first and then the OS default route rather than discarding the peer sighting.
        if (context != null && routes.isEmpty()) {
            UltraShareDiagnostics.event(
                "DISCOVERY", "ANDROID_ROUTE_NOT_EXPOSED_TRYING_DEFAULT",
                mapOf("peerHost" to candidate.host, "dataPort" to candidate.port)
            )
        }
        val attempts = UltraShareNetworkRouting.orderedRoutesWithDefaultFallback(routes)
        // Pairing/control reachability is the only discovery gate. The data-plane
        // listener is probed separately and must never suppress an otherwise valid peer.
        return attempts.any { network ->
            probeEndpoint(candidate.host, UltraShareControlChannel.DEFAULT_PORT, network)
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
        if (!UltraShareCore.isValidPrivateLanHost(host) || port !in 1..65535) return false
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
            socket.soTimeout = 700
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), 700)
            // Complete the server's greeting instead of connecting and immediately
            // closing. The old probe caused the server to write into a closed socket,
            // producing hundreds of misleading Broken pipe handshake errors.
            val input = DataInputStream(socket.getInputStream())
            check(input.readInt() == 0x4D504332) { "MPC2 control listener is unavailable." }
            check(input.readUnsignedByte() == 1) { "MPC2 control version is unsupported." }
            val challenge = ByteArray(32)
            input.readFully(challenge)
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
