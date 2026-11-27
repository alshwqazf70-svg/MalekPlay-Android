package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Shared LAN security, multi-interface IPv4/IPv6 binding, Scope ID preservation,
 * and bounded concurrency helpers used across WebCastServer, CastMediaHttpServer,
 * MdnsDiscoveryClient, NetworkDiscoveryClient, and DlnaRendererClient.
 */
internal object LanNetworkSupport {

    private val NON_LAN_INTERFACE_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "wwan", "tun", "tap", "ppp", "ipsec", "wg", "sit", "clat", "v4-"
    )

    internal data class LanBindingInfo(
        val interfaceName: String,
        val address: InetAddress,
        val prefixLength: Int
    )

    internal fun isLanInterfaceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase(Locale.ROOT)
        if (lower == "lo" || lower.startsWith("dummy")) return false
        return NON_LAN_INTERFACE_PREFIXES.none { lower.startsWith(it) }
    }

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
     * Strict production peer validation: verifies that [remoteAddr] is not merely a private IP
     * on any arbitrary cellular/VPN tunnel, but belongs to an active local LAN interface subnet
     * (Wi-Fi, Hotspot/AP, Ethernet, or USB tether) or a matching scoped IPv6 link-local interface.
     */
    fun isPeerOnActiveLocalLan(
        remoteAddr: InetAddress?,
        localAddr: InetAddress? = null,
        lanBindings: List<LanBindingInfo> = findActiveLanBindings(),
        allowLoopback: Boolean = false
    ): Boolean {
        if (remoteAddr == null) return false
        if (remoteAddr.isAnyLocalAddress || remoteAddr.isMulticastAddress) return false

        if (remoteAddr.isLoopbackAddress) {
            // Loopback is only accepted if explicitly permitted for tests or if the accepted
            // socket's local endpoint is genuinely loopback (never from a non-loopback NIC).
            return allowLoopback && (localAddr == null || localAddr.isLoopbackAddress)
        }

        if (!isAllowedLanPeerAddress(remoteAddr)) return false

        if (lanBindings.isEmpty()) {
            // No identifiable NIC prefix table (e.g., restricted sandbox environment).
            return false
        }

        // If the accepted socket knows which local IP received the connection, ensure that local IP
        // itself belongs to one of the active LAN interfaces (never a cellular/VPN local IP).
        if (localAddr != null && !localAddr.isAnyLocalAddress && !localAddr.isLoopbackAddress) {
            val localBare = stripZone(localAddr.hostAddress)
            val localMatchesLan = lanBindings.any {
                stripZone(it.address.hostAddress).equals(localBare, ignoreCase = true)
            }
            if (!localMatchesLan) return false
        }

        return when (remoteAddr) {
            is Inet4Address -> {
                val ipv4Bindings = lanBindings.filter { it.address is Inet4Address }
                ipv4Bindings.any { binding ->
                    matchesPrefix(remoteAddr.address, binding.address.address, binding.prefixLength)
                }
            }
            is Inet6Address -> {
                val ipv6Bindings = lanBindings.filter { it.address is Inet6Address }
                if (ipv6Bindings.isEmpty()) return false
                if (remoteAddr.isLinkLocalAddress) {
                    val peerScopeName = remoteAddr.scopedInterface?.name
                    val peerScopeId = remoteAddr.scopeId
                    if (!peerScopeName.isNullOrBlank()) {
                        isLanInterfaceName(peerScopeName) &&
                            ipv6Bindings.any { it.interfaceName.equals(peerScopeName, ignoreCase = true) }
                    } else if (peerScopeId > 0) {
                        val iface = runCatching { NetworkInterface.getByIndex(peerScopeId) }.getOrNull()
                        iface != null && isLanInterfaceName(iface.name) &&
                            ipv6Bindings.any { it.interfaceName.equals(iface.name, ignoreCase = true) }
                    } else {
                        // Link-local without scope is only accepted when the local endpoint itself is
                        // on a verified LAN IPv6 interface.
                        localAddr is Inet6Address && localAddr.isLinkLocalAddress
                    }
                } else {
                    // IPv6 ULA (fc00::/7)
                    ipv6Bindings.any { binding ->
                        matchesPrefix(remoteAddr.address, binding.address.address, binding.prefixLength)
                    }
                }
            }
            else -> false
        }
    }

    internal fun findActiveLanBindings(): List<LanBindingInfo> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        return interfaces.toList()
            .filter { it.isUp && !it.isLoopback && isLanInterfaceName(it.name) }
            .sortedBy { interfacePriority(it.name) }
            .flatMap { iface ->
                iface.interfaceAddresses.mapNotNull { ifaceAddr: InterfaceAddress ->
                    val addr = ifaceAddr.address ?: return@mapNotNull null
                    if (addr.isLoopbackAddress || !isAllowedLanPeerAddress(addr)) return@mapNotNull null
                    val defaultPrefix = if (addr is Inet4Address) 24 else 64
                    val maxBits = addr.address.size * 8
                    val prefix = ifaceAddr.networkPrefixLength.toInt()
                        .takeIf { it in 1..maxBits } ?: defaultPrefix
                    LanBindingInfo(
                        interfaceName = iface.name,
                        address = addr,
                        prefixLength = prefix
                    )
                }
            }
    }

    private fun interfacePriority(rawName: String): Int {
        val name = rawName.lowercase(Locale.ROOT)
        return when {
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
            name.startsWith("p2p") || name.startsWith("eth") || name.startsWith("rndis") -> 1
            else -> 2
        }
    }

    /**
     * Returns all active private LAN IPv4 addresses on Wi-Fi, Hotspot/AP, Ethernet, and USB tether
     * interfaces, sorted so Wi-Fi/Hotspot interfaces appear first.
     */
    fun findAllPrivateIpv4s(): List<Inet4Address> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        return interfaces.toList()
            .filter { it.isUp && !it.isLoopback && isLanInterfaceName(it.name) }
            .sortedBy { interfacePriority(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { address ->
                !address.isLoopbackAddress && isAllowedLanPeerAddress(address)
            }
    }

    /**
     * Returns all active private/link-local LAN IPv6 addresses on Wi-Fi, Hotspot/AP, Ethernet,
     * and USB tether interfaces, preserving Scope ID on link-local addresses.
     */
    fun findAllPrivateIpv6s(): List<Inet6Address> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        return interfaces.toList()
            .filter { it.isUp && !it.isLoopback && isLanInterfaceName(it.name) }
            .sortedBy { interfacePriority(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet6Address>()
            .filter { address ->
                !address.isLoopbackAddress && isAllowedLanPeerAddress(address)
            }
    }

    /**
     * Formats an IP address for discovery/storage while preserving IPv6 link-local Scope ID.
     * Never strips `%scope` from `fe80::/10` addresses.
     */
    fun formatHostPreservingScope(
        address: InetAddress?,
        fallbackInterface: NetworkInterface? = null
    ): String? {
        if (address == null) return null
        val raw = address.hostAddress ?: return null
        if (address !is Inet6Address) return raw
        val bare = raw.substringBefore('%')
        if (!address.isLinkLocalAddress) return bare

        val existingZone = raw.substringAfter('%', "").takeIf { it.isNotBlank() }
            ?: address.scopedInterface?.name?.takeIf { it.isNotBlank() }
            ?: address.scopeId.takeIf { it > 0 }?.let { id ->
                runCatching { NetworkInterface.getByIndex(id)?.name }.getOrNull() ?: id.toString()
            }
            ?: fallbackInterface?.name?.takeIf { it.isNotBlank() }
        return if (!existingZone.isNullOrBlank()) "$bare%$existingZone" else bare
    }

    /** Formats a host string into a valid RFC 6874 URI authority (`[fe80::1%25wlan0]` for scoped IPv6). */
    fun formatUriAuthorityHost(rawHost: String): String {
        val decoded = decodeScopedHost(rawHost)
        return if (decoded.contains(':')) {
            val encoded = decoded.replace("%", "%25")
            "[$encoded]"
        } else {
            decoded
        }
    }

    /** Formats a host string for `java.net.URL` (`[fe80::1%wlan0]` or `[fd00::1]` or `192.168.1.1`). */
    fun formatUrlHost(rawHost: String): String {
        val decoded = decodeScopedHost(rawHost)
        return if (decoded.contains(':')) "[$decoded]" else decoded
    }

    /** Decodes `[fe80::1%25wlan0]` or `[fe80::1%wlan0]` back to `fe80::1%wlan0`. */
    fun decodeScopedHost(rawHost: String): String {
        val clean = rawHost.trim().removePrefix("[").removeSuffix("]")
        val percent25 = clean.indexOf("%25", ignoreCase = true)
        return if (percent25 >= 0) {
            clean.substring(0, percent25) + "%" + clean.substring(percent25 + 3)
        } else {
            clean
        }
    }

    /** Strips `%zone` ONLY when validating address type/prefix or numeric literal syntax. */
    fun stripZone(rawHost: String?): String =
        rawHost.orEmpty().trim().removePrefix("[").removeSuffix("]")
            .let { decodeScopedHost(it) }
            .substringBefore('%')

    /**
     * Resolves a numeric host string to an [InetAddress] while preserving or attaching the
     * link-local Scope ID (`NetworkInterface` / numeric index) whenever needed for `fe80::/10`.
     */
    fun resolveScopedInetAddress(
        rawHost: String,
        fallbackInterface: NetworkInterface? = null
    ): InetAddress {
        val decoded = decodeScopedHost(rawHost)
        val parsed = InetAddress.getByName(decoded)
        if (parsed is Inet6Address && parsed.isLinkLocalAddress) {
            val hasScope = parsed.scopedInterface != null || parsed.scopeId > 0
            if (!hasScope && fallbackInterface != null) {
                return Inet6Address.getByAddress(null, parsed.address, fallbackInterface)
            }
        }
        return parsed
    }

    /**
     * Resolves the Android [Network] whose local subnet contains [host], falling back to an
     * active Wi-Fi or Ethernet network when available. Returns null when running on a local
     * hotspot/AP interface not managed by [ConnectivityManager].
     */
    fun findLanNetworkForHost(context: Context?, host: String): Network? {
        if (context == null) return null
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val targetAddr = runCatching {
            val bare = stripZone(host)
            if (bare.contains(':') || bare.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                InetAddress.getByName(bare)
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
        return matchesPrefix(target.address, local.address, linkAddress.prefixLength)
    }

    internal fun matchesPrefix(targetBytes: ByteArray, localBytes: ByteArray, rawPrefixBits: Int): Boolean {
        if (targetBytes.size != localBytes.size) return false
        val prefixBits = rawPrefixBits.coerceIn(0, targetBytes.size * 8)
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
     * Opens an [HttpURLConnection] (HTTP or HTTPS) bound directly to [network] (with Proxy.NO_PROXY).
     *
     * CRITICAL: When [network] is explicitly supplied, this method NEVER silently falls back to the
     * system default network on failure — doing so could leak local LAN requests or credentials
     * over cellular data or VPN. An explicit [IOException] is thrown instead.
     * Only when [network] is null (e.g., local Hotspot/AP not represented by ConnectivityManager)
     * is an unbound direct connection opened.
     */
    fun openLanHttpConnection(
        url: URL,
        network: Network?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection = openLanHttpConnectionWithOpeners(
        url = url,
        network = network,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        boundOpener = { net, target -> net.openConnection(target, Proxy.NO_PROXY) as HttpURLConnection },
        unboundOpener = { target -> target.openConnection(Proxy.NO_PROXY) as HttpURLConnection }
    )

    fun openLanHttpsConnection(
        url: URL,
        network: Network?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpsURLConnection {
        require(url.protocol.equals("https", ignoreCase = true)) { "Expected HTTPS URL." }
        val conn = openLanHttpConnection(url, network, connectTimeoutMs, readTimeoutMs)
        if (conn !is HttpsURLConnection) {
            conn.disconnect()
            throw IOException("Expected HttpsURLConnection for HTTPS endpoint.")
        }
        return conn
    }

    internal fun openLanHttpConnectionWithOpeners(
        url: URL,
        network: Network?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        boundOpener: (Network, URL) -> HttpURLConnection,
        unboundOpener: (URL) -> HttpURLConnection
    ): HttpURLConnection {
        val connection = if (network != null) {
            try {
                boundOpener(network, url)
            } catch (io: IOException) {
                throw io
            } catch (failure: Exception) {
                throw IOException("تعذر فتح الاتصال عبر الشبكة المحلية المحددة: ${failure.message}", failure)
            }
        } else {
            unboundOpener(url)
        }
        return try {
            connection.apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
            }
        } catch (configFailure: Throwable) {
            runCatching { connection.disconnect() }
            throw configFailure
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
