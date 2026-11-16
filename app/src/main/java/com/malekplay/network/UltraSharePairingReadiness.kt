package com.malekplay.network

/**
 * Keeps the invitation hidden until both independently-bound receiver sockets
 * and the temporary hotspot are actually ready.
 */
internal object UltraSharePairingReadiness {
    /** Keeps the first invitation bytes stable across transient network/address updates. */
    fun stableQrPayload(existing: String?, candidate: String?): String? =
        existing?.takeIf { it.isNotBlank() } ?: candidate?.takeIf { it.isNotBlank() }

    fun isDataListenerReady(actualPort: Int, expectedPort: Int): Boolean =
        expectedPort in 1..65535 && actualPort == expectedPort

    fun canShowWifiDirectQr(
        receiving: Boolean,
        controlChannelReady: Boolean,
        dataChannelReady: Boolean,
        activePeerCount: Int,
        pairingSecret: String,
        invitationId: String?
    ): Boolean =
        receiving &&
            controlChannelReady &&
            dataChannelReady &&
            activePeerCount == 0 &&
            UltraShareTransport.isValidPairingSecret(pairingSecret) &&
            !invitationId.isNullOrBlank()

    fun canShowLanQr(
        receiving: Boolean,
        controlChannelReady: Boolean,
        dataChannelReady: Boolean,
        lanFallbackReady: Boolean,
        activePeerCount: Int,
        pairingSecret: String,
        localAddresses: List<String>
    ): Boolean =
        receiving &&
            controlChannelReady &&
            dataChannelReady &&
            lanFallbackReady &&
            activePeerCount == 0 &&
            UltraShareTransport.isValidPairingSecret(pairingSecret) &&
            localAddresses.any(UltraShareTransport::isValidPrivateLanHost)

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
