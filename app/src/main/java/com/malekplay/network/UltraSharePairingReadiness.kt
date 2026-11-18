package com.malekplay.network

/**
 * Keeps the invitation hidden until both independently-bound receiver sockets
 * and the temporary hotspot are actually ready.
 */
internal object UltraSharePairingReadiness {
    /** Reuses stored route hints only for the exact same ephemeral invitation. */
    fun stableInvitationHosts(
        storedInvitationId: String?,
        invitationId: String,
        storedHosts: List<String>,
        currentHosts: List<String>
    ): List<String> =
        if (invitationId.isNotBlank() && storedInvitationId == invitationId) storedHosts
        else currentHosts

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
            UltraShareCore.isValidPairingSecret(pairingSecret) &&
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
            UltraShareCore.isValidPairingSecret(pairingSecret) &&
            localAddresses.any(UltraShareCore::isValidPrivateLanHost)

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
            UltraShareCore.isValidPairingSecret(pairingSecret) &&
            !hotspotSsid.isNullOrBlank() &&
            !hotspotPassword.isNullOrBlank() &&
            localAddresses.any(UltraShareCore::isValidPrivateLanHost)

    fun canSendData(controlSessionActive: Boolean, dataEndpointReachable: Boolean): Boolean =
        controlSessionActive && dataEndpointReachable
}

/** Retry only immediate route-establishment failures; never replay user approval timeouts. */
internal fun isRetryableUltraSharePairingFailure(failure: Throwable): Boolean {
    val visited = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Throwable, Boolean>()
    )
    var current: Throwable? = failure
    while (current != null && visited.add(current)) {
        if (current is java.net.ConnectException ||
            current is java.net.NoRouteToHostException ||
            (current is IllegalArgumentException &&
                current.message == "إصدار قناة التحكم غير متوافق.")
        ) return true
        current = current.cause
    }
    return false
}
