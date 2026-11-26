package com.malekplay.ui.network

import android.content.Context
import android.os.Build
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.network.UltraShareDiagnostics
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.isRetryableUltraSharePairingFailure
import com.malekplay.network.UltraShareCore
import com.malekplay.network.UltraShareBatchTransport
import com.malekplay.network.UltraShareControlChannel
import com.malekplay.data.repository.MediaRepository
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareNegotiation
import com.malekplay.network.UltraShareNetworkRouting
import com.malekplay.network.UltraSharePairingReadiness
import com.malekplay.network.UltraSharePlatformCompatibility
import com.malekplay.network.UltraShareQueueEntry
import com.malekplay.network.UltraShareReceivedEntry
import com.malekplay.network.UltraShareReceivedHistoryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraShareApprovedSendTarget
import com.malekplay.network.UltraSharePeerDeliveryStore
import com.malekplay.network.UltraShareMultiRecipientCoordinator
import com.malekplay.network.UltraShareWifiDirect
import com.malekplay.network.UltraShareBluetoothDiscovery
import com.malekplay.network.UltraShareBleDiscovery
import com.malekplay.network.IncomingMirrorOffer
import com.malekplay.network.UltraShareScreenMirrorProtocol
import com.malekplay.network.UltraShareScreenMirrorService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class UltraShareUiState(
    val pairingSecret: String = "",
    val invitationId: String = "",
    // Immutable QR invitation payload for the active receiver attempt.
    val receiverQrPayload: String? = null,
    val localAddresses: List<String> = emptyList(),
    val receiving: Boolean = false,
    val controlChannelReady: Boolean = false,
    val dataChannelReady: Boolean = false,
    val peerDataChannelReady: Boolean = false,
    val sending: Boolean = false,
    val scanning: Boolean = false,
    val discoveredPeers: List<UltraShareDiscovery.Peer> = emptyList(),
    val progress: UltraShareCore.Progress? = null,
    val sendProgress: UltraShareCore.Progress? = null,
    val receiveProgress: UltraShareCore.Progress? = null,
    val receivedFile: UltraShareCore.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null,
    val queueCompleted: Int = 0,
    val queueTotal: Int = 0,
    val queueCurrentName: String? = null,
    val queueTotalBytes: Long = 0L,
    val queueTransferredBytes: Long = 0L,
    val lastTransferMetrics: com.malekplay.network.UltraShareBatchTransport.TransferMetrics? = null,
    val lastReceiveMetrics: com.malekplay.network.UltraShareBatchTransport.ReceiveMetrics? = null,
    val queueEntries: List<UltraShareQueueEntry> = emptyList(),
    val receivedHistory: List<UltraShareReceivedEntry> = emptyList(),
    val wifiDirectPeers: List<UltraShareWifiDirect.Peer> = emptyList(),
    val bluetoothPeers: List<UltraShareBluetoothDiscovery.Peer> = emptyList(),
    val blePeers: List<UltraShareBleDiscovery.Peer> = emptyList(),
    val bleScanning: Boolean = false,
    val bluetoothScanning: Boolean = false,
    val wifiDirectScanning: Boolean = false,
    val wifiDirectHost: String? = null,
    /** Actual group-owner address reported by Android's Wi-Fi Direct stack. */
    val wifiDirectLocalAddress: String? = null,
    val hotspotSsid: String? = null,
    val hotspotPassword: String? = null,
    val hotspotSecurity: String = "wpa2",
    val hotspotStarting: Boolean = false,
    val hotspotActive: Boolean = false,
    val lanFallbackReady: Boolean = false,
    val pendingConnection: UltraShareControlChannel.Request? = null,
    val approvedPeerHost: String? = null,
    val requestingConnection: Boolean = false,
    val activePeers: List<com.malekplay.network.UltraSharePeerSession> = emptyList(),
    val reconnectablePeers: List<com.malekplay.network.UltraSharePeerSession> = emptyList(),
    val peerTransfers: List<com.malekplay.network.UltraSharePeerTransfer> = emptyList(),
    val approvedSenderPeerCount: Int = 0,
    val approvedSenderPeerHosts: List<String> = emptyList(),
    val reconnectableSenderPeerHosts: List<String> = emptyList(),
    val peerDeliveryEntries: List<com.malekplay.network.UltraSharePeerDeliveryEntry> = emptyList(),
    val roomResetGeneration: Long = 0L,
    val mirrorHostState: UltraShareScreenMirrorService.MirrorHostState =
        UltraShareScreenMirrorService.MirrorHostState(),
    val incomingMirrorOffer: IncomingMirrorOffer? = null,
    val watchingMirrorSession: IncomingMirrorOffer? = null,
    val mirrorAudioRequested: Boolean = false
)

@HiltViewModel
class UltraShareViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _state = MutableStateFlow(UltraShareUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var discoveryStoppedForPairedSession = false
    private var wifiDirectScanTimeout: Job? = null
    private var bluetoothScanTimeout: Job? = null
    private var wifiDirectSession: UltraShareWifiDirect.Session? = null
    private val invitationPreferences by lazy {
        context.getSharedPreferences("ultra_share_invitation", Context.MODE_PRIVATE)
    }

    private fun clearStoredInvitation() {
        invitationPreferences.edit()
            .remove("active_invitation_id")
            .remove("active_invitation_hosts_id")
            .remove("active_invitation_hosts")
            .apply()
    }

    /** Reuses the exact host list issued with this invitation without persisting its secret. */
    fun stableWifiDirectQrHosts(invitationId: String, currentHosts: List<String>): List<String> {
        if (invitationId.isBlank()) return currentHosts
        val storedId = invitationPreferences.getString("active_invitation_hosts_id", null)
        val storedHosts = invitationPreferences.getString("active_invitation_hosts", null)
        val parsedHosts = storedHosts?.split(',')?.map { it.trim() }
            ?.filter { UltraShareCore.isValidPrivateLanHost(it) }
            ?.distinct()?.take(8).orEmpty()
        val activeStoredHosts = UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
            context, parsedHosts
        )
        return com.malekplay.network.UltraSharePairingReadiness.stableInvitationHosts(
            storedId, invitationId, activeStoredHosts, currentHosts
        )
    }
    private var bluetoothSession: UltraShareBluetoothDiscovery.Session? = null
    private var bleSession: UltraShareBleDiscovery.Session? = null
    // The requested Wi-Fi Network is process-scoped, not screen-scoped. Keep
    // its callback lease shared across ViewModel recreation while a paired session
    // is alive; stopReceiving() or onLost() releases it.
    private var hotspotNetworkCallback: android.net.ConnectivityManager.NetworkCallback?
        get() = retainedHotspotNetworkCallback
        set(value) { retainedHotspotNetworkCallback = value }
    private var hotspotNetworkPaired: Boolean
        get() = retainedHotspotNetworkPaired
        set(value) { retainedHotspotNetworkPaired = value }
    private data class PendingQrPairing(
        val secret: String,
        val hosts: List<String>,
        val details: com.malekplay.network.UltraShareQrCode.PairingDetails? = null
    )
    private var pendingQrPairing: PendingQrPairing? = null
    private var qrP2pConnectStarted = false
    private var receiverWifiDirectTimeoutJob: kotlinx.coroutines.Job? = null
    // UI's requestingConnection also covers Wi-Fi Network requests and P2P discovery.
    // Keep the actual MPC2 handshake guard independent so those flows can hand off
    // into requestConnection without blocking themselves.
    private var controlPairingInProgress = false
    private var wifiDirectPrimaryAttempt = false
    // Prevent the service-state observer from racing LAN discovery by starting P2P early.
    @Volatile private var lanProbeInProgress = false
    private var hotspotFallbackStarted = false
    @Volatile private var wifiDirectGroupSsid: String? = null
    @Volatile private var wifiDirectGroupPassword: String? = null
    @Volatile private var wifiDirectOwnerAddress: String? = null
    @Volatile private var wifiDirectGroupSecurity: String = "wpa2"
    @Volatile private var wifiDirectCredentialsFromJoinedInvitation = false
    private var dnsSdQrFallbackScheduledFor: String? = null
    @Volatile private var wifiDirectAdvertisedInvitationId: String? = null
    private var wifiDirectCleanupInProgress = false
    private companion object {
        const val PEER_SIGHTING_TTL_MS = 12_000L
        @Volatile private var retainedHotspotNetworkCallback:
            android.net.ConnectivityManager.NetworkCallback? = null
        @Volatile private var retainedHotspotNetworkPaired: Boolean = false
    }

    init {
        viewModelScope.launch {
            if (!UltraShareForegroundService.state.value.sending) {
                UltraShareQueueStore.recoverInterrupted(context)
                UltraSharePeerDeliveryStore.recoverInterrupted(context)
            }
            UltraShareQueueStore.observe(context).collect { entries ->
                _state.value = _state.value.copy(queueEntries = entries)
            }
        }
        viewModelScope.launch {
            UltraShareReceivedHistoryStore.observe(context).collect { entries ->
                _state.value = _state.value.copy(receivedHistory = entries)
            }
        }
        viewModelScope.launch {
            UltraSharePeerDeliveryStore.observe(context).collect { entries ->
                _state.value = _state.value.copy(peerDeliveryEntries = entries)
            }
        }
        viewModelScope.launch {
            UltraShareScreenMirrorService.hostState.collect { hostState ->
                _state.value = _state.value.copy(mirrorHostState = hostState)
            }
        }
        viewModelScope.launch {
            UltraShareForegroundService.state.collect { serviceState ->
                val current = _state.value
                val received = serviceState.receivedFile
                val roomPresentationWasReset = serviceState.roomResetGeneration != current.roomResetGeneration
                val updatedWatching = when {
                    current.watchingMirrorSession == null -> null
                    serviceState.incomingMirrorOffer == null -> null
                    serviceState.incomingMirrorOffer.sessionId == current.watchingMirrorSession.sessionId ->
                        serviceState.incomingMirrorOffer
                    else -> current.watchingMirrorSession
                }
                val stoppedWatchingNotice = current.watchingMirrorSession != null && updatedWatching == null
                if (received?.publishedUri != null &&
                    received.file.absolutePath != current.receivedFile?.file?.absolutePath
                ) {
                    // Refresh Room after MediaStore publication so received video/audio appears in My Library.
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { mediaRepository.refresh() }
                    }
                }
                _state.value = current.copy(
                    pairingSecret = serviceState.pairingSecret,
                    localAddresses = serviceState.localAddresses,
                    receiving = serviceState.receiving,
                    controlChannelReady = serviceState.controlChannelReady,
                    dataChannelReady = serviceState.dataChannelReady,
                    sending = serviceState.sending,
                    progress = serviceState.progress,
                    sendProgress = serviceState.sendProgress,
                    receiveProgress = serviceState.receiveProgress,
                    receivedFile = serviceState.receivedFile,
                    status = if (stoppedWatchingNotice) {
                        "انتهى بث الشاشة الجماعية من منشئ الغرفة."
                    } else if (roomPresentationWasReset) {
                        null
                    } else {
                        serviceState.status ?: current.status
                    },
                    error = if (roomPresentationWasReset) null else serviceState.error,
                    queueCompleted = serviceState.queueCompleted,
                    queueTotal = serviceState.queueTotal,
                    queueCurrentName = serviceState.queueCurrentName,
                    queueTotalBytes = serviceState.queueTotalBytes,
                    queueTransferredBytes = serviceState.queueTransferredBytes,
                    lastTransferMetrics = serviceState.lastTransferMetrics,
                    lastReceiveMetrics = serviceState.lastReceiveMetrics,
                    hotspotSsid = wifiDirectGroupSsid ?: serviceState.hotspotSsid,
                    hotspotPassword = wifiDirectGroupPassword ?: serviceState.hotspotPassword,
                    hotspotStarting = serviceState.hotspotStarting,
                    hotspotActive = serviceState.hotspotActive,
                    lanFallbackReady = current.lanFallbackReady || (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                            hotspotFallbackStarted &&
                            serviceState.receiving &&
                            UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
                                context, serviceState.localAddresses
                            ).isNotEmpty()
                        ),
                    pendingConnection = serviceState.pendingConnection,
                    activePeers = serviceState.roomPeers.ifEmpty { serviceState.activePeers },
                    reconnectablePeers = serviceState.reconnectablePeers,
                    peerTransfers = serviceState.peerTransfers,
                    roomResetGeneration = serviceState.roomResetGeneration,
                    approvedSenderPeerCount = serviceState.approvedSenderPeerHosts.size,
                    approvedSenderPeerHosts = serviceState.approvedSenderPeerHosts,
                    reconnectableSenderPeerHosts = serviceState.reconnectableSenderPeerHosts,
                    approvedPeerHost = current.approvedPeerHost?.takeIf { approved ->
                        serviceState.activePeers.any { it.host == approved }
                    },
                    incomingMirrorOffer = serviceState.incomingMirrorOffer,
                    watchingMirrorSession = updatedWatching
                )
                val pairedSessionActive = serviceState.activePeers.isNotEmpty() ||
                    serviceState.approvedSenderPeerHosts.isNotEmpty()
                if (serviceState.hotspotFailed &&
                    hotspotFallbackStarted &&
                    serviceState.receiving &&
                    !serviceState.hotspotActive &&
                    !serviceState.hotspotStarting &&
                    !current.lanFallbackReady
                ) {
                    val liveLan = UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
                        context,
                        serviceState.localAddresses + current.localAddresses
                    )
                    if (liveLan.isNotEmpty()) {
                        UltraShareDiagnostics.event(
                            "PAIRING",
                            "HOTSPOT_FAILED_SWITCHED_TO_LAN",
                            mapOf("lanCount" to liveLan.size)
                        )
                        _state.value = _state.value.copy(
                            invitationId = "",
                            localAddresses = liveLan,
                            lanFallbackReady = true,
                            hotspotStarting = false,
                            hotspotActive = false,
                            error = null,
                            status = "تعذرت نقطة الاتصال؛ تم الانتقال تلقائياً إلى الشبكة المحلية (LAN). صِل الجهازين بالشبكة نفسها وامسح الرمز."
                        )
                    }
                }
                if (serviceState.controlChannelReady) publishUnifiedReceiverQrIfReady()
                if (pairedSessionActive) {
                    stopNearbyDiscoveryAfterPairing()
                } else {
                    discoveryStoppedForPairedSession = false
                }
                // A navigation-scoped ViewModel can be recreated while the foreground
                // receiver service and P2P group remain alive. Re-publish the ephemeral
                // invitation on the retained group with Wi-Fi Direct as primary transport.
                if (UltraSharePairingReadiness.shouldStartPrimaryWifiDirect(
                        receiving = serviceState.receiving,
                        hotspotActive = serviceState.hotspotActive || serviceState.hotspotStarting ||
                            _state.value.hotspotActive || _state.value.hotspotStarting,
                        hotspotFallbackStarted = hotspotFallbackStarted,
                        invitationIdBlank = _state.value.invitationId.isBlank(),
                        wifiDirectAlreadyAttempted = wifiDirectPrimaryAttempt,
                        hasActivePeers = serviceState.activePeers.isNotEmpty()
                    )
                ) {
                    wifiDirectPrimaryAttempt = true
                    viewModelScope.launch {
                        delay(350L)
                        if (_state.value.receiving &&
                            _state.value.invitationId.isBlank() &&
                            !hotspotFallbackStarted
                        ) {
                            advertiseCurrentWifiDirectInvitation()
                        }
                    }
                }
            }
        }
    }

    /** Latch the first valid receiver QR payload; later network/address refreshes cannot rotate it. */
    fun latchReceiverQrPayload(payload: String) {
        if (payload.isBlank() || payload.length > 16_384) return
        val current = _state.value
        if (!current.receiving ||
            !UltraShareCore.isValidPairingSecret(current.pairingSecret)
        ) return
        val candidateDetails = runCatching { UltraShareQrCode.parsePayload(payload) }.getOrNull()
            ?: return
        val existingPayload = current.receiverQrPayload
        val existingDetails = existingPayload?.let {
            runCatching { UltraShareQrCode.parsePayload(it) }.getOrNull()
        }
        val existingVersion = existingPayload?.let { Uri.parse(it).getQueryParameter("v") }
        val candidateVersion = Uri.parse(payload).getQueryParameter("v")
        val readiness = com.malekplay.network.UltraSharePairingReadiness
        val mayUpgradeToUnifiedV5 = existingPayload != null &&
            readiness.shouldUpgradeStableQrToUnifiedV5(
                existingVersion = existingVersion,
                candidateVersion = candidateVersion,
                existingInvitationId = existingDetails?.invitationId,
                candidateInvitationId = candidateDetails.invitationId,
                existingPairingSecret = existingDetails?.pairingSecret,
                candidatePairingSecret = candidateDetails.pairingSecret
            )
        val mayUpgradeOrRefreshRoomV6 = existingPayload != null && existingDetails != null &&
            candidateVersion == "6" &&
            existingDetails.pairingSecret == candidateDetails.pairingSecret &&
            existingDetails.invitationId == candidateDetails.invitationId &&
            (existingVersion != "6" || readiness.shouldRefreshRoomV6Qr(
                existingVersion = existingVersion,
                candidateVersion = candidateVersion,
                existingInvitationId = existingDetails.invitationId,
                candidateInvitationId = candidateDetails.invitationId,
                existingPairingSecret = existingDetails.pairingSecret,
                candidatePairingSecret = candidateDetails.pairingSecret,
                existingDeviceName = existingDetails.deviceName,
                candidateDeviceName = candidateDetails.deviceName,
                existingSsid = existingDetails.hotspotSsid,
                candidateSsid = candidateDetails.hotspotSsid,
                existingWifiPassword = existingDetails.hotspotPassword,
                candidateWifiPassword = candidateDetails.hotspotPassword,
                candidateHosts = candidateDetails.hosts
            ))
        val mayEnrichV5Routes = existingPayload != null && existingDetails != null &&
            readiness.shouldEnrichUnifiedV5Qr(
                existingVersion = existingVersion,
                candidateVersion = candidateVersion,
                existingInvitationId = existingDetails.invitationId,
                candidateInvitationId = candidateDetails.invitationId,
                existingPairingSecret = existingDetails.pairingSecret,
                candidatePairingSecret = candidateDetails.pairingSecret,
                existingSsid = existingDetails.hotspotSsid,
                candidateSsid = candidateDetails.hotspotSsid,
                existingWifiPassword = existingDetails.hotspotPassword,
                candidateWifiPassword = candidateDetails.hotspotPassword,
                existingWifiSecurity = existingDetails.hotspotSecurity,
                candidateWifiSecurity = candidateDetails.hotspotSecurity,
                existingHosts = existingDetails.hosts,
                candidateHosts = candidateDetails.hosts
            )
        // Upgrade a fallback QR when P2P credentials arrive; if a LAN-only v5
        // was shown first, enrich that same invitation with late SoftAP credentials
        // and newly enumerated LAN routes without rotating its pairing secret.
        val stablePayload = if (mayUpgradeToUnifiedV5 || mayEnrichV5Routes || mayUpgradeOrRefreshRoomV6) payload else
            com.malekplay.network.UltraSharePairingReadiness.stableQrPayload(
                existingPayload, payload
            ) ?: return
        if (stablePayload == existingPayload) return
        _state.value = current.copy(receiverQrPayload = stablePayload)
        runCatching { UltraShareQrCode.parsePayload(stablePayload) }.getOrNull()
            ?.takeIf { it.invitationId == current.invitationId && !current.invitationId.isBlank() }
            ?.let { invitation ->
                // Host addresses are routing hints, not credentials. Persisting only
                // these hints preserves identical QR bytes across ViewModel recreation
                // while the one-session pairing secret remains memory-only.
                invitationPreferences.edit()
                    .putString("active_invitation_hosts_id", invitation.invitationId)
                    .putString("active_invitation_hosts", invitation.hosts.joinToString(","))
                    .apply()
            }
        UltraShareDiagnostics.event(
            "PAIRING_QR", "QR_PUBLISHED_STABLE",
            mapOf("invitationId" to current.invitationId, "payloadLength" to payload.length)
        )
    }

    fun startReceiving() {
        if (_state.value.receiving || UltraShareForegroundService.state.value.receiving) return
        if (wifiDirectCleanupInProgress) {
            _state.value = _state.value.copy(
                error = "يجري إغلاق جلسة Wi-Fi Direct السابقة؛ انتظر اكتمال التنظيف ثم أعد المحاولة."
            )
            return
        }
        clearStoredInvitation()
        UltraShareDiagnostics.beginSession("RECEIVER", "start_receiving")
        UltraShareDiagnostics.event("PAIRING", "RECEIVER_WAITING_FOR_PEER")
        val secret = UltraShareCore.createPairingSecret()
        receiverWifiDirectTimeoutJob?.cancel()
        receiverWifiDirectTimeoutJob = null
        wifiDirectPrimaryAttempt = true
        hotspotFallbackStarted = false
        lanProbeInProgress = false
        wifiDirectGroupSsid = null
        wifiDirectGroupPassword = null
        wifiDirectOwnerAddress = null
        wifiDirectGroupSecurity = "wpa2"
        wifiDirectCredentialsFromJoinedInvitation = false
        dnsSdQrFallbackScheduledFor = null
        wifiDirectAdvertisedInvitationId = null
        _state.value = _state.value.copy(
            pairingSecret = secret,
            invitationId = "",
            receiverQrPayload = null,
            receiving = true,
            controlChannelReady = false,
            dataChannelReady = false,
            hotspotSsid = null,
            hotspotPassword = null,
            hotspotSecurity = "wpa2",
            hotspotActive = false,
            hotspotStarting = false,
            lanFallbackReady = false,
            wifiDirectLocalAddress = null,
            progress = null,
            error = null,
            status = "جارٍ تشغيل الاتصال المباشر Wi-Fi Direct (وينتقل تلقائياً إلى LAN عند التعذر)..."
        )
        runCatching {
            // Start the authenticated listeners immediately and advertise Wi-Fi Direct
            // as the primary transport; local LAN addresses are refreshed in parallel
            // so automatic LAN fallback works immediately if Wi-Fi Direct fails.
            UltraShareForegroundService.startReceiving(context, secret, createHotspot = false)
            UltraShareForegroundService.refreshLocalAddresses(context)
        }.onFailure { failure ->
            wifiDirectPrimaryAttempt = false
            UltraShareDiagnostics.failure("RECEIVER_START_LISTENER", failure)
            _state.value = _state.value.copy(
                receiving = false,
                pairingSecret = "",
                error = failure.localizedMessage ?: "تعذر تشغيل مستقبل النقل."
            )
            return
        }

        val requiredPermissions = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT)
        val missingPermissions = requiredPermissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            UltraShareDiagnostics.event("PAIRING", "WIFI_DIRECT_PERMISSION_MISSING_FALLING_BACK_TO_LAN")
            startHotspotFallback("لم يُمنح إذن Wi-Fi Direct؛ تم الانتقال تلقائياً إلى الشبكة المحلية (LAN).")
            return
        }

        advertiseCurrentWifiDirectInvitation()
        receiverWifiDirectTimeoutJob = viewModelScope.launch {
            delay(UltraSharePairingReadiness.WIFI_DIRECT_RECEIVER_STARTUP_FALLBACK_TIMEOUT_MS)
            val current = _state.value
            val service = UltraShareForegroundService.state.value
            if (current.receiving &&
                wifiDirectPrimaryAttempt &&
                !hotspotFallbackStarted &&
                wifiDirectAdvertisedInvitationId.isNullOrBlank() &&
                service.activePeers.isEmpty()
            ) {
                UltraShareDiagnostics.event("PAIRING", "WIFI_DIRECT_RECEIVER_STARTUP_TIMEOUT_SWITCHING_TO_LAN")
                startHotspotFallback("استغرق تجهيز Wi-Fi Direct وقتاً أطول من المتوقع؛ تم الانتقال تلقائياً إلى LAN.")
            }
        }
    }

    /** Stop every Wi-Fi Direct discovery/advertisement operation before releasing its group. */
    private fun cleanupWifiDirectBeforeFallback(
        session: UltraShareWifiDirect.Session,
        onComplete: (Boolean) -> Unit
    ) {
        // Retire credentials immediately; discovery and group-removal callbacks are
        // asynchronous and must not republish a QR for the group being removed.
        session.invalidateGroupCredentials()
        session.stopInvitationDiscoveryChecked { discoveryStopped ->
            if (!discoveryStopped) {
                // A stale DNS-SD request must not prevent us from attempting to
                // remove the actual P2P group and activate a usable LAN fallback.
                UltraShareDiagnostics.event("WIFI_DIRECT", "DISCOVERY_CLEANUP_FAILED_CONTINUING_GROUP_REMOVAL")
            }
            session.stopInvitationAdvertisementChecked { advertisementStopped ->
                if (!advertisementStopped) {
                    UltraShareDiagnostics.event("WIFI_DIRECT", "ADVERTISEMENT_CLEANUP_FAILED_CONTINUING_GROUP_REMOVAL")
                }
                // Group removal is the safety boundary: only its confirmed result
                // decides whether another Wi-Fi transport may be started.
                session.removeGroup(onComplete)
            }
        }
    }

    private fun startHotspotFallback(reason: String) {
        if (hotspotFallbackStarted || !_state.value.receiving) return
        hotspotFallbackStarted = true
        receiverWifiDirectTimeoutJob?.cancel()
        receiverWifiDirectTimeoutJob = null
        wifiDirectSession?.invalidateGroupCredentials()
        clearStoredInvitation()
        // The P2P group is being retired. Never carry its SSID/passphrase or
        // owner address into the independent LAN/LocalOnlyHotspot QR payload.
        wifiDirectGroupSsid = null
        wifiDirectGroupPassword = null
        wifiDirectOwnerAddress = null
        wifiDirectGroupSecurity = "wpa2"
        wifiDirectCredentialsFromJoinedInvitation = false
        dnsSdQrFallbackScheduledFor = null
        wifiDirectAdvertisedInvitationId = null
        _state.value = _state.value.copy(
            receiverQrPayload = null,
            wifiDirectLocalAddress = null,
            wifiDirectHost = null,
            hotspotSsid = "",
            hotspotPassword = "",
            hotspotSecurity = "wpa2"
        )
        wifiDirectPrimaryAttempt = false
        pendingQrPairing = null
        qrP2pConnectStarted = false
        val session = wifiDirectSession

        viewModelScope.launch {
            if (!_state.value.receiving) return@launch

            // Zapya-style fallback when Wi-Fi Direct fails:
            // 1) On Android 8+, start LocalOnlyHotspot first. On single-radio devices
            //    Android automatically replaces any active/voucher Wi-Fi with the
            //    direct hotspot; on dual-STA+AP phones both stay active.
            // 2) Only if LocalOnlyHotspot cannot start or is unsupported, switch
            //    automatically to LAN whenever a non-P2P LAN interface is live.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val beginHotspot: () -> Unit = {
                    _state.value = _state.value.copy(
                        invitationId = "",
                        wifiDirectHost = null,
                        wifiDirectLocalAddress = null,
                        wifiDirectScanning = false,
                        error = null,
                        status = "تعذر Wi-Fi Direct؛ جارٍ إنشاء نقطة اتصال مباشرة تلقائياً..."
                    )
                    runCatching { UltraShareForegroundService.startHotspot(context) }
                        .onFailure { failure ->
                            UltraShareDiagnostics.failure("HOTSPOT_FALLBACK_START", failure)
                            switchReceiverToLanFallbackOrReport(
                                failure.localizedMessage ?: "فشل Wi-Fi Direct وتعذر تشغيل نقطة الاتصال المحلية."
                            )
                        }
                }
                if (session == null) {
                    beginHotspot()
                } else {
                    cleanupWifiDirectBeforeFallback(session) { removed ->
                        if (removed) {
                            beginHotspot()
                        } else {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "FALLBACK_BLOCKED_GROUP_CLEANUP_FAILED",
                                mapOf("reason" to reason.take(160))
                            )
                            switchReceiverToLanFallbackOrReport(
                                "تعذر إغلاق مجموعة Wi-Fi Direct ولا توجد شبكة LAN بديلة متاحة."
                            )
                        }
                    }
                }
                return@launch
            }

            if (session != null) {
                cleanupWifiDirectBeforeFallback(session) { }
            }
            switchReceiverToLanFallbackOrReport(
                "تعذر Wi-Fi Direct ولا تتوفر شبكة LAN حالياً، وهذا الإصدار لا يدعم نقطة الاتصال المحلية داخل التطبيق. صِل الهاتفين بشبكة Wi-Fi نفسها أو فعّل نقطة اتصال النظام."
            )
        }
    }

    private fun switchReceiverToLanFallbackOrReport(fallbackFailureMessage: String) {
        viewModelScope.launch {
            var lanHosts = emptyList<String>()
            repeat(3) { attempt ->
                if (!_state.value.receiving) return@launch
                UltraShareForegroundService.refreshLocalAddresses(context)
                val serviceState = UltraShareForegroundService.state.value
                lanHosts = UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
                    context,
                    _state.value.localAddresses + serviceState.localAddresses
                )
                if (lanHosts.isNotEmpty() || attempt == 2) return@repeat
                delay(150L)
            }
            if (!_state.value.receiving) return@launch
            if (lanHosts.isNotEmpty()) {
                UltraShareDiagnostics.event(
                    "PAIRING",
                    "WIFI_DIRECT_AND_HOTSPOT_SWITCHED_TO_LAN",
                    mapOf("hostCount" to lanHosts.size)
                )
                _state.value = _state.value.copy(
                    invitationId = "",
                    localAddresses = lanHosts,
                    lanFallbackReady = true,
                    wifiDirectHost = null,
                    wifiDirectLocalAddress = null,
                    wifiDirectScanning = false,
                    hotspotStarting = false,
                    error = null,
                    status = "تم الانتقال تلقائياً إلى الشبكة المحلية (LAN). صِل الجهازين بالشبكة نفسها ثم امسح رمز QR."
                )
                publishUnifiedReceiverQrIfReady()
            } else {
                hotspotFallbackStarted = false
                _state.value = _state.value.copy(
                    invitationId = "",
                    localAddresses = emptyList(),
                    lanFallbackReady = false,
                    wifiDirectHost = null,
                    wifiDirectLocalAddress = null,
                    wifiDirectScanning = false,
                    error = fallbackFailureMessage,
                    status = "الاستقبال يعمل؛ ننتظر اتصال الجهازين بشبكة Wi-Fi (LAN) لعرض رمز QR."
                )
            }
        }
    }

    fun requestConnection(
        host: String,
        secret: String,
        candidates: List<com.malekplay.network.UltraShareNegotiation.Candidate> = emptyList(),
        alternateHosts: List<String> = emptyList(),
        preferredNetwork: android.net.Network? = null,
        port: Int = UltraShareControlChannel.DEFAULT_PORT,
        onConnectionEstablished: ((String) -> Unit)? = null,
        onConnectionFailed: ((rejected: Boolean, reason: String?) -> Unit)? = null
    ) {
        if (controlPairingInProgress) {
            UltraShareDiagnostics.event("PAIRING", "DUPLICATE_PAIRING_ATTEMPT_IGNORED", mapOf("host" to host.trim()))
            return
        }
        UltraShareDiagnostics.beginSession(
            "SENDER",
            "pairing_attempt",
            host.trim().takeIf { it.isNotBlank() }?.let { "peer:$it" }
        )
        UltraShareDiagnostics.event("PAIRING", "CONNECTION_REQUESTED", mapOf("host" to host.trim(), "candidateCount" to candidates.size, "alternateHostCount" to alternateHosts.size))
        if (host.isBlank() || !UltraShareCore.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = "أدخل عنوان الجهاز ورمز الاقتران الصحيح أولاً."
            )
            return
        }
        // Inbound and outbound pairings are independent authenticated sessions.
        // The receiver's local secret authenticates peers connecting TO this device;
        // the supplied secret authenticates this device TO the selected remote receiver.
        // Requiring them to match incorrectly blocked full-duplex use and multi-device
        // transfers even though the control channel keeps per-peer session keys isolated.
        controlPairingInProgress = true
        val discoveredCandidates = candidates.ifEmpty {
            _state.value.discoveredPeers.firstOrNull { peer ->
                peer.host == host.trim() || peer.candidates.any { it.host == host.trim() }
            }?.candidates.orEmpty()
        }
        val discoveredPeer = _state.value.discoveredPeers.firstOrNull { peer ->
            peer.host == host.trim() ||
                peer.candidates.any { it.host == host.trim() } ||
                peer.candidates.any { route ->
                    discoveredCandidates.any { it.host == route.host && it.port == route.port }
                }
        }
        val expectedRemotePeerId = discoveredPeer?.peerId
        // Claim the pairing slot synchronously before launching, so rapid QR scans
        // or duplicate callbacks cannot create overlapping control-channel sessions.
        _state.value = _state.value.copy(requestingConnection = true)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                requestingConnection = true,
                peerDataChannelReady = false,
                error = null,
                status = "جارٍ الاتصال تلقائياً بالجهاز؛ نختبر أفضل مسارات الشبكة..."
            )
            try {
                val orderedRoutes = UltraShareNegotiation.orderedCandidates(discoveredCandidates)
                // Try the selected address first, then each verified interface belonging
                // to the same peer. The receiver accepts a valid authenticated QR/PIN
                // automatically; only transport failures move to another route.
                val routeHosts = (
                    listOf(host.trim()) +
                        alternateHosts.filter {
                            UltraShareCore.isValidPrivateLanHost(it)
                        } +
                        orderedRoutes.map { it.host }
                    ).distinct().filterNot { routeHost ->
                        val localAddress = UltraShareNetworkRouting.isLocalAddress(context, routeHost)
                        if (localAddress) {
                            UltraShareDiagnostics.event(
                                "NETWORK_ROUTING",
                                "SELF_CONNECT_BLOCKED",
                                mapOf("host" to routeHost)
                            )
                        }
                        localAddress
                    }
                if (routeHosts.isEmpty()) {
                    val message = "عنوان الجهاز الموجود في الدعوة يطابق عنوان هذا الهاتف نفسه؛ أُوقف الاتصال الذاتي. أنشئ رمز QR جديداً من الهاتف المستقبل ثم امسحه من الهاتف الآخر."
                    _state.value = _state.value.copy(
                        requestingConnection = false,
                        error = message
                    )
                    // NetworkSpecifier callers own a temporary NetworkCallback lease;
                    // notify them on every terminal route failure so they can release it.
                    onConnectionFailed?.invoke(false, message)
                    return@launch
                }
                var acceptedHost: String? = null
                var rejected = false
                var lastFailure: Exception? = null
                for (routeHost in routeHosts) {
                    var routeAttempt = 0
                    while (routeAttempt < 3) {
                        UltraShareDiagnostics.event(
                            "CONTROL_CHANNEL",
                            "ROUTE_ATTEMPT",
                            mapOf("host" to routeHost, "attempt" to routeAttempt)
                        )
                        try {
                            val routeNetworks = (
                                listOfNotNull(preferredNetwork) +
                                    UltraShareNetworkRouting.findNetworksForHost(context, routeHost)
                                ).distinct()
                            UltraShareDiagnostics.event(
                                "NETWORK_ROUTING",
                                "HOST_ROUTES_RESOLVED",
                                mapOf(
                                    "host" to routeHost,
                                    "routeCount" to routeNetworks.size,
                                    "networkHandles" to routeNetworks.joinToString(",") { it.networkHandle.toString() }
                                )
                            )
                            val routeNetwork = routeNetworks.firstOrNull()
                            // Android 9 and some OEM Android 10 builds may not publish
                            // the Wi-Fi Direct Network in ConnectivityManager.allNetworks.
                            // UltraShareControlChannel.request() tries all explicit routes and
                            // then the OS default route, which can be the active P2P route.
                            if (UltraShareNetworkRouting.isWifiDirectGroupHost(routeHost) && routeNetwork == null) {
                                UltraShareDiagnostics.event(
                                    "NETWORK_ROUTING",
                                    "P2P_NETWORK_NOT_EXPOSED_TRYING_DEFAULT_ROUTE",
                                    mapOf("host" to routeHost)
                                )
                            }
                            val decision = UltraShareControlChannel.request(
                                host = routeHost,
                                pairingSecret = secret.trim(),
                                peerName = android.os.Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                                port = port,
                                network = routeNetwork,
                                networks = routeNetworks.drop(1),
                                peerId = UltraShareDiscovery.localPeerId(context),
                                expectedPeerId = expectedRemotePeerId,
                                onSessionClosed = { disconnectedHost ->
                                    UltraShareForegroundService.disconnectApprovedPeer(disconnectedHost)
                                    forgetApprovedSenderTarget(disconnectedHost)
                                    if (UltraShareForegroundService.approvedSendTargets().isEmpty()) {
                                        releaseLegacyWifiNetwork()
                                    }
                                },
                            )
                            UltraShareDiagnostics.event(
                                "CONTROL_CHANNEL",
                                "DECISION_RECEIVED",
                                mapOf("host" to routeHost, "decision" to decision.name, "attempt" to routeAttempt)
                            )
                            if (decision == UltraShareControlChannel.Decision.ACCEPTED) {
                                check(UltraShareControlChannel.isSessionActive(routeHost)) {
                                    "انقطعت قناة التحكم قبل اكتمال الاقتران."
                                }
                                acceptedHost = routeHost
                                break
                            }
                            rejected = true
                            break
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            UltraShareDiagnostics.failure(
                                "CONTROL_CHANNEL_CONNECT",
                                failure,
                                "route=" + routeHost + ";attempt=" + routeAttempt
                            )
                            lastFailure = failure
                            if (routeAttempt < 2 && isRetryableUltraSharePairingFailure(failure)) {
                                routeAttempt++
                                UltraShareDiagnostics.event(
                                    "CONTROL_CHANNEL",
                                    "TRANSIENT_CONNECT_RETRY_SCHEDULED",
                                    mapOf(
                                        "host" to routeHost,
                                        "attempt" to routeAttempt,
                                        "delayMs" to (250L * routeAttempt),
                                        "cause" to failure.javaClass.simpleName
                                    )
                                )
                                delay(250L * routeAttempt)
                                continue
                            }
                            break
                        }
                    }
                    if (acceptedHost != null || rejected) break
                }
                if (acceptedHost != null) {
                    UltraShareDiagnostics.event("PAIRING", "CONTROL_CHANNEL_ESTABLISHED", mapOf("host" to acceptedHost))
                    val connectedHost = acceptedHost
                    val peerName = UltraShareControlChannel.sessionPeerName(connectedHost)
                        ?: "جهاز MalekPlay المستقبل"
                    // Reuse identity only for a currently live session. Disconnected peers
                    // are deliberately not retained for reconnect after the room ends.
                    val retainedPeerSessions =
                        UltraShareForegroundService.approvedSendTargets().map { it.host to it.sessionId }
                    val authenticatedRemotePeerId =
                        UltraShareControlChannel.sessionPeerId(connectedHost) ?: expectedRemotePeerId
                    val sessionId = authenticatedRemotePeerId
                        ?: UltraShareMultiRecipientCoordinator.resolveSessionId(
                            connectedHost,
                            retainedPeerSessions.distinctBy { it.first },
                            java.util.UUID.randomUUID().toString()
                        )
                    // Pairing is the single user-facing connection step. The receiver
                    // starts its transfer listener as part of this session; advertise the
                    // paired host directly and let the transfer worker retry a brief
                    // listener-start race instead of blocking the UI on a second probe.
                    val directTransport = when {
                        UltraShareNetworkRouting.isWifiDirectGroupHost(connectedHost) ->
                            UltraShareNegotiation.Transport.WIFI_DIRECT
                        preferredNetwork != null ->
                            UltraShareNegotiation.Transport.HOTSPOT
                        else ->
                            UltraShareNegotiation.Transport.WLAN
                    }
                    val sendRoutes = listOf(
                        UltraShareNegotiation.Candidate(
                            transport = directTransport,
                            host = connectedHost,
                            port = com.malekplay.network.UltraShareBatchTransport.DEFAULT_PORT,
                            connected = true,
                            reachable = true,
                            priority = 100
                        )
                    )
                    val dataReady = true
                    val approvedTarget = UltraShareApprovedSendTarget(
                        sessionId = sessionId,
                        host = connectedHost,
                        secret = secret.trim(),
                        candidates = sendRoutes,
                        peerId = authenticatedRemotePeerId
                    )
                    UltraShareForegroundService.registerApprovedSendTarget(approvedTarget)
                    // Every authenticated QR/peer connection is full-duplex: the joining
                    // phone must also expose its receive listeners. The remote control
                    // server already knows this phone's source address from the handshake,
                    // so no second hotspot or public discovery advertisement is required.
                    if (!UltraShareForegroundService.state.value.receiving) {
                        UltraShareForegroundService.startReceiving(
                            context,
                            secret.trim(),
                            createHotspot = false
                        )
                        UltraShareForegroundService.updateWifiDirectLocalAddress(
                            context,
                            _state.value.wifiDirectLocalAddress
                        )
                    }
                    UltraShareForegroundService.registerApprovedPeer(
                        name = peerName,
                        host = connectedHost,
                        role = "أرسل طلب الاتصال",
                        routeCandidates = sendRoutes,
                        sessionId = sessionId
                    )
                    _state.value = _state.value.copy(
                        approvedPeerHost = connectedHost,
                        peerDataChannelReady = dataReady,
                        approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                        approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                        status = "تم الاقتران. يمكنك الآن إرسال الملفات واستقبالها مباشرة.",
                        requestingConnection = false,
                        error = null
                    )
                    stopNearbyDiscoveryAfterPairing()
                    onConnectionEstablished?.invoke(connectedHost)
                } else {
                    UltraShareDiagnostics.event(
                        "PAIRING",
                        "CONNECTION_NOT_ESTABLISHED",
                        mapOf("rejected" to rejected, "lastFailure" to (lastFailure?.javaClass?.simpleName ?: "none"))
                    )
                    val previousTarget = UltraShareForegroundService.approvedSendTargets().lastOrNull()
                    _state.value = _state.value.copy(
                        approvedPeerHost = previousTarget?.host,
                        approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                        approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                        status = if (rejected) "رفض الجهاز الآخر طلب الاتصال."
                            else "تعذر الوصول إلى الجهاز عبر المسارات المكتشفة.",
                        requestingConnection = false,
                        error = if (rejected) null else lastFailure?.localizedMessage
                            ?: "تعذر إتمام طلب الاتصال."
                    )
                    onConnectionFailed?.invoke(
                        rejected,
                        lastFailure?.localizedMessage ?: if (rejected) "رفض الجهاز طلب الاقتران." else "تعذر الوصول إلى الجهاز."
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(requestingConnection = false)
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("PAIRING_WORKFLOW", failure, "host=" + host.trim())
                val previousTarget = UltraShareForegroundService.approvedSendTargets().lastOrNull()
                _state.value = _state.value.copy(
                    approvedPeerHost = previousTarget?.host,
                    approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                    approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                    requestingConnection = false,
                    error = failure.localizedMessage ?: "تعذر إتمام طلب الاتصال."
                )
                onConnectionFailed?.invoke(false, failure.localizedMessage)
            } finally {
                controlPairingInProgress = false
            }
        }
    }

private fun forgetApprovedSenderTarget(host: String) {
        UltraShareForegroundService.removeApprovedSendTarget(host)
        val next = UltraShareForegroundService.approvedSendTargets().lastOrNull()
        _state.value = _state.value.copy(
            approvedPeerHost = next?.host,
            peerDataChannelReady = next?.candidates?.any {
                it.reachable && it.port != UltraShareDiscovery.LEGACY_V1_DATA_PORT
            } == true,
            approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
            approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host }
        )
    }

    fun disconnectPeer(host: String) {
        UltraShareForegroundService.disconnectApprovedPeer(host)
        forgetApprovedSenderTarget(host)
        if (UltraShareForegroundService.approvedSendTargets().isEmpty()) releaseLegacyWifiNetwork()
        // This is an explicit user disconnect. Stop only an otherwise-idle sender
        // service; an active receiver remains available for other incoming peers.
        UltraShareForegroundService.stopIfUnpaired(context)
    }

    fun reconnectPeer(host: String) {
        // No saved device pairing exists. A disconnected peer must scan a fresh QR
        // invitation and create a new short-lived room session.
        _state.value = _state.value.copy(
            error = "انتهت جلسة هذا الجهاز. امسح رمز QR جديداً للاتصال من جديد."
        )
    }

    fun forgetPeer(host: String) {
        // Forget is equivalent to closing the current temporary connection.
        UltraShareForegroundService.disconnectApprovedPeer(host)
        forgetApprovedSenderTarget(host)
        if (UltraShareForegroundService.approvedSendTargets().isEmpty()) releaseLegacyWifiNetwork()
        UltraShareForegroundService.stopIfUnpaired(context)
    }

    /** Disconnect only the currently selected sender peer; keep other approved peers available. */
    fun clearApprovedConnection() {
        val currentHost = _state.value.approvedPeerHost ?: return
        UltraShareForegroundService.disconnectApprovedPeer(currentHost)
        forgetApprovedSenderTarget(currentHost)
        if (UltraShareForegroundService.approvedSendTargets().isEmpty()) releaseLegacyWifiNetwork()
        UltraShareForegroundService.stopIfUnpaired(context)
    }

    fun acceptConnection() {
        UltraShareForegroundService.acceptConnection(context)
    }

    fun rejectConnection() {
        UltraShareForegroundService.rejectConnection(context)
    }

    fun startHotspot() {
        UltraShareForegroundService.startHotspot(context)
    }

    fun stopHotspot() {
        UltraShareForegroundService.stopHotspot(context)
    }

    fun stopReceiving() {
        // Explicitly closing the room releases all network pins owned by this
        // temporary sharing session; a later session must negotiate its own route.
        receiverWifiDirectTimeoutJob?.cancel()
        receiverWifiDirectTimeoutJob = null
        UltraShareNetworkRouting.clearPinnedNetworks()
        hotspotNetworkCallback?.let { callback ->
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
        hotspotNetworkCallback = null
        hotspotNetworkPaired = false
        wifiDirectPrimaryAttempt = false
        hotspotFallbackStarted = false
        wifiDirectGroupSsid = null
        wifiDirectGroupPassword = null
        wifiDirectOwnerAddress = null
        wifiDirectGroupSecurity = "wpa2"
        wifiDirectCredentialsFromJoinedInvitation = false
        dnsSdQrFallbackScheduledFor = null
        wifiDirectAdvertisedInvitationId = null
        val session = wifiDirectSession
        wifiDirectCleanupInProgress = session != null
        // End the logical receive session before asynchronous P2P cleanup callbacks
        // arrive; otherwise an old discovery error can accidentally start hotspot
        // fallback while the user is explicitly stopping reception.
        UltraShareForegroundService.stopReceiving(context)
        if (UltraShareForegroundService.approvedSendTargets().isEmpty()) releaseLegacyWifiNetwork()
        clearStoredInvitation()
        _state.value = _state.value.copy(
            receiving = false,
            receiverQrPayload = null,
            controlChannelReady = false,
            dataChannelReady = false,
            peerDataChannelReady = false,
            pairingSecret = "",
            invitationId = "",
            wifiDirectHost = null,
            wifiDirectLocalAddress = null,
            hotspotSsid = null,
            hotspotPassword = null,
            hotspotSecurity = "wpa2",
            hotspotStarting = false,
            hotspotActive = false,
            pendingConnection = null,
            requestingConnection = false,
            lanFallbackReady = false,
            localAddresses = emptyList(),
            progress = if (_state.value.sending) UltraShareForegroundService.state.value.sendProgress else null,
            status = context.getString(R.string.ultra_share_stopped)
        )
        if (session != null) {
            runCatching {
                cleanupWifiDirectBeforeFallback(session) { cleaned ->
                    wifiDirectCleanupInProgress = false
                    if (!cleaned) {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "STOP_RECEIVING_CLEANUP_FAILED")
                        _state.value = _state.value.copy(
                            error = "تم إيقاف الاستقبال، لكن Android لم يؤكد تنظيف مجموعة Wi-Fi Direct بالكامل. أعد تشغيل Wi-Fi قبل محاولة جديدة."
                        )
                    }
                }
            }.onFailure { failure ->
                wifiDirectCleanupInProgress = false
                UltraShareDiagnostics.failure("WIFI_DIRECT_STOP_RECEIVING_CLEANUP", failure)
                _state.value = _state.value.copy(
                    error = failure.localizedMessage ?: "تعذر تنظيف مجموعة Wi-Fi Direct بعد إيقاف الاستقبال."
                )
            }
        } else {
        }
    }

    /**
     * Last-resort QR fallback after DNS-SD has been cleaned up. P2P SoftAP
     * credentials are used only when the exact invitation and secret still match;
     * the caller disables a return-to-DNS-SD fallback to avoid an infinite loop.
     */
    private fun fallbackToQrWifiCredentials(
        pairing: com.malekplay.network.UltraShareQrCode.PairingDetails?,
        invitationId: String,
        secret: String,
        reason: String
    ): Boolean {
        val candidate = pairing?.takeIf {
            UltraSharePairingReadiness.canUseQrWifiCredentialFallback(
                expectedInvitationId = invitationId,
                expectedPairingSecret = secret,
                qrInvitationId = it.invitationId,
                qrPairingSecret = it.pairingSecret,
                ssid = it.hotspotSsid,
                password = it.hotspotPassword,
                hosts = it.hosts
            )
        } ?: return false
        UltraShareDiagnostics.event(
            "PAIRING",
            "DNSSD_FAILED_USING_QR_WIFI_CREDENTIALS",
            mapOf("sdkInt" to Build.VERSION.SDK_INT, "reason" to reason.take(120))
        )
        _state.value = _state.value.copy(
            requestingConnection = true,
            error = null,
            status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "تعذر اكتشاف دعوة Wi-Fi Direct؛ سنحاول الانضمام إلى شبكة المجموعة عبر طلب Android النظامي..."
            else
                "تعذر اكتشاف دعوة Wi-Fi Direct؛ سنحاول الانضمام إلى شبكة المجموعة مباشرة..."
        )
        connectHotspotInvitation(candidate, allowWifiDirectFallback = false)
        return true
    }

    /**
     * Returns to the advertised P2P invitation after a temporary Wi-Fi network
     * join fails, then performs the same authenticated MPC2 handshake.
     */
    private fun fallbackToWifiDirectInvitation(
        pairing: com.malekplay.network.UltraShareQrCode.PairingDetails,
        reason: String
    ): Boolean {
        val invitationId = pairing.invitationId?.takeIf { it.isNotBlank() } ?: return false
        val required = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT)
        val missing = required.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = "$reason امنح إذن Wi-Fi Direct ثم أعد محاولة الانضمام."
            )
            return true
        }
        UltraShareDiagnostics.event("PAIRING", "DIRECT_WIFI_JOIN_FAILED_FALLING_BACK_TO_DNSSD")
        val activeNetworkCallback = hotspotNetworkCallback ?: retainedHotspotNetworkCallback
        activeNetworkCallback?.let { callback ->
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
        if (retainedHotspotNetworkCallback === activeNetworkCallback) {
            retainedHotspotNetworkCallback = null
            retainedHotspotNetworkPaired = false
        }
        hotspotNetworkCallback = null
        hotspotNetworkPaired = false
        releaseLegacyWifiNetwork()
        if (controlPairingInProgress) {
            viewModelScope.launch {
                var waitedMs = 0
                while (controlPairingInProgress && waitedMs < 2_000) {
                    delay(50L)
                    waitedMs += 50
                }
                if (!controlPairingInProgress) {
                    joinWifiDirectInvitation(invitationId, pairing.pairingSecret, pairing.hosts, pairing)
                } else {
                    _state.value = _state.value.copy(
                        requestingConnection = false,
                        error = "$reason تعذر إغلاق محاولة الاقتران السابقة؛ أعد المحاولة."
                    )
                }
            }
        } else {
            joinWifiDirectInvitation(invitationId, pairing.pairingSecret, pairing.hosts, pairing)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun connectLegacyWifiNetwork(
        pairing: com.malekplay.network.UltraShareQrCode.PairingDetails,
        allowWifiDirectFallback: Boolean = true
    ) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
        if (wifi == null) {
            val message = "خدمة Wi-Fi غير متاحة؛ تعذر الانضمام المباشر إلى الشبكة."
            if (!allowWifiDirectFallback || !fallbackToWifiDirectInvitation(pairing, message)) {
                _state.value = _state.value.copy(requestingConnection = false, error = message)
            }
            return
        }
        val ssid = pairing.hotspotSsid ?: return
        val password = pairing.hotspotPassword ?: return
        runCatching {
            if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
            val configuration = com.malekplay.network.buildUltraShareLegacyWifiConfiguration(
                ssid,
                password
            )
            val networkId = wifi.addNetwork(configuration)
            check(networkId >= 0) { "رفض نظام Android إضافة شبكة Wi-Fi؛ قد تمنع الشركة المصنّعة هذا الأسلوب." }
            // Disconnect any active captive-portal / voucher Wi-Fi first (Zapya behavior
            // on Android <= 9) so enableNetwork(networkId, true) switches cleanly to the QR SoftAP.
            runCatching { wifi.disconnect() }
            if (!wifi.enableNetwork(networkId, true)) {
                wifi.removeNetwork(networkId)
                runCatching { wifi.reconnect() }
                error("تعذر تفعيل شبكة Wi-Fi الجديدة.")
            }
            // reconnect() may return false on OEM builds while association is
            // already in progress; enableNetwork() is the authoritative result.
            runCatching { wifi.reconnect() }
            val wifiPreferences = context.getSharedPreferences("ultra_share_wifi", Context.MODE_PRIVATE)
            val previousNetworkId = wifiPreferences.getInt("legacy_network_id", -1)
                .takeIf { it >= 0 }
            previousNetworkId?.takeIf { it != networkId }?.let { oldId ->
                runCatching { wifi.removeNetwork(oldId) }
            }
            wifiPreferences.edit().putInt("legacy_network_id", networkId).apply()
            _state.value = _state.value.copy(
                requestingConnection = true,
                error = null,
                status = "تم إعداد الاتصال بشبكة المجموعة؛ جارٍ انتظار اتصال Wi-Fi ثم توثيق MPC2..."
            )
            viewModelScope.launch {
                // Association is asynchronous on Android 6–9 and differs across OEMs.
                // Wait for the actual supplicant/network ID instead of assuming a
                // fixed delay means the phone has joined the DIRECT SoftAP.
                val associated = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    var connected = false
                    while (!connected) {
                        val info = runCatching { wifi.connectionInfo }.getOrNull()
                        connected = com.malekplay.network.UltraSharePlatformCompatibility
                            .isLegacyWifiNetworkConnected(
                                expectedNetworkId = networkId,
                                actualNetworkId = info?.networkId ?: -1,
                                expectedSsid = ssid,
                                actualSsid = info?.ssid,
                                supplicantCompleted = info?.supplicantState ==
                                    android.net.wifi.SupplicantState.COMPLETED
                            )
                        if (!connected) delay(250L)
                    }
                    connected
                } == true
                if (!associated) {
                    val message = "انتهت مهلة الانضمام إلى شبكة المجموعة قبل اكتمال اتصال Wi-Fi."
                    releaseLegacyWifiNetwork()
                    if (!allowWifiDirectFallback || !fallbackToWifiDirectInvitation(pairing, message)) {
                        _state.value = _state.value.copy(requestingConnection = false, error = message)
                    }
                    return@launch
                }
                val hosts = (pairing.hosts + pairing.host).distinct()
                val targetHost = hosts.firstOrNull { UltraShareCore.isValidPrivateLanHost(it) }
                if (targetHost == null) {
                    releaseLegacyWifiNetwork()
                    _state.value = _state.value.copy(
                        requestingConnection = false,
                        error = "لا يحتوي رمز QR على عنوان صالح للوصول إلى الجهاز."
                    )
                    return@launch
                }
                requestConnection(
                    host = targetHost,
                    secret = pairing.pairingSecret,
                    alternateHosts = hosts.dropWhile { it != targetHost }.drop(1),
                    port = pairing.port,
                    onConnectionFailed = { rejected, reason ->
                        releaseLegacyWifiNetwork()
                        if (!rejected) {
                            val message = reason ?: "تعذر الوصول إلى مجموعة Wi-Fi بعد الانضمام إليها."
                            if (!allowWifiDirectFallback ||
                                !fallbackToWifiDirectInvitation(pairing, message)
                            ) {
                                _state.value = _state.value.copy(
                                    requestingConnection = false,
                                    error = message
                                )
                            }
                        }
                    }
                )
            }
        }.onFailure { failure ->
            releaseLegacyWifiNetwork()
            UltraShareDiagnostics.failure("LEGACY_WIFI_JOIN", failure)
            val message = (failure.localizedMessage
                ?: "تعذر الانضمام التلقائي إلى شبكة Wi-Fi على هذا الجهاز.") +
                " يمكنك الاتصال بها يدوياً من إعدادات Wi-Fi ثم إعادة مسح الرمز."
            if (!allowWifiDirectFallback || !fallbackToWifiDirectInvitation(pairing, message)) {
                _state.value = _state.value.copy(requestingConnection = false, error = message)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseLegacyWifiNetwork() {
        val preferences = context.getSharedPreferences("ultra_share_wifi", Context.MODE_PRIVATE)
        val networkId = preferences.getInt("legacy_network_id", -1).takeIf { it >= 0 } ?: return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: return
        runCatching {
            runCatching { wifi.disableNetwork(networkId) }
            val removed = wifi.removeNetwork(networkId)
            val profileStillExists = runCatching {
                try {
                    wifi.configuredNetworks?.any { it.networkId == networkId }
                } catch (_: SecurityException) {
                    // Permission can be revoked while the pairing flow is being cleaned up.
                    // Keep the saved id when Android does not allow us to inspect profiles.
                    null
                }
            }.getOrNull()
            if (removed || profileStillExists == false) {
                runCatching { wifi.saveConfiguration() }
                runCatching { wifi.reconnect() }
                preferences.edit().remove("legacy_network_id").apply()
            } else {
                error("Android refused to remove the temporary Wi-Fi Direct network profile.")
            }
        }.onFailure { UltraShareDiagnostics.failure("LEGACY_WIFI_REMOVE_NETWORK", it) }
    }

    /**
     * QR join order follows the proven P2P sequence: discover the advertised peer,
     * stop discovery, negotiate Wi-Fi Direct, then authenticate the control channel.
     * If DNS-SD times out, use a verified LAN route or the exact QR network credentials.
     * Hotspot/LAN invitations retain their direct paths.
     */
    fun cancelPendingPairingAttempt() {
        pendingQrPairing = null
        qrP2pConnectStarted = false
        val activeNetworkCallback = hotspotNetworkCallback ?: retainedHotspotNetworkCallback
        if (activeNetworkCallback != null && !hotspotNetworkPaired) {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            runCatching { connectivity?.unregisterNetworkCallback(activeNetworkCallback) }
            if (retainedHotspotNetworkCallback === activeNetworkCallback) {
                retainedHotspotNetworkCallback = null
                retainedHotspotNetworkPaired = false
            }
            if (hotspotNetworkCallback === activeNetworkCallback) {
                hotspotNetworkCallback = null
                hotspotNetworkPaired = false
            }
        }
        wifiDirectSession?.let { session ->
            session.stopInvitationDiscovery()
        }
        _state.value = _state.value.copy(
            requestingConnection = false,
            error = null,
            status = "أُلغيت محاولة الاقتران السابقة؛ جاهز لمسح رمز QR جديد."
        )
    }

    private fun resolveLanFallbackCandidates(hosts: List<String>): List<String> {
        val verified = UltraShareNetworkRouting.findNonP2pLanFallbackHosts(context, hosts)
        val extraNonP2p = hosts.asSequence()
            .map { it.trim() }
            .filter { UltraShareCore.isValidPrivateLanHost(it) }
            .filterNot { UltraShareNetworkRouting.isWifiDirectGroupHost(it) }
            .filterNot { UltraShareNetworkRouting.isLocalAddress(context, it) }
            .filterNot { it in verified }
            .toList()
        return (verified + extraNonP2p).distinct()
    }

    private fun fallbackAfterWifiDirectScanFailure(
        secret: String,
        hosts: List<String>,
        pairing: com.malekplay.network.UltraShareQrCode.PairingDetails?,
        invitationId: String,
        reason: String
    ) {
        val hasWifiCredentials = pairing != null &&
            !pairing.hotspotSsid.isNullOrBlank() &&
            !pairing.hotspotPassword.isNullOrBlank()
        val lanCandidates = resolveLanFallbackCandidates((hosts + listOfNotNull(pairing?.host)).distinct())
        val isCaptivePortal = UltraShareNetworkRouting.hasCaptivePortalWifi(context)

        // Zapya-style switching on voucher/captive-portal networks: skip isolated
        // LAN probing and switch directly to the QR hotspot credentials if available.
        if (hasWifiCredentials && isCaptivePortal) {
            if (fallbackToQrWifiCredentials(pairing, invitationId, secret, "captive_portal_bypass:$reason")) {
                return
            }
        }

        if (lanCandidates.isNotEmpty() && hasWifiCredentials) {
            // Fast probe (<650ms): if the two phones share an un-isolated router, pair
            // via LAN immediately; otherwise switch cleanly to QR hotspot credentials.
            _state.value = _state.value.copy(
                requestingConnection = true,
                error = null,
                status = "تعذر Wi-Fi Direct؛ جارٍ فحص الشبكة المحلية أو الانتقال إلى نقطة الاتصال..."
            )
            viewModelScope.launch {
                val reachable = withTimeoutOrNull(650L) {
                    UltraShareDiscovery.probeControlHostsFast(lanCandidates, timeoutMs = 450)
                }.orEmpty()
                if (reachable.isNotEmpty()) {
                    requestConnection(
                        host = reachable.first(),
                        secret = secret,
                        alternateHosts = (reachable.drop(1) + lanCandidates.filterNot { it in reachable }).distinct(),
                        port = pairing?.port ?: UltraShareControlChannel.DEFAULT_PORT,
                        onConnectionFailed = { rejected, failReason ->
                            if (!rejected && !fallbackToQrWifiCredentials(
                                    pairing,
                                    invitationId,
                                    secret,
                                    failReason ?: reason
                                )
                            ) {
                                _state.value = _state.value.copy(
                                    requestingConnection = false,
                                    error = failReason ?: "تعذر الاتصال عبر الشبكة المحلية ونقطة الاتصال."
                                )
                            }
                        }
                    )
                } else if (!fallbackToQrWifiCredentials(pairing, invitationId, secret, reason)) {
                    _state.value = _state.value.copy(
                        requestingConnection = false,
                        error = "تعذر الاتصال عبر Wi-Fi Direct أو الشبكة المحلية (LAN)."
                    )
                }
            }
            return
        }

        if (lanCandidates.isNotEmpty()) {
            UltraShareDiagnostics.event(
                "PAIRING",
                "WIFI_DIRECT_SCAN_FAILED_FALLING_BACK_TO_LAN",
                mapOf("lanCount" to lanCandidates.size, "reason" to reason.take(120))
            )
            _state.value = _state.value.copy(
                requestingConnection = true,
                error = null,
                status = "تعذر الاتصال المباشر Wi-Fi Direct؛ جارٍ الانتقال تلقائياً إلى الشبكة المحلية (LAN)..."
            )
            requestConnection(
                host = lanCandidates.first(),
                secret = secret,
                alternateHosts = lanCandidates.drop(1),
                port = pairing?.port ?: UltraShareControlChannel.DEFAULT_PORT,
                onConnectionFailed = { rejected, failReason ->
                    if (!rejected && !fallbackToQrWifiCredentials(
                            pairing,
                            invitationId,
                            secret,
                            failReason ?: reason
                        )
                    ) {
                        _state.value = _state.value.copy(
                            requestingConnection = false,
                            error = failReason ?: "تعذر الاتصال عبر Wi-Fi Direct وعبر الشبكة المحلية (LAN)."
                        )
                    }
                }
            )
            return
        }
        if (!fallbackToQrWifiCredentials(pairing, invitationId, secret, reason)) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = "تعذر الاتصال عبر Wi-Fi Direct، ولا يتوفر مسار شبكة محلية (LAN) صالح للاتصال البديل."
            )
        }
    }

    fun joinUnifiedInvitation(pairing: com.malekplay.network.UltraShareQrCode.PairingDetails) {
        if (controlPairingInProgress) return
        if (pendingQrPairing != null && !qrP2pConnectStarted) {
            cancelPendingPairingAttempt()
        } else if (pendingQrPairing != null) {
            return
        }

        val hasWifiCredentials = !pairing.hotspotSsid.isNullOrBlank() &&
            !pairing.hotspotPassword.isNullOrBlank()
        val lanCandidates = resolveLanFallbackCandidates(pairing.hosts.ifEmpty { listOf(pairing.host) })
        val preferHotspotOverLan = UltraShareNetworkRouting.hasCaptivePortalWifi(context)
        val routePlan = UltraSharePairingReadiness.resolveQrJoinRoutePlan(
            mode = pairing.mode,
            invitationId = pairing.invitationId,
            hasWifiCredentials = hasWifiCredentials,
            hasLanHosts = lanCandidates.isNotEmpty(),
            preferHotspotOverLan = preferHotspotOverLan
        )
        val primaryRoute = routePlan.firstOrNull()

        when (primaryRoute) {
            UltraSharePairingReadiness.QrJoinTransport.WIFI_DIRECT -> {
                UltraShareDiagnostics.event(
                    "PAIRING",
                    "QR_WIFI_DIRECT_PRIMARY_SELECTED",
                    mapOf("sdkInt" to Build.VERSION.SDK_INT, "lanFallbackCount" to lanCandidates.size)
                )
                joinWifiDirectInvitation(
                    requireNotNull(pairing.invitationId),
                    pairing.pairingSecret,
                    pairing.hosts,
                    pairing
                )
            }
            UltraSharePairingReadiness.QrJoinTransport.LAN -> {
                viewModelScope.launch {
                    val reachableHosts = withTimeoutOrNull(700L) {
                        UltraShareDiscovery.probeControlHostsFast(lanCandidates, timeoutMs = 500)
                    }.orEmpty()
                    val orderedLan = (reachableHosts + lanCandidates.filterNot { it in reachableHosts }).distinct()
                    val firstHost = orderedLan.firstOrNull() ?: pairing.host
                    if (firstHost.isNotBlank()) {
                        UltraShareDiagnostics.event(
                            "PAIRING", "QR_LAN_ROUTE_SELECTED",
                            mapOf("lanCount" to orderedLan.size)
                        )
                        requestConnection(
                            host = firstHost,
                            secret = pairing.pairingSecret,
                            alternateHosts = orderedLan.drop(1),
                            port = pairing.port,
                            onConnectionFailed = { rejected, reason ->
                                if (!rejected && !pairing.invitationId.isNullOrBlank()) {
                                    joinWifiDirectInvitation(
                                        pairing.invitationId,
                                        pairing.pairingSecret,
                                        pairing.hosts,
                                        pairing
                                    )
                                } else if (!rejected && hasWifiCredentials) {
                                    connectHotspotInvitation(pairing)
                                } else if (!rejected) {
                                    _state.value = _state.value.copy(
                                        requestingConnection = false,
                                        error = reason ?: "تعذر الاتصال عبر الشبكة المحلية (LAN)."
                                    )
                                }
                            }
                        )
                    }
                }
            }
            UltraSharePairingReadiness.QrJoinTransport.HOTSPOT -> {
                UltraShareDiagnostics.event(
                    "PAIRING", "QR_DIRECT_WIFI_JOIN_SELECTED",
                    mapOf("sdkInt" to Build.VERSION.SDK_INT)
                )
                connectHotspotInvitation(pairing)
            }
            null -> {
                val firstHost = pairing.hosts.firstOrNull() ?: pairing.host
                if (firstHost.isNotBlank()) {
                    requestConnection(
                        host = firstHost,
                        secret = pairing.pairingSecret,
                        alternateHosts = pairing.hosts.drop(1),
                        port = pairing.port
                    )
                } else {
                    _state.value = _state.value.copy(
                        requestingConnection = false,
                        error = "لا يحتوي رمز QR على مسار اتصال متاح."
                    )
                }
            }
        }
    }

    fun connectHotspotInvitation(
        pairing: com.malekplay.network.UltraShareQrCode.PairingDetails,
        allowWifiDirectFallback: Boolean = true
    ) {
        if (hotspotNetworkCallback != null && !hotspotNetworkPaired &&
            UltraShareForegroundService.approvedSendTargets().isEmpty()
        ) {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            hotspotNetworkCallback?.let { old -> runCatching { connectivity?.unregisterNetworkCallback(old) } }
            if (retainedHotspotNetworkCallback === hotspotNetworkCallback) {
                retainedHotspotNetworkCallback = null
                retainedHotspotNetworkPaired = false
            }
            hotspotNetworkCallback = null
            hotspotNetworkPaired = false
        }
        if (hotspotNetworkCallback != null || controlPairingInProgress || pendingQrPairing != null) {
            _state.value = _state.value.copy(
                error = "هناك محاولة اقتران أو جلسة شبكة قائمة بالفعل. أنهِ الجلسة الحالية قبل مسح رمز آخر."
            )
            return
        }
        rememberInvitationPeer(pairing.invitationId, pairing.pairingSecret)
        rememberInvitationNetwork(pairing)
        if (pairing.hotspotSsid.isNullOrBlank() || pairing.hotspotPassword.isNullOrBlank()) {
            requestConnection(pairing.host, pairing.pairingSecret, alternateHosts = pairing.hosts, port = pairing.port)
            return
        }
        // Keep the API-29+ guard explicit for WifiNetworkSpecifier; older
        // Android versions use WifiManager.addNetwork in the legacy helper.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            connectLegacyWifiNetwork(pairing, allowWifiDirectFallback)
            return
        }

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        if (connectivity == null) {
            val message = "خدمة اتصال Wi-Fi غير متاحة على هذا الجهاز."
            if (!allowWifiDirectFallback || !fallbackToWifiDirectInvitation(pairing, message)) {
                _state.value = _state.value.copy(requestingConnection = false, error = message)
            }
            return
        }
        hotspotNetworkCallback?.let { old ->
            runCatching { connectivity.unregisterNetworkCallback(old) }
        }
        hotspotNetworkPaired = false
        _state.value = _state.value.copy(
            requestingConnection = true,
            error = null,
            status = "جارٍ طلب الانضمام إلى شبكة MalekPlay من Android..."
        )

        val request = runCatching {
            val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
                .setSsid(pairing.hotspotSsid)
                .apply {
                    if (pairing.hotspotSecurity == "wpa3") setWpa3Passphrase(pairing.hotspotPassword)
                    else setWpa2Passphrase(pairing.hotspotPassword)
                }
                .build()
            android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        }.getOrElse { failure ->
            val message = failure.localizedMessage ?: "تعذر إعداد طلب اتصال Wi-Fi المباشر."
            if (!allowWifiDirectFallback || !fallbackToWifiDirectInvitation(pairing, message)) {
                _state.value = _state.value.copy(requestingConnection = false, error = message)
            }
            return
        }
        val connectionStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val paired = java.util.concurrent.atomic.AtomicBoolean(false)
        val networkLost = java.util.concurrent.atomic.AtomicBoolean(false)
        val connectedHost = java.util.concurrent.atomic.AtomicReference<String?>(null)
        // Keep the NetworkCallback alive after the screen is closed without making
        // ConnectivityManager retain the entire ViewModel through an inner closure.
        val owner = java.lang.ref.WeakReference(this)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                UltraShareDiagnostics.event("WIFI_NETWORK", "AVAILABLE", mapOf("networkHandle" to network.networkHandle))
                UltraShareNetworkRouting.pinNetworkForHosts(pairing.hosts + pairing.host, network)
                if (!connectionStarted.compareAndSet(false, true)) return
                val viewModel = owner.get() ?: run {
                    runCatching { connectivity.unregisterNetworkCallback(this) }
                    return
                }
                viewModel._state.value = viewModel._state.value.copy(
                    status = "اتصل Android بشبكة MalekPlay؛ جارٍ التحقق من رمز الاقتران..."
                )
                val orderedHotspotHosts = (
                    listOf(pairing.host) + pairing.hosts
                    )
                    .map { it.trim() }
                    .filter { UltraShareCore.isValidPrivateLanHost(it) }
                    .distinct()
                    .sortedBy { hostCandidate ->
                        when {
                            hostCandidate.startsWith("192.168.43.") -> 0
                            hostCandidate.startsWith("192.168.49.") -> 1
                            else -> 2
                        }
                    }
                val primaryHotspotHost = orderedHotspotHosts.firstOrNull() ?: pairing.host
                viewModel.requestConnection(
                    host = primaryHotspotHost,
                    secret = pairing.pairingSecret,
                    alternateHosts = orderedHotspotHosts.drop(1),
                    preferredNetwork = network,
                    port = pairing.port,
                    onConnectionEstablished = { peerHost ->
                        if (networkLost.get()) {
                            UltraShareForegroundService.disconnectApprovedPeer(peerHost)
                            return@requestConnection
                        }
                        connectedHost.set(peerHost)
                        paired.set(true)
                        viewModel.hotspotNetworkPaired = true
                        // requestConnection() has already enabled this device's
                        // reverse receive listeners after authenticating MPC2.
                        val remoteDataReady = viewModel._state.value.peerDataChannelReady
                        viewModel._state.value = viewModel._state.value.copy(
                            approvedPeerHost = peerHost,
                            status = if (remoteDataReady)
                                "تم الاقتران عبر شبكة MalekPlay، وتم التحقق من قناة نقل الملفات."
                            else
                                "تم الاقتران عبر شبكة MalekPlay، لكن منفذ نقل الملفات لدى الجهاز الآخر لم يستجب بعد. يمكنك الاستقبال، وسيُمنع الإرسال حتى تتأكد جاهزيته.",
                            error = if (remoteDataReady) null else viewModel._state.value.error
                        )
                    },
                    onConnectionFailed = { rejected, reason ->
                        if (!networkLost.get()) {
                            val message = reason ?: "تعذر إتمام اقتران MPC2 عبر شبكة Wi-Fi المباشرة."
                            val fallbackStarted = !rejected &&
                                allowWifiDirectFallback &&
                                viewModel.fallbackToWifiDirectInvitation(pairing, message)
                            if (!fallbackStarted) {
                                // Authentication rejection or a terminal fallback failure
                                // must release the process-scoped NetworkCallback lease.
                                val activeCallback = viewModel.hotspotNetworkCallback
                                if (activeCallback != null) {
                                    runCatching { connectivity.unregisterNetworkCallback(activeCallback) }
                                    if (viewModel.hotspotNetworkCallback === activeCallback) {
                                        viewModel.hotspotNetworkCallback = null
                                        viewModel.hotspotNetworkPaired = false
                                    }
                                    if (retainedHotspotNetworkCallback === activeCallback) {
                                        retainedHotspotNetworkCallback = null
                                        retainedHotspotNetworkPaired = false
                                    }
                                }
                                UltraShareNetworkRouting.releasePinnedNetworkForHosts(
                                    pairing.hosts + pairing.host,
                                    network
                                )
                                viewModel._state.value = viewModel._state.value.copy(
                                    requestingConnection = false,
                                    error = message
                                )
                            }
                        }
                    }
                )
            }

            override fun onUnavailable() {
                UltraShareDiagnostics.event("WIFI_NETWORK", "UNAVAILABLE", mapOf("requestedHost" to pairing.host))
                networkLost.set(true)
                val viewModel = owner.get()
                if (retainedHotspotNetworkCallback === this) {
                    retainedHotspotNetworkCallback = null
                    retainedHotspotNetworkPaired = false
                }
                if (viewModel != null && viewModel.hotspotNetworkCallback === this) {
                    viewModel.hotspotNetworkCallback = null
                    viewModel.hotspotNetworkPaired = false
                    val message = "لم يكتمل الانضمام إلى شبكة MalekPlay. وافق على طلب Wi-Fi الذي عرضه Android وأبقِ نقطة الاتصال مفتوحة."
                    val switchedToP2p = allowWifiDirectFallback &&
                        viewModel.fallbackToWifiDirectInvitation(pairing, message)
                    if (!switchedToP2p) {
                        val lanFallbacks = viewModel.resolveLanFallbackCandidates(
                            (pairing.hosts + pairing.host).distinct()
                        )
                        if (lanFallbacks.isNotEmpty() && !UltraShareNetworkRouting.hasCaptivePortalWifi(viewModel.context)) {
                            viewModel._state.value = viewModel._state.value.copy(
                                requestingConnection = true,
                                error = null,
                                status = "تعذر الانضمام التلقائي لنقطة الاتصال؛ جارٍ محاولة الاتصال عبر الشبكة المحلية (LAN)..."
                            )
                            viewModel.requestConnection(
                                host = lanFallbacks.first(),
                                secret = pairing.pairingSecret,
                                alternateHosts = lanFallbacks.drop(1),
                                port = pairing.port
                            )
                        } else {
                            viewModel._state.value = viewModel._state.value.copy(
                                requestingConnection = false,
                                error = message
                            )
                        }
                    }
                }
                runCatching { connectivity.unregisterNetworkCallback(this) }
            }

            override fun onLost(network: android.net.Network) {
                UltraShareDiagnostics.event("WIFI_NETWORK", "LOST", mapOf("networkHandle" to network.networkHandle, "paired" to paired.get()))
                UltraShareNetworkRouting.releasePinnedNetworkForHosts(pairing.hosts + pairing.host, network)
                networkLost.set(true)
                val viewModel = owner.get()
                val isCurrent = retainedHotspotNetworkCallback === this
                if (paired.get() && isCurrent) {
                    connectedHost.get()?.let { peerHost ->
                        UltraShareForegroundService.disconnectApprovedPeer(peerHost)
                    }
                } else if (!paired.get()) {
                    val lostReason = "انقطع الاتصال بالشبكة قبل اكتمال الاقتران."
                    val fallbackStarted = allowWifiDirectFallback &&
                        owner.get()?.fallbackToWifiDirectInvitation(pairing, lostReason) == true
                    if (!fallbackStarted) {
                        viewModel?.let { ownerViewModel ->
                            ownerViewModel._state.value = ownerViewModel._state.value.copy(
                                requestingConnection = false,
                                error = "انقطع الاتصال بالشبكة قبل اكتمال الاقتران."
                            )
                        }
                    }
                }
                if (isCurrent) {
                    retainedHotspotNetworkCallback = null
                    retainedHotspotNetworkPaired = false
                    if (viewModel != null && viewModel.hotspotNetworkCallback === this) {
                        viewModel.hotspotNetworkCallback = null
                        viewModel.hotspotNetworkPaired = false
                    }
                }
                if (viewModel != null && isCurrent && paired.get()) {
                    val lostHost = connectedHost.get()
                    if (paired.get() && lostHost != null) {
                        viewModel.forgetApprovedSenderTarget(lostHost)
                    }
                    viewModel.hotspotNetworkPaired = false
                    viewModel.hotspotNetworkCallback = null
                    viewModel._state.value = viewModel._state.value.copy(
                        requestingConnection = false,
                        approvedPeerHost = if (paired.get()) null else viewModel._state.value.approvedPeerHost,
                        peerDataChannelReady = false,
                        status = "انقطع اتصال شبكة MalekPlay؛ أُغلقت جلسة الاقتران المؤقتة.",
                        error = "انقطع اتصال Wi-Fi بين الهاتفين. أعد تشغيل نقطة الاتصال ثم امسح رمز QR مجدداً."
                    )
                }
                runCatching { connectivity.unregisterNetworkCallback(this) }
            }
        }
        hotspotNetworkCallback = callback
        try {
            UltraShareDiagnostics.event("WIFI_NETWORK", "REQUESTED", mapOf("host" to pairing.host, "timeoutMs" to 30_000))
            connectivity.requestNetwork(request, callback, 30_000)
        } catch (failure: Exception) {
            UltraShareDiagnostics.failure("WIFI_NETWORK_REQUEST", failure)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            hotspotNetworkCallback = null
            hotspotNetworkPaired = false
            val message = failure.localizedMessage ?: "تعذر طلب الانضمام إلى شبكة MalekPlay."
            if (!allowWifiDirectFallback || !fallbackToWifiDirectInvitation(pairing, message)) {
                _state.value = _state.value.copy(
                    requestingConnection = false,
                    error = message
                )
            }
        }
    }

    /**
     * Discovery scans are useful only until a peer is paired. Stop LAN, Wi-Fi Direct,
     * Bluetooth and BLE scans without releasing the paired P2P group or foreground
     * transfer service; keep the receiver's DNS-SD invitation available for more peers.
     */
    private fun stopNearbyDiscoveryAfterPairing() {
        if (discoveryStoppedForPairedSession) return
        discoveryStoppedForPairedSession = true

        scanJob?.cancel()
        scanJob = null
        wifiDirectScanTimeout?.cancel()
        wifiDirectScanTimeout = null
        bluetoothScanTimeout?.cancel()
        bluetoothScanTimeout = null

        runCatching { bluetoothSession?.stop() }
        runCatching { bleSession?.stop() }

        wifiDirectSession?.let { session ->
            session.stopDiscoveryChecked { }
            session.stopInvitationDiscoveryChecked { }
            val receiverRoomStillOpen = UltraSharePairingReadiness
                .shouldRetainReceiverInvitationAdvertisement(
                    _state.value.receiving || UltraShareForegroundService.state.value.receiving
                )
            if (receiverRoomStillOpen) {
                // Stop scanning after the first peer, but keep the receiver's local
                // DNS-SD invitation advertised so additional devices can join the
                // same multi-peer room using the still-valid QR.
                UltraShareDiagnostics.event(
                    "DISCOVERY",
                    "RECEIVER_INVITATION_ADVERTISEMENT_RETAINED_FOR_ADDITIONAL_PEERS"
                )
            } else {
                session.stopInvitationAdvertisementChecked { }
            }
        }

        _state.value = _state.value.copy(
            scanning = false,
            wifiDirectScanning = false,
            bluetoothScanning = false,
            bleScanning = false,
            status = "تم الاقتران؛ أوقفنا البحث عن الأجهزة لتوفير البطارية."
        )
        UltraShareDiagnostics.event("DISCOVERY", "STOPPED_AFTER_PAIRING")
    }

    fun scanNearbyDevices() {
        if (scanJob != null) {
            scanJob?.cancel()
            _state.value = _state.value.copy(scanning = false, status = "تم إيقاف البحث عن الأجهزة.")
            return
        }
        _state.value = _state.value.copy(
            scanning = true, discoveredPeers = emptyList(), error = null,
            status = "جارٍ البحث المستمر عن أجهزة MalekPlay على الشبكة..."
        )
        scanJob = viewModelScope.launch {
            // Keep the latest sighting time per stable peer identity. A device that
            // leaves Wi-Fi must disappear from the radar instead of remaining selectable.
            val found = LinkedHashMap<String, Pair<UltraShareDiscovery.Peer, Long>>()
            try {
                // Short repeated discovery windows make newly-started receivers appear
                // without forcing the user to restart the scan manually.
                while (true) {
                    val peers = UltraShareDiscovery.discoverPeers(scanWindowMs = 1_500L, context = context)
                    val now = System.currentTimeMillis()
                    peers.forEach { peer ->
                        found[UltraShareDiscovery.identityKey(peer)] = peer to now
                    }
                    found.entries.removeAll { (_, sighting) ->
                        !UltraShareDiscovery.isSightingFresh(sighting.second, now, PEER_SIGHTING_TTL_MS)
                    }
                    val visible = found.values.map { sighting ->
                        val peer = sighting.first
                        peer
                    }
                    _state.value = _state.value.copy(
                        discoveredPeers = visible,
                        status = if (visible.isEmpty()) "نبحث باستمرار عن أجهزة MalekPlay القريبة..."
                        else "تم العثور على ${visible.size} جهاز؛ البحث مستمر."
                    )
                    delay(350)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    discoveredPeers = emptyList(),
                    error = failure.localizedMessage ?: context.getString(R.string.ultra_share_discovery_unavailable)
                )
            } finally {
                scanJob = null
                _state.value = _state.value.copy(scanning = false)
            }
        }
    }


    private fun ensureWifiDirectSession(): UltraShareWifiDirect.Session {
        return wifiDirectSession ?: UltraShareWifiDirect.open(
            context = context,
            onPeers = { peers ->
                _state.value = _state.value.copy(
                    wifiDirectPeers = peers,
                    wifiDirectScanning = false,
                    status = if (peers.isEmpty()) "لم يتم العثور على أجهزة Wi-Fi Direct."
                    else "تم العثور على " + peers.size + " أجهزة عبر Wi-Fi Direct."
                )
            },
            onConnected = { remoteHost, isGroupOwner ->
                val serviceState = UltraShareForegroundService.state.value
                if (isGroupOwner) {
                    val pending = pendingQrPairing
                    if (pending != null && !serviceState.receiving) {
                        // Android selected the QR scanner as group owner. It must
                        // become the temporary receiver; the QR creator will connect
                        // back to this new group using the same short-lived secret.
                        // The scanned QR's SSID/passphrase belong to the remote group,
                        // so discard them before publishing this device's new group QR.
                        wifiDirectGroupSsid = null
                        wifiDirectGroupPassword = null
                        wifiDirectOwnerAddress = null
                        wifiDirectGroupSecurity = "wpa2"
                        wifiDirectCredentialsFromJoinedInvitation = false
                        UltraShareDiagnostics.event("PAIRING", "P2P_INVITATION_ROLE_REVERSED")
                        // Prevent serviceState.collect from starting another createGroup()
                        // on top of this already-reversed group-owner role.
                        wifiDirectPrimaryAttempt = true
                        pendingQrPairing = null
                        qrP2pConnectStarted = false
                        val localGroupOwnerAddress = remoteHost
                            ?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
                        runCatching {
                            UltraShareForegroundService.startReceiving(
                                context,
                                pending.secret,
                                createHotspot = false
                            )
                            // Publish the address confirmed by this group callback,
                            // not the ViewModel's possibly stale pre-callback value.
                            UltraShareForegroundService.updateWifiDirectLocalAddress(
                                context,
                                localGroupOwnerAddress
                            )
                        }.onSuccess {
                            _state.value = _state.value.copy(
                                receiving = true,
                                pairingSecret = pending.secret,
                                requestingConnection = false,
                                wifiDirectHost = null,
                                wifiDirectLocalAddress = localGroupOwnerAddress,
                                hotspotSsid = "",
                                hotspotPassword = "",
                                hotspotSecurity = "wpa2",
                                error = null,
                                status = "انعكس دور Wi-Fi Direct؛ أصبح هذا الهاتف مستقبلاً مؤقتاً وننتظر مصادقة منشئ رمز QR..."
                            )
                        }.onFailure { failure ->
                            _state.value = _state.value.copy(
                                requestingConnection = false,
                                wifiDirectHost = null,
                                error = failure.localizedMessage ?: "تعذر تشغيل الاستقبال العكسي."
                            )
                        }
                    } else if (serviceState.receiving) {
                        UltraShareForegroundService.refreshLocalAddresses(context)
                        _state.value = _state.value.copy(
                            wifiDirectLocalAddress = remoteHost?.takeIf { UltraShareCore.isValidPrivateLanHost(it) },
                            status = "اتصل Wi-Fi Direct بنجاح؛ هذا الهاتف جاهز للاستقبال."
                        )
                    } else {
                        _state.value = _state.value.copy(
                            wifiDirectLocalAddress = remoteHost?.takeIf { UltraShareCore.isValidPrivateLanHost(it) },
                            status = "تم إنشاء اتصال Wi-Fi Direct؛ جارٍ تجهيز قناة الاقتران."
                        )
                    }
                } else if (!remoteHost.isNullOrBlank()) {
                    val pending = pendingQrPairing
                    if (pending != null) {
                        pendingQrPairing = null
                        qrP2pConnectStarted = false
                        val safeFallbacks = UltraShareNetworkRouting.findNonP2pLanFallbackHosts(
                            context,
                            pending.hosts.filter { it != remoteHost }
                        )
                        _state.value = _state.value.copy(wifiDirectHost = remoteHost)
                        requestConnection(
                            host = remoteHost,
                            secret = pending.secret,
                            alternateHosts = safeFallbacks,
                            onConnectionFailed = { rejected, failReason ->
                                if (!rejected) {
                                    fallbackAfterWifiDirectScanFailure(
                                        secret = pending.secret,
                                        hosts = pending.hosts,
                                        pairing = pending.details,
                                        invitationId = pending.details?.invitationId.orEmpty(),
                                        reason = failReason ?: "p2p_control_connect_failed"
                                    )
                                }
                            }
                        )
                    } else if (serviceState.receiving &&
                        UltraShareCore.isValidPairingSecret(serviceState.pairingSecret)
                    ) {
                        // The QR creator is now the P2P client. Authenticate back to
                        // the scanner's temporary receiver rather than merely showing
                        // the group-owner IP as an unpaired peer.
                        UltraShareDiagnostics.event("PAIRING", "REVERSED_INVITATION_CLIENT_CONNECTING")
                        _state.value = _state.value.copy(
                            wifiDirectHost = remoteHost,
                            status = "تم اتصال Wi-Fi Direct؛ جارٍ توثيق الجلسة العكسية..."
                        )
                        requestConnection(
                            host = remoteHost,
                            secret = serviceState.pairingSecret
                        )
                    } else {
                        _state.value = _state.value.copy(
                            wifiDirectHost = remoteHost,
                            status = "تم اتصال Wi-Fi Direct. أدخل رمز الاقتران الظاهر على جهاز الاستقبال."
                        )
                    }
                }
            },
            onError = { message ->
                val receiving = _state.value.receiving || UltraShareForegroundService.state.value.receiving
                val pending = pendingQrPairing
                if (pending != null) {
                    // A terminal scanner-side DNS-SD/P2P error can leave an OEM session
                    // marked as discovering even after cleanup was attempted. Retire
                    // that session before LAN fallback or a later QR retry; keep any
                    // established foreground transfers independent of this ViewModel.
                    val failedInvitationSession = wifiDirectSession
                    wifiDirectSession = null
                    runCatching { failedInvitationSession?.close() }
                    pendingQrPairing = null
                    qrP2pConnectStarted = false
                }
                if (pending == null && receiving && wifiDirectPrimaryAttempt) {
                    startHotspotFallback(message)
                } else if (pending != null) {
                    _state.value = _state.value.copy(
                        wifiDirectScanning = false,
                        wifiDirectHost = null,
                        invitationId = if (receiving) "" else _state.value.invitationId
                    )
                    fallbackAfterWifiDirectScanFailure(
                        secret = pending.secret,
                        hosts = pending.hosts,
                        pairing = pending.details,
                        invitationId = pending.details?.invitationId.orEmpty(),
                        reason = message
                    )
                } else {
                    _state.value = _state.value.copy(
                        wifiDirectScanning = false,
                        wifiDirectHost = null,
                        invitationId = if (receiving) "" else _state.value.invitationId,
                        error = message
                    )
                }
            },
            onLocalAddress = { address ->
                val safeAddress = address?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
                _state.value = _state.value.copy(
                    wifiDirectLocalAddress = safeAddress,
                    hotspotSsid = if (wifiDirectGroupSsid == null) UltraShareForegroundService.state.value.hotspotSsid else wifiDirectGroupSsid,
                    hotspotPassword = if (wifiDirectGroupPassword == null) UltraShareForegroundService.state.value.hotspotPassword else wifiDirectGroupPassword,
                    hotspotSecurity = wifiDirectGroupSecurity
                )
                if (_state.value.receiving || UltraShareForegroundService.state.value.receiving) {
                    UltraShareForegroundService.updateWifiDirectLocalAddress(context, safeAddress)
                }
                if (safeAddress != null) {
                    // The group credentials may arrive before Android exposes the P2P IPv4
                    // address. Rebuild the QR now that the actual local route is available.
                    publishUnifiedReceiverQrIfReady()
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "LOCAL_GROUP_OWNER_ADDRESS_AVAILABLE",
                        mapOf("addressPresent" to true, "sdkInt" to Build.VERSION.SDK_INT)
                    )
                }
            },
            onGroupInfo = { ssid, passphrase, ownerAddress, wifiSecurity ->
                wifiDirectGroupSsid = ssid
                wifiDirectGroupPassword = passphrase
                wifiDirectOwnerAddress = ownerAddress
                wifiDirectGroupSecurity = wifiSecurity
                wifiDirectCredentialsFromJoinedInvitation = false
                _state.value = _state.value.copy(
                    hotspotSsid = ssid,
                    hotspotPassword = passphrase,
                    hotspotSecurity = wifiSecurity,
                    wifiDirectLocalAddress = ownerAddress?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
                        ?: _state.value.wifiDirectLocalAddress,
                    invitationId = _state.value.invitationId.ifBlank {
                        invitationPreferences.getString("active_invitation_id", "").orEmpty()
                    }
                )
                publishUnifiedReceiverQrIfReady()
            }
        ).also { wifiDirectSession = it }
    }

    fun disableWifiDirectInvitation() {
        clearStoredInvitation()
        val serviceState = UltraShareForegroundService.state.value
        val serviceHotspotActive = serviceState.hotspotActive
        wifiDirectGroupSsid = null
        wifiDirectGroupPassword = null
        wifiDirectOwnerAddress = null
        wifiDirectGroupSecurity = "wpa2"
        wifiDirectCredentialsFromJoinedInvitation = false
        dnsSdQrFallbackScheduledFor = null
        wifiDirectAdvertisedInvitationId = null
        _state.value = _state.value.copy(
            receiverQrPayload = null,
            wifiDirectLocalAddress = null,
            wifiDirectHost = null,
            hotspotSsid = serviceState.hotspotSsid.takeIf { serviceHotspotActive }.orEmpty(),
            hotspotPassword = serviceState.hotspotPassword.takeIf { serviceHotspotActive }.orEmpty(),
            hotspotSecurity = if (serviceHotspotActive) _state.value.hotspotSecurity else "wpa2"
        )
        val session = wifiDirectSession
        if (session == null) {
            _state.value = _state.value.copy(
                invitationId = "",
                status = "تم تعطيل دعوة Wi-Fi Direct؛ يمكنك استخدام رمز QR عبر الشبكة المحلية."
            )
            return
        }
        _state.value = _state.value.copy(status = "جارٍ إيقاف اكتشاف وإعلان Wi-Fi Direct وإغلاق المجموعة...")
        cleanupWifiDirectBeforeFallback(session) { cleaned ->
            _state.value = _state.value.copy(
                invitationId = "",
                error = if (cleaned) null else "تعذر تأكيد إيقاف جميع عمليات Wi-Fi Direct؛ أعد المحاولة قبل تشغيل مسار اتصال آخر.",
                status = if (cleaned)
                    "تم تعطيل دعوة Wi-Fi Direct وإغلاق المجموعة؛ يمكنك استخدام رمز QR عبر الشبكة المحلية."
                else "لم يكتمل تنظيف Wi-Fi Direct؛ لم نعلن نجاح إيقاف الدعوة."
            )
        }
    }

    private fun publishDnsSdReceiverQrIfReady(invitationId: String) {
        if (dnsSdQrFallbackScheduledFor == invitationId) return
        dnsSdQrFallbackScheduledFor = invitationId
        viewModelScope.launch {
            // Give requestGroupInfo a short opportunity to publish WPA2 credentials.
            // If this OEM withholds them, retain the compatible DNS-SD invitation QR.
            repeat(20) {
                val current = _state.value
                if (!current.receiving || hotspotFallbackStarted || current.invitationId != invitationId) {
                    return@launch
                }
                if (wifiDirectGroupSsid != null && wifiDirectGroupPassword != null) {
                    // Prefer the richer room QR when its live route is ready. If
                    // Android has not exposed a usable IPv4 yet, still publish the
                    // DNS-SD-only invitation below; credentials alone must not leave
                    // the receiver waiting with no QR on older OEM P2P stacks.
                    publishUnifiedReceiverQrIfReady()
                    val publishedInvitationId = _state.value.receiverQrPayload
                        ?.let { runCatching { UltraShareQrCode.parsePayload(it).invitationId }.getOrNull() }
                    if (publishedInvitationId == invitationId) return@launch
                }
                val service = UltraShareForegroundService.state.value
                if (current.receiving && service.controlChannelReady && current.invitationId == invitationId) {
                    val secret = service.pairingSecret.takeIf { UltraShareCore.isValidPairingSecret(it) }
                        ?: current.pairingSecret.takeIf { UltraShareCore.isValidPairingSecret(it) }
                        ?: return@launch
                    // Prefer the P2P group-owner interface so legacy Android
                    // 6–7 clients retain the direct SoftAP route even when LAN
                    // interfaces are numerous or DNS-SD is unavailable.
                    // A joined invitation's owner address belongs to the remote device,
                    // not this receiver. Advertise only our P2P interface and live LAN routes.
                    val localOwnerAddress = wifiDirectOwnerAddress
                        .takeUnless { wifiDirectCredentialsFromJoinedInvitation }
                        ?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
                    val liveLanHosts = UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
                        context, current.localAddresses + service.localAddresses
                    )
                    val hosts = (
                        listOfNotNull(current.wifiDirectLocalAddress, localOwnerAddress) + liveLanHosts
                        )
                        .distinct()
                        .take(8)
                    val payload = runCatching {
                        UltraShareQrCode.createReceiverPayload(
                            hosts = hosts,
                            pairingSecret = secret,
                            invitationId = invitationId
                        )
                    }.getOrElse {
                        UltraShareDiagnostics.failure("DNS_SD_QR_CREATE", it)
                        return@launch
                    }
                    latchReceiverQrPayload(payload)
                    return@launch
                }
                delay(250L)
            }
        }
    }

    fun rememberInvitationPeer(invitationId: String?, secret: String) {
        if (!UltraShareCore.isValidPairingSecret(secret)) return
        _state.value = _state.value.copy(
            invitationId = invitationId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }.orEmpty(),
            receiverQrPayload = null,
            hotspotSsid = wifiDirectGroupSsid ?: _state.value.hotspotSsid,
            hotspotPassword = wifiDirectGroupPassword ?: _state.value.hotspotPassword,
            hotspotSecurity = wifiDirectGroupSecurity
        )
    }

    private fun rememberInvitationNetwork(pairing: UltraShareQrCode.PairingDetails) {
        val ssid = pairing.hotspotSsid?.takeIf { it.isNotBlank() } ?: return
        val password = pairing.hotspotPassword?.takeIf { it.isNotBlank() } ?: return
        wifiDirectGroupSsid = ssid
        wifiDirectGroupPassword = password
        wifiDirectGroupSecurity = pairing.hotspotSecurity
        wifiDirectCredentialsFromJoinedInvitation = true
        wifiDirectOwnerAddress = pairing.hosts.firstOrNull()
        _state.value = _state.value.copy(
            hotspotSsid = ssid,
            hotspotPassword = password,
            hotspotSecurity = pairing.hotspotSecurity
        )
    }

    private fun publishUnifiedReceiverQrIfReady() {
        val current = _state.value
        val service = UltraShareForegroundService.state.value
        val secret = service.pairingSecret.takeIf { UltraShareCore.isValidPairingSecret(it) }
            ?: current.pairingSecret.takeIf { UltraShareCore.isValidPairingSecret(it) }
            ?: return
        if (!current.receiving && !service.receiving) return
        if (!service.controlChannelReady) return

        // Service state can retain credentials from a hotspot that was stopped.
        // Use its SoftAP values only while the service currently owns that network.
        val serviceHotspotAvailable = service.hotspotActive
        val rawSsid = wifiDirectGroupSsid ?: service.hotspotSsid.takeIf { serviceHotspotAvailable }
        val rawPassword = wifiDirectGroupPassword ?: service.hotspotPassword.takeIf { serviceHotspotAvailable }
        val hasWifiCredentials = !rawSsid.isNullOrBlank() &&
            !rawPassword.isNullOrBlank() && rawPassword.length in 8..63
        val ssid = rawSsid.takeIf { hasWifiCredentials }
        val password = rawPassword.takeIf { hasWifiCredentials }
        val requestedInvitationId = current.invitationId.takeIf {
            it.matches(Regex("[A-Za-z0-9_-]{1,64}"))
        }
        if (requestedInvitationId != null &&
            !UltraSharePairingReadiness.canPublishWifiDirectInvitationQr(
                requestedInvitationId,
                wifiDirectAdvertisedInvitationId
            )
        ) {
            // The QR scanner selects DNS-SD whenever a P2P invitation ID is present.
            // Publishing before addLocalService succeeds creates a QR that can only
            // wait for a service record which Android has not advertised yet.
            UltraShareDiagnostics.event(
                "PAIRING_QR",
                "P2P_QR_WITHHELD_UNTIL_DNSSD_ADVERTISEMENT",
                mapOf("invitationIdPresent" to true)
            )
            return
        }
        val invitationId = requestedInvitationId
        val advertisedAddresses = current.localAddresses + service.localAddresses
        val verifiedLanHosts = UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
            context, advertisedAddresses
        )
        val hasLanRoute = verifiedLanHosts.isNotEmpty()
        val dnsSdReady = dnsSdQrFallbackScheduledFor == current.invitationId
        if ((ssid.isNullOrBlank() || password.isNullOrBlank()) && !hasLanRoute &&
            !dnsSdReady && invitationId == null
        ) return
        val ownerAddress = wifiDirectOwnerAddress
            .takeUnless { wifiDirectCredentialsFromJoinedInvitation }
            ?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
        // Keep the Android-confirmed P2P owner route, but rebuild LAN hints from
        // interfaces that are still active now; never carry stale WLAN addresses.
        val p2pHosts = listOfNotNull(ownerAddress, current.wifiDirectLocalAddress)
            .filter(UltraShareCore::isValidPrivateLanHost)
        val orderedLanHosts = if (!ssid.isNullOrBlank()) {
            UltraShareNetworkRouting.orderHotspotPreferredAddresses(context, verifiedLanHosts)
        } else {
            verifiedLanHosts
        }
        val candidateHosts = (p2pHosts + orderedLanHosts)
            .distinct()
            .take(8)
        val hosts = candidateHosts
        if (hosts.isEmpty() && invitationId == null) {
            UltraShareDiagnostics.event(
                "PAIRING_QR", "QR_WITHHELD_NO_ACTIVE_ROUTE",
                mapOf(
                    "candidateCount" to candidateHosts.size,
                    "verifiedLanCount" to verifiedLanHosts.size,
                    "mode" to if (!ssid.isNullOrBlank()) "hotspot" else "lan"
                )
            )
            return
        }
        val payload = runCatching {
            UltraShareQrCode.createRoomPayload(
                hosts = hosts,
                port = UltraShareControlChannel.DEFAULT_PORT,
                pairingSecret = secret,
                deviceName = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" }
                    ?: "MalekPlayer Android",
                mode = when {
                    invitationId != null -> "p2p"
                    !ssid.isNullOrBlank() -> "hotspot"
                    else -> "lan"
                },
                ssid = ssid,
                psk = password,
                invitationId = invitationId,
                wifiSecurity = if (ssid != null) wifiDirectGroupSecurity else "wpa2"
            )
        }.getOrElse { failure ->
            UltraShareDiagnostics.failure("ROOM_V6_QR_CREATE", failure)
            return
        }
        latchReceiverQrPayload(payload)
    }

    fun advertiseCurrentWifiDirectInvitation() {
        val serviceHotspotState = UltraShareForegroundService.state.value
        if (_state.value.hotspotActive || _state.value.hotspotStarting ||
            serviceHotspotState.hotspotActive || serviceHotspotState.hotspotStarting
        ) {
            disableWifiDirectInvitation()
            _state.value = _state.value.copy(
                error = null,
                status = "نقطة الاتصال تعمل حالياً؛ لا يمكن تشغيل مجموعة Wi-Fi Direct بالتزامن على هذا الجهاز. سيستخدم QR عناوين نقطة الاتصال والشبكة المحلية."
            )
            return
        }
        if (!_state.value.receiving) {
            _state.value = _state.value.copy(error = "ابدأ الاستقبال أولاً قبل نشر دعوة Wi-Fi Direct.")
            return
        }
        val invitationId = _state.value.invitationId.ifBlank {
            invitationPreferences.getString("active_invitation_id", null)
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                ?: java.util.UUID.randomUUID().toString().replace("-", "")
        }
        invitationPreferences.edit().putString("active_invitation_id", invitationId).apply()
        _state.value = _state.value.copy(invitationId = invitationId)
        val session = runCatching { ensureWifiDirectSession() }.getOrElse { failure ->
            UltraShareDiagnostics.failure("WIFI_DIRECT_SESSION_INITIALIZATION", failure)
            if (wifiDirectPrimaryAttempt && _state.value.receiving) {
                startHotspotFallback(
                    failure.localizedMessage ?: "تعذر تهيئة Wi-Fi Direct؛ ننتقل إلى مسار الشبكة المحلية."
                )
            } else {
                _state.value = _state.value.copy(
                    error = failure.localizedMessage ?: "تعذر تهيئة Wi-Fi Direct."
                )
            }
            return
        }
        // A new group attempt must never reuse credentials or owner routes from
        // a previous P2P group or a joined invitation. Invalidate pending old-group
        // callbacks before the asynchronous discovery shutdown begins.
        session.invalidateGroupCredentials()
        wifiDirectGroupSsid = null
        wifiDirectGroupPassword = null
        wifiDirectOwnerAddress = null
        wifiDirectGroupSecurity = "wpa2"
        wifiDirectCredentialsFromJoinedInvitation = false
        dnsSdQrFallbackScheduledFor = null
        wifiDirectAdvertisedInvitationId = null
        _state.value = _state.value.copy(
            receiverQrPayload = null,
            hotspotSsid = "",
            hotspotPassword = "",
            hotspotSecurity = "wpa2",
            wifiDirectLocalAddress = null,
            wifiDirectHost = null,
            lanFallbackReady = false,
            error = null,
            status = "جارٍ إيقاف الاكتشاف وتجهيز مجموعة الاقتران المباشر..."
        )
        session.stopInvitationDiscoveryChecked { stopped ->
            if (!stopped) {
                val message = "لم يؤكد Android إيقاف اكتشاف Wi-Fi Direct وتنظيف طلب الدعوة؛ لم ننشئ المجموعة لتجنب تعارض عمليات P2P."
                UltraShareDiagnostics.event("WIFI_DIRECT", "GROUP_CREATE_ABORTED_DISCOVERY_STOP_UNCONFIRMED")
                if (wifiDirectPrimaryAttempt && _state.value.receiving) startHotspotFallback(message)
                else _state.value = _state.value.copy(error = message, status = "لم يتم إنشاء مجموعة Wi-Fi Direct.")
                return@stopInvitationDiscoveryChecked
            }
            if (wifiDirectSession !== session || !_state.value.receiving) return@stopInvitationDiscoveryChecked
            session.createGroup {
                if (wifiDirectSession !== session || !_state.value.receiving) return@createGroup
                // A P2P QR selects DNS-SD on the joining device. Do not expose it
                // until Android confirms addLocalService; otherwise a fast scanner
                // can start discovery before the invitation exists on the radio.
                session.advertiseInvitation(invitationId) {
                    if (wifiDirectSession !== session || !_state.value.receiving) return@advertiseInvitation
                    receiverWifiDirectTimeoutJob?.cancel()
                    receiverWifiDirectTimeoutJob = null
                    wifiDirectAdvertisedInvitationId = invitationId
                    UltraShareForegroundService.refreshLocalAddresses(context)
                    _state.value = _state.value.copy(
                        invitationId = invitationId,
                        error = null,
                        status = "مجموعة Wi-Fi Direct جاهزة؛ تم إعلان الدعوة وأصبح رمز QR متاحاً."
                    )
                    publishUnifiedReceiverQrIfReady()
                    publishDnsSdReceiverQrIfReady(invitationId)
                }
            }
        }
    }

    fun joinWifiDirectInvitation(
        invitationId: String,
        secret: String,
        fallbackHosts: List<String> = emptyList(),
        fallbackPairing: com.malekplay.network.UltraShareQrCode.PairingDetails? = null
    ) {
        if (pendingQrPairing != null || qrP2pConnectStarted || controlPairingInProgress ||
            hotspotNetworkCallback != null
        ) {
            _state.value = _state.value.copy(
                requestingConnection = true,
                error = "توجد جلسة اقتران أخرى قيد التنفيذ. انتظر نتيجتها قبل مسح رمز جديد."
            )
            return
        }
        if (!UltraShareCore.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = "رمز الاقتران داخل QR غير صالح."
            )
            return
        }
        val activeReceiver = UltraShareForegroundService.state.value
        if (activeReceiver.receiving) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = if (activeReceiver.pairingSecret == secret.trim())
                    "هذا الهاتف يستقبل بالفعل باستخدام رمز QR نفسه؛ لا حاجة لمسح الرمز مرة أخرى."
                else
                    "هناك جلسة استقبال نشطة. أوقفها قبل الانضمام إلى رمز QR آخر."
            )
            return
        }
        val pairingAttempt = PendingQrPairing(secret.trim(), fallbackHosts, fallbackPairing)
        pendingQrPairing = pairingAttempt
        qrP2pConnectStarted = false
        try {
            val session = ensureWifiDirectSession()
            _state.value = _state.value.copy(
                requestingConnection = true,
                wifiDirectHost = null,
                error = null,
                status = "تمت قراءة الدعوة؛ جارٍ الاتصال المباشر عبر Wi-Fi Direct..."
            )
            // QR service discovery must not overlap a regular discoverPeers request.
            // Wait for the framework's stop callback before installing DNS-SD requests.
            session.stopDiscoveryChecked { stopped ->
                if (!UltraSharePairingReadiness.isCurrentInvitationAttempt(pendingQrPairing, pairingAttempt) ||
                    wifiDirectSession !== session
                ) return@stopDiscoveryChecked
                if (!stopped) {
                    val pending = pendingQrPairing ?: return@stopDiscoveryChecked
                    pendingQrPairing = null
                    qrP2pConnectStarted = false
                    session.stopInvitationDiscovery()
                    wifiDirectSession = null
                    runCatching { session.close() }
                    fallbackAfterWifiDirectScanFailure(
                        secret = pending.secret,
                        hosts = pending.hosts,
                        pairing = fallbackPairing,
                        invitationId = invitationId,
                        reason = "discovery_stop_unconfirmed"
                    )
                    return@stopDiscoveryChecked
                }
                session.discoverInvitation(invitationId) { peer ->
                    if (UltraSharePairingReadiness.isCurrentInvitationAttempt(pendingQrPairing, pairingAttempt) &&
                        !qrP2pConnectStarted
                    ) {
                        qrP2pConnectStarted = true
                        _state.value = _state.value.copy(
                            status = "تم العثور على جهاز الاستقبال؛ جارٍ إنشاء اتصال Wi-Fi Direct..."
                        )
                        session.stopInvitationDiscoveryChecked { discoveryStopped ->
                            if (!UltraSharePairingReadiness.isCurrentInvitationAttempt(pendingQrPairing, pairingAttempt) ||
                                wifiDirectSession !== session
                            ) return@stopInvitationDiscoveryChecked
                            if (!discoveryStopped) {
                                val pending = pendingQrPairing ?: return@stopInvitationDiscoveryChecked
                                val failedInvitationSession = wifiDirectSession
                                wifiDirectSession = null
                                runCatching { failedInvitationSession?.close() }
                                pendingQrPairing = null
                                qrP2pConnectStarted = false
                                fallbackAfterWifiDirectScanFailure(
                                    secret = pending.secret,
                                    hosts = pending.hosts,
                                    pairing = fallbackPairing,
                                    invitationId = invitationId,
                                    reason = "invitation_discovery_stop_unconfirmed"
                                )
                                return@stopInvitationDiscoveryChecked
                            }
                            // The Wi-Fi Direct engine owns the complete connect lifecycle:
                            // 10s for a missing ActionListener callback, 30s for each accepted
                            // negotiation, bounded framework retries, and 35s peer confirmation.
                            // Do not close this session with a shorter ViewModel watchdog.
                            session.connect(peer.deviceAddress, preferThisDeviceAsGroupOwner = false)
                        }
                    }
                }
            }
            // This deadline covers DNS-SD discovery only. Once a peer is found,
            // the Wi-Fi Direct session owns negotiation and terminal-failure fallback.
            viewModelScope.launch {
                delay(UltraSharePairingReadiness.WIFI_DIRECT_INVITATION_DISCOVERY_TIMEOUT_MS)
                val pending = pairingAttempt
                if (UltraSharePairingReadiness.isCurrentInvitationAttempt(pendingQrPairing, pairingAttempt) &&
                    UltraSharePairingReadiness.shouldUseLanFallbackAfterInvitationDiscoveryTimeout(
                        hasPendingInvitation = true,
                        wifiDirectConnectionStarted = qrP2pConnectStarted
                    )
                ) {
                    UltraShareDiagnostics.event(
                        "PAIRING",
                        "INVITATION_DISCOVERY_TIMEOUT",
                        mapOf(
                            "qrHostCount" to pending.hosts.size,
                            "sdkInt" to Build.VERSION.SDK_INT
                        )
                    )
                    session.stopInvitationDiscoveryChecked { stopped ->
                        if (!UltraSharePairingReadiness.isCurrentInvitationAttempt(pendingQrPairing, pending) ||
                            qrP2pConnectStarted || wifiDirectSession !== session
                        ) return@stopInvitationDiscoveryChecked

                        if (!stopped) {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "INVITATION_TIMEOUT_CLEANUP_UNCONFIRMED_SESSION_RETIRED"
                            )
                            wifiDirectSession = null
                            runCatching { session.close() }
                        }
                        pendingQrPairing = null
                        qrP2pConnectStarted = false
                        fallbackAfterWifiDirectScanFailure(
                            secret = pending.secret,
                            hosts = pending.hosts,
                            pairing = fallbackPairing,
                            invitationId = invitationId,
                            reason = if (stopped) "dnssd_timeout" else "dnssd_timeout_cleanup_unconfirmed"
                        )
                    }
                }
            }
        } catch (failure: Exception) {
            pendingQrPairing = null
            qrP2pConnectStarted = false
            val failedSession = wifiDirectSession
            wifiDirectSession = null
            runCatching { failedSession?.close() }
            fallbackAfterWifiDirectScanFailure(
                secret = secret.trim(),
                hosts = fallbackHosts,
                pairing = fallbackPairing,
                invitationId = invitationId,
                reason = failure.localizedMessage ?: "dnssd_start_failure"
            )
        }
    }

    fun scanWifiDirectPeers() {
        if (_state.value.wifiDirectScanning) {
            stopWifiDirectDiscovery()
            return
        }
        try {
            val session = ensureWifiDirectSession()
            _state.value = _state.value.copy(
                wifiDirectScanning = true,
                wifiDirectHost = null,
                error = null,
                status = "جارٍ البحث عن أجهزة Wi-Fi Direct..."
            )
            session.discover()
            wifiDirectScanTimeout?.cancel()
            wifiDirectScanTimeout = viewModelScope.launch {
                delay(15_000)
                if (_state.value.wifiDirectScanning) {
                    session.stopDiscoveryChecked { stopped ->
                        _state.value = _state.value.copy(
                            wifiDirectScanning = false,
                            error = if (stopped) null else "انتهت مهلة البحث لكن Android لم يؤكد إيقاف عملية Wi-Fi Direct؛ لن نبدأ اكتشافاً آخر حتى تستقر الحالة.",
                            status = if (!stopped) "تعذر تأكيد إيقاف البحث عبر Wi-Fi Direct."
                            else if (_state.value.wifiDirectPeers.isEmpty())
                                "انتهى البحث عبر Wi-Fi Direct دون العثور على أجهزة."
                            else "انتهى البحث عبر Wi-Fi Direct."
                        )
                    }
                }
            }
        } catch (failure: Exception) {
            _state.value = _state.value.copy(
                wifiDirectScanning = false,
                error = failure.localizedMessage ?: "تعذر بدء Wi-Fi Direct على هذا الجهاز."
            )
        }
    }

    fun stopWifiDirectDiscovery() {
        wifiDirectScanTimeout?.cancel()
        wifiDirectScanTimeout = null
        val session = wifiDirectSession
        if (session == null) {
            _state.value = _state.value.copy(
                wifiDirectScanning = false,
                status = "لا توجد عملية اكتشاف Wi-Fi Direct نشطة."
            )
            return
        }
        session.stopDiscoveryChecked { stopped ->
            _state.value = _state.value.copy(
                wifiDirectScanning = false,
                error = if (stopped) null else "لم يؤكد Android إيقاف اكتشاف Wi-Fi Direct؛ لن نبدأ عملية P2P أخرى حتى تستقر حالة النظام.",
                status = if (stopped) "تم إيقاف البحث عبر Wi-Fi Direct."
                    else "تعذر تأكيد إيقاف البحث عبر Wi-Fi Direct."
            )
        }
    }

    fun scanBluetoothPeers() {
        if (_state.value.bluetoothScanning || _state.value.bleScanning) {
            stopBluetoothDiscovery()
            return
        }
        _state.value = _state.value.copy(
            bluetoothPeers = emptyList(),
            blePeers = emptyList(),
            bluetoothScanning = true,
            bleScanning = false,
            error = null,
            status = "جارٍ اكتشاف الأجهزة عبر Bluetooth Classic وBLE؛ نقل الملفات الآمن يستمر عبر Wi-Fi."
        )
        runCatching {
            val session = bluetoothSession ?: UltraShareBluetoothDiscovery.open(
                context = context,
                onPeers = { peers -> _state.value = _state.value.copy(bluetoothPeers = peers) },
                onScanning = { scanning -> _state.value = _state.value.copy(bluetoothScanning = scanning) },
                onError = { message -> _state.value = _state.value.copy(bluetoothScanning = false, error = message) }
            ).also { bluetoothSession = it }
            session.start()
        }.onFailure {
            _state.value = _state.value.copy(bluetoothScanning = false, error = it.localizedMessage ?: "تعذر تهيئة Bluetooth Classic.")
        }
        bluetoothScanTimeout?.cancel()
        bluetoothScanTimeout = viewModelScope.launch {
            delay(15_000)
            if (_state.value.bluetoothScanning || _state.value.bleScanning) {
                stopBluetoothDiscovery()
                _state.value = _state.value.copy(
                    status = "انتهى البحث عن أجهزة Bluetooth وBLE تلقائياً."
                )
            }
        }
        runCatching {
            val session = bleSession ?: UltraShareBleDiscovery.open(
                context = context,
                onPeers = { peers -> _state.value = _state.value.copy(blePeers = peers) },
                onScanning = { scanning -> _state.value = _state.value.copy(bleScanning = scanning) },
                onError = { message -> _state.value = _state.value.copy(bleScanning = false, error = message) }
            ).also { bleSession = it }
            session.start()
        }.onFailure {
            _state.value = _state.value.copy(bleScanning = false, error = it.localizedMessage ?: "تعذر تهيئة اكتشاف BLE.")
        }
    }

    fun stopBluetoothDiscovery() {
        bluetoothScanTimeout?.cancel()
        bluetoothScanTimeout = null
        runCatching { bluetoothSession?.stop() }
        runCatching { bleSession?.stop() }
        _state.value = _state.value.copy(
            bluetoothScanning = false,
            bleScanning = false,
            status = "تم إيقاف اكتشاف Bluetooth وBLE."
        )
    }

    fun connectWifiDirectPeer(peer: UltraShareWifiDirect.Peer) {
        val session = wifiDirectSession ?: run {
            scanWifiDirectPeers()
            wifiDirectSession
        }
        if (session == null) {
            _state.value = _state.value.copy(error = "تعذر تهيئة اتصال Wi-Fi Direct.")
            return
        }
        _state.value = _state.value.copy(
            error = null,
            status = "جارٍ الاتصال بـ " + peer.name + " عبر Wi-Fi Direct..."
        )
        session.stopInvitationDiscoveryChecked { stopped ->
            if (!stopped) {
                _state.value = _state.value.copy(
                    error = "لم يؤكد Android إيقاف اكتشاف Wi-Fi Direct وتنظيف طلب الدعوة؛ لم نبدأ الاتصال لتجنب تعارض عمليات P2P.",
                    status = "تعذر بدء الاتصال المباشر."
                )
                return@stopInvitationDiscoveryChecked
            }
            if (wifiDirectSession !== session) return@stopInvitationDiscoveryChecked
            runCatching {
                session.connect(
                    peer.deviceAddress,
                    preferThisDeviceAsGroupOwner = UltraShareForegroundService.state.value.receiving
                )
            }.onFailure {
                _state.value = _state.value.copy(error = it.localizedMessage ?: "تعذر الاتصال بالجهاز.")
            }
        }
    }

    fun send(uri: Uri, host: String, secret: String) {
        UltraShareDiagnostics.event("TRANSFER", "SEND_REQUESTED", mapOf("host" to host.trim()))
        if (UltraShareForegroundService.isSendingToPeer(host.trim())) {
            _state.value = _state.value.copy(
                error = "هناك إرسال جارٍ إلى هذا الجهاز بالفعل. اختر جهازاً آخر لمتابعة الإرسال بالتوازي."
            )
            return
        }
        runCatching {
            UltraShareForegroundService.startSending(context, uri, host, secret)
            _state.value = _state.value.copy(
                sending = true,
                progress = null,
                error = null,
                status = context.getString(R.string.ultra_share_sending)
            )
        }.onFailure {
            UltraShareDiagnostics.failure("SEND_START", it)
            _state.value = _state.value.copy(
                sending = UltraShareForegroundService.state.value.sending,
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
        }
    }

    fun sendBatch(uris: List<Uri>, host: String): Boolean {
        val approvedTargets = UltraShareForegroundService.approvedSendTargets()
        val requestedHost = host.trim()
        val resolvedHost = requestedHost.ifBlank {
            _state.value.approvedPeerHost ?: approvedTargets.singleOrNull()?.host.orEmpty()
        }
        UltraShareDiagnostics.event(
            "TRANSFER",
            "BATCH_SEND_REQUESTED",
            mapOf("host" to resolvedHost, "fileCount" to uris.size, "requestedHostPresent" to requestedHost.isNotBlank())
        )
        val target = if (resolvedHost.isNotBlank()) {
            UltraShareForegroundService.approvedSendTarget(resolvedHost)
        } else {
            approvedTargets.singleOrNull()
        }
        if (uris.isEmpty()) return false
        if (target == null) {
            _state.value = _state.value.copy(
                error = "لا يوجد جهاز مقترن ومعتمد للإرسال. أعد الاقتران أو اختر الجهاز من قائمة الأجهزة المتصلة."
            )
            return false
        }
        if (UltraShareForegroundService.isSendingToPeer(target.host)) {
            _state.value = _state.value.copy(
                error = "هناك إرسال جارٍ إلى هذا الجهاز بالفعل. اختر جهازاً آخر لمتابعة الإرسال بالتوازي."
            )
            return false
        }
        return runCatching {
            UltraShareForegroundService.startSendingBatchToPeers(context, uris, listOf(target))
            _state.value = _state.value.copy(
                sending = true, progress = null, queueCompleted = 0,
                queueTotal = uris.size, queueCurrentName = null, error = null,
                status = "بدأ إرسال " + uris.size + " ملفات بالتتابع"
            )
            true
        }.getOrElse {
            UltraShareDiagnostics.failure("BATCH_SEND_START", it)
            _state.value = _state.value.copy(
                sending = UltraShareForegroundService.state.value.sending,
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
            false
        }
    }

    fun sendBatchToApprovedPeers(uris: List<Uri>): Boolean {
        val allTargets = UltraShareForegroundService.approvedSendTargets()
        val targets = UltraShareForegroundService.availableSendTargets()
        if (uris.isEmpty() || targets.isEmpty()) {
            if (uris.isNotEmpty() && allTargets.isNotEmpty()) {
                _state.value = _state.value.copy(
                    error = "جميع الأجهزة المقترنة لديها عمليات إرسال نشطة بالفعل. يمكنك اختيار جهاز آخر أو الانتظار حتى ينتهي الإرسال."
                )
            }
            return false
        }
        return runCatching {
            UltraShareForegroundService.startSendingBatchToPeers(context, uris, targets)
            _state.value = _state.value.copy(
                sending = true,
                progress = null,
                queueCompleted = 0,
                queueTotal = uris.distinctBy { it.toString() }.size,
                queueCurrentName = null,
                error = null,
                status = "بدأ الإرسال إلى " + targets.size + " أجهزة مقترنة"
            )
            true
        }.getOrElse { failure ->
            _state.value = _state.value.copy(
                sending = UltraShareForegroundService.state.value.sending,
                error = failure.localizedMessage ?: "تعذر بدء الإرسال الجماعي."
            )
            false
        }
    }

    fun resumePendingForAllPeers() {
        val allTargets = UltraShareForegroundService.approvedSendTargets()
        val targets = UltraShareForegroundService.availableSendTargets()
        if (targets.isEmpty()) {
            if (allTargets.isNotEmpty()) {
                _state.value = _state.value.copy(
                    error = "لا توجد أجهزة متاحة للاستئناف حالياً؛ جميع الأجهزة لديها عمليات نقل نشطة."
                )
            }
            return
        }
        viewModelScope.launch {
            val peerIds = targets.mapTo(mutableSetOf()) { it.sessionId }
            val ledger = UltraSharePeerDeliveryStore.observe(context).first()
            val pendingUris = ledger.filter {
                it.peerSessionId in peerIds &&
                    (it.status == UltraSharePeerDeliveryStore.STATUS_PENDING ||
                        it.status == UltraSharePeerDeliveryStore.STATUS_FAILED)
            }.map { it.parsedUri }.distinctBy { it.toString() }
            if (pendingUris.isEmpty()) {
                _state.value = _state.value.copy(
                    status = "لا توجد تسليمات متبقية للأجهزة المقترنة حالياً."
                )
            } else {
                runCatching {
                    UltraShareForegroundService.startSendingBatchToPeers(
                        context, pendingUris, targets, preserveCompleted = true
                    )
                    _state.value = _state.value.copy(
                        sending = true,
                        queueCompleted = 0,
                        queueTotal = pendingUris.size,
                        error = null,
                        status = "استئناف الملفات غير المكتملة فقط؛ لن نعيد إرسال ما اكتمل سابقاً."
                    )
                }.onFailure { failure ->
                    _state.value = _state.value.copy(
                        error = failure.localizedMessage ?: "تعذر استئناف الإرسال الجماعي."
                    )
                }
            }
        }
    }

    fun resumePendingBatch(host: String) {
        val target = UltraShareForegroundService.approvedSendTarget(host.trim()) ?: return
        if (UltraShareForegroundService.isSendingToPeer(target.host)) {
            _state.value = _state.value.copy(
                error = "يوجد إرسال نشط إلى هذا الجهاز بالفعل؛ يمكنك استئناف جهاز آخر بالتوازي."
            )
            return
        }
        viewModelScope.launch {
            val peerEntries = UltraSharePeerDeliveryStore.observe(context).first()
                .filter { it.peerSessionId == target.sessionId }
            val pendingUris = if (peerEntries.isNotEmpty()) {
                peerEntries.filter {
                    it.status == UltraSharePeerDeliveryStore.STATUS_PENDING ||
                        it.status == UltraSharePeerDeliveryStore.STATUS_FAILED
                }.map { it.parsedUri }
            } else {
                UltraShareQueueStore.pending(context).map { it.parsedUri }
            }
            if (pendingUris.isEmpty()) {
                _state.value = _state.value.copy(status = "لا توجد ملفات متبقية لهذا الجهاز؛ تم الحفاظ على الملفات التي استلمها سابقاً.")
            } else {
                runCatching {
                    UltraShareForegroundService.startSendingBatchToPeers(context, pendingUris, listOf(target), preserveCompleted = true)
                    _state.value = _state.value.copy(
                        sending = true,
                        queueCompleted = 0,
                        queueTotal = pendingUris.size,
                        error = null,
                        status = "استئناف الملفات المتبقية لهذا الجهاز فقط"
                    )
                }.onFailure { failure ->
                    _state.value = _state.value.copy(error = failure.localizedMessage ?: "تعذر استئناف النقل.")
                }
            }
        }
    }

    fun retryFile(entry: UltraShareQueueEntry, host: String) {
        val target = UltraShareForegroundService.approvedSendTarget(host.trim())
        if (target == null || entry.status != UltraShareQueueStore.STATUS_FAILED) return
        if (UltraShareForegroundService.isSendingToPeer(target.host)) {
            _state.value = _state.value.copy(
                error = "يوجد إرسال نشط إلى هذا الجهاز بالفعل؛ اختر جهازاً آخر لإعادة المحاولة بالتوازي."
            )
            return
        }
        runCatching {
            UltraShareForegroundService.startSendingBatchToPeers(
                context, listOf(entry.parsedUri), listOf(target), preserveCompleted = true
            )
            _state.value = _state.value.copy(
                sending = true,
                progress = null,
                error = null,
                status = "إعادة محاولة إرسال " + entry.displayName + " إلى هذا الجهاز فقط"
            )
        }.onFailure {
            _state.value = _state.value.copy(
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
        }
    }

    fun skipQueuedFile(entry: UltraShareQueueEntry) {
        if (entry.status != UltraShareQueueStore.STATUS_PENDING &&
            entry.status != UltraShareQueueStore.STATUS_FAILED
        ) return
        viewModelScope.launch {
            UltraShareQueueStore.skip(context, entry.parsedUri)
        }
    }

    fun pauseSending() {
        if (_state.value.sending) UltraShareForegroundService.pause(context)
    }

    fun clearQueue() {
        viewModelScope.launch {
            if (!_state.value.sending) UltraShareQueueStore.clear(context)
        }
    }

    fun clearReceivedHistory() {
        viewModelScope.launch {
            if (!_state.value.receiving) UltraShareReceivedHistoryStore.clear(context)
        }
    }

    fun exportReceived(destination: Uri) {
        val received = _state.value.receivedFile ?: UltraShareForegroundService.state.value.receivedFile ?: return
        exportReceivedFile(received.file, received.fileName, received.sizeBytes, destination)
    }

    fun exportReceived(entry: UltraShareReceivedEntry, destination: Uri) {
        if (!entry.isAvailable) {
            _state.value = _state.value.copy(error = "الملف غير موجود في مجلد الاستقبال؛ لم يتم إنشاء نسخة فارغة.")
            return
        }
        exportReceivedFile(entry.file, entry.fileName, entry.sizeBytes, destination)
    }

    private fun exportReceivedFile(file: java.io.File, fileName: String, sizeBytes: Long, destination: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(file.isFile && file.length() == sizeBytes) {
                        "الملف المستلم غير مكتمل أو لم يعد موجوداً."
                    }
                    val output = context.contentResolver.openOutputStream(destination, "w")
                        ?: error("تعذر فتح وجهة الحفظ.")
                    file.inputStream().use { input ->
                        output.use { target ->
                            val copied = input.copyTo(target, UltraShareBatchTransport.COPY_BUFFER_BYTES)
                            check(copied == sizeBytes) { "لم يكتمل نسخ الملف إلى المكان المحدد." }
                        }
                    }
                }
                _state.value = _state.value.copy(
                    status = context.getString(R.string.ultra_share_exported, fileName),
                    error = null
                )
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.localizedMessage ?: "تعذر تصدير الملف المستلم.")
            }
        }
    }

    fun cancelSending() {
        UltraShareForegroundService.cancelSending(context)
        _state.value = _state.value.copy(
            progress = if (_state.value.receiving) UltraShareForegroundService.state.value.receiveProgress else null,
            status = context.getString(R.string.ultra_share_send_cancelled)
        )
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, status = null)
    }

    fun setMirrorAudioRequested(enabled: Boolean) {
        _state.value = _state.value.copy(mirrorAudioRequested = enabled)
    }

    fun startHostScreenMirroring(resultCode: Int, data: android.content.Intent) {
        val current = _state.value
        if (!current.receiving && current.activePeers.isEmpty()) {
            _state.value = current.copy(error = "يجب أن تكون منشئ المجموعة ومتصلاً بالأجهزة أولاً.")
            return
        }
        UltraShareScreenMirrorService.startMirroring(
            context = context,
            resultCode = resultCode,
            data = data,
            enableAudio = current.mirrorAudioRequested
        )
    }

    fun stopHostScreenMirroring() {
        UltraShareScreenMirrorService.stopMirroring(context)
    }

    fun joinMirrorViewer() {
        val offer = _state.value.incomingMirrorOffer ?: return
        _state.value = _state.value.copy(watchingMirrorSession = offer)
        UltraShareForegroundService.sendMirrorViewerAction(
            context = context,
            hostAddress = offer.hostAddress,
            sessionId = offer.sessionId,
            action = UltraShareScreenMirrorProtocol.ViewerAction.JOINED
        )
    }

    fun leaveMirrorViewer() {
        val watching = _state.value.watchingMirrorSession ?: return
        _state.value = _state.value.copy(watchingMirrorSession = null)
        UltraShareForegroundService.sendMirrorViewerAction(
            context = context,
            hostAddress = watching.hostAddress,
            sessionId = watching.sessionId,
            action = UltraShareScreenMirrorProtocol.ViewerAction.LEFT
        )
    }

    override fun onCleared() {
        if (!hotspotNetworkPaired) {
            hotspotNetworkCallback?.let { callback ->
                val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                runCatching { connectivity?.unregisterNetworkCallback(callback) }
            }
        }
        if (!hotspotNetworkPaired) hotspotNetworkCallback = null
        // API 23–28 uses a WifiManager network profile instead of a retained
        // ConnectivityManager callback. Remove it when the pairing never became
        // an approved peer; a live paired session deliberately keeps its route.
        if (UltraShareForegroundService.approvedSendTargets().isEmpty()) {
            releaseLegacyWifiNetwork()
        }
        scanJob?.cancel()
        wifiDirectScanTimeout?.cancel()
        bluetoothScanTimeout?.cancel()
        wifiDirectSession?.close()
        wifiDirectSession = null
        bluetoothSession?.close()
        bluetoothSession = null
        bleSession?.close()
        bleSession = null
        // Transfer jobs belong to the foreground service and deliberately outlive this ViewModel.
        super.onCleared()
    }
}
