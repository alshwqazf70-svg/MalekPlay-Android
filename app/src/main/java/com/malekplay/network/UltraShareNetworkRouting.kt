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
    fun findNetworkForHost(context: Context, host: String): Network? = runCatching {
        val remote = InetAddress.getByName(host) as? Inet4Address ?: return@runCatching null
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@runCatching null

        manager.allNetworks.mapNotNull { network ->
            val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
            val interfaceName = properties.interfaceName.orEmpty()
            val p2pInterface = isWifiDirectInterfaceName(interfaceName)
            val matchingLinkPrefix = properties.linkAddresses.mapNotNull { link ->
                val local = link.address as? Inet4Address ?: return@mapNotNull null
                link.prefixLength.takeIf { isInPrefix(remote, local, it) }
            }.maxOrNull()
            val matchingRoutePrefix = properties.routes.mapNotNull { route ->
                val destination = route.destination
                val routeAddress = destination.address as? Inet4Address ?: return@mapNotNull null
                val prefix = destination.prefixLength
                // Ignore the default route: it says nothing about which local
                // interface owns a peer-specific path.
                prefix.takeIf { it in 1..32 && isInPrefix(remote, routeAddress, it) }
            }.maxOrNull()

            val p2pGroupOwnerRoute = p2pInterface &&
                isAndroidWifiDirectGroupAddress(remote)
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
            Triple(network, matchRank * 100 + transportRank * 10 + prefixRank, prefixRank)
        }.maxWithOrNull(
            compareBy<Triple<Network, Int, Int>> { it.second }.thenBy { it.third }
        )?.first
    }.getOrNull()

    /** True when the host belongs to Android's conventional Wi-Fi Direct group subnet. */
    fun isWifiDirectGroupHost(host: String): Boolean = runCatching {
        val address = InetAddress.getByName(host) as? Inet4Address ?: return@runCatching false
        isAndroidWifiDirectGroupAddress(address)
    }.getOrDefault(false)

    internal fun isWifiDirectInterfaceName(name: String): Boolean =
        name.trim().lowercase().let { it.startsWith("p2p") }

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
