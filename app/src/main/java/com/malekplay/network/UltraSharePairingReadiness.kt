package com.malekplay.network

/**
 * Keeps the invitation hidden until both independently-bound receiver sockets
 * and the temporary hotspot are actually ready.
 */
internal object UltraSharePairingReadiness {
    fun isDataListenerReady(actualPort: Int, expectedPort: Int): Boolean =
        expectedPort in 1..65535 && actualPort == expectedPort

    fun canShowHotspotQr(
        receiving: Boolean,
        controlChannelReady: Boolean,
        dataChannelReady: Boolean,
        hotspotActive: Boolean,
        activePeerCount: Int,
        pairingSecret: String,
        hotspotSsid: String?,
        hotspotPassword: String?,
        localAddresses: List<String>
    ): Boolean =
        receiving &&
            controlChannelReady &&
            dataChannelReady &&
            hotspotActive &&
            activePeerCount == 0 &&
            UltraShareTransport.isValidPairingSecret(pairingSecret) &&
            !hotspotSsid.isNullOrBlank() &&
            !hotspotPassword.isNullOrBlank() &&
            localAddresses.any(UltraShareTransport::isValidPrivateLanHost)

    fun canSendData(controlSessionActive: Boolean, dataEndpointReachable: Boolean): Boolean =
        controlSessionActive && dataEndpointReachable
}
