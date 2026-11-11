package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Selects the Android network whose routing table can actually reach a paired peer.
 * This matters on phones that expose both the normal WLAN and a Wi-Fi Direct
 * interface at the same time: a valid private IP is not necessarily reachable
 * through the process default network.
 */
object UltraShareNetworkRouter {
    fun networkForHost(context: Context, host: String): Network? {
        val target = parsePrivateIpv4(host) ?: return null
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        return connectivity.allNetworks
            .mapNotNull { network ->
                val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                val matchingRoutes = properties.routes
                    .filter { it.matches(target) }
                    .maxOfOrNull { it.destination.prefixLength }
                    ?: return@mapNotNull null
                val capabilities = connectivity.getNetworkCapabilities(network)
                val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                Triple(network, matchingRoutes, wifi)
            }
            .sortedWith(
                compareByDescending<Triple<Network, Int, Boolean>> { it.second }
                    .thenByDescending { it.third }
            )
            .firstOrNull()
            ?.first
    }

    private fun parsePrivateIpv4(host: String): InetAddress? = runCatching {
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))
        (InetAddress.getByName(host) as? Inet4Address)?.takeIf {
            !it.isAnyLocalAddress && !it.isLoopbackAddress &&
                !it.isMulticastAddress && (it.isSiteLocalAddress || it.isLinkLocalAddress)
        }
    }.getOrNull()
}
