package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Resolves the Android Network whose local IPv4 link contains a remote LAN peer.
 * Binding sockets to that Network prevents a candidate discovered on Wi-Fi Direct
 * from accidentally being tested or used through the device's default WLAN route.
 */
internal object UltraShareNetworkRouting {
    fun findNetworkForHost(context: Context, host: String): Network? = runCatching {
        val remote = InetAddress.getByName(host) as? Inet4Address ?: return@runCatching null
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@runCatching null

        manager.allNetworks.mapNotNull { network ->
            val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
            val matchingLink = properties.linkAddresses.firstOrNull { link ->
                val local = link.address as? Inet4Address ?: return@firstOrNull false
                isInPrefix(remote, local, link.prefixLength)
            } ?: return@mapNotNull null
            val capabilities = manager.getNetworkCapabilities(network)
            val rank = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 4
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 3
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 2
                else -> 1
            }
            Triple(network, rank, matchingLink.prefixLength)
        }.maxWithOrNull(
            compareBy<Triple<Network, Int, Int>> { it.third }.thenBy { it.second }
        )?.first
    }.getOrNull()

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
