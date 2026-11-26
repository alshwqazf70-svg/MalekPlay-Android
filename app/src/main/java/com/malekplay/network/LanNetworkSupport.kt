package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared LAN security, multi-interface network binding, and bounded concurrency helpers
 * used across WebCastServer, CastMediaHttpServer, NetworkDiscoveryClient, and DlnaRendererClient.
 */
internal object LanNetworkSupport {

    /**
     * Validates an incoming socket peer for both IPv4 and IPv6 independently.
     * Accepts only loopback, RFC1918, IPv4 link-local (169.254/16), CGNAT (100.64/10),
     * IPv6 link-local (fe80::/10), or IPv6 ULA (fc00::/7). Rejects all global/multicast/anycast addresses.
     */
    fun isAllowedLanPeerAddress(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        return when (address) {
            is Inet4Address -> {
                if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
                    true
                } else {
                    val b = address.address
                    // 169.254.0.0/16 or 100.64.0.0/10 (RFC 6598 / tether subnets)
                    val first = b[0].toInt() and 0xFF
                    val second = b[1].toInt() and 0xFF
                    (first == 169 && second == 254) || (first == 100 && (second and 0xC0) == 0x40)
                }
            }
            is Inet6Address -> {
                if (address.isLoopbackAddress || address.isLinkLocalAddress) {
                    true
                } else {
                    val b = address.address
                    // IPv6 Unique Local Address: fc00::/7 (never accept global 2000::/3).
                    (b[0].toInt() and 0xFE) == 0xFC
                }
            }
            else -> false
        }
    }

    /**
     * Returns all active private LAN IPv4 addresses on Wi-Fi, Hotspot/AP, Ethernet, and USB tether
     * interfaces, sorted so Wi-Fi/Hotspot interfaces appear first.
     */
    fun findAllPrivateIpv4s(): List<Inet4Address> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        return interfaces.toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy {
                val name = it.name.lowercase()
                when {
                    name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
                    name.startsWith("p2p") || name.startsWith("eth") || name.startsWith("rndis") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { address ->
                !address.isLoopbackAddress && isAllowedLanPeerAddress(address)
            }
    }

    /**
     * Resolves the Android [Network] whose local subnet contains [host], falling back to the
     * active Wi-Fi or Ethernet network when available. Returns null when running on a local
     * hotspot/AP interface not managed by [ConnectivityManager].
     */
    fun findLanNetworkForHost(context: Context?, host: String): Network? {
        if (context == null) return null
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val targetAddr = runCatching {
            val clean = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            if (clean.contains(':') || clean.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                InetAddress.getByName(clean)
            } else null
        }.getOrNull()

        val lanCandidates = connectivity.allNetworks.filter { candidate ->
            connectivity.getNetworkCapabilities(candidate)?.let { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
        if (lanCandidates.isEmpty()) return null

        if (targetAddr != null) {
            val subnetMatch = lanCandidates.firstOrNull { candidate ->
                val props = connectivity.getLinkProperties(candidate) ?: return@firstOrNull false
                props.linkAddresses.any { linkAddr -> matchesSubnet(targetAddr, linkAddr) }
            }
            if (subnetMatch != null) return subnetMatch
        }

        val active = connectivity.activeNetwork
        return if (active != null && active in lanCandidates) active else lanCandidates.firstOrNull()
    }

    internal fun matchesSubnet(target: InetAddress, linkAddress: LinkAddress): Boolean {
        val local = linkAddress.address ?: return false
        val targetBytes = target.address
        val localBytes = local.address
        if (targetBytes.size != localBytes.size) return false
        val prefixBits = linkAddress.prefixLength.coerceIn(0, targetBytes.size * 8)
        val fullBytes = prefixBits / 8
        for (i in 0 until fullBytes) {
            if (targetBytes[i] != localBytes[i]) return false
        }
        val remBits = prefixBits % 8
        if (remBits > 0) {
            val mask = (0xFF shl (8 - remBits)) and 0xFF
            if ((targetBytes[fullBytes].toInt() and mask) != (localBytes[fullBytes].toInt() and mask)) {
                return false
            }
        }
        return true
    }

    /**
     * Opens an [HttpURLConnection] bound directly to [network] (with Proxy.NO_PROXY) when
     * provided so discovery and DLNA/Roku requests never leak onto mobile data or VPNs.
     */
    fun openLanHttpConnection(
        url: URL,
        network: Network?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection {
        val connection = if (network != null) {
            runCatching { network.openConnection(url, Proxy.NO_PROXY) as HttpURLConnection }
                .getOrElse { url.openConnection(Proxy.NO_PROXY) as HttpURLConnection }
        } else {
            url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        }
        return connection.apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
        }
    }

    /**
     * Bounded worker pool preventing unbounded queue growth or thread exhaustion under bursty
     * browser/TV polling or slow-loris client attempts.
     */
    fun createBoundedHttpExecutor(namePrefix: String): ThreadPoolExecutor {
        val counter = AtomicInteger(1)
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "$namePrefix-${counter.getAndIncrement()}").apply { isDaemon = true }
        }
        return ThreadPoolExecutor(
            4,
            24,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(64),
            factory,
            ThreadPoolExecutor.AbortPolicy()
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }
}
