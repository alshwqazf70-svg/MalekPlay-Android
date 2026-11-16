package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Resolves the Android Network whose local link or explicit route reaches a peer.
 * Wi-Fi Direct commonly exposes the group-owner address (192.168.49.1) through
 * a p2p interface that is not the device's default WLAN. Matching only local
 * interface addresses can miss that route and silently send sockets over WLAN.
 */
internal object UltraShareNetworkRouting {
    /** Executes route attempts in order and stops only after the first successful connection. */
    internal fun <T> firstSuccessfulRoute(
        routes: List<T>,
        attempt: (T) -> Boolean
    ): T? {
        for (route in routes) {
            if (attempt(route)) return route
        }
        return null
    }

    /**
     * QR invitations discovered over Wi-Fi Direct are pinned to the confirmed
     * group-owner address. Advertised LAN/SoftAP addresses are not interchangeable
     * with the P2P peer and must not be tried after a P2P connection callback.
     */
    internal fun invitationHostCandidates(
        confirmedHost: String,
        advertisedHosts: List<String>,
        pinToConfirmedHost: Boolean
    ): List<String> =
        (if (pinToConfirmedHost) listOf(confirmedHost) else listOf(confirmedHost) + advertisedHosts)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Preserve the preferred route first, then try every distinct alternative once. */
    internal fun <T> orderedDistinctRoutes(preferred: T?, alternatives: List<T>): List<T> =
        (listOfNotNull(preferred) + alternatives).distinct()

    /** Try explicit Android routes first, then the process-default route as fallback. */
    internal fun <T> orderedRoutesWithDefaultFallback(networks: List<T>): List<T?> =
        networks.distinct().map { it } + listOf(null)

    /**
     * Returns every Android network with a peer-specific route, best candidates first.
     * Some OEMs publish a P2P route on more than one Network or report stale link
     * properties during group negotiation; trying a small ordered set is more robust
     * than binding the only socket to one guessed interface.
     */
    fun findNetworksForHost(context: Context, host: String): List<Network> = runCatching {
        val remote = InetAddress.getByName(host) as? Inet4Address ?: return@runCatching emptyList()
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@runCatching emptyList()
        val active = if (android.os.Build.VERSION.SDK_INT >= 23) {
            runCatching { manager.activeNetwork }.getOrNull()
        } else null

        val allNetworks = manager.allNetworks
        val routeMatches = allNetworks.mapNotNull { network ->
            val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
            val interfaceName = properties.interfaceName.orEmpty()
            val p2pInterface = isWifiDirectInterfaceName(interfaceName)
            val matchingLinkPrefix = properties.linkAddresses.mapNotNull { link ->
                val local = link.address as? Inet4Address ?: return@mapNotNull null
                link.prefixLength.takeIf { isInPrefix(remote, local, it) }
            }.maxOrNull()
            val matchingRoutePrefix = properties.routes.mapNotNull { route ->
                val routeAddress = route.destination.address as? Inet4Address ?: return@mapNotNull null
                val prefix = route.destination.prefixLength
                prefix.takeIf { it in 1..32 && isInPrefix(remote, routeAddress, it) }
            }.maxOrNull()
            // OEM builds can expose a different private P2P subnet than 192.168.49.0/24.
            // The dedicated p2p* interface is the stronger route signal.
            val p2pGroupOwnerRoute = isP2pGroupOwnerRoute(interfaceName, remote)
            if (matchingLinkPrefix == null && matchingRoutePrefix == null && !p2pGroupOwnerRoute) {
                return@mapNotNull null
            }

            val capabilities = manager.getNetworkCapabilities(network)
            val transportRank = when {
                p2pInterface -> 5
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 4
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 3
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 2
                else -> 1
            }
            val prefixRank = maxOf(matchingLinkPrefix ?: 0, matchingRoutePrefix ?: 0)
            val matchRank = when {
                p2pGroupOwnerRoute -> 3
                matchingRoutePrefix != null -> 2
                else -> 1
            }
            val reason = when {
                p2pGroupOwnerRoute -> "p2p-private"
                matchingRoutePrefix != null -> "route-prefix"
                else -> "link-prefix"
            }
            val score = matchRank * 100 + transportRank * 10 + prefixRank
            Triple(network, score, prefixRank) to "$interfaceName:$reason:$prefixRank"
        }
        val orderedMatches = routeMatches.sortedWith(
            compareByDescending<Pair<Triple<Network, Int, Int>, String>> { it.first.second }
                .thenByDescending { it.first.third }
                .thenBy { if (it.first.first == active) 0 else 1 }
        ).take(4)
        UltraShareDiagnostics.event(
            "NETWORK_ROUTING",
            "HOST_ROUTE_SCAN",
            mapOf(
                "host" to host,
                "networkCount" to allNetworks.size,
                "candidateCount" to orderedMatches.size,
                "activeNetworkPresent" to (active != null),
                "candidateInterfaces" to orderedMatches.joinToString("|") { it.second },
                "observedInterfaces" to allNetworks.take(8).joinToString("|") { network ->
                    val name = manager.getLinkProperties(network)?.interfaceName.orEmpty().ifBlank { "unknown" }
                    val capabilities = manager.getNetworkCapabilities(network)
                    val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    "$name:wifi=$wifi"
                }
            )
        )
        orderedMatches.map { it.first.first }
    }.onFailure { failure ->
        UltraShareDiagnostics.failure("NETWORK_ROUTE_DISCOVERY", failure, "host=" + host)
    }.getOrDefault(emptyList())

    /** Compatibility helper for call sites that need only the best matching route. */
    fun findNetworkForHost(context: Context, host: String): Network? =
        findNetworksForHost(context, host).firstOrNull()

    /**
     * A QR v3 invitation may contain addresses from the receiver's old P2P group.
     * They must not be used as a LAN fallback before the new group is joined.
     * Accept a fallback only when Android exposes a matching Wi-Fi/Ethernet route
     * on a non-P2P interface. The authenticated MPC2 handshake remains mandatory.
     */
    fun findNonP2pLanFallbackHosts(context: Context, hosts: List<String>): List<String> =
        hosts.asSequence()
            .map { it.trim() }
            .filter { UltraShareTransport.isValidPrivateLanHost(it) }
            .distinct()
            .filter { hasNonP2pLanRoute(context, it) }
            .toList()

    fun findNonP2pLanFallbackHost(context: Context, hosts: List<String>): String? =
        findNonP2pLanFallbackHosts(context, hosts).firstOrNull()

    /**
     * Return only addresses owned by a live non-P2P Wi-Fi/Ethernet/SoftAP
     * interface. These can be advertised as optional QR fallback candidates;
     * a receiver's old P2P address is never copied into the LAN list.
     */
    fun nonP2pLocalIpv4Addresses(context: Context, candidates: List<String>): List<String> =
        runCatching {
            val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return@runCatching emptyList()
            val localAddresses = manager.allNetworks.flatMap { network ->
                val properties = manager.getLinkProperties(network) ?: return@flatMap emptyList()
                val interfaceName = properties.interfaceName.orEmpty()
                val capabilities = manager.getNetworkCapabilities(network)
                val localTransport =
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                        isHotspotInterfaceName(interfaceName)
                if (isWifiDirectInterfaceName(interfaceName) || !localTransport) {
                    emptyList()
                } else {
                    properties.linkAddresses.mapNotNull { link ->
                        (link.address as? Inet4Address)?.hostAddress
                    }
                }
            }.toSet()
            candidates.asSequence()
                .map { it.trim() }
                .filter { it in localAddresses && UltraShareTransport.isValidPrivateLanHost(it) }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())

    private fun hasNonP2pLanRoute(context: Context, host: String): Boolean = runCatching {
        val remote = InetAddress.getByName(host) as? Inet4Address ?: return@runCatching false
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@runCatching false
        manager.allNetworks.any networkLoop@{ network ->
            val properties = manager.getLinkProperties(network) ?: return@networkLoop false
            val interfaceName = properties.interfaceName.orEmpty()
            val capabilities = manager.getNetworkCapabilities(network)
            val localTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                isHotspotInterfaceName(interfaceName)
            val linkMatch = properties.linkAddresses.any linkLoop@{ link ->
                val local = link.address as? Inet4Address ?: return@linkLoop false
                link.prefixLength in 1..32 && isInPrefix(remote, local, link.prefixLength)
            }
            val routeMatch = properties.routes.any routeLoop@{ route ->
                val destination = route.destination.address as? Inet4Address ?: return@routeLoop false
                val prefix = route.destination.prefixLength
                prefix in 1..32 && isInPrefix(remote, destination, prefix)
            }
            isNonP2pLanInterfaceName(interfaceName) && localTransport && (linkMatch || routeMatch)
        }
    }.getOrDefault(false)

    /** A private peer address is a P2P candidate only when the route belongs to a p2p interface. */
    internal fun isP2pGroupOwnerRoute(interfaceName: String, remote: Inet4Address): Boolean =
        isWifiDirectInterfaceName(interfaceName) && isPrivateIpv4Address(remote)

    /** True for RFC1918 IPv4 addresses commonly used by local P2P groups. */
    internal fun isPrivateIpv4Address(address: Inet4Address): Boolean =
        address.isSiteLocalAddress

    /** True when the host belongs to Android's conventional Wi-Fi Direct group subnet. */
    fun isWifiDirectGroupHost(host: String): Boolean = runCatching {
        val address = InetAddress.getByName(host) as? Inet4Address ?: return@runCatching false
        isAndroidWifiDirectGroupAddress(address)
    }.getOrDefault(false)

    internal fun isWifiDirectInterfaceName(name: String): Boolean =
        name.trim().lowercase().let { it.startsWith("p2p") }

    internal fun isNonP2pLanInterfaceName(name: String): Boolean {
        val normalized = name.trim().lowercase()
        if (isWifiDirectInterfaceName(normalized)) return false
        return normalized.startsWith("wlan") ||
            normalized.startsWith("swlan") ||
            normalized.startsWith("wifi") ||
            normalized.startsWith("eth") ||
            normalized.startsWith("rndis") ||
            isHotspotInterfaceName(normalized)
    }

    /** Android OEMs expose local-only hotspots under different interface prefixes. */
    internal fun isHotspotInterfaceName(name: String): Boolean =
        name.trim().lowercase().let { it.startsWith("ap") || it.startsWith("softap") || it.startsWith("swlan") }

    internal fun isAndroidWifiDirectGroupAddress(address: Inet4Address): Boolean =
        isInPrefix(
            address,
            InetAddress.getByName("192.168.49.0") as Inet4Address,
            24
        )

    internal fun isInPrefix(
        address: Inet4Address,
        networkAddress: Inet4Address,
        prefixLength: Int
    ): Boolean {
        if (prefixLength !in 0..32) return false
        val addressBytes = address.address
        val networkBytes = networkAddress.address
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (addressBytes[index] != networkBytes[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (addressBytes[fullBytes].toInt() and mask) ==
            (networkBytes[fullBytes].toInt() and mask)
    }
}
