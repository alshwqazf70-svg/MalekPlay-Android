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
    /** Preserve the preferred route first, then try every distinct alternative once. */
    internal fun <T> orderedDistinctRoutes(preferred: T?, alternatives: List<T>): List<T> =
        (listOfNotNull(preferred) + alternatives).distinct()

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

        manager.allNetworks.mapNotNull { network ->
            val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
            val p2pInterface = isWifiDirectInterfaceName(properties.interfaceName.orEmpty())
            val matchingLinkPrefix = properties.linkAddresses.mapNotNull { link ->
                val local = link.address as? Inet4Address ?: return@mapNotNull null
                link.prefixLength.takeIf { isInPrefix(remote, local, it) }
            }.maxOrNull()
            val matchingRoutePrefix = properties.routes.mapNotNull { route ->
                val routeAddress = route.destination.address as? Inet4Address ?: return@mapNotNull null
                val prefix = route.destination.prefixLength
                prefix.takeIf { it in 1..32 && isInPrefix(remote, routeAddress, it) }
            }.maxOrNull()
            val p2pGroupOwnerRoute = p2pInterface && isAndroidWifiDirectGroupAddress(remote)
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
            val score = matchRank * 100 + transportRank * 10 + prefixRank
            Triple(network, score, prefixRank)
        }.sortedWith(
            compareByDescending<Triple<Network, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenBy { if (it.first == active) 0 else 1 }
        ).take(4).map { it.first }
    }.getOrDefault(emptyList())

    /** Compatibility helper for call sites that need only the best matching route. */
    fun findNetworkForHost(context: Context, host: String): Network? =
        findNetworksForHost(context, host).firstOrNull()

    /** True when the host belongs to Android's conventional Wi-Fi Direct group subnet. */
    fun isWifiDirectGroupHost(host: String): Boolean = runCatching {
        val address = InetAddress.getByName(host) as? Inet4Address ?: return@runCatching false
        isAndroidWifiDirectGroupAddress(address)
    }.getOrDefault(false)

    internal fun isWifiDirectInterfaceName(name: String): Boolean =
        name.trim().lowercase().let { it.startsWith("p2p") }

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
