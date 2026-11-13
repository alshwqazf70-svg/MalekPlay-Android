package com.malekplay.network

import android.content.Context
import android.net.Network

/**
 * Compatibility facade for callers that need the Android network for a peer.
 * Keep one routing policy so legacy single-file sends and the bidirectional
 * control/data channels cannot disagree about which interface owns the route.
 */
object UltraShareNetworkRouter {
    fun networkForHost(context: Context, host: String): Network? =
        UltraShareNetworkRouting.findNetworkForHost(context, host)
}
