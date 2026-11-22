package com.malekplay.network

/**
 * Publishes pairing invitations once the authenticated control listener and
 * required network invitation are ready. The V2 data listener is independent:
 * its delay or failure must not block pairing.
 */
internal object UltraSharePairingReadiness {
    /** P2P recovery is allowed only after LAN probing and any hotspot transition are complete. */
    fun shouldStartWifiDirectFallback(
        receiving: Boolean,
        lanProbeInProgress: Boolean,
        hotspotActive: Boolean,
        hotspotFallbackStarted: Boolean,
        invitationIdBlank: Boolean,
        wifiDirectAlreadyAttempted: Boolean,
        hasActivePeers: Boolean,
        hasActiveLanRoute: Boolean
    ): Boolean =
        receiving &&
            !lanProbeInProgress &&
            !hotspotActive &&
            !hotspotFallbackStarted &&
            invitationIdBlank &&
            !wifiDirectAlreadyAttempted &&
            !hasActivePeers &&
            !hasActiveLanRoute
    /**
     * Legacy unified-v5 invitations carry a DNS-SD peer ID but no explicit mode.
     * Treat that identity as a P2P invitation before considering optional SSID/PSK
     * credentials, otherwise older P2P QR codes are misrouted into hotspot joining.
     */
    fun shouldUseWifiDirectInvitation(mode: String?, invitationId: String?): Boolean =
        !invitationId.isNullOrBlank() && (mode.isNullOrBlank() || mode == "p2p")

    /** A delayed callback may mutate state only while its exact invitation attempt is current. */
    fun isCurrentInvitationAttempt(current: Any?, expected: Any): Boolean = current === expected

    /** A discovery timeout may use LAN only if no P2P connection negotiation has begun. */
    fun shouldUseLanFallbackAfterInvitationDiscoveryTimeout(
        hasPendingInvitation: Boolean,
        wifiDirectConnectionStarted: Boolean
    ): Boolean = hasPendingInvitation && !wifiDirectConnectionStarted

    /** Reuses stored route hints only for the exact same ephemeral invitation. */
    fun stableInvitationHosts(
        storedInvitationId: String?,
        invitationId: String,
        storedHosts: List<String>,
        currentHosts: List<String>
    ): List<String> =
        if (invitationId.isNotBlank() && storedInvitationId == invitationId && currentHosts.isEmpty()) {
            storedHosts
        } else {
            currentHosts
        }

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
    fun shouldEnrichUnifiedV5Qr(
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
    ): Boolean {
        val existingHasWifi = !existingSsid.isNullOrBlank() && !existingWifiPassword.isNullOrBlank()
        val candidateHasWifi = !candidateSsid.isNullOrBlank() && !candidateWifiPassword.isNullOrBlank()
        val existingHasPartialWifi =
            !existingSsid.isNullOrBlank() || !existingWifiPassword.isNullOrBlank()
        val candidateHasPartialWifi =
            !candidateSsid.isNullOrBlank() || !candidateWifiPassword.isNullOrBlank()
        val credentialsCompatible = when {
            existingHasWifi -> candidateHasWifi &&
                existingSsid == candidateSsid &&
                existingWifiPassword == candidateWifiPassword &&
                existingWifiSecurity == candidateWifiSecurity
            existingHasPartialWifi -> false
            candidateHasWifi -> true
            candidateHasPartialWifi -> false
            else -> existingWifiSecurity == candidateWifiSecurity
        }
        val addsWifiCredentials = !existingHasWifi && candidateHasWifi
        val hasValidCandidateHosts = candidateHosts.isNotEmpty() &&
            candidateHosts.all(UltraShareCore::isValidPrivateLanHost)
        val changesHosts = candidateHosts != existingHosts
        return existingVersion == "5" &&
            candidateVersion == "5" &&
            !existingInvitationId.isNullOrBlank() &&
            existingInvitationId == candidateInvitationId &&
            !existingPairingSecret.isNullOrBlank() &&
            existingPairingSecret == candidatePairingSecret &&
            credentialsCompatible &&
            hasValidCandidateHosts &&
            (addsWifiCredentials || changesHosts)
    }

    /** Allows a v6 room QR to replace stale route hints without changing its session identity. */
    fun shouldRefreshRoomV6Qr(
        existingVersion: String?,
        candidateVersion: String?,
        existingInvitationId: String?,
        candidateInvitationId: String?,
        existingPairingSecret: String?,
        candidatePairingSecret: String?,
        existingDeviceName: String?,
        candidateDeviceName: String?,
        existingSsid: String?,
        candidateSsid: String?,
        existingWifiPassword: String?,
        candidateWifiPassword: String?,
        candidateHosts: List<String>
    ): Boolean {
        val credentialsCompatible =
            (existingSsid.isNullOrBlank() || existingSsid == candidateSsid) &&
                (existingWifiPassword.isNullOrBlank() || existingWifiPassword == candidateWifiPassword)
        return existingVersion == "6" &&
            candidateVersion == "6" &&
            existingInvitationId == candidateInvitationId &&
            !existingPairingSecret.isNullOrBlank() &&
            existingPairingSecret == candidatePairingSecret &&
            existingDeviceName == candidateDeviceName &&
            credentialsCompatible &&
            candidateHosts.isNotEmpty() &&
            candidateHosts.all(UltraShareCore::isValidPrivateLanHost)
    }

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

/** Clear a cached P2P route only after a previously confirmed group actually disconnects. */
internal fun shouldClearWifiDirectLocalAddress(groupFormed: Boolean, wasConnected: Boolean): Boolean =
    !groupFormed && wasConnected

/** Poll an empty receiver room gently; verify a visible peer more frequently within its deadline. */
internal fun wifiDirectOwnerPeerConfirmationDelayMs(hasObservedClient: Boolean): Long =
    if (hasObservedClient) 500L else 2_000L

/** A known peer ID must match; a missing ID is allowed only for legacy peers. */
internal fun matchesExpectedUltraSharePeerIdentity(
    expectedPeerId: String?,
    authenticatedPeerId: String?
): Boolean {
    // A legacy invitation may omit the expected ID. Once discovery supplied
    // an expected identity, the authenticated handshake must present it too.
    // Only a genuinely absent ID denotes a legacy discovery record. A blank
    // value is malformed metadata and must not silently bypass identity binding.
    if (expectedPeerId == null) return true
    if (expectedPeerId.isBlank() || authenticatedPeerId.isNullOrBlank()) return false
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
