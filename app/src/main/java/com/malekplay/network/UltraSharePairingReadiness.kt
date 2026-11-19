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

    /**
     * A receiver may publish a LAN/DNS-SD fallback QR before Android returns its
     * Wi-Fi Direct SoftAP credentials. Permit exactly one monotonic upgrade to v5
     * for the same ephemeral invitation and secret; never rotate an established v5
     * invitation or replace it with another session's credentials.
     */
    fun shouldUpgradeStableQrToUnifiedV5(
        existingVersion: String?,
        candidateVersion: String?,
        existingInvitationId: String?,
        candidateInvitationId: String?,
        existingPairingSecret: String?,
        candidatePairingSecret: String?
    ): Boolean =
        existingVersion != "5" &&
            candidateVersion == "5" &&
            !existingInvitationId.isNullOrBlank() &&
            existingInvitationId == candidateInvitationId &&
            !existingPairingSecret.isNullOrBlank() &&
            existingPairingSecret == candidatePairingSecret

    /**
     * Local interface enumeration can finish after the first v5 QR is shown.
     * Enrich that same invitation only when every existing route is retained
     * and the candidate adds at least one new host with identical credentials.
     */
    fun shouldEnrichUnifiedV5QrHosts(
        existingVersion: String?,
        candidateVersion: String?,
        existingInvitationId: String?,
        candidateInvitationId: String?,
        existingPairingSecret: String?,
        candidatePairingSecret: String?,
        existingSsid: String?,
        candidateSsid: String?,
        existingWifiPassword: String?,
        candidateWifiPassword: String?,
        existingWifiSecurity: String?,
        candidateWifiSecurity: String?,
        existingHosts: List<String>,
        candidateHosts: List<String>
    ): Boolean =
        existingVersion == "5" &&
            candidateVersion == "5" &&
            !existingInvitationId.isNullOrBlank() &&
            existingInvitationId == candidateInvitationId &&
            !existingPairingSecret.isNullOrBlank() &&
            existingPairingSecret == candidatePairingSecret &&
            existingSsid == candidateSsid &&
            existingWifiPassword == candidateWifiPassword &&
            existingWifiSecurity == candidateWifiSecurity &&
            (existingSsid.isNullOrBlank() == existingWifiPassword.isNullOrBlank()) &&
            candidateHosts.size > existingHosts.size &&
            candidateHosts.containsAll(existingHosts)

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
            UltraShareCore.isValidPairingSecret(pairingSecret) &&
            !hotspotSsid.isNullOrBlank() &&
            !hotspotPassword.isNullOrBlank() &&
            localAddresses.any(UltraShareCore::isValidPrivateLanHost)
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
            (current is java.net.SocketTimeoutException &&
                !current.message.orEmpty().contains("approval", ignoreCase = true)) ||
            current is java.net.SocketException ||
            current is java.io.EOFException ||
            (current is IllegalArgumentException &&
                current.message == "إصدار قناة التحكم غير متوافق.") ||
            (current is IllegalStateException &&
                current.message?.startsWith("هوية الجهاز لا تطابق الجهاز المكتشف") == true)
        ) return true
        current = current.cause
    }
    return false
}
