package com.malekplay.ui.network

import android.content.Context
import android.os.Build
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.network.UltraShareDiagnostics
import com.malekplay.network.UltraShareTransport
import com.malekplay.network.UltraShareControlChannel
import com.malekplay.network.UltraShareTrustedPeerStore
import com.malekplay.data.repository.MediaRepository
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareNegotiation
import com.malekplay.network.UltraShareNetworkRouting
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
import com.malekplay.network.NstackxAndroidDiscoverySession
import com.malekplay.network.NstackxNativeBridge
import com.malekplay.network.NstackxPeerNameCache
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
import javax.inject.Inject

data class UltraShareUiState(
    val pairingSecret: String = "",
    val invitationId: String = "",
    val localAddresses: List<String> = emptyList(),
    val receiving: Boolean = false,
    val controlChannelReady: Boolean = false,
    val dataChannelReady: Boolean = false,
    val peerDataChannelReady: Boolean = false,
    val sending: Boolean = false,
    val scanning: Boolean = false,
    val discoveredPeers: List<UltraShareDiscovery.Peer> = emptyList(),
    val progress: UltraShareTransport.Progress? = null,
    val sendProgress: UltraShareTransport.Progress? = null,
    val receiveProgress: UltraShareTransport.Progress? = null,
    val receivedFile: UltraShareTransport.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null,
    val queueCompleted: Int = 0,
    val queueTotal: Int = 0,
    val queueCurrentName: String? = null,
    val queueTotalBytes: Long = 0L,
    val queueTransferredBytes: Long = 0L,
    val queueEntries: List<UltraShareQueueEntry> = emptyList(),
    val receivedHistory: List<UltraShareReceivedEntry> = emptyList(),
    val wifiDirectPeers: List<UltraShareWifiDirect.Peer> = emptyList(),
    val bluetoothPeers: List<UltraShareBluetoothDiscovery.Peer> = emptyList(),
    val blePeers: List<UltraShareBleDiscovery.Peer> = emptyList(),
    val bleScanning: Boolean = false,
    val bluetoothScanning: Boolean = false,
    val wifiDirectScanning: Boolean = false,
    val wifiDirectHost: String? = null,
    val hotspotSsid: String? = null,
    val hotspotPassword: String? = null,
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
    val peerDeliveryEntries: List<com.malekplay.network.UltraSharePeerDeliveryEntry> = emptyList()
)

@HiltViewModel
class UltraShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _state = MutableStateFlow(UltraShareUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var wifiDirectScanTimeout: Job? = null
    private var bluetoothScanTimeout: Job? = null
    private var wifiDirectSession: UltraShareWifiDirect.Session? = null
    private var bluetoothSession: UltraShareBluetoothDiscovery.Session? = null
    private var bleSession: UltraShareBleDiscovery.Session? = null
    private var nstackxStartJob: Job? = null
    // The requested Wi-Fi Network is process-scoped, not screen-scoped. Keep
    // its callback lease shared across ViewModel recreation while a paired session
    // is alive; stopReceiving() or onLost() releases it.
    private var hotspotNetworkCallback: android.net.ConnectivityManager.NetworkCallback?
        get() = retainedHotspotNetworkCallback
        set(value) { retainedHotspotNetworkCallback = value }
    private var hotspotNetworkPaired: Boolean
        get() = retainedHotspotNetworkPaired
        set(value) { retainedHotspotNetworkPaired = value }
    private val nstackxPeerNames = NstackxPeerNameCache()
    private data class PendingQrPairing(val secret: String, val hosts: List<String>)
    private var pendingQrPairing: PendingQrPairing? = null
    private var qrP2pConnectStarted = false
    // UI's requestingConnection also covers Wi-Fi Network requests and P2P discovery.
    // Keep the actual MPC2 handshake guard independent so those flows can hand off
    // into requestConnection without blocking themselves.
    private var controlPairingInProgress = false
    private var wifiDirectPrimaryAttempt = false
    private var hotspotFallbackStarted = false
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
            UltraShareForegroundService.state.collect { serviceState ->
                val current = _state.value
                val received = serviceState.receivedFile
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
                    receivedFile = serviceState.receivedFile ?: current.receivedFile,
                    status = serviceState.status ?: current.status,
                    error = serviceState.error,
                    queueCompleted = serviceState.queueCompleted,
                    queueTotal = serviceState.queueTotal,
                    queueCurrentName = serviceState.queueCurrentName,
                    queueTotalBytes = serviceState.queueTotalBytes,
                    queueTransferredBytes = serviceState.queueTransferredBytes,
                    hotspotSsid = serviceState.hotspotSsid,
                    hotspotPassword = serviceState.hotspotPassword,
                    hotspotStarting = serviceState.hotspotStarting,
                    hotspotActive = serviceState.hotspotActive,
                    lanFallbackReady = current.lanFallbackReady || (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                            hotspotFallbackStarted &&
                            serviceState.receiving &&
                            serviceState.localAddresses.any(UltraShareTransport::isValidPrivateLanHost)
                        ),
                    pendingConnection = serviceState.pendingConnection,
                    activePeers = serviceState.activePeers,
                    reconnectablePeers = serviceState.reconnectablePeers,
                    peerTransfers = serviceState.peerTransfers,
                    approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                    approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                    approvedPeerHost = current.approvedPeerHost?.takeIf { approved ->
                        serviceState.activePeers.any { it.host == approved }
                    }
                )
                // A navigation-scoped ViewModel can be recreated while the foreground
                // receiver service and P2P group remain alive. Re-publish the ephemeral
                // invitation on the retained group instead of leaving a dead QR session.
                if (serviceState.receiving && !serviceState.hotspotActive &&
                    !serviceState.hotspotStarting && _state.value.invitationId.isBlank() &&
                    !wifiDirectPrimaryAttempt && !hotspotFallbackStarted
                ) {
                    wifiDirectPrimaryAttempt = true
                    viewModelScope.launch {
                        delay(350L)
                        if (_state.value.receiving && _state.value.invitationId.isBlank()) {
                            advertiseCurrentWifiDirectInvitation()
                        }
                    }
                }
            }
        }
    }

    fun startReceiving() {
        if (_state.value.receiving || UltraShareForegroundService.state.value.receiving) return
        if (wifiDirectCleanupInProgress) {
            _state.value = _state.value.copy(
                error = "يجري إغلاق جلسة Wi-Fi Direct السابقة؛ انتظر اكتمال التنظيف ثم أعد المحاولة."
            )
            return
        }
        UltraShareDiagnostics.beginSession("RECEIVER", "start_receiving")
        UltraShareDiagnostics.event("PAIRING", "RECEIVER_WAITING_FOR_PEER")
        val secret = UltraShareTransport.createPairingSecret()
        wifiDirectPrimaryAttempt = true
        hotspotFallbackStarted = false
        _state.value = _state.value.copy(
            pairingSecret = secret,
            invitationId = "",
            receiving = true,
            controlChannelReady = false,
            dataChannelReady = false,
            hotspotSsid = null,
            hotspotPassword = null,
            hotspotActive = false,
            hotspotStarting = false,
            lanFallbackReady = false,
            progress = null,
            error = null,
            status = "جارٍ تجهيز Wi-Fi Direct كمسار استقبال أساسي..."
        )
        runCatching {
            // Start the authenticated control/data listeners only. Wi-Fi Direct
            // group ownership is established first; LocalOnlyHotspot is fallback-only.
            UltraShareForegroundService.startReceiving(context, secret, createHotspot = false)
            advertiseCurrentWifiDirectInvitation()
        }.onFailure {
            UltraShareDiagnostics.failure("RECEIVER_P2P_START", it)
            startHotspotFallback(it.localizedMessage ?: "تعذر بدء Wi-Fi Direct.")
        }
    }

    /** Stop every Wi-Fi Direct discovery/advertisement operation before releasing its group. */
    private fun cleanupWifiDirectBeforeFallback(
        session: UltraShareWifiDirect.Session,
        onComplete: (Boolean) -> Unit
    ) {
        session.stopInvitationDiscoveryChecked { discoveryStopped ->
            if (!discoveryStopped) {
                UltraShareDiagnostics.event("WIFI_DIRECT", "FALLBACK_BLOCKED_DISCOVERY_CLEANUP_FAILED")
                onComplete(false)
                return@stopInvitationDiscoveryChecked
            }
            session.stopInvitationAdvertisementChecked { advertisementStopped ->
                if (!advertisementStopped) {
                    UltraShareDiagnostics.event("WIFI_DIRECT", "FALLBACK_BLOCKED_ADVERTISEMENT_CLEANUP_FAILED")
                    onComplete(false)
                    return@stopInvitationAdvertisementChecked
                }
                session.removeGroup(onComplete)
            }
        }
    }

    private fun startHotspotFallback(reason: String) {
        if (hotspotFallbackStarted || !_state.value.receiving) return
        hotspotFallbackStarted = true
        wifiDirectPrimaryAttempt = false
        pendingQrPairing = null
        qrP2pConnectStarted = false
        val session = wifiDirectSession
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val beginLanFallback: () -> Unit = {
                val hasExistingLan = (
                    _state.value.localAddresses + UltraShareForegroundService.state.value.localAddresses
                ).any(UltraShareTransport::isValidPrivateLanHost)
                _state.value = _state.value.copy(
                    invitationId = "",
                    lanFallbackReady = hasExistingLan,
                    wifiDirectHost = null,
                    wifiDirectScanning = false,
                    error = if (hasExistingLan) null
                        else "تعذر Wi-Fi Direct، وهذا الإصدار لا يدعم نقطة الاتصال المحلية داخل التطبيق. اتصل الهاتفان بشبكة Wi-Fi نفسها ثم أعد المحاولة.",
                    status = if (hasExistingLan)
                        "تعذر Wi-Fi Direct؛ نستخدم شبكة Wi-Fi الحالية كبديل. يجب أن يكون الهاتف الآخر على الشبكة نفسها."
                    else "تعذر Wi-Fi Direct ولا توجد شبكة محلية بديلة متاحة."
                )
            }
            if (session == null) {
                beginLanFallback()
            } else {
                cleanupWifiDirectBeforeFallback(session) { removed ->
                    if (removed) {
                        beginLanFallback()
                    } else {
                        // Allow an explicit retry after cleanup failure; do not leave
                        // the fallback latch permanently closed for this receive session.
                        hotspotFallbackStarted = false
                        _state.value = _state.value.copy(
                            invitationId = "",
                            lanFallbackReady = false,
                            error = "تعذر إيقاف اكتشاف/إعلان Wi-Fi Direct أو إغلاق المجموعة السابقة؛ لم أفعّل مساراً بديلاً قبل تأكيد التنظيف."
                        )
                    }
                }
            }
            return
        }
        val beginFallback: () -> Unit = {
            _state.value = _state.value.copy(
                invitationId = "",
                wifiDirectHost = null,
                wifiDirectScanning = false,
                error = null,
                status = "تعذر Wi-Fi Direct؛ جارٍ تشغيل نقطة الاتصال المحلية كمسار بديل..."
            )
            runCatching { UltraShareForegroundService.startHotspot(context) }
                .onFailure { failure ->
                    UltraShareDiagnostics.failure("HOTSPOT_FALLBACK_START", failure)
                    hotspotFallbackStarted = false
                    _state.value = _state.value.copy(
                        error = failure.localizedMessage
                            ?: "فشل Wi-Fi Direct وتعذر تشغيل نقطة الاتصال المحلية."
                    )
                }
        }
        if (session == null) {
            beginFallback()
        } else {
            cleanupWifiDirectBeforeFallback(session) { removed ->
                if (removed) {
                    beginFallback()
                } else {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "FALLBACK_BLOCKED_GROUP_CLEANUP_FAILED",
                        mapOf("reason" to reason.take(160))
                    )
                    hotspotFallbackStarted = false
                    _state.value = _state.value.copy(
                        invitationId = "",
                        error = "تعذر إيقاف اكتشاف/إعلان Wi-Fi Direct أو إغلاق المجموعة السابقة بأمان؛ لم أشغّل نقطة الاتصال لتجنب تداخل الشبكتين."
                    )
                }
            }
        }
    }

    fun requestConnection(
        host: String,
        secret: String,
        candidates: List<com.malekplay.network.UltraShareNegotiation.Candidate> = emptyList(),
        alternateHosts: List<String> = emptyList(),
        preferredNetwork: android.net.Network? = null,
        onConnectionEstablished: ((String) -> Unit)? = null
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
        if (host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = "أدخل عنوان الجهاز ورمز الاقتران الصحيح أولاً."
            )
            return
        }
        val existingReceiver = UltraShareForegroundService.state.value
        if (existingReceiver.receiving && existingReceiver.pairingSecret.isNotBlank() &&
            existingReceiver.pairingSecret != secret.trim()
        ) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = "لديك جلسة استقبال نشطة برمز مختلف. أوقف الجلسة الحالية أولاً حتى لا تتعارض مفاتيح الاستقبال بين الجهازين."
            )
            return
        }
        controlPairingInProgress = true
        val discoveredCandidates = candidates.ifEmpty {
            _state.value.discoveredPeers.firstOrNull { peer ->
                peer.host == host.trim() || peer.candidates.any { it.host == host.trim() }
            }?.candidates.orEmpty()
        }
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
                            UltraShareTransport.isValidPrivateLanHost(it)
                        } +
                        orderedRoutes.map { it.host }
                    ).distinct()
                var acceptedHost: String? = null
                var rejected = false
                var lastFailure: Exception? = null
                for (routeHost in routeHosts) {
                    UltraShareDiagnostics.event("CONTROL_CHANNEL", "ROUTE_ATTEMPT", mapOf("host" to routeHost))
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
                        // then the OS default route, which can be the active P2P route on
                        // these devices. Do not discard a valid group-owner address here.
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
                            network = routeNetwork,
                            networks = routeNetworks.drop(1),
                            peerId = UltraShareDiscovery.localPeerId(context),
                            onSessionClosed = { disconnectedHost ->
                                UltraShareForegroundService.disconnectApprovedPeer(disconnectedHost)
                                forgetApprovedSenderTarget(disconnectedHost)
                            }
                        )
                        UltraShareDiagnostics.event("CONTROL_CHANNEL", "DECISION_RECEIVED", mapOf("host" to routeHost, "decision" to decision.name))
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
                        UltraShareDiagnostics.failure("CONTROL_CHANNEL_CONNECT", failure, "route=" + routeHost)
                        lastFailure = failure
                    }
                }
                if (acceptedHost != null) {
                    UltraShareDiagnostics.event("PAIRING", "CONTROL_CHANNEL_ESTABLISHED", mapOf("host" to acceptedHost))
                    val connectedHost = acceptedHost
                    val peerName = UltraShareControlChannel.sessionPeerName(connectedHost)
                        ?: "جهاز MalekPlay المستقبل"
                    val retainedPeerSessions =
                        UltraShareForegroundService.reconnectableSendTargets().map { it.host to it.sessionId } +
                            _state.value.reconnectablePeers.map { it.host to it.sessionId }
                    val sessionId = UltraShareControlChannel.sessionPeerId(connectedHost)
                        ?: UltraShareMultiRecipientCoordinator.resolveSessionId(
                            connectedHost,
                            retainedPeerSessions.distinctBy { it.first },
                            java.util.UUID.randomUUID().toString()
                        )
                    // MPC2 authenticates the peer, but file transfer has a separate
                    // listener. Probe the actual V2 data endpoint on every QR/discovery
                    // host and every Android Network that routes to that host.
                    val dataRoutes = ArrayList<UltraShareNegotiation.Candidate>()
                    val dataHosts = (
                        listOf(connectedHost) + alternateHosts + orderedRoutes.map { it.host }
                    ).filter { UltraShareTransport.isValidPrivateLanHost(it) }
                        .distinct()
                        .take(8)
                    for (dataHost in dataHosts) {
                        val networks = (
                            listOfNotNull(preferredNetwork) +
                                UltraShareNetworkRouting.findNetworksForHost(context, dataHost)
                        ).distinct()
                        val dataReady = UltraShareDiscovery.probeDataEndpoint(
                            dataHost,
                            com.malekplay.network.UltraShareBatchTransport.DEFAULT_PORT,
                            networks
                        )
                        UltraShareDiagnostics.event("DATA_CHANNEL", if (dataReady) "PROBE_SUCCEEDED" else "PROBE_FAILED", mapOf("host" to dataHost, "port" to com.malekplay.network.UltraShareBatchTransport.DEFAULT_PORT, "networkCount" to networks.size))
                        if (dataReady) {
                            val transport = when {
                                UltraShareNetworkRouting.isWifiDirectGroupHost(dataHost) ->
                                    UltraShareNegotiation.Transport.WIFI_DIRECT
                                preferredNetwork != null ->
                                    UltraShareNegotiation.Transport.HOTSPOT
                                else -> UltraShareNegotiation.Transport.WLAN
                            }
                            dataRoutes += UltraShareNegotiation.Candidate(
                                transport = transport,
                                host = dataHost,
                                port = com.malekplay.network.UltraShareBatchTransport.DEFAULT_PORT,
                                connected = true,
                                reachable = true,
                                priority = if (dataHost == connectedHost) 100 else 50
                            )
                        }
                    }
                    val verifiedRoutes = UltraShareNegotiation.orderedCandidates(dataRoutes)
                    val dataReady = verifiedRoutes.isNotEmpty()
                    val approvedTarget = UltraShareApprovedSendTarget(
                        sessionId = sessionId,
                        host = connectedHost,
                        secret = secret.trim(),
                        candidates = verifiedRoutes
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
                    }
                    UltraShareForegroundService.registerApprovedPeer(
                        name = peerName,
                        host = connectedHost,
                        role = "أرسل طلب الاتصال",
                        routeCandidates = verifiedRoutes,
                        sessionId = sessionId
                    )
                    _state.value = _state.value.copy(
                        approvedPeerHost = connectedHost,
                        peerDataChannelReady = dataReady,
                        approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                        approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                        status = if (dataReady)
                            "تم الاقتران والتحقق من منفذ نقل الملفات فعلياً عبر ${verifiedRoutes.size} مسار."
                        else
                            "تم توثيق الاقتران، لكن منفذ نقل الملفات لم يستجب بعد. لن يبدأ إرسال أي ملف حتى يصبح مسار البيانات جاهزاً.",
                        requestingConnection = false,
                        error = if (dataReady) null else
                            "قناة التحكم متصلة، لكن منفذ نقل الملفات غير متاح على عناوين الجهاز المكتشفة. تأكد من بقاء شاشة الاستقبال مفتوحة ثم أعد الاتصال."
                    )
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
            peerDataChannelReady = next?.candidates?.any { it.reachable } == true,
            approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
            approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host }
        )
    }

    fun disconnectPeer(host: String) {
        UltraShareForegroundService.disconnectApprovedPeer(host)
        forgetApprovedSenderTarget(host)
        // This is an explicit user disconnect. Stop only an otherwise-idle sender
        // service; an active receiver remains available for other incoming peers.
        UltraShareForegroundService.stopIfUnpaired(context)
    }

    fun reconnectPeer(host: String) {
        val peer = _state.value.reconnectablePeers.firstOrNull { it.host == host }
        val retainedTarget = UltraShareForegroundService.reconnectableSendTarget(host)
        val secret = retainedTarget?.secret ?: _state.value.pairingSecret
        if (!UltraShareTransport.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(error = "انتهت صلاحية رمز الاقتران في الذاكرة. أعد مسح QR أو أدخل رمز الجهاز للاتصال مجدداً.")
            return
        }
        viewModelScope.launch {
            // V2 data ports are allocated per receiver-service lifetime. Refresh the
            // authenticated peer's candidates before reusing a previously saved route.
            val discovered = runCatching {
                UltraShareDiscovery.discoverPeers(context = context)
            }.getOrDefault(emptyList())
            val expectedPeerId = peer?.sessionId ?: retainedTarget?.sessionId
            val freshPeer = discovered.firstOrNull { candidate ->
                expectedPeerId != null && candidate.peerId == expectedPeerId
            } ?: discovered.firstOrNull { candidate ->
                candidate.host == host || candidate.candidates.any { it.host == host }
            }
            if (freshPeer != null) {
                requestConnection(
                    freshPeer.host,
                    secret,
                    candidates = freshPeer.candidates,
                    alternateHosts = freshPeer.candidates.map { it.host }
                )
            } else {
                // Keep the legacy fixed-port path available when discovery is offline.
                requestConnection(host, secret)
            }
        }
    }

    fun forgetPeer(host: String) {
        // "Forget device" must revoke both the live route and the persisted consent.
        val peerId = (_state.value.activePeers + _state.value.reconnectablePeers)
            .firstOrNull { it.host == host }?.sessionId
        peerId?.takeIf(UltraShareTrustedPeerStore::isValidPeerId)?.let {
            UltraShareTrustedPeerStore.forget(context, it)
        }
        UltraShareForegroundService.forgetApprovedPeer(host)
        val next = UltraShareForegroundService.approvedSendTargets().lastOrNull()
        _state.value = _state.value.copy(
            approvedPeerHost = next?.host,
            peerDataChannelReady = next?.candidates?.any { it.reachable } == true,
            approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
            approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
            reconnectablePeers = _state.value.reconnectablePeers.filterNot { it.host == host }
        )
    }

    /** Disconnect only the currently selected sender peer; keep other approved peers available. */
    fun clearApprovedConnection() {
        val currentHost = _state.value.approvedPeerHost ?: return
        UltraShareForegroundService.disconnectApprovedPeer(currentHost)
        forgetApprovedSenderTarget(currentHost)
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
        hotspotNetworkCallback?.let { callback ->
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
        hotspotNetworkCallback = null
        hotspotNetworkPaired = false
        UltraShareForegroundService.stopReceiving(context)
        runCatching { wifiDirectSession?.stopInvitationAdvertisement() }
        wifiDirectCleanupInProgress = wifiDirectSession != null
        runCatching {
            wifiDirectSession?.removeGroup {
                wifiDirectCleanupInProgress = false
            }
        }.onFailure { wifiDirectCleanupInProgress = false }
        wifiDirectPrimaryAttempt = false
        hotspotFallbackStarted = false
        _state.value = _state.value.copy(
            receiving = false,
            controlChannelReady = false,
            pairingSecret = "",
            invitationId = "",
            lanFallbackReady = false,
            localAddresses = emptyList(),
            progress = if (_state.value.sending) UltraShareForegroundService.state.value.sendProgress else null,
            status = context.getString(R.string.ultra_share_stopped)
        )
        stopNstackxIfIdle()
    }

    /**
     * Joins the temporary LocalOnlyHotspot advertised by QR v4, then performs
     * the same authenticated MPC2 handshake as every other route.
     */
    fun connectHotspotInvitation(pairing: com.malekplay.network.UltraShareQrCode.PairingDetails) {
        if (hotspotNetworkCallback != null || controlPairingInProgress || pendingQrPairing != null) {
            _state.value = _state.value.copy(
                error = "هناك محاولة اقتران أو جلسة شبكة قائمة بالفعل. أنهِ الجلسة الحالية قبل مسح رمز آخر."
            )
            return
        }
        if (pairing.hotspotSsid.isNullOrBlank() || pairing.hotspotPassword.isNullOrBlank()) {
            requestConnection(pairing.host, pairing.pairingSecret, alternateHosts = pairing.hosts)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 8–9 cannot use WifiNetworkSpecifier, but after the user
            // joins the advertised LocalOnlyHotspot manually the QR's LAN host
            // is reachable through the system Wi-Fi route and MPC2 can proceed.
            if (pairing.host.isNotBlank()) {
                requestConnection(
                    host = pairing.host,
                    secret = pairing.pairingSecret,
                    alternateHosts = pairing.hosts.drop(1)
                )
            } else {
                _state.value = _state.value.copy(
                    requestingConnection = false,
                    error = "اتصل أولاً بشبكة MalekPlay من إعدادات Wi-Fi، ثم امسح رمز QR مرة أخرى."
                )
            }
            return
        }

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        if (connectivity == null) {
            _state.value = _state.value.copy(error = "خدمة اتصال Wi-Fi غير متاحة على هذا الجهاز.")
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

        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(pairing.hotspotSsid)
            .setWpa2Passphrase(pairing.hotspotPassword)
            .build()
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
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
                if (!connectionStarted.compareAndSet(false, true)) return
                val viewModel = owner.get() ?: run {
                    runCatching { connectivity.unregisterNetworkCallback(this) }
                    return
                }
                viewModel._state.value = viewModel._state.value.copy(
                    status = "اتصل Android بشبكة MalekPlay؛ جارٍ التحقق من رمز الاقتران..."
                )
                viewModel.requestConnection(
                    host = pairing.host,
                    secret = pairing.pairingSecret,
                    alternateHosts = pairing.hosts.drop(1),
                    preferredNetwork = network,
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
                    viewModel._state.value = viewModel._state.value.copy(
                        requestingConnection = false,
                        error = "لم يكتمل الانضمام إلى شبكة MalekPlay. وافق على طلب Wi-Fi الذي عرضه Android وأبقِ نقطة الاتصال مفتوحة."
                    )
                }
                runCatching { connectivity.unregisterNetworkCallback(this) }
            }

            override fun onLost(network: android.net.Network) {
                UltraShareDiagnostics.event("WIFI_NETWORK", "LOST", mapOf("networkHandle" to network.networkHandle, "paired" to paired.get()))
                networkLost.set(true)
                val viewModel = owner.get()
                val isCurrent = retainedHotspotNetworkCallback === this
                if (paired.get() && isCurrent) {
                    connectedHost.get()?.let { peerHost ->
                        UltraShareForegroundService.disconnectApprovedPeer(peerHost)
                    }
                }
                if (isCurrent) {
                    retainedHotspotNetworkCallback = null
                    retainedHotspotNetworkPaired = false
                }
                if (viewModel != null && isCurrent) {
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
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = failure.localizedMessage ?: "تعذر طلب الانضمام إلى شبكة MalekPlay."
            )
        }
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
        ensureNstackxDiscovery()
        scanJob = viewModelScope.launch {
            // Keep the latest sighting time per stable peer identity. A device that
            // leaves Wi-Fi must disappear from the radar instead of remaining selectable.
            val found = LinkedHashMap<String, Pair<UltraShareDiscovery.Peer, Long>>()
            try {
                // Short repeated discovery windows make newly-started receivers appear
                // without forcing the user to restart the scan manually.
                while (true) {
                    // NSTACKX may start before Android finishes bringing up Wi-Fi/P2P.
                    // Re-attempt its bounded startup while the user is still scanning;
                    // UDP discovery remains available as an independent fallback.
                    if (NstackxAndroidDiscoverySession.current() == null &&
                        nstackxStartJob?.isActive != true
                    ) {
                        ensureNstackxDiscovery()
                    }
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
                        val nativeName = peer.peerId
                            ?.lowercase(java.util.Locale.ROOT)
                            ?.let { nstackxPeerNames.get(it) }
                        if (nativeName.isNullOrBlank()) peer
                        else UltraShareDiscovery.mergeNativePeerName(
                            peer,
                            peer.peerId.orEmpty(),
                            nativeName
                        )
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
                stopNstackxIfIdle()
            }
        }
    }


    /**
     * Starts NSTACKX as an additional discovery signal. Existing UDP/QR
     * endpoint discovery remains authoritative until native peer-to-peer
     * address mapping is validated on real Android devices.
     */
    private fun onNstackxPeerFound(peer: NstackxNativeBridge.Peer) {
        nstackxPeerNames.put(peer.deviceId, peer.deviceName)
        val current = _state.value
        _state.value = current.copy(
            discoveredPeers = current.discoveredPeers.map { sighting ->
                if (sighting.peerId?.equals(peer.deviceId, ignoreCase = true) == true) {
                    sighting.copy(name = peer.deviceName)
                } else sighting
            }
        )
    }

    private fun ensureNstackxDiscovery() {
        if (!NstackxNativeBridge.isAvailable || nstackxStartJob?.isActive == true) return

        // A foreground receiver can outlive this ViewModel. Reattach the current
        // screen observer to the existing native engine instead of stopping/restarting
        // NSTACKX, which can tear down a DFile session already serving another phone.
        if (NstackxAndroidDiscoverySession.current() != null) {
            NstackxAndroidDiscoverySession.setPeerListener(::onNstackxPeerFound)
            return
        }

        nstackxStartJob = viewModelScope.launch(Dispatchers.IO) {
            var lastFailure: Exception? = null
            for (attempt in 0..2) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val serviceState = UltraShareForegroundService.state.value
                val stillNeeded = _state.value.receiving || _state.value.scanning ||
                    serviceState.receiving || serviceState.activePeers.isNotEmpty()
                if (!stillNeeded) break
                try {
                    NstackxAndroidDiscoverySession.start(
                        context,
                        onPeerFound = ::onNstackxPeerFound
                    )
                    lastFailure = null
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    lastFailure = failure
                    if (attempt < 2) delay(750L * (attempt + 1))
                }
            }

            val serviceState = UltraShareForegroundService.state.value
            val stillNeeded = _state.value.receiving || _state.value.scanning ||
                serviceState.receiving || serviceState.activePeers.isNotEmpty()
            if (lastFailure != null && stillNeeded) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        error = "تعذر تشغيل اكتشاف NSTACKX بعد عدة محاولات: " +
                            (lastFailure?.localizedMessage ?: "خطأ غير معروف") +
                            ". يستمر البحث المحلي وQR كمسار احتياطي."
                    )
                }
            }
            if (!stillNeeded) NstackxAndroidDiscoverySession.stop()
        }
    }

    private fun stopNstackxIfIdle() {
        val serviceState = UltraShareForegroundService.state.value
        if (_state.value.receiving || _state.value.scanning ||
            serviceState.receiving || serviceState.activePeers.isNotEmpty()
        ) return
        if (nstackxStartJob?.isActive == true) nstackxStartJob?.cancel()
        NstackxAndroidDiscoverySession.stop()
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
                if (isGroupOwner) {
                    if (UltraShareForegroundService.state.value.receiving) {
                        UltraShareForegroundService.refreshLocalAddresses(context)
                        _state.value = _state.value.copy(
                            status = "اتصل Wi-Fi Direct بنجاح؛ هذا الهاتف جاهز للاستقبال."
                        )
                    } else {
                        _state.value = _state.value.copy(
                            status = "تم إنشاء اتصال Wi-Fi Direct؛ جارٍ تجهيز قناة الاقتران."
                        )
                    }
                } else if (!remoteHost.isNullOrBlank()) {
                    val pending = pendingQrPairing
                    if (pending != null) {
                        pendingQrPairing = null
                        qrP2pConnectStarted = false
                        _state.value = _state.value.copy(wifiDirectHost = remoteHost)
                        requestConnection(
                            host = remoteHost,
                            secret = pending.secret,
                            alternateHosts = pending.hosts.filter { it != remoteHost }
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
                val fallbackHosts = pending?.hosts.orEmpty().filter {
                    UltraShareTransport.isValidPrivateLanHost(it) &&
                        !UltraShareNetworkRouting.isWifiDirectGroupHost(it)
                }
                if (pending != null) {
                    pendingQrPairing = null
                    qrP2pConnectStarted = false
                }
                if (pending == null && receiving && wifiDirectPrimaryAttempt) {
                    startHotspotFallback(message)
                } else {
                    _state.value = _state.value.copy(
                        wifiDirectScanning = false,
                        wifiDirectHost = null,
                        invitationId = if (receiving) "" else _state.value.invitationId,
                        requestingConnection = if (pending != null) fallbackHosts.isNotEmpty() else _state.value.requestingConnection,
                        error = if (pending != null && fallbackHosts.isNotEmpty()) null else message,
                        status = if (pending != null && fallbackHosts.isNotEmpty())
                            "تعذر إكمال Wi-Fi Direct؛ ننتقل الآن إلى مسار الشبكة المحلية الآمن..."
                        else _state.value.status
                    )
                    if (pending != null && fallbackHosts.isNotEmpty()) {
                        requestConnection(
                            host = fallbackHosts.first(),
                            secret = pending.secret,
                            alternateHosts = fallbackHosts.drop(1)
                        )
                    }
                }
            }
        ).also { wifiDirectSession = it }
    }

    fun disableWifiDirectInvitation() {
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

    fun advertiseCurrentWifiDirectInvitation() {
        if (_state.value.hotspotActive || _state.value.hotspotStarting) {
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
            java.util.UUID.randomUUID().toString().replace("-", "")
        }
        val session = runCatching { ensureWifiDirectSession() }.getOrElse { failure ->
            _state.value = _state.value.copy(error = failure.localizedMessage ?: "تعذر تهيئة Wi-Fi Direct.")
            return
        }
        _state.value = _state.value.copy(
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
                session.advertiseInvitation(invitationId) {
                    UltraShareForegroundService.refreshLocalAddresses(context)
                    _state.value = _state.value.copy(
                        invitationId = invitationId,
                        error = null,
                        status = "مجموعة Wi-Fi Direct جاهزة ودعوة QR منشورة؛ يمكن للهاتف الآخر الانضمام مباشرة."
                    )
                }
            }
        }
    }

    fun joinWifiDirectInvitation(
        invitationId: String,
        secret: String,
        fallbackHosts: List<String> = emptyList()
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
        if (!UltraShareTransport.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(
                requestingConnection = false,
                error = "رمز الاقتران داخل QR غير صالح."
            )
            return
        }
        pendingQrPairing = PendingQrPairing(secret.trim(), fallbackHosts)
        qrP2pConnectStarted = false
        try {
            val session = ensureWifiDirectSession()
            _state.value = _state.value.copy(
                requestingConnection = true,
                wifiDirectHost = null,
                error = null,
                status = "تمت قراءة الدعوة؛ جارٍ العثور على الجهاز وإنشاء اتصال مباشر آمن..."
            )
            // QR service discovery must not overlap a regular discoverPeers request.
            // Wait for the framework's stop callback before installing DNS-SD requests.
            session.stopDiscoveryChecked { stopped ->
                if (pendingQrPairing == null || wifiDirectSession !== session) return@stopDiscoveryChecked
                if (!stopped) {
                    pendingQrPairing = null
                    qrP2pConnectStarted = false
                    _state.value = _state.value.copy(
                        requestingConnection = false,
                        error = "لم يؤكد Android إيقاف البحث السابق؛ لم نبدأ اكتشاف دعوة QR بالتزامن. أوقف البحث ثم أعد المحاولة."
                    )
                    return@stopDiscoveryChecked
                }
                session.discoverInvitation(invitationId) { peer ->
                if (pendingQrPairing != null && !qrP2pConnectStarted) {
                    qrP2pConnectStarted = true
                    _state.value = _state.value.copy(
                        status = "تم العثور على جهاز الاستقبال؛ جارٍ إنشاء الاتصال المباشر..."
                    )
                    session.stopInvitationDiscoveryChecked { stopped ->
                        if (pendingQrPairing == null || wifiDirectSession !== session) return@stopInvitationDiscoveryChecked
                        if (!stopped) {
                            val pending = pendingQrPairing ?: return@stopInvitationDiscoveryChecked
                            val fallback = pending.hosts.firstOrNull {
                                UltraShareTransport.isValidPrivateLanHost(it) &&
                                    !UltraShareNetworkRouting.isWifiDirectGroupHost(it)
                            }
                            if (fallback != null) {
                                pendingQrPairing = null
                                qrP2pConnectStarted = false
                                requestConnection(fallback, pending.secret, alternateHosts = pending.hosts.filter { it != fallback })
                            } else {
                                pendingQrPairing = null
                                qrP2pConnectStarted = false
                                _state.value = _state.value.copy(
                                    requestingConnection = false,
                                    error = "لم يؤكد Android إيقاف اكتشاف دعوة Wi-Fi Direct؛ لم نبدأ اتصالاً متزامناً. أعد المحاولة أو استخدم اتصال الشبكة المحلية."
                                )
                            }
                            return@stopInvitationDiscoveryChecked
                        }
                        session.connect(peer.deviceAddress, preferThisDeviceAsGroupOwner = false)
                    }
                }
                }
            }
            viewModelScope.launch {
                delay(12_000)
                var pending = pendingQrPairing
                if (pending != null && pending.secret == secret.trim()) {
                    if (qrP2pConnectStarted) {
                        delay(18_000)
                        pending = pendingQrPairing
                        if (pending != null && pending.secret == secret.trim()) {
                            pendingQrPairing = null
                            qrP2pConnectStarted = false
                            session.stopInvitationDiscovery()
                            // A peer can be discovered over DNS-SD while the P2P group
                            // negotiation still fails on some Android/OEM combinations.
                            // Do not strand the user after that attempt: try the QR's
                            // already-advertised LAN addresses through the authenticated
                            // MPC2 channel before reporting a terminal connection error.
                            val fallback = pending.hosts.firstOrNull {
                                UltraShareTransport.isValidPrivateLanHost(it) &&
                                    !UltraShareNetworkRouting.isWifiDirectGroupHost(it)
                            }
                            if (fallback != null) {
                                _state.value = _state.value.copy(
                                    requestingConnection = true,
                                    error = null,
                                    status = "تعذر إكمال Wi-Fi Direct؛ نجرب تلقائياً مسار الشبكة المحلية الآمن..."
                                )
                                requestConnection(
                                    host = fallback,
                                    secret = pending.secret,
                                    alternateHosts = pending.hosts.filter { it != fallback }
                                )
                            } else {
                                _state.value = _state.value.copy(
                                    requestingConnection = false,
                                    error = "عُثر على الجهاز لكن تعذر إكمال اتصال Wi-Fi Direct، ولا يوجد عنوان شبكة محلية بديل صالح. تأكد من اتصال الهاتفين بالشبكة نفسها ثم أعد المحاولة."
                                )
                            }
                        }
                    } else if (fallbackHosts.isNotEmpty()) {
                        // Older peers and same-LAN sessions can still use the existing
                        // authenticated MPC2 route if DNS-SD is unavailable on this device.
                        val fallback = fallbackHosts.firstOrNull()
                        if (!fallback.isNullOrBlank()) {
                            pendingQrPairing = null
                            session.stopInvitationDiscovery()
                            requestConnection(fallback, pending.secret, alternateHosts = pending.hosts.drop(1))
                        }
                    } else {
                        pendingQrPairing = null
                        session.stopInvitationDiscovery()
                        _state.value = _state.value.copy(
                            requestingConnection = false,
                            error = "لم يتم العثور على جهاز الدعوة. تأكد من تشغيل Wi-Fi ومنح إذن الأجهزة القريبة على الهاتفين، ثم أعد مسح QR."
                        )
                    }
                }
            }
        } catch (failure: Exception) {
            pendingQrPairing = null
            qrP2pConnectStarted = false
            val fallback = fallbackHosts.firstOrNull()
            if (!fallback.isNullOrBlank()) {
                requestConnection(
                    host = fallback,
                    secret = secret,
                    alternateHosts = fallbackHosts.drop(1)
                )
            } else {
                _state.value = _state.value.copy(
                    requestingConnection = false,
                    error = failure.localizedMessage ?: "تعذر بدء اكتشاف دعوة الجهاز."
                )
            }
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
        if (_state.value.sending) return
        UltraShareDiagnostics.event("TRANSFER", "SEND_REQUESTED", mapOf("host" to host.trim()))
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
                sending = false,
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
        }
    }

    fun sendBatch(uris: List<Uri>, host: String): Boolean {
        UltraShareDiagnostics.event("TRANSFER", "BATCH_SEND_REQUESTED", mapOf("host" to host.trim(), "fileCount" to uris.size))
        val target = UltraShareForegroundService.approvedSendTarget(host.trim())
        if (uris.isEmpty() || _state.value.sending || target == null) return false
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
                sending = false,
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
            false
        }
    }

    fun sendBatchToApprovedPeers(uris: List<Uri>): Boolean {
        val targets = UltraShareForegroundService.approvedSendTargets()
        if (uris.isEmpty() || _state.value.sending || targets.isEmpty()) return false
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
                sending = false,
                error = failure.localizedMessage ?: "تعذر بدء الإرسال الجماعي."
            )
            false
        }
    }

    fun resumePendingForAllPeers() {
        val targets = UltraShareForegroundService.approvedSendTargets()
        if (targets.isEmpty() || _state.value.sending) return
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
        if (_state.value.sending) return
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
        if (_state.value.sending || target == null ||
            entry.status != UltraShareQueueStore.STATUS_FAILED
        ) return
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
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(destination, "w")
                        ?: error("تعذر فتح وجهة الحفظ.")
                    received.file.inputStream().use { input ->
                        output.use { target -> input.copyTo(target, UltraShareTransport.CHUNK_BYTES) }
                    }
                }
                _state.value = _state.value.copy(
                    status = context.getString(R.string.ultra_share_exported, received.fileName),
                    error = null
                )
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.localizedMessage)
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

    override fun onCleared() {
        if (!hotspotNetworkPaired) {
            hotspotNetworkCallback?.let { callback ->
                val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                runCatching { connectivity?.unregisterNetworkCallback(callback) }
            }
        }
        if (!hotspotNetworkPaired) hotspotNetworkCallback = null
        scanJob?.cancel()
        wifiDirectScanTimeout?.cancel()
        bluetoothScanTimeout?.cancel()
        wifiDirectSession?.close()
        wifiDirectSession = null
        bluetoothSession?.close()
        bluetoothSession = null
        bleSession?.close()
        bleSession = null
        nstackxStartJob?.cancel()
        // The native engine is process-global and may currently own DFile sessions
        // for the foreground service. Detach this screen's callback first; stop the
        // engine only when no foreground receive/approved peer session depends on it.
        NstackxAndroidDiscoverySession.detachPeerListener()
        val serviceState = UltraShareForegroundService.state.value
        if (!serviceState.receiving && serviceState.activePeers.isEmpty()) {
            NstackxAndroidDiscoverySession.stop()
        }
        nstackxPeerNames.clear()
        // Transfer jobs belong to the foreground service and deliberately outlive this ViewModel.
        super.onCleared()
    }
}
