package com.malekplay.network

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Small LAN-only UDP discovery. It never broadcasts pairing secrets or scans ports. */
object UltraShareDiscovery {
    const val DISCOVERY_PORT = 38742
    private const val PROBE = "MPULTRA_DISCOVER|1"
    private const val OFFER_PREFIX = "MPULTRA_OFFER|1|"
    private const val RECEIVE_TIMEOUT_MS = 500
    private const val SCAN_WINDOW_MS = 4_000L
    private const val MAX_PACKET_BYTES = 512

    data class Peer(
        val host: String,
        val port: Int,
        val name: String
    )

    suspend fun advertiseWhileReceiving(
        context: Context,
        transferPort: Int = UltraShareTransport.DEFAULT_PORT
    ) = withContext(Dispatchers.IO) {
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
                    if (request != PROBE) continue
                    val name = "MalekPlay"
                    val response = "$OFFER_PREFIX$transferPort|$name".toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                } catch (_: SocketTimeoutException) {
                    // Periodically check coroutine cancellation.
                }
            }
        }
    }

    suspend fun discoverPeers(
        context: Context,
        scanWindowMs: Long = SCAN_WINDOW_MS
    ): List<Peer> = withContext(Dispatchers.IO) {
        require(scanWindowMs in 500L..15_000L)
        val broadcasts = findBroadcastAddresses()
        require(broadcasts.isNotEmpty()) { "تعذر تحديد عنوان البث لشبكة Wi-Fi المحلية." }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            val probe = PROBE.toByteArray(Charsets.UTF_8)
            broadcasts.forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(probe, probe.size, address, DISCOVERY_PORT))
                }
            }

            val peers = LinkedHashMap<String, Peer>()
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
                    if (!message.startsWith(OFFER_PREFIX)) continue
                    val parts = message.removePrefix(OFFER_PREFIX).split('|', limit = 2)
                    val port = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val name = parts.getOrNull(1)?.take(64)?.takeIf { it.isNotBlank() } ?: "MalekPlay"
                    if (port !in 1..65535) continue
                    val host = source.hostAddress ?: continue
                    peers[host] = Peer(host, port, name)
                } catch (_: SocketTimeoutException) {
                    // Continue until the bounded scan window expires.
                }
            }
            peers.values.toList()
        }
    }

    private fun findBroadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    (name.startsWith("wlan") || name.startsWith("swlan") ||
                        name.startsWith("ap") || name.startsWith("eth"))
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
