package com.malekplay.network

/**
 * Publishes pairing invitations once the authenticated control listener and
 * required network invitation are ready. The V2 data listener is independent:
 * its delay or failure must not block pairing.
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

    @Suppress("UNUSED_PARAMETER")
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
            activePeerCount == 0 &&
            UltraShareCore.isValidPairingSecret(pairingSecret) &&
            !invitationId.isNullOrBlank()

    @Suppress("UNUSED_PARAMETER")
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
            lanFallbackReady &&
            activePeerCount == 0 &&
            UltraShareCore.isValidPairingSecret(pairingSecret) &&
            localAddresses.any(UltraShareCore::isValidPrivateLanHost)

    @Suppress("UNUSED_PARAMETER")
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
            hotspotActive &&
            activePeerCount == 0 &&
            UltraShareCore.isValidPairingSecret(pairingSecret) &&
            !hotspotSsid.isNullOrBlank() &&
            !hotspotPassword.isNullOrBlank() &&
            localAddresses.any(UltraShareCore::isValidPrivateLanHost)

    fun canSendData(controlSessionActive: Boolean, dataEndpointReachable: Boolean): Boolean =
        controlSessionActive && dataEndpointReachable
}

/** A known peer ID must match; a missing ID is allowed only for legacy peers. */
internal fun matchesExpectedUltraSharePeerIdentity(
    expectedPeerId: String?,
    authenticatedPeerId: String?
): Boolean {
    if (expectedPeerId.isNullOrBlank() || authenticatedPeerId.isNullOrBlank()) return true
    return expectedPeerId.replace("-", "").equals(
        authenticatedPeerId.replace("-", ""),
        ignoreCase = true
    )
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
                current.message == "إصدار قناة التحكم غير متوافق.") ||
            (current is IllegalStateException &&
                current.message?.startsWith("هوية الجهاز لا تطابق الجهاز المكتشف") == true)
        ) return true
        current = current.cause
    }
    return false
}
