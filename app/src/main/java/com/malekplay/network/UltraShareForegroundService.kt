package com.malekplay.network

import com.malekplay.R

import android.app.Notification
import android.app.Service
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import java.io.IOException

internal fun resetRoomTransferPresentation(state: UltraShareServiceState): UltraShareServiceState = state.copy(
    progress = null,
    sendProgress = null,
    receiveProgress = null,
    receivedFile = null,
    queueCompleted = 0,
    queueTotal = 0,
    queueCurrentName = null,
    queueTotalBytes = 0L,
    queueTransferredBytes = 0L,
    lastTransferMetrics = null,
    lastReceiveMetrics = null,
    peerTransfers = emptyList(),
    incomingMirrorOffer = null,
    roomResetGeneration = if (state.roomResetGeneration == Long.MAX_VALUE) 1L else state.roomResetGeneration + 1L,
    status = null,
    error = null
)

data class IncomingMirrorOffer(
    val sessionId: String,
    val hostAddress: String,
    val hostPeerId: String,
    val hostName: String,
    val streamPort: Int,
    val streamKey: ByteArray,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audioEnabled: Boolean,
    val revision: Long
) {
    override fun toString(): String =
        "IncomingMirrorOffer(sessionId=$sessionId, hostAddress=$hostAddress, port=$streamPort, " +
            "size=${width}x$height, fps=$fps, audio=$audioEnabled, rev=$revision, key=[REDACTED])"
}

data class UltraShareServiceState(
    val pairingSecret: String = "",
    val localAddresses: List<String> = emptyList(),
    val wifiDirectLocalAddress: String? = null,
    val receiving: Boolean = false,
    val controlChannelReady: Boolean = false,
    val dataChannelReady: Boolean = false,
    val controlChannelError: String? = null,
    val sending: Boolean = false,
    val progress: UltraShareCore.Progress? = null,
    val sendProgress: UltraShareCore.Progress? = null,
    val receiveProgress: UltraShareCore.Progress? = null,
    val hotspotSsid: String? = null,
    val hotspotPassword: String? = null,
    val hotspotStarting: Boolean = false,
    val hotspotActive: Boolean = false,
    val hotspotFailed: Boolean = false,
    val receivedFile: UltraShareCore.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null,
    val queueCompleted: Int = 0,
    val queueTotal: Int = 0,
    val queueCurrentName: String? = null,
    val queueTotalBytes: Long = 0L,
    val queueTransferredBytes: Long = 0L,
    val lastTransferMetrics: UltraShareBatchTransport.TransferMetrics? = null,
    val lastReceiveMetrics: UltraShareBatchTransport.ReceiveMetrics? = null,
    val pendingConnection: UltraShareControlChannel.Request? = null,
    val activePeers: List<UltraSharePeerSession> = emptyList(),
    /** Authenticated peers currently attached to the temporary bidirectional room. */
    val roomPeers: List<UltraSharePeerSession> = emptyList(),
    val roomConnected: Boolean = false,
    val reconnectablePeers: List<UltraSharePeerSession> = emptyList(),
    val peerTransfers: List<UltraSharePeerTransfer> = emptyList(),
    /** Monotonic signal that room-only UI data must be discarded by observers. */
    val roomResetGeneration: Long = 0L,
    /** Authenticated outbound MPC2 peers; distinct from inbound activePeers. */
    val approvedSenderPeerHosts: List<String> = emptyList(),
    /** Legacy UI compatibility only; always empty because disconnected peers are not retained. */
    val reconnectableSenderPeerHosts: List<String> = emptyList(),
    val incomingMirrorOffer: IncomingMirrorOffer? = null
)

internal data class UltraShareApprovedSendTarget(
    val sessionId: String,
    val host: String,
    val secret: String,
    val candidates: List<UltraShareNegotiation.Candidate> = emptyList(),
    /** Stable remote device identity; never substitute the local/session UI identifier. */
    val peerId: String? = null
) {
    override fun toString(): String =
        "UltraShareApprovedSendTarget(sessionId=" + sessionId +
            ", peerIdPresent=" + !peerId.isNullOrBlank() +
            ", host=" + host + ", secret=[REDACTED], candidates=" + candidates.size + ")"
}

data class UltraSharePeerSession(
    val sessionId: String,
    val name: String,
    val host: String,
    val role: String,
    val approvedAtMillis: Long,
    val status: String = "تم القبول • قناة التحكم نشطة",
    /** Routes negotiated for this approved peer; shared across navigation-scoped ViewModels. */
    val routeCandidates: List<UltraShareNegotiation.Candidate> = emptyList()
)

/** Latest independent data-channel progress for one remote peer. */
data class UltraSharePeerTransfer(
    val host: String,
    val fileName: String,
    val transferredBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val status: String,
    val updatedAtMillis: Long = (System.nanoTime() / 1_000_000L)
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** Sum only active peer rates; saturate rather than overflow for large fan-out rooms. */
internal fun aggregateActivePeerBytesPerSecond(
    transfers: List<UltraSharePeerTransfer>,
    receiving: Boolean
): Long = transfers.asSequence()
    .filter { transfer ->
        if (receiving) transfer.status == "يستقبل الآن"
        else transfer.status.startsWith("يرسل الآن")
    }
    .fold(0L) { total, transfer ->
        val rate = transfer.bytesPerSecond.coerceAtLeast(0L)
        if (Long.MAX_VALUE - total < rate) Long.MAX_VALUE else total + rate
    }

/** Keeps the shared receive progress indicator truthful while peers transfer concurrently. */
internal fun latestActivePeerReceiveProgress(
    transfers: List<UltraSharePeerTransfer>
): UltraShareCore.Progress? =
    transfers.asSequence()
        .filter { it.status == "يستقبل الآن" }
        .maxByOrNull { it.updatedAtMillis }
        ?.let { active ->
            UltraShareCore.Progress(
                active.fileName,
                active.transferredBytes,
                active.totalBytes,
                aggregateActivePeerBytesPerSecond(transfers, receiving = true),
                true
            )
        }

/** A stalled socket must not leave its last non-zero throughput on screen indefinitely. */
internal fun zeroStalePeerTransferSpeeds(
    transfers: List<UltraSharePeerTransfer>,
    nowMillis: Long,
    staleAfterMillis: Long = 1_500L
): List<UltraSharePeerTransfer> {
    require(staleAfterMillis >= 0L)
    return transfers.map { transfer ->
        val isActive = transfer.status == "يستقبل الآن" || transfer.status.startsWith("يرسل الآن")
        val age = (nowMillis - transfer.updatedAtMillis).coerceAtLeast(0L)
        if (isActive && transfer.bytesPerSecond > 0L && age >= staleAfterMillis) {
            transfer.copy(bytesPerSecond = 0L)
        } else transfer
    }
}

/** The global send indicator follows the most recently updated active outbound peer. */
internal fun latestActivePeerSendProgress(
    transfers: List<UltraSharePeerTransfer>
): UltraShareCore.Progress? =
    transfers.asSequence()
        .filter { it.status.startsWith("يرسل الآن") }
        .maxByOrNull { it.updatedAtMillis }
        ?.let { active ->
            UltraShareCore.Progress(
                active.fileName,
                active.transferredBytes,
                active.totalBytes,
                aggregateActivePeerBytesPerSecond(transfers, receiving = false),
                false
            )
        }

/**
 * Owns transfer sockets and coroutine jobs independently from the navigation/ViewModel lifecycle.
 * The service remains active when the Ultra Share screen is removed from the back stack.
 */
class UltraShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var receiveJob: Job? = null
    @Volatile private var batchReceiveJob: Job? = null
    @Volatile private var activeBatchTransferPort: Int = 0
    @Volatile private var activeControlPort: Int = UltraShareControlChannel.DEFAULT_PORT
    @Volatile private var addressMonitorJob: Job? = null
    private val notificationProgressGuard = Any()
    @Volatile private var lastProgressNotificationAtMs: Long = 0L
    @Volatile private var pauseRequested = false
    private val pendingReplacementCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var advertisementJob: Job? = null
    @Volatile private var controlJob: Job? = null
    @Volatile private var pendingConnectionDecision: CompletableDeferred<UltraShareControlChannel.Decision>? = null
    private val hotspotController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UltraShareHotspotController(this, serviceScope)
    }
    private val notificationHelper by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UltraShareNotificationHelper(this, CHANNEL_ID, NOTIFICATION_ID, ACTION_CANCEL)
    }
    private val transferLocks by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { UltraShareTransferLocks(this) }
    @Volatile private var idleDisconnectJob: Job? = null
    // Mesh room state is process-local and is destroyed with the foreground service.
    private val meshRoomGuard = Any()
    @Volatile private var meshRoomCoordinator: UltraShareMeshRoomCoordinator? = null
    private val meshServerJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val meshServerHandshakeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val meshClientJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val meshClientHandshakeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val meshClientJoinedHosts = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val meshClientJoinMutex = kotlinx.coroutines.sync.Mutex()
    private val meshRoomHandshakeMutex = kotlinx.coroutines.sync.Mutex()
    private val meshDirectServerGuard = Any()
    @Volatile private var meshDirectServerJob: Job? = null
    @Volatile private var meshDirectServerReady: CompletableDeferred<Unit>? = null
    private val meshDirectDialJobs = java.util.concurrent.ConcurrentHashMap<UltraShareMeshTopology.Edge, Job>()
    private val meshDirectEdges = java.util.concurrent.ConcurrentHashMap<String, UltraShareMeshTopology.Edge>()
    /** Primary authority-side MPC2 sessions whose peers are present in the room roster. */
    private val meshAuthorityMemberHosts = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )


    override fun onCreate() {
        super.onCreate()
        // Clear approvals written by older app versions; no peer trust survives a new service.
        UltraShareTrustedPeerStore.clear(this)
        UltraShareDiagnostics.initialize(this)
        UltraShareDiagnostics.event("SERVICE", "CREATED")
        notificationHelper.ensureChannel()
        UltraShareScreenMirrorService.authorizedViewersProvider = {
            currentAuthorizedMirrorViewerIds()
        }
        UltraShareScreenMirrorService.onControlBroadcastRequired = { wireBytes ->
            val targets = (meshServerJobs.keys + meshAuthorityMemberHosts).distinct()
            serviceScope.launch {
                targets.forEach { viewerHost ->
                    if (UltraShareControlChannel.supportsServerApplicationFrames(viewerHost)) {
                        runCatching {
                            UltraShareControlChannel.sendServerApplicationMessage(viewerHost, wireBytes)
                        }
                    }
                }
            }
        }
        // Refresh only stale speed values; no state emission occurs while rates are current.
        serviceScope.launch {
            while (true) {
                delay(500L)
                updateState { current ->
                    val refreshed = zeroStalePeerTransferSpeeds(current.peerTransfers, (System.nanoTime() / 1_000_000L))
                    if (refreshed == current.peerTransfers) current else {
                        val receive = latestActivePeerReceiveProgress(refreshed)
                        val send = latestActivePeerSendProgress(refreshed)
                        val sendProgress = send ?: current.sendProgress?.copy(bytesPerSecond = 0L)
                        current.copy(
                            peerTransfers = refreshed,
                            receiveProgress = receive,
                            sendProgress = sendProgress,
                            progress = when {
                                current.receiving && receive != null -> receive
                                current.receiving && current.sending -> sendProgress ?: current.progress
                                current.receiving -> current.receiveProgress?.copy(bytesPerSecond = 0L)
                                current.sending -> sendProgress ?: current.progress?.copy(bytesPerSecond = 0L)
                                else -> current.progress
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedAction = intent?.action
        if (requestedAction in setOf(
                ACTION_RECEIVE, ACTION_SEND, ACTION_SEND_BATCH, ACTION_SEND_MULTI_BATCH,
                ACTION_RETRY_FILE, ACTION_START_HOTSPOT, ACTION_PEER_CONNECTED,
                ACTION_MESH_SESSION_READY
            )
        ) startForegroundCompat()
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (currentState().sending && currentState().queueTotal > 0 && sendJobsByPeer.isNotEmpty()) {
                    pauseRequested = true
                    updateState { current -> current.copy(status = "جارٍ إيقاف طابور الإرسال مؤقتاً...") }
                    sendJobsByPeer.values.toSet().forEach { it.cancel() }
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_WIFI_DIRECT_ADDRESS -> {
                val address = intent.getStringExtra(EXTRA_WIFI_DIRECT_LOCAL_ADDRESS)
                    ?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
                updateState { current -> current.copy(wifiDirectLocalAddress = address) }
            }
            ACTION_REFRESH_ADDRESSES -> {
                if (currentState().receiving) {
                    serviceScope.launch(Dispatchers.IO) {
                        val addresses = findLocalIpv4Addresses()
                        updateState { current -> current.copy(localAddresses = addresses) }
                    }
                }
            }
            ACTION_CANCEL_SEND -> {
                sendJobsByPeer.values.toSet().forEach { it.cancel() }
                return START_NOT_STICKY
            }
            ACTION_STOP_RECEIVING -> {
                idleDisconnectJob?.cancel()
                idleDisconnectJob = null
                UltraShareScreenMirrorService.stopMirroring(this)
                receiveJob?.cancel()
                batchReceiveJob?.cancel()
                advertisementJob?.cancel()
                controlJob?.cancel()
                pendingConnectionDecision?.cancel()
                val hostsToDisconnect = (currentState().activePeers.map { it.host } +
                    approvedSendTargets().map { it.host }).distinct()
                serviceScope.launch(Dispatchers.IO) {
                    closeMeshRoom()
                    hostsToDisconnect.forEach(::disconnectApprovedPeer)
                    withContext(Dispatchers.Main.immediate) { finishService() }
                }
                stopLocalHotspot()
                updateState { current -> current.copy(
                    receiving = false,
                    pairingSecret = "",
                    localAddresses = emptyList(),
                    wifiDirectLocalAddress = null,
                    controlChannelReady = false,
                    dataChannelReady = false,
                    controlChannelError = null,
                    pendingConnection = null,
                    activePeers = emptyList()
                ) }
                return START_NOT_STICKY
            }
            ACTION_ACCEPT_CONNECTION -> {
                pendingConnectionDecision?.complete(UltraShareControlChannel.Decision.ACCEPTED)
                return START_NOT_STICKY
            }
            ACTION_REJECT_CONNECTION -> {
                pendingConnectionDecision?.complete(UltraShareControlChannel.Decision.REJECTED)
                return START_NOT_STICKY
            }
            ACTION_START_HOTSPOT -> {
                startForegroundCompat()
                startLocalHotspot()
                return START_NOT_STICKY
            }
            ACTION_STOP_HOTSPOT -> {
                stopLocalHotspot()
                return START_NOT_STICKY
            }
            ACTION_STOP_IF_IDLE -> {
                if (sendJobsByPeer.isEmpty() && receiveJob == null && batchReceiveJob == null &&
                    !currentState().receiving && currentState().activePeers.isEmpty() &&
                    !hotspotController.isActive && !currentState().hotspotStarting
                ) {
                    finishService()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP, ACTION_CANCEL -> {
                idleDisconnectJob?.cancel()
                idleDisconnectJob = null
                UltraShareScreenMirrorService.stopMirroring(this)
                if (sendJobsByPeer.isEmpty() && receiveJob == null && batchReceiveJob == null) startForegroundCompat()
                sendJobsByPeer.values.toSet().forEach { it.cancel() }
                receiveJob?.cancel()
                batchReceiveJob?.cancel()
                advertisementJob?.cancel()
                controlJob?.cancel()
                pendingConnectionDecision?.cancel()
                val hostsToDisconnect = (currentState().activePeers.map { it.host } +
                    approvedSendTargets().map { it.host }).distinct()
                serviceScope.launch(Dispatchers.IO) {
                    hostsToDisconnect.forEach(::disconnectApprovedPeer)
                    withContext(Dispatchers.Main.immediate) { finishService() }
                }
                stopLocalHotspot()
                if (sendJobsByPeer.isEmpty() && receiveJob == null && batchReceiveJob == null) finishService()
                return START_NOT_STICKY
            }
            ACTION_MESH_SESSION_READY -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                if (UltraShareCore.isValidPrivateLanHost(host)) {
                    startForegroundCompat()
                    startMeshClientSession(host)
                }
                return START_NOT_STICKY
            }
            ACTION_PEER_CONNECTED -> {
                scheduleIdleDisconnect()
                return START_NOT_STICKY
            }
            ACTION_RECEIVE -> {
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                val requestedControlPort = intent.getIntExtra(EXTRA_CONTROL_PORT, UltraShareControlChannel.DEFAULT_PORT)
                if (requestedControlPort !in 1024..65535 || !UltraShareCore.isValidPairingSecret(secret)) {
                    updateState { current -> current.copy(
                        error = if (requestedControlPort !in 1024..65535)
                            "منفذ قناة التحكم غير صالح؛ يجب أن يكون بين 1024 و65535."
                        else getString(R.string.ultra_share_transfer_failed)
                    ) }
                    finishService()
                    return START_NOT_STICKY
                }
                activeControlPort = requestedControlPort
                val retiringReceive = receiveJob
                val retiringBatch = batchReceiveJob
                if (retiringReceive?.isCancelled == true || retiringBatch?.isCancelled == true) {
                    pendingReplacementCount.incrementAndGet()
                    serviceScope.launch {
                        retiringReceive?.cancelAndJoin()
                        retiringBatch?.cancelAndJoin()
                        if (receiveJob === retiringReceive) receiveJob = null
                        if (batchReceiveJob === retiringBatch) batchReceiveJob = null
                        withContext(Dispatchers.Main.immediate) {
                            pendingReplacementCount.decrementAndGet()
                            onStartCommand(intent, flags, startId)
                        }
                    }
                    return START_NOT_STICKY
                }
                if (receiveJob != null) return START_NOT_STICKY
                activeBatchTransferPort = 0
                startForegroundCompat()
                updateState { current ->
                    val startsFreshRoom = current.activePeers.isEmpty() &&
                        approvedSendTargets().isEmpty() && sendJobsByPeer.isEmpty()
                    val roomState = if (startsFreshRoom) resetRoomTransferPresentation(current) else current
                    roomState.copy(
                        pairingSecret = secret,
                        localAddresses = emptyList(),
                        receiving = true,
                        controlChannelReady = false,
                        dataChannelReady = false,
                        controlChannelError = null,
                        pendingConnection = null,
                        status = current.activePeers.lastOrNull()?.name?.let { peerName ->
                            "متصل مع $peerName؛ الإرسال والاستقبال متاحان."
                        } ?: getString(R.string.ultra_share_waiting)
                    ) }
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    var addresses = withContext(Dispatchers.IO) { findLocalIpv4Addresses() }
                    updateState { current -> current.copy(localAddresses = addresses) }
                    // Network interfaces can appear after the receiver starts (SoftAP,
                    // WLAN association, or P2P formation). Refresh automatically so the
                    // ViewModel can republish the same room invitation with newly-ready routes.
                    addressMonitorJob?.cancel()
                    addressMonitorJob = launch {
                        var poll = 0
                        while (true) {
                            delay(if (poll < 20) 200L else 1_500L)
                            val latest = withContext(Dispatchers.IO) { findLocalIpv4Addresses() }
                            if (latest != addresses) {
                                addresses = latest
                                updateState { current -> current.copy(localAddresses = latest) }
                                UltraShareDiagnostics.event(
                                    "NETWORK_ROUTING",
                                    "RECEIVER_LOCAL_ADDRESSES_REFRESHED",
                                    mapOf("addressCount" to latest.size, "poll" to poll)
                                )
                            }
                            poll++
                        }
                    }
                    advertisementJob = launch {
                        try {
                            UltraShareDiscovery.advertiseWhileReceiving(
                                context = this@UltraShareForegroundService,
                                transferPortProvider = { activeBatchTransferPort }
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            UltraShareDiagnostics.failure("DISCOVERY_ADVERTISEMENT", failure)
                            // Receiving remains available through the displayed local IP
                            // even if another app/process already occupies the discovery port.
                            updateState { current -> current.copy(
                                status = "الاستقبال يعمل، لكن الاكتشاف التلقائي غير متاح. استخدم عنوان IP الظاهر يدوياً.",
                                error = failure.localizedMessage ?: "تعذر تشغيل الاكتشاف التلقائي للأجهزة."
                            ) }
                        }
                    }
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        updateState { current -> current.copy(receiving = false, pairingSecret = "", localAddresses = emptyList(), progress = if (current.sending) current.sendProgress else null, receiveProgress = null, status = getString(R.string.ultra_share_stopped)) }
                        throw cancelled
                    } catch (failure: Exception) {
                        UltraShareDiagnostics.failure("RECEIVER_LOOP", failure)
                        updateState { current -> current.copy(receiving = false, error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)) }
                    } finally {
                        addressMonitorJob?.cancel()
                        addressMonitorJob = null
                        advertisementJob?.cancel()
                        advertisementJob = null
                        controlJob?.cancel()
                        controlJob = null
                        pendingConnectionDecision?.cancel()
                        receiveJob = null
                        updateState { current -> current.copy(receiving = false, pairingSecret = "", localAddresses = emptyList(), progress = if (current.sending) current.sendProgress else null, receiveProgress = null) }
                        finishService()
                    }
                }
                receiveJob = job
                if (batchReceiveJob == null) {
                    batchReceiveJob = serviceScope.launch {
                        try {
                            UltraShareBatchTransport.serveMany(
                                // QR does not carry a dynamic data port. Keep this endpoint stable
                                // so the sender can verify it after authenticated pairing.
                                port = UltraShareBatchTransport.DEFAULT_PORT,
                                pairingSecret = secret,
                                destinationDirectory = UltraShareCore.defaultReceiveDirectory(this@UltraShareForegroundService),
                                maxPeers = UltraShareBatchTransport.recommendedMaxPeers(
                                    (getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager)
                                        ?.memoryClass ?: UltraShareBatchTransport.DEFAULT_MAX_PEERS * 32
                                ),
                                onPeerStarted = { peer ->
                                    UltraShareDiagnostics.event("DATA_CHANNEL", "V2_PEER_AUTHENTICATED", mapOf("peerHost" to peer))
                                    beginTransfer(isReceiving = true)
                                    updateState { current ->
                                        val item = UltraSharePeerTransfer(
                                            host = peer, fileName = "جارٍ تجهيز الدفعة",
                                            transferredBytes = 0L, totalBytes = 0L, bytesPerSecond = 0L,
                                            status = "جارٍ الاستقبال"
                                        )
                                        current.copy(peerTransfers = current.peerTransfers.filterNot { it.host == peer } + item)
                                    }
                                },
                                onPeerFinished = { peer ->
                                    UltraShareDiagnostics.event("DATA_CHANNEL", "V2_PEER_SESSION_CLOSED", mapOf("peerHost" to peer))
                                    updateState { current ->
                                        val existing = current.peerTransfers.firstOrNull { it.host == peer }
                                        val updatedTransfers = if (existing == null || existing.status == "جارٍ الاستقبال") {
                                            val idle = (existing ?: UltraSharePeerTransfer(peer, "", 0L, 0L, 0L, "اكتملت الجلسة"))
                                                .copy(status = "اكتملت الجلسة", bytesPerSecond = 0L, updatedAtMillis = (System.nanoTime() / 1_000_000L))
                                            current.peerTransfers.filterNot { it.host == peer } + idle
                                        } else current.peerTransfers
                                        val activeProgress = latestActivePeerReceiveProgress(updatedTransfers)
                                        current.copy(
                                            peerTransfers = updatedTransfers,
                                            receiveProgress = activeProgress,
                                            progress = if (current.sending) current.sendProgress else activeProgress
                                        )
                                    }
                                    endTransfer(isReceiving = true)
                                },
                                onProgress = { peer, progress ->
                                    updateState { current ->
                                        val item = UltraSharePeerTransfer(
                                            host = peer,
                                            fileName = progress.fileName,
                                            transferredBytes = progress.transferredBytes,
                                            totalBytes = progress.totalBytes,
                                            bytesPerSecond = progress.bytesPerSecond,
                                            status = "يستقبل الآن"
                                        )
                                        val updatedTransfers = current.peerTransfers.filterNot { it.host == peer } + item
                                        val activeProgress = latestActivePeerReceiveProgress(updatedTransfers)
                                        current.copy(
                                            peerTransfers = updatedTransfers,
                                            receiveProgress = activeProgress,
                                            progress = if (current.sending) current.sendProgress else activeProgress,
                                            status = "استقبال ملفات عبر جلسة V2 من $peer",
                                            error = null
                                        )
                                    }
                                    updateNotification(
                                        currentState().receiveProgress ?: UltraShareCore.Progress(
                                            progress.fileName, progress.transferredBytes, progress.totalBytes,
                                            progress.bytesPerSecond, true
                                        )
                                    )
                                },
                                onBatchFinished = { peer, batch ->
                                    val receiverPublishStartedAtNanos = System.nanoTime()
                                    batch.receiveMetrics?.let { metrics ->
                                        updateState { current -> current.copy(lastReceiveMetrics = metrics) }
                                    }
                                    UltraShareDiagnostics.event(
                                        "TRANSFER", "V2_BATCH_RECEIVED",
                                        mapOf(
                                            "peerHost" to peer,
                                            "files" to batch.files.size,
                                            "completed" to batch.files.count { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED },
                                            "failed" to batch.files.count { it.status != UltraShareBatchProtocol.FileStatus.COMPLETED }
                                        )
                                    )
                                    batch.files.forEach { item ->
                                        val received = item.receivedFile ?: return@forEach
                                        try {
                                            val publishedUri = UltraShareReceivedPublisher.publish(this@UltraShareForegroundService, received)
                                            UltraShareReceivedHistoryStore.record(
                                                context = this@UltraShareForegroundService,
                                                file = received.file,
                                                fileName = received.fileName,
                                                mimeType = received.mimeType,
                                                sizeBytes = received.sizeBytes,
                                                publishedUri = publishedUri?.toString()
                                            )
                                            updateState { current -> current.copy(
                                                receivedFile = received.copy(publishedUri = publishedUri),
                                                status = if (publishedUri != null) {
                                                "استُلم ${received.fileName} وحُفظ في التنزيلات/MalekPlayer"
                                            } else {
                                                "استُلم ${received.fileName} وحُفظ في مجلد MalekPlayer داخل ملفات التطبيق"
                                            },
                                                error = null
                                            ) }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            UltraShareDiagnostics.failure("RECEIVED_FILE_PUBLISH", failure, "peerHost=" + peer + ";file=" + received.fileName)
                                            updateState { current -> current.copy(error = failure.localizedMessage ?: "تعذر حفظ الملف المستلم.") }
                                        }
                                    }
                                    val receiverPublishElapsedMs =
                                        (System.nanoTime() - receiverPublishStartedAtNanos).coerceAtLeast(0L) / 1_000_000L
                                    batch.receiveMetrics?.let { metrics ->
                                        updateState { current ->
                                            current.copy(lastReceiveMetrics = metrics.copy(publishElapsedMs = receiverPublishElapsedMs))
                                        }
                                    }
                                    updateState { current ->
                                        val completed = batch.files.count { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED }
                                        val failed = batch.files.size - completed
                                        val previous = current.peerTransfers.firstOrNull { it.host == peer }
                                        val receivedBytes = batch.files.sumOf { it.transferredBytes.coerceAtLeast(0L) }
                                        val totalBytes = batch.files.sumOf { it.receivedFile?.sizeBytes ?: it.transferredBytes.coerceAtLeast(0L) }
                                        val summary = (previous ?: UltraSharePeerTransfer(peer, "", 0L, 0L, 0L, "اكتملت الدفعة"))
                                            .copy(
                                                fileName = if (failed == 0) "اكتملت الدفعة" else "اكتملت مع وجود ملفات فاشلة",
                                                transferredBytes = receivedBytes,
                                                totalBytes = totalBytes,
                                                bytesPerSecond = 0L,
                                                status = if (failed == 0) "اكتمل • $completed ملفات" else "اكتمل $completed • فشل $failed",
                                                updatedAtMillis = (System.nanoTime() / 1_000_000L)
                                            )
                                        val updatedTransfers = current.peerTransfers.filterNot { it.host == peer } + summary
                                        val activeProgress = latestActivePeerReceiveProgress(updatedTransfers)
                                        current.copy(
                                            peerTransfers = updatedTransfers,
                                            receiveProgress = activeProgress,
                                            progress = if (current.sending) current.sendProgress else activeProgress,
                                            status = "اكتملت دفعة V2 من $peer: $completed ملف"
                                        )
                                    }
                                },
                                onPeerError = { peer, failure ->
                                    UltraShareDiagnostics.failure("BATCH_RECEIVE_PEER", failure, "peerHost=" + peer)
                                    updateState { current ->
                                        val previous = current.peerTransfers.firstOrNull { it.host == peer }
                                        val failed = (previous ?: UltraSharePeerTransfer(peer, "", 0L, 0L, 0L, "فشل النقل"))
                                            .copy(status = "فشل النقل", bytesPerSecond = 0L, updatedAtMillis = (System.nanoTime() / 1_000_000L))
                                        val updatedTransfers = current.peerTransfers.filterNot { it.host == peer } + failed
                                        val activeProgress = latestActivePeerReceiveProgress(updatedTransfers)
                                        current.copy(
                                            peerTransfers = updatedTransfers,
                                            receiveProgress = activeProgress,
                                            progress = if (current.sending) current.sendProgress else activeProgress,
                                            error = "جلسة V2 من $peer: ${failure.localizedMessage ?: "فشل النقل"}"
                                        )
                                    }
                                },
                                onListening = { actualPort ->
                                    activeBatchTransferPort = actualPort
                                    val ready = UltraSharePairingReadiness.isDataListenerReady(
                                        actualPort,
                                        UltraShareBatchTransport.DEFAULT_PORT
                                    )
                                    UltraShareDiagnostics.event(
                                        "DATA_CHANNEL",
                                        if (ready) "V2_LISTENER_READY" else "V2_LISTENER_WRONG_PORT",
                                        mapOf("actualPort" to actualPort, "expectedPort" to UltraShareBatchTransport.DEFAULT_PORT)
                                    )
                                    updateState { current -> current.copy(
                                        dataChannelReady = ready,
                                        error = if (ready) current.error else
                                            "منفذ نقل الملفات V2 غير متوقع؛ سيظل الاقتران عبر QR متاحاً، لكن نقل الوسائط لن يبدأ حتى إصلاح المنفذ."
                                    ) }
                                },
                                pairingSecretResolver = { peerHost ->
                                    val remoteId = canonicalMeshPeerId(
                                        UltraShareControlChannel.authenticatedPeerIdForHost(peerHost)
                                    )
                                    val localId = canonicalMeshPeerId(
                                        UltraShareDiscovery.localPeerId(this@UltraShareForegroundService)
                                    )
                                    val coordinator = meshRoomCoordinator
                                    if (remoteId != null && meshDirectEdges.containsKey(remoteId)) {
                                        checkNotNull(coordinator) { "غرفة Mesh غير متاحة أثناء نقل مباشر." }
                                            .deriveDirectLinkCredential(
                                                checkNotNull(localId) { "هوية الجهاز المحلية غير متاحة." },
                                                remoteId
                                            )
                                    } else {
                                        secret
                                    }
                                }
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            UltraShareDiagnostics.failure("BATCH_RECEIVER_SERVER", failure, "port=" + UltraShareBatchTransport.DEFAULT_PORT)
                            updateState { current -> current.copy(error = "تعذر تشغيل مستقبل V2: ${failure.localizedMessage}") }
                        } finally {
                            activeBatchTransferPort = 0
                            batchReceiveJob = null
                            updateState { current -> current.copy(dataChannelReady = false) }
                            finishService()
                        }
                    }
                }
                controlJob?.cancel()
                controlJob = serviceScope.launch {
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        try {
                            UltraShareControlChannel.serveOnce(
                                pairingSecret = secret,
                                port = activeControlPort,
                                localPeerName = Build.MODEL?.take(96)?.ifBlank { "MalekPlayer Android" } ?: "MalekPlayer Android",
                                localPeerId = UltraShareDiscovery.localPeerId(this@UltraShareForegroundService),
                                onListening = { actualPort ->
                                    val ready = actualPort == activeControlPort
                                    UltraShareDiagnostics.event(
                                        "CONTROL_CHANNEL",
                                        if (ready) "LISTENING" else "UNEXPECTED_LISTENING_PORT",
                                        mapOf("port" to actualPort, "expectedPort" to activeControlPort)
                                    )
                                    if (ready) {
                                        updateState { current -> current.copy(
                                            controlChannelReady = true,
                                            controlChannelError = null
                                        ) }
                                    }
                                },
                                onSessionClosed = { request ->
                                    stopMeshSession(request.peerAddress)
                                    disconnectApprovedPeer(request.peerAddress)
                                    scheduleIdleDisconnect()
                                },
                            ) { request ->
                                // The receiver explicitly started a temporary invitation and
                                // the peer has now completed the pairing-secret handshake.
                                // Pairing is the single consent step: accept this paired peer
                                // immediately and do not interrupt transfers with per-peer or
                                // per-file approval prompts.
                                UltraShareDiagnostics.event(
                                    "CONTROL_CHANNEL",
                                    "PAIRED_PEER_AUTO_ACCEPTED",
                                    mapOf("peerHost" to request.peerAddress, "peerName" to request.peerName, "peerIdPresent" to (request.peerId != null))
                                )
                                val authenticatedPeerId = request.peerId ?: request.requestId
                                registerApprovedPeer(
                                    name = request.peerName,
                                    host = request.peerAddress,
                                    role = "جلسة مقترنة مؤقتة",
                                    sessionId = authenticatedPeerId
                                )
                                registerApprovedSendTarget(
                                    UltraShareApprovedSendTarget(
                                        sessionId = authenticatedPeerId,
                                        host = request.peerAddress,
                                        secret = secret,
                                        peerId = request.peerId
                                    )
                                )
                                // A paired session no longer needs public discovery.
                                // Keep the authenticated control/data listeners alive.
                                advertisementJob?.cancel()
                                advertisementJob = null
                                updateState { current -> current.copy(
                                    pendingConnection = null,
                                    status = "تم اقتران " + request.peerName + "؛ الإرسال والاستقبال متاحان مباشرة.",
                                    error = null
                                ) }
                                scheduleIdleDisconnect()
                                UltraShareControlChannel.Decision.ACCEPTED
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            UltraShareDiagnostics.failure(
                                "CONTROL_CHANNEL_SERVER",
                                failure,
                                "port=" + activeControlPort
                            )
                            updateState { current -> current.copy(
                                controlChannelReady = false,
                                controlChannelError = failure.localizedMessage ?: "تعذر فتح منفذ الاتصال الآمن 38744.",
                                error = failure.localizedMessage ?: "تعذر تشغيل قناة طلبات الاتصال."
                            ) }
                            kotlinx.coroutines.delay(1_000)
                        }
                    }
                }
                // A waiting room stays open until a peer actually joins. The five-minute
                // idle window starts only after an authenticated peer session exists.
                job.start()
            }
            ACTION_SEND_MULTI_BATCH -> {
                val uris = intent.parcelableUriList(EXTRA_URIS).orEmpty()
                val targets = decodeApprovedSendTargets(intent.getStringExtra(EXTRA_PEER_TARGETS).orEmpty())
                if (targets.isEmpty()) {
                    updateState { current -> current.copy(error = "لا توجد أجهزة مقترنة صالحة للإرسال الجماعي.") }
                    finishService()
                } else {
                    startSendingQueueToPeers(uris, targets, intent.getBooleanExtra(EXTRA_PRESERVE_DELIVERIES, false))
                }
            }
            ACTION_SEND -> {
                // Keep the single-item entry point, but route it through the V2 batch
                // engine exactly like every other send path. There is no legacy V1 sender.
                val uri = intent.parcelableUri<Uri>(EXTRA_URI)
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                if (uri == null) {
                    updateState { current -> current.copy(error = getString(R.string.ultra_share_transfer_failed)) }
                    finishService()
                } else {
                    startSendingQueue(listOf(uri), host, secret)
                }
            }
            ACTION_SEND_BATCH -> {
                val uris = intent.parcelableUriList(EXTRA_URIS).orEmpty()
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                val candidates = intent.getStringExtra(EXTRA_ROUTE_OFFER)?.let(UltraShareNegotiation::decode)
                    ?.candidates.orEmpty()
                startSendingQueue(uris, host, secret, candidates = candidates)
            }
            ACTION_RETRY_FILE -> {
                val uri = intent.parcelableUri<Uri>(EXTRA_URI)
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                if (uri == null) {
                    updateState { current -> current.copy(error = getString(R.string.ultra_share_transfer_failed)) }
                    finishService()
                } else {
                    startSendingQueue(listOf(uri), host, secret, replaceQueue = false)
                }
            }
            else -> {
                // A plain start keeps the service alive only long enough to establish its notification.
                startForegroundCompat()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15 enforces a time limit for dataSync foreground services.
     * Stop all work immediately when the system reports that the limit was reached.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android may terminate a dataSync foreground service while a peer is paired.
        // Close both client and server control sockets before
        // clearing state; otherwise the independent heartbeat coroutine can outlive us.
        val timeoutPeers = (
            currentState().activePeers.map { it.host } +
                approvedSendTargets().map { it.host }
            ).distinct()
        timeoutPeers.forEach { host ->
            disconnectApprovedPeer(host)
        }
        updateState { current -> current.copy(
                receiving = false,
                sending = false,
                pairingSecret = "",
                localAddresses = emptyList(),
                controlChannelReady = false,
                dataChannelReady = false,
                progress = null,
                sendProgress = null,
                receiveProgress = null,
                hotspotStarting = false,
                hotspotActive = false,
                hotspotSsid = null,
                hotspotPassword = null,
                pendingConnection = null,
                activePeers = emptyList(),
                error = "أوقف Android خدمة النقل بعد بلوغ الحد الزمني المسموح. يمكنك بدء النقل مجدداً."
            ) }
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        sendJobsByPeer.values.toSet().forEach { it.cancel() }
        sendJobsByPeer.clear()
        removePersistedLegacyWifiNetwork()
        updateState { current -> current.copy(wifiDirectLocalAddress = null) }
        receiveJob?.cancel()
        batchReceiveJob?.cancel()
        advertisementJob?.cancel()
        controlJob?.cancel()
        pendingConnectionDecision?.cancel()
        UltraShareControlChannel.setClientSessionReadyListener(null)
        closeMeshRoom()
        releaseTransferLocks()
        activeBatchTransferPort = 0
        hotspotController.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    @Suppress("DEPRECATION")
    private fun removePersistedLegacyWifiNetwork() {
        val preferences = getSharedPreferences("ultra_share_wifi", MODE_PRIVATE)
        val networkId = preferences.getInt("legacy_network_id", -1)
        if (networkId < 0) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifi == null) return
        runCatching {
            runCatching { wifi.disableNetwork(networkId) }
            val removed = wifi.removeNetwork(networkId)
            val profileStillExists = runCatching {
                try {
                    wifi.configuredNetworks?.any { it.networkId == networkId }
                } catch (_: SecurityException) {
                    // Android may revoke location access while the service is stopping.
                    // Unknown profile state must retain the preference for a later retry.
                    null
                }
            }.getOrNull()
            if (removed || profileStillExists == false) {
                runCatching { wifi.saveConfiguration() }
                preferences.edit().remove("legacy_network_id").apply()
            } else {
                UltraShareDiagnostics.event(
                    "WIFI_NETWORK",
                    "LEGACY_NETWORK_PROFILE_RETAINED_FOR_RETRY",
                    mapOf("networkId" to networkId)
                )
            }
        }.onFailure { UltraShareDiagnostics.failure("SERVICE_LEGACY_WIFI_CLEANUP", it) }
    }

    override fun onDestroy() {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        UltraShareScreenMirrorService.stopMirroring(this)
        UltraShareScreenMirrorService.authorizedViewersProvider = { emptySet() }
        UltraShareScreenMirrorService.onControlBroadcastRequired = null
        // Service destruction is a hard session boundary, regardless of whether this
        // device was the sender or receiver. Close both socket directions and wipe
        // session-scoped keys so no heartbeat or stale peer remains in process memory.
        val destroyPeers = (
            currentState().activePeers.map { it.host } +
                approvedSendTargets().map { it.host }
            ).distinct()
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {
            destroyPeers.forEach(::disconnectApprovedPeer)
            closeMeshRoom()
            cleanupScope.cancel()
        }
        sendJobsByPeer.values.toSet().forEach { it.cancel() }
        sendJobsByPeer.clear()
        removePersistedLegacyWifiNetwork()
        receiveJob?.cancel()
        batchReceiveJob?.cancel()
        advertisementJob?.cancel()
        controlJob?.cancel()
        pendingConnectionDecision?.cancel()
        UltraShareControlChannel.setClientSessionReadyListener(null)
        hotspotController.stop()
        serviceScope.cancel()
        releaseTransferLocks()
        updateState { current ->
            current.copy(
                receiving = false,
                sending = false,
                pairingSecret = "",
                localAddresses = emptyList(),
                controlChannelReady = false,
                dataChannelReady = false,
                progress = null,
                sendProgress = null,
                receiveProgress = null,
                hotspotStarting = false,
                hotspotActive = false,
                hotspotSsid = null,
                hotspotPassword = null,
                pendingConnection = null,
                activePeers = emptyList()
            )
        }
        notificationHelper.cancel()
        UltraShareDiagnostics.event("SERVICE", "DESTROYED")
        UltraShareDiagnostics.endSession("service_destroyed")
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startLocalHotspot() {
        if (hotspotController.isActive || hotspotController.isStarting || currentState().hotspotStarting) return
        updateState { it.copy(hotspotStarting = true, hotspotFailed = false, error = null, status = "جارٍ إنشاء شبكة MalekPlayer المحلية...") }
        hotspotController.start(
            onStarted = { ssid, password, addresses ->
                updateState { current ->
                    current.copy(
                        hotspotStarting = false,
                        hotspotActive = true,
                        hotspotFailed = false,
                        hotspotSsid = ssid.takeIf { it.isNotBlank() },
                        hotspotPassword = password,
                        localAddresses = addresses,
                        status = if (ssid.isNotBlank()) "تم إنشاء شبكة MalekPlayer المحلية. صِل الجهاز الآخر بها ثم ابحث عن الأجهزة القريبة."
                        else "تم إنشاء نقطة الاتصال، لكن تعذر قراءة بياناتها. افتح إعدادات Wi-Fi لعرضها.",
                        error = null
                    )
                }
            },
            onReservationStopped = {
                updateState { current ->
                    current.copy(
                        hotspotStarting = false,
                        hotspotActive = false,
                        hotspotFailed = false,
                        hotspotSsid = null,
                        hotspotPassword = null,
                        status = "أوقِفت نقطة اتصال MalekPlayer."
                    )
                }
                finishService()
            },
            onFailure = { message ->
                val liveLan = UltraShareDeviceNetworkInfo.findLocalIpv4Addresses(this)
                updateState {
                    it.copy(
                        hotspotStarting = false,
                        hotspotActive = false,
                        hotspotFailed = true,
                        hotspotSsid = null,
                        hotspotPassword = null,
                        localAddresses = liveLan.ifEmpty { it.localAddresses },
                        error = message
                    )
                }
                finishService()
            }
        )
    }

    private fun stopLocalHotspot() {
        hotspotController.stop()
        updateState { it.copy(hotspotStarting = false, hotspotActive = false, hotspotFailed = false, hotspotSsid = null, hotspotPassword = null) }
        finishService()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(progress: UltraShareCore.Progress) {
        val now = System.nanoTime() / 1_000_000L
        val completed = progress.totalBytes > 0L && progress.transferredBytes >= progress.totalBytes
        val shouldNotify = synchronized(notificationProgressGuard) {
            if (!completed && now - lastProgressNotificationAtMs < 350L) {
                false
            } else {
                lastProgressNotificationAtMs = now
                true
            }
        }
        if (!shouldNotify) return
        val current = currentState()
        val visibleProgress = when {
            current.sending && current.receiving -> null
            current.sending -> current.sendProgress ?: progress.takeUnless { it.receiving }
            current.receiving -> current.receiveProgress ?: progress.takeIf { it.receiving }
            else -> progress
        }
        notificationHelper.notifyProgress(current, visibleProgress)
    }

    private fun buildNotification(progress: UltraShareCore.Progress?): Notification =
        notificationHelper.build(currentState(), progress)

    private fun finishService() {
        if (pendingReplacementCount.get() > 0) return
        // A completed file/queue keeps the temporary room available briefly. An idle timer
        // closes all peer sessions and the room after five minutes without active transfer.
        // A failed/closed data listener must never stop the independent MPC2 control
        // listener while the receiver is still waiting for a peer to pair.
        val receiverStillWaiting = currentState().receiving || controlJob?.isActive == true
        // Outbound approvals are separate from inbound activePeers. Keep their
        // authenticated sessions alive after pause/completion so the sender can
        // resume the remaining queue without pairing again.
        val senderStillPaired = approvedSendTargets().isNotEmpty() ||
            currentState().approvedSenderPeerHosts.isNotEmpty()
        if (sendJobsByPeer.isNotEmpty() || receiveJob != null || batchReceiveJob != null ||
            hotspotController.isActive || currentState().hotspotStarting ||
            receiverStillWaiting || senderStillPaired
        ) {
            if (receiverStillWaiting || senderStillPaired) startForegroundCompat()
            return
        }

        releaseTransferLocks()
        if (currentState().activePeers.isNotEmpty()) {
            updateState { current ->
                current.copy(
                    sending = sendJobsByPeer.isNotEmpty(),
                    progress = if (current.receiving) current.receiveProgress else null,
                    sendProgress = null,
                    status = "اكتمل النقل؛ الاتصال مع الجهاز الآخر ما زال نشطاً وجاهزاً."
                )
            }
            // Keep the ongoing foreground notification so Android does not tear down
            // the idle-but-paired service and its temporary session.
            startForegroundCompat()
            return
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Keep a room available only briefly after the last transfer. The timer is cancelled
     * while any send/receive is active, so large files are never cut off by the idle policy.
     */
    private fun scheduleIdleDisconnect() {
        idleDisconnectJob?.cancel()
        val busy = transferLocks.hasActiveTransfers() ||
            sendJobsByPeer.isNotEmpty() || currentState().sending
        if (busy || (!currentState().receiving && currentState().activePeers.isEmpty() && approvedSendTargets().isEmpty())) {
            idleDisconnectJob = null
            return
        }
        idleDisconnectJob = serviceScope.launch {
            kotlinx.coroutines.delay(SESSION_IDLE_TIMEOUT_MS)
            val transferStillActive = transferLocks.hasActiveTransfers() || sendJobsByPeer.isNotEmpty() || currentState().sending
            if (transferStillActive) {
                // The transfer's finally/endTransfer path will schedule a fresh idle window.
                idleDisconnectJob = null
                return@launch
            }
            closeTemporarySessionAfterIdle()
        }
    }

    private fun closeTemporarySessionAfterIdle() {
        idleDisconnectJob = null
        UltraShareDiagnostics.event("SESSION", "IDLE_TIMEOUT_DISCONNECT")
        (currentState().activePeers.map { it.host } + approvedSendTargets().map { it.host })
            .distinct()
            .forEach(::disconnectApprovedPeer)
        pendingConnectionDecision?.cancel()
        pendingConnectionDecision = null
        advertisementJob?.cancel()
        advertisementJob = null
        controlJob?.cancel()
        controlJob = null
        receiveJob?.cancel()
        batchReceiveJob?.cancel()
        stopLocalHotspot()
        closeMeshRoom()
        updateState { current -> current.copy(
            receiving = false,
            sending = false,
            pairingSecret = "",
            localAddresses = emptyList(),
            wifiDirectLocalAddress = null,
            controlChannelReady = false,
            dataChannelReady = false,
            controlChannelError = null,
            pendingConnection = null,
            activePeers = emptyList(),
            reconnectablePeers = emptyList(),
            approvedSenderPeerHosts = emptyList(),
            reconnectableSenderPeerHosts = emptyList(),
            peerTransfers = emptyList(),
            status = "أُغلقت الغرفة تلقائياً بعد خمس دقائق من عدم النشاط."
        ) }
        finishService()
    }


    private fun canonicalMeshPeerId(raw: String?): String? {
        val value = raw?.replace("-", "")?.lowercase(java.util.Locale.ROOT) ?: return null
        return value.takeIf { it.length == 32 && it.all { ch -> ch.digitToIntOrNull(16) != null } }
    }

    private fun meshPeer(
        peerId: String,
        displayName: String,
        addresses: List<String>,
        port: Int
    ): UltraShareMeshTopology.Peer {
        val candidates = addresses.distinct().mapNotNull { host ->
            if (!UltraShareCore.isValidPrivateLanHost(host)) return@mapNotNull null
            UltraShareNegotiation.Candidate(
                transport = if (UltraShareNetworkRouting.isWifiDirectGroupHost(host)) {
                    UltraShareNegotiation.Transport.WIFI_DIRECT
                } else UltraShareNegotiation.Transport.WLAN,
                host = host,
                port = port,
                connected = true,
                reachable = true,
                priority = 100
            )
        }.take(UltraShareMeshRoster.MAX_CANDIDATES_PER_PEER)
        return UltraShareMeshTopology.Peer(
            peerId = peerId,
            displayName = displayName.take(96).ifBlank { "MalekPlayer Android" },
            candidates = candidates
        )
    }

    private fun getOrCreateMeshAuthority(): UltraShareMeshRoomCoordinator {
        val localId = canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this))
            ?: error("تعذر الحصول على هوية الجهاز المحلية لإنشاء غرفة Mesh.")
        synchronized(meshRoomGuard) {
            val current = meshRoomCoordinator
            if (current != null) {
                check(current.state().isAuthority) {
                    "هذا الجهاز عضو في غرفة Mesh أخرى وليس سلطة إنشاء الغرفة."
                }
                return current
            }
            val local = meshPeer(
                localId,
                Build.MODEL?.take(96) ?: "MalekPlayer Android",
                findLocalIpv4Addresses(),
                UltraShareBatchTransport.DEFAULT_PORT
            )
            return UltraShareMeshRoomCoordinator(localId).also { coordinator ->
                coordinator.createAuthorityRoom(local, System.currentTimeMillis().coerceAtLeast(1L))
                meshRoomCoordinator = coordinator
            }
        }
    }

    private fun getOrCreateMeshJoiner(): UltraShareMeshRoomCoordinator {
        val localId = canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this))
            ?: error("تعذر الحصول على هوية الجهاز المحلية للانضمام إلى غرفة Mesh.")
        synchronized(meshRoomGuard) {
            val current = meshRoomCoordinator
            if (current != null) return current
            return UltraShareMeshRoomCoordinator(localId).also { meshRoomCoordinator = it }
        }
    }

    /** Runs after MPC2 ACCEPT has been flushed; one job owns reads for this server session. */
    private fun startMeshServerSession(request: UltraShareControlChannel.Request) {
        val peerId = canonicalMeshPeerId(request.peerId)
        val host = request.peerAddress
        if (peerId == null) {
            UltraShareDiagnostics.event(
                "MESH_SERVER_SESSION", "SKIPPED_INVALID_PEER_ID",
                mapOf("peerHost" to host, "peerIdPresent" to !request.peerId.isNullOrBlank())
            )
            return
        }
        if (!request.applicationFramesEnabled ||
            !UltraShareControlChannel.supportsServerApplicationFrames(host)
        ) {
            UltraShareDiagnostics.event(
                "MESH_SERVER_SESSION", "SKIPPED_APPLICATION_FRAMES_UNAVAILABLE",
                mapOf("peerHost" to host, "capabilityAdvertised" to request.applicationFramesEnabled)
            )
            return
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var roomLockAcquired = false
            var roomJoined = false
            try {
                UltraShareDiagnostics.event(
                    "MESH_SERVER_SESSION", "HANDSHAKE_QUEUED",
                    mapOf("peerHost" to host, "peerId" to peerId)
                )
                meshRoomHandshakeMutex.lock()
                roomLockAcquired = true
                UltraShareDiagnostics.event(
                    "MESH_SERVER_SESSION", "HANDSHAKE_LOCK_ACQUIRED",
                    mapOf("peerHost" to host)
                )
                val coordinator = getOrCreateMeshAuthority()
                val remote = meshPeer(
                    peerId, request.peerName, listOf(host),
                    UltraShareBatchTransport.DEFAULT_PORT
                )

                // Send the room key immediately after the authenticated MPC2 session is ready.
                // Starting the optional direct-link listener here used to consume the same
                // 12-second window in which the client was waiting for KEY_OFFER.
                val keyOffer = coordinator.createKeyOffer()
                val keyOfferWire = UltraShareMeshControlProtocol.encode(keyOffer)
                try {
                    UltraShareDiagnostics.event(
                        "MESH_ROOM", "KEY_OFFER_SEND_STARTED",
                        mapOf("peerHost" to host, "revision" to keyOffer.revision, "bytes" to keyOfferWire.size)
                    )
                    UltraShareControlChannel.sendServerApplicationMessage(host, keyOfferWire)
                    UltraShareDiagnostics.event(
                        "MESH_ROOM", "KEY_OFFER_SENT",
                        mapOf("peerHost" to host, "revision" to keyOffer.revision)
                    )
                    val keyAckBytes = withTimeoutOrNull(30_000L) {
                        UltraShareControlChannel.receiveServerApplicationMessage(host)
                    } ?: error("انتهت مهلة تأكيد مفتاح غرفة Mesh.")
                    try {
                        UltraShareDiagnostics.event(
                            "MESH_ROOM", "KEY_ACK_RECEIVED",
                            mapOf("peerHost" to host, "bytes" to keyAckBytes.size)
                        )
                        val keyAck = UltraShareMeshControlProtocol.decode(keyAckBytes)
                            ?: error("رسالة تأكيد مفتاح الغرفة غير صالحة.")
                        check(coordinator.verifyAcknowledgement(
                            peerId, keyAck, UltraShareMeshControlProtocol.Type.KEY_ACK, keyOffer.payload
                        )) { "لم يتم التحقق من تأكيد مفتاح الغرفة من الجهاز المقترن." }
                    } finally {
                        keyAckBytes.fill(0)
                    }
                } finally {
                    keyOfferWire.fill(0)
                    keyOffer.payload.fill(0)
                }

                coordinator.upsertMember(peerId, remote)
                meshAuthorityMemberHosts.add(host)
                val rosterUpdate = coordinator.createRosterUpdate()
                val rosterWire = UltraShareMeshControlProtocol.encode(rosterUpdate)
                try {
                    UltraShareControlChannel.sendServerApplicationMessage(host, rosterWire)
                    UltraShareDiagnostics.event(
                        "MESH_ROOM", "ROSTER_UPDATE_SENT",
                        mapOf("peerHost" to host, "revision" to rosterUpdate.revision, "bytes" to rosterWire.size)
                    )
                    val rosterAckBytes = withTimeoutOrNull(30_000L) {
                        UltraShareControlChannel.receiveServerApplicationMessage(host)
                    } ?: error("انتهت مهلة تأكيد قائمة أعضاء الغرفة.")
                    try {
                        val rosterAck = UltraShareMeshControlProtocol.decode(rosterAckBytes)
                            ?: error("رسالة تأكيد قائمة الأعضاء غير صالحة.")
                        check(coordinator.verifyAcknowledgement(
                            peerId, rosterAck, UltraShareMeshControlProtocol.Type.ROSTER_ACK,
                            rosterUpdate.payload
                        )) { "قائمة أعضاء الغرفة لم تحصل على تأكيد موثوق." }
                    } finally {
                        rosterAckBytes.fill(0)
                    }
                } finally {
                    rosterWire.fill(0)
                    rosterUpdate.payload.fill(0)
                }

                UltraShareDiagnostics.event(
                    "MESH_ROOM", "PEER_JOINED",
                    mapOf("peerId" to peerId, "peerHost" to host, "revision" to coordinator.state().revision)
                )
                updateState { current -> current.copy(
                    status = "انضم " + request.peerName + " إلى غرفة Mesh الآمنة.",
                    error = null
                ) }

                // Push the new authenticated roster to previously approved clients.
                val latestRoster = coordinator.createRosterUpdate()
                try {
                    broadcastMeshRosterUpdate(latestRoster, excludedHost = host)
                } finally {
                    latestRoster.payload.fill(0)
                }
                meshServerHandshakeJobs.remove(host)
                startMeshServerMessagePump(host)
                // If Host is already sharing their screen, invite the newly joined viewer immediately.
                val localHostId = canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this@UltraShareForegroundService))
                val mirrorOffer = localHostId?.let { UltraShareScreenMirrorService.currentOfferPayloadForHost(it) }
                if (mirrorOffer != null) {
                    runCatching {
                        UltraShareControlChannel.sendServerApplicationMessage(host, mirrorOffer)
                    }
                }
                roomJoined = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("MESH_SERVER_SESSION", failure, "peerHost=" + host)
                if (meshAuthorityMemberHosts.remove(host)) {
                    val coordinator = meshRoomCoordinator
                    val removal = runCatching { coordinator?.removeMember(peerId) }.getOrNull()
                    if (removal != null) broadcastMeshRosterUpdate(removal, excludedHost = host)
                }
                updateState { current -> current.copy(
                    status = "اتصال MPC2 نشط، لكن مزامنة غرفة Mesh لم تكتمل.",
                    error = failure.localizedMessage ?: "تعذر مزامنة غرفة Mesh."
                ) }
            } finally {
                meshServerHandshakeJobs.remove(host)
                if (roomLockAcquired) meshRoomHandshakeMutex.unlock()
            }
            // Do not hold the room-wide handshake mutex while an optional listener binds.
            if (roomJoined) {
                try {
                    ensureMeshDirectServerReady()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    UltraShareDiagnostics.failure("MESH_DIRECT_LISTENER_OPTIONAL", failure)
                }
                try {
                    reconcileMeshDirectLinks()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    UltraShareDiagnostics.failure("MESH_DIRECT_RECONCILE_AFTER_JOIN", failure)
                }
            }
        }
        val previous = meshServerHandshakeJobs.putIfAbsent(host, job)
        if (previous != null) job.cancel() else job.start()
    }

    private suspend fun broadcastMeshRosterUpdate(
        update: UltraShareMeshControlProtocol.Message,
        excludedHost: String? = null
    ) {
        val wire = UltraShareMeshControlProtocol.encode(update)
        val existingHosts = meshServerJobs.keys.filter { it != excludedHost }
        existingHosts.forEach { existingHost ->
            runCatching {
                UltraShareControlChannel.sendServerApplicationMessage(existingHost, wire)
            }.onFailure { failure ->
                UltraShareDiagnostics.failure(
                    "MESH_ROSTER_BROADCAST", failure, "peerHost=" + existingHost
                )
            }
        }
    }

    private fun currentAuthorizedMirrorViewerIds(): Set<String> {
        val result = linkedSetOf<String>()
        meshAuthorityMemberHosts.forEach { memberHost ->
            canonicalMeshPeerId(UltraShareControlChannel.serverSessionIdentity(memberHost))?.let(result::add)
        }
        currentState().activePeers.forEach { peer ->
            canonicalMeshPeerId(UltraShareControlChannel.serverSessionIdentity(peer.host))?.let(result::add)
        }
        meshRoomCoordinator?.state()?.members?.forEach { member ->
            canonicalMeshPeerId(member.peerId)?.let(result::add)
        }
        canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this))?.let { result.remove(it) }
        return result
    }

    private fun startMeshServerMessagePump(host: String) {
        val pump = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (UltraShareControlChannel.supportsServerApplicationFrames(host)) {
                    val bytes = UltraShareControlChannel.receiveServerApplicationMessage(host) ?: break
                    val authenticatedPeerId = canonicalMeshPeerId(
                        UltraShareControlChannel.serverSessionIdentity(host)
                    ) ?: break
                    if (UltraShareScreenMirrorProtocol.isMirrorControlPayload(bytes)) {
                        val mirrorMsg = runCatching { UltraShareScreenMirrorProtocol.decodeControl(bytes) }.getOrNull()
                        if (mirrorMsg != null &&
                            canonicalMeshPeerId(mirrorMsg.senderPeerId) == authenticatedPeerId &&
                            mirrorMsg.type == UltraShareScreenMirrorProtocol.ControlType.MIRROR_VIEWER_STATE
                        ) {
                            when (mirrorMsg.viewerAction) {
                                UltraShareScreenMirrorProtocol.ViewerAction.REQUEST_IDR ->
                                    UltraShareScreenMirrorService.requestKeyframeFromViewer(authenticatedPeerId)
                                UltraShareScreenMirrorProtocol.ViewerAction.LEFT ->
                                    UltraShareScreenMirrorService.disconnectViewer(authenticatedPeerId)
                                UltraShareScreenMirrorProtocol.ViewerAction.JOINED, null -> Unit
                            }
                        }
                        continue
                    }
                    val message = UltraShareMeshControlProtocol.decode(bytes) ?: continue
                    if (canonicalMeshPeerId(message.senderPeerId) != authenticatedPeerId) {
                        UltraShareDiagnostics.event(
                            "MESH_ROOM", "IGNORED_UNBOUND_SERVER_MESSAGE",
                            mapOf("peerHost" to host, "messageType" to message.type.name)
                        )
                        continue
                    }
                    if (message.type == UltraShareMeshControlProtocol.Type.ROSTER_ACK) {
                        val coordinator = meshRoomCoordinator
                        val expected = runCatching { coordinator?.createRosterUpdate() }.getOrNull()
                        val verified = expected != null && coordinator?.verifyAcknowledgement(
                            authenticatedPeerId,
                            message,
                            UltraShareMeshControlProtocol.Type.ROSTER_ACK,
                            expected.payload
                        ) == true
                        expected?.payload?.fill(0)
                        UltraShareDiagnostics.event(
                            "MESH_ROOM",
                            if (verified) "ROSTER_ACK_VERIFIED" else "ROSTER_ACK_REJECTED",
                            mapOf("peerHost" to host, "revision" to message.revision)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("MESH_SERVER_MESSAGE_PUMP", failure, "peerHost=" + host)
            }
        }
        val previous = meshServerJobs.putIfAbsent(host, pump)
        if (previous != null) pump.cancel() else pump.start()
    }


    /** Start room negotiation immediately after the client-side MPC2 ACCEPT response. */
    private fun startMeshClientSession(host: String) {
        if (!UltraShareControlChannel.supportsClientApplicationFrames(host)) return
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureMeshClientJoined(host)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("MESH_CLIENT_HANDSHAKE", failure, "peerHost=" + host)
                updateState { current -> current.copy(
                    status = "قناة الاتصال آمنة، لكن تعذّر إكمال الانضمام إلى غرفة Mesh.",
                    error = failure.localizedMessage ?: "تعذر الانضمام إلى غرفة Mesh."
                ) }
            } finally {
                meshClientHandshakeJobs.remove(host)
            }
        }
        val previous = meshClientHandshakeJobs.putIfAbsent(host, job)
        if (previous != null) job.cancel() else job.start()
    }

    /** Join the authority room on an already-approved client MPC2 session. */
    private suspend fun ensureMeshClientJoined(host: String) {
        if (host in meshClientJoinedHosts ||
            !UltraShareControlChannel.supportsClientApplicationFrames(host)
        ) return
        var roomJoined = false
        meshClientJoinMutex.lock()
        try {
            if (host in meshClientJoinedHosts ||
                !UltraShareControlChannel.supportsClientApplicationFrames(host)
            ) return
            UltraShareDiagnostics.event(
                "MESH_CLIENT_HANDSHAKE", "STARTED",
                mapOf("peerHost" to host)
            )
            val authorityId = canonicalMeshPeerId(UltraShareControlChannel.sessionPeerId(host))
                ?: error("هوية سلطة غرفة Mesh غير متاحة في جلسة MPC2.")
            val coordinator = getOrCreateMeshJoiner()
            val keyOfferBytes = withTimeoutOrNull(30_000L) {
                UltraShareControlChannel.receiveClientApplicationMessage(host)
            } ?: error("لم يصل مفتاح غرفة Mesh من الجهاز المضيف.")
            val keyOffer = try {
                UltraShareMeshControlProtocol.decode(keyOfferBytes)
                    ?: error("رسالة مفتاح غرفة Mesh غير صالحة.")
            } finally {
                keyOfferBytes.fill(0)
            }
            UltraShareDiagnostics.event(
                "MESH_CLIENT_HANDSHAKE", "KEY_OFFER_RECEIVED",
                mapOf(
                    "peerHost" to host,
                    "senderPeerId" to keyOffer.senderPeerId,
                    "revision" to keyOffer.revision,
                    "payloadBytes" to keyOffer.payload.size
                )
            )
            try {
                check(keyOffer.type == UltraShareMeshControlProtocol.Type.KEY_OFFER &&
                    canonicalMeshPeerId(keyOffer.senderPeerId) == authorityId
                ) { "هوية مرسل مفتاح غرفة Mesh لا تطابق هوية MPC2." }
                val keyAck = coordinator.acceptKeyOffer(authorityId, keyOffer)
                val keyAckWire = UltraShareMeshControlProtocol.encode(keyAck)
                try {
                    UltraShareControlChannel.sendClientApplicationMessage(host, keyAckWire)
                    UltraShareDiagnostics.event(
                        "MESH_CLIENT_HANDSHAKE", "KEY_ACK_SENT",
                        mapOf("peerHost" to host, "revision" to keyAck.revision)
                    )
                } finally {
                    keyAckWire.fill(0)
                    keyAck.payload.fill(0)
                }
            } finally {
                keyOffer.payload.fill(0)
            }

            val rosterBytes = withTimeoutOrNull(30_000L) {
                UltraShareControlChannel.receiveClientApplicationMessage(host)
            } ?: error("لم تصل قائمة أعضاء غرفة Mesh.")
            val rosterUpdate = try {
                UltraShareMeshControlProtocol.decode(rosterBytes)
                    ?: error("رسالة قائمة أعضاء غرفة Mesh غير صالحة.")
            } finally {
                rosterBytes.fill(0)
            }
            try {
                val rosterAck = coordinator.acceptRosterUpdate(authorityId, rosterUpdate)
                val rosterAckWire = UltraShareMeshControlProtocol.encode(rosterAck)
                try {
                    // ACK first: the authority must not wait for an optional listener to start.
                    UltraShareControlChannel.sendClientApplicationMessage(host, rosterAckWire)
                    UltraShareDiagnostics.event(
                        "MESH_CLIENT_HANDSHAKE", "ROSTER_ACK_SENT",
                        mapOf("peerHost" to host, "revision" to rosterAck.revision)
                    )
                } finally {
                    rosterAckWire.fill(0)
                    rosterAck.payload.fill(0)
                }
            } finally {
                rosterUpdate.payload.fill(0)
            }

            meshClientJoinedHosts.add(host)
            UltraShareDiagnostics.event(
                "MESH_ROOM", "JOINED_AUTHORITY_ROOM",
                mapOf("authorityPeerId" to authorityId, "revision" to coordinator.state().revision)
            )
            startMeshClientMessagePump(host, authorityId, coordinator)
            roomJoined = true
        } finally {
            meshClientJoinMutex.unlock()
        }
        // Listener binding and topology dialing must not block other authenticated sessions.
        if (roomJoined) {
            try {
                ensureMeshDirectServerReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("MESH_DIRECT_LISTENER_OPTIONAL", failure)
            }
            try {
                reconcileMeshDirectLinks()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("MESH_DIRECT_RECONCILE_AFTER_JOIN", failure)
            }
        }
    }

    private fun startMeshClientMessagePump(
        host: String,
        authorityId: String,
        coordinator: UltraShareMeshRoomCoordinator
    ) {
        val pump = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (UltraShareControlChannel.supportsClientApplicationFrames(host)) {
                    val bytes = UltraShareControlChannel.receiveClientApplicationMessage(host) ?: break
                    if (UltraShareScreenMirrorProtocol.isMirrorControlPayload(bytes)) {
                        val mirrorMsg = runCatching { UltraShareScreenMirrorProtocol.decodeControl(bytes) }.getOrNull()
                        if (mirrorMsg != null && canonicalMeshPeerId(mirrorMsg.senderPeerId) == authorityId) {
                            when (mirrorMsg.type) {
                                UltraShareScreenMirrorProtocol.ControlType.MIRROR_START,
                                UltraShareScreenMirrorProtocol.ControlType.MIRROR_UPDATE -> {
                                    val key = mirrorMsg.streamKey
                                    val cfg = mirrorMsg.config
                                    val port = mirrorMsg.streamPort
                                    if (key != null && cfg != null && port in 1024..65535) {
                                        val hostDisplayName = currentState().activePeers
                                            .firstOrNull { it.host == host }?.name ?: "منشئ الغرفة"
                                        updateState { current ->
                                            current.copy(
                                                incomingMirrorOffer = IncomingMirrorOffer(
                                                    sessionId = mirrorMsg.sessionId,
                                                    hostAddress = host,
                                                    hostPeerId = authorityId,
                                                    hostName = hostDisplayName,
                                                    streamPort = port,
                                                    streamKey = key.copyOf(),
                                                    width = cfg.width,
                                                    height = cfg.height,
                                                    fps = cfg.fps,
                                                    audioEnabled = cfg.audioEnabled,
                                                    revision = mirrorMsg.revision
                                                )
                                            )
                                        }
                                    }
                                }
                                UltraShareScreenMirrorProtocol.ControlType.MIRROR_STOP -> {
                                    updateState { current ->
                                        val existing = current.incomingMirrorOffer
                                        if (existing != null &&
                                            (existing.sessionId == mirrorMsg.sessionId || existing.hostAddress == host)
                                        ) {
                                            current.copy(incomingMirrorOffer = null)
                                        } else current
                                    }
                                }
                                UltraShareScreenMirrorProtocol.ControlType.MIRROR_VIEWER_STATE -> Unit
                            }
                        }
                        continue
                    }
                    val message = UltraShareMeshControlProtocol.decode(bytes) ?: continue
                    if (message.type == UltraShareMeshControlProtocol.Type.ROSTER_UPDATE) {
                        val ack = coordinator.acceptRosterUpdate(authorityId, message)
                        UltraShareControlChannel.sendClientApplicationMessage(
                            host, UltraShareMeshControlProtocol.encode(ack)
                        )
                        reconcileMeshDirectLinks()
                        UltraShareDiagnostics.event(
                            "MESH_ROOM", "ROSTER_UPDATED",
                            mapOf("authorityPeerId" to authorityId, "revision" to coordinator.state().revision)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("MESH_CLIENT_MESSAGE_PUMP", failure, "peerHost=" + host)
            } finally {
                meshClientJoinedHosts.remove(host)
                meshClientJobs.remove(host)
            }
        }
        val previous = meshClientJobs.putIfAbsent(host, pump)
        if (previous != null) pump.cancel() else pump.start()
    }

    private fun stopMeshSession(host: String) {
        val remoteId = canonicalMeshPeerId(
            UltraShareControlChannel.serverSessionIdentity(host)
                ?: UltraShareControlChannel.sessionPeerId(host)
                ?: currentState().activePeers.firstOrNull { it.host == host }?.sessionId
                ?: approvedSendTarget(host)?.sessionId
        )
        val wasAuthorityMember = meshAuthorityMemberHosts.remove(host)
        if (remoteId != null) meshDirectEdges.remove(remoteId)
        meshServerJobs.remove(host)?.cancel()
        meshServerHandshakeJobs.remove(host)?.cancel()
        meshClientJobs.remove(host)?.cancel()
        meshClientHandshakeJobs.remove(host)?.cancel()
        meshClientJoinedHosts.remove(host)
        if (wasAuthorityMember && remoteId != null) {
            serviceScope.launch {
                meshRoomHandshakeMutex.lock()
                try {
                    val coordinator = meshRoomCoordinator ?: return@launch
                    if (!coordinator.state().isAuthority) return@launch
                    // A member may reconnect through a new IP while the old socket
                    // is closing. Do not remove its stable identity if another
                    // authenticated primary session for that same peer is already live.
                    val replacementSessionExists = (meshAuthorityMemberHosts + meshServerHandshakeJobs.keys)
                        .any { candidateHost ->
                            candidateHost != host &&
                                UltraShareControlChannel.isServerSessionActive(candidateHost) &&
                                canonicalMeshPeerId(
                                    UltraShareControlChannel.serverSessionIdentity(candidateHost)
                                ) == remoteId
                        }
                    if (replacementSessionExists) {
                        UltraShareDiagnostics.event(
                            "MESH_ROOM", "STALE_ROUTE_CLOSED_MEMBER_RETAINED",
                            mapOf("peerId" to remoteId, "oldHost" to host)
                        )
                        return@launch
                    }
                    val update = coordinator.removeMember(remoteId)
                    if (update != null) broadcastMeshRosterUpdate(update)
                    reconcileMeshDirectLinks()
                    UltraShareScreenMirrorService.pruneUnauthorizedViewers()
                    UltraShareDiagnostics.event(
                        "MESH_ROOM", "MEMBER_REMOVED",
                        mapOf("peerId" to remoteId, "revision" to coordinator.state().revision)
                    )
                } catch (failure: Exception) {
                    UltraShareDiagnostics.failure(
                        "MESH_MEMBER_REMOVAL", failure, "peerId=" + remoteId
                    )
                } finally {
                    meshRoomHandshakeMutex.unlock()
                }
            }
        }
        updateState { current ->
            if (current.incomingMirrorOffer?.hostAddress == host) {
                current.copy(incomingMirrorOffer = null)
            } else current
        }
        UltraShareScreenMirrorService.pruneUnauthorizedViewers()
    }

    private fun closeMeshRoom() {
        UltraShareScreenMirrorService.stopMirroring(this)
        updateState { current -> current.copy(incomingMirrorOffer = null) }
        synchronized(meshDirectServerGuard) {
            meshDirectServerJob?.cancel()
            meshDirectServerJob = null
            meshDirectServerReady?.cancel()
            meshDirectServerReady = null
        }
        meshDirectDialJobs.values.forEach { it.cancel() }
        meshDirectDialJobs.clear()
        meshDirectEdges.clear()
        meshAuthorityMemberHosts.clear()
        meshServerJobs.values.forEach { it.cancel() }
        meshServerJobs.clear()
        meshServerHandshakeJobs.values.forEach { it.cancel() }
        meshServerHandshakeJobs.clear()
        meshClientJobs.values.forEach { it.cancel() }
        meshClientJobs.clear()
        meshClientHandshakeJobs.values.forEach { it.cancel() }
        meshClientHandshakeJobs.clear()
        meshClientJoinedHosts.clear()
        synchronized(meshRoomGuard) {
            meshRoomCoordinator?.close()
            meshRoomCoordinator = null
        }
    }


    /**
     * Every room member hosts a separate MPC2 listener for pair-specific direct links.
     * The resolver authenticates only identities already present in the HMAC-verified roster.
     */
    private suspend fun ensureMeshDirectServerReady() {
        val ready = synchronized(meshDirectServerGuard) {
            val existing = meshDirectServerJob
            val existingReady = meshDirectServerReady
            if (existing != null && existing.isActive && existingReady != null) {
                existingReady
            } else {
                val deferred = CompletableDeferred<Unit>()
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        UltraShareControlChannel.serveOnce(
                            pairingSecret = null,
                            port = MESH_DIRECT_CONTROL_PORT,
                            localPeerName = Build.MODEL?.take(96)?.ifBlank { "MalekPlayer Android" }
                                ?: "MalekPlayer Android",
                            localPeerId = UltraShareDiscovery.localPeerId(this@UltraShareForegroundService),
                            onListening = { actualPort ->
                                check(actualPort == MESH_DIRECT_CONTROL_PORT)
                                deferred.complete(Unit)
                                UltraShareDiagnostics.event(
                                    "MESH_LINK", "DIRECT_CONTROL_LISTENER_READY",
                                    mapOf("port" to actualPort)
                                )
                            },
                            pairingSecretResolver = { remotePeerId ->
                                val coordinator = meshRoomCoordinator
                                val localId = canonicalMeshPeerId(
                                    UltraShareDiscovery.localPeerId(this@UltraShareForegroundService)
                                )
                                val remoteId = canonicalMeshPeerId(remotePeerId)
                                if (coordinator == null || localId == null || remoteId == null) {
                                    null
                                } else {
                                    runCatching {
                                        coordinator.deriveDirectLinkCredential(localId, remoteId)
                                    }.getOrNull()
                                }
                            },
                            onRequest = { request ->
                                val localId = canonicalMeshPeerId(
                                    UltraShareDiscovery.localPeerId(this@UltraShareForegroundService)
                                )
                                val remoteId = canonicalMeshPeerId(request.peerId)
                                val coordinator = meshRoomCoordinator
                                if (!request.applicationFramesEnabled ||
                                    localId == null || remoteId == null || coordinator == null ||
                                    coordinator.state().members.none { it.peerId == remoteId }
                                ) {
                                    UltraShareControlChannel.Decision.REJECTED
                                } else {
                                    val secret = coordinator.deriveDirectLinkCredential(localId, remoteId)
                                    registerApprovedPeer(
                                        name = request.peerName,
                                        host = request.peerAddress,
                                        role = "اتصال مباشر موثق داخل غرفة Mesh",
                                        sessionId = remoteId
                                    )
                                    registerApprovedSendTarget(
                                        UltraShareApprovedSendTarget(
                                            sessionId = remoteId,
                                            host = request.peerAddress,
                                            secret = secret,
                                            candidates = coordinator.state().members
                                                .first { it.peerId == remoteId }.candidates,
                                            peerId = remoteId
                                        )
                                    )
                                    // Mark the authenticated edge before ACCEPT is flushed so a
                                    // peer that starts V2 immediately selects this pair's key.
                                    meshDirectEdges[remoteId] = UltraShareMeshTopology.Edge.of(localId, remoteId)
                                    UltraShareControlChannel.Decision.ACCEPTED
                                }
                            },
                            onSessionReady = { request ->
                                val localId = canonicalMeshPeerId(
                                    UltraShareDiscovery.localPeerId(this@UltraShareForegroundService)
                                )
                                val remoteId = canonicalMeshPeerId(request.peerId)
                                if (localId != null && remoteId != null) {
                                    meshDirectEdges[remoteId] = UltraShareMeshTopology.Edge.of(localId, remoteId)
                                    UltraShareDiagnostics.event(
                                        "MESH_LINK", "DIRECT_LINK_ACCEPTED",
                                        mapOf("peerId" to remoteId, "peerHost" to request.peerAddress)
                                    )
                                }
                            },
                            onSessionClosed = { request ->
                                val remoteId = canonicalMeshPeerId(request.peerId)
                                if (remoteId != null) meshDirectEdges.remove(remoteId)
                                stopMeshSession(request.peerAddress)
                                disconnectApprovedPeer(request.peerAddress)
                                UltraShareDiagnostics.event(
                                    "MESH_LINK", "DIRECT_LINK_CLOSED",
                                    mapOf("peerId" to (remoteId ?: "unknown"), "peerHost" to request.peerAddress)
                                )
                            }
                        )
                    } catch (cancelled: CancellationException) {
                        if (!deferred.isCompleted) deferred.cancel(cancelled)
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!deferred.isCompleted) deferred.completeExceptionally(failure)
                        UltraShareDiagnostics.failure("MESH_DIRECT_SERVER", failure)
                    }
                }
                meshDirectServerReady = deferred
                meshDirectServerJob = job
                job.start()
                deferred
            }
        }
        val completed = withTimeoutOrNull(12_000L) { ready.await(); true } ?: false
        check(completed) { "انتهت مهلة تشغيل مستمع الاتصالات المباشرة لغرفة Mesh." }
    }

    /** Reconcile the authenticated roster into direct MPC2 edges; V2 carries the media bytes. */
    private suspend fun reconcileMeshDirectLinks() {
        val coordinator = meshRoomCoordinator ?: return
        val localId = canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this)) ?: return
        val snapshot = runCatching { coordinator.state() }.getOrNull() ?: return
        val activeMemberIds = snapshot.members.map { it.peerId }.toSet()
        // A roster revision can revoke a previously approved member. Retire every
        // stale direct edge and its approved send target before planning new links.
        val removedDirectPeers = meshDirectEdges.keys.filterNot { it in activeMemberIds }
        removedDirectPeers.forEach { removedPeerId ->
            meshDirectEdges.remove(removedPeerId)
            meshDirectDialJobs.entries
                .filter { it.key.firstPeerId == removedPeerId || it.key.secondPeerId == removedPeerId }
                .forEach { entry ->
                    if (meshDirectDialJobs.remove(entry.key, entry.value)) entry.value.cancel()
                }
            approvedSendTargets()
                .filter { it.peerId == removedPeerId }
                .forEach { target -> disconnectApprovedPeer(target.host) }
        }
        if (snapshot.members.size < 2) return
        runCatching { ensureMeshDirectServerReady() }.onFailure { failure ->
            UltraShareDiagnostics.failure("MESH_DIRECT_RECONCILE_LISTENER", failure)
            return
        }
        val localPeer = snapshot.members.firstOrNull { it.peerId == localId } ?: return
        val activeEdges = linkedSetOf<UltraShareMeshTopology.Edge>()
        UltraShareControlChannel.activeServerApplicationSessionHosts().forEach { host ->
            canonicalMeshPeerId(UltraShareControlChannel.serverSessionIdentity(host))?.let { remoteId ->
                if (remoteId != localId) activeEdges += UltraShareMeshTopology.Edge.of(localId, remoteId)
            }
        }
        UltraShareControlChannel.activeClientApplicationSessionHosts().forEach { host ->
            canonicalMeshPeerId(UltraShareControlChannel.sessionPeerId(host))?.let { remoteId ->
                if (remoteId != localId) activeEdges += UltraShareMeshTopology.Edge.of(localId, remoteId)
            }
        }
        activeEdges += meshDirectEdges.values
        val plan = try {
            UltraShareMeshTopology.plan(
                localPeer = localPeer,
                discoveredPeers = snapshot.members.filterNot { it.peerId == localId },
                activeEdges = activeEdges
            )
        } catch (failure: Exception) {
            UltraShareDiagnostics.failure("MESH_TOPOLOGY_PLAN", failure, "localPeerId=" + localId)
            return
        }
        UltraShareDiagnostics.event(
            "MESH_LINK", "TOPOLOGY_RECONCILED",
            mapOf(
                "participants" to plan.participants.size,
                "requiredEdges" to plan.requiredEdges.size,
                "activeEdges" to plan.activeEdges.size,
                "missingEdges" to plan.missingEdges.size,
                "outgoingEdges" to plan.outgoingLinks.size
            )
        )
        plan.outgoingLinks.forEach { link ->
            if (meshDirectEdges.containsKey(link.remotePeer.peerId)) return@forEach
            val existingJob = meshDirectDialJobs[link.edge]
            if (existingJob?.isActive == true) return@forEach
            val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val credential = coordinator.deriveDirectLinkCredential(localId, link.remotePeer.peerId)
                    var connected = false
                    var lastFailure: Exception? = null
                    repeat(MESH_DIRECT_DIAL_ATTEMPTS) { attempt ->
                        if (connected) return@repeat
                        try {
                            UltraShareDiagnostics.event(
                                "MESH_LINK", "DIRECT_DIAL_ATTEMPT",
                                mapOf(
                                    "peerId" to link.remotePeer.peerId,
                                    "host" to link.endpoint.host,
                                    "attempt" to (attempt + 1)
                                )
                            )
                            val decision = UltraShareControlChannel.request(
                                host = link.endpoint.host,
                                pairingSecret = credential,
                                peerName = Build.MODEL?.take(96)?.ifBlank { "MalekPlayer Android" }
                                    ?: "MalekPlayer Android",
                                port = MESH_DIRECT_CONTROL_PORT,
                                network = UltraShareNetworkRouting.findNetworkForHost(
                                    this@UltraShareForegroundService, link.endpoint.host
                                ),
                                peerId = localId,
                                expectedPeerId = link.remotePeer.peerId,
                                notifyClientSessionReady = false,
                                onSessionClosed = { closedHost ->
                                    meshDirectEdges.remove(link.remotePeer.peerId)
                                    stopMeshSession(closedHost)
                                    disconnectApprovedPeer(closedHost)
                                }
                            )
                            check(decision == UltraShareControlChannel.Decision.ACCEPTED) {
                                "رفض الجهاز المقابل اتصال Mesh المباشر."
                            }
                            val authenticatedRemote = canonicalMeshPeerId(
                                UltraShareControlChannel.sessionPeerId(link.endpoint.host)
                            )
                            if (authenticatedRemote != link.remotePeer.peerId) {
                                UltraShareControlChannel.closeSession(link.endpoint.host)
                                error("هوية الطرف الذي قبل الاتصال لا تطابق هوية عضو الغرفة.")
                            }
                            registerApprovedPeer(
                                name = UltraShareControlChannel.sessionPeerName(link.endpoint.host)
                                    ?: link.remotePeer.displayName,
                                host = link.endpoint.host,
                                role = "اتصال مباشر موثق داخل غرفة Mesh",
                                routeCandidates = link.remotePeer.candidates,
                                sessionId = link.remotePeer.peerId
                            )
                            registerApprovedSendTarget(
                                UltraShareApprovedSendTarget(
                                    sessionId = link.remotePeer.peerId,
                                    host = link.endpoint.host,
                                    secret = credential,
                                    candidates = link.remotePeer.candidates,
                                    peerId = link.remotePeer.peerId
                                )
                            )
                            meshDirectEdges[link.remotePeer.peerId] = link.edge
                            connected = true
                            UltraShareDiagnostics.event(
                                "MESH_LINK", "DIRECT_LINK_ESTABLISHED",
                                mapOf("peerId" to link.remotePeer.peerId, "host" to link.endpoint.host)
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            lastFailure = failure
                            if (attempt + 1 < MESH_DIRECT_DIAL_ATTEMPTS) {
                                kotlinx.coroutines.delay((attempt + 1) * 700L)
                            }
                        }
                    }
                    if (!connected) {
                        UltraShareDiagnostics.failure(
                            "MESH_DIRECT_DIAL",
                            lastFailure ?: IOException("تعذر إنشاء اتصال Mesh مباشر."),
                            "peerId=" + link.remotePeer.peerId
                        )
                    }
                } finally {
                    meshDirectDialJobs.remove(link.edge)
                }
            }
            val previous = meshDirectDialJobs.putIfAbsent(link.edge, job)
            if (previous != null) job.cancel() else job.start()
        }
    }

    private fun beginTransfer(isReceiving: Boolean) {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        transferLocks.beginTransfer(isReceiving)
    }

    private fun endTransfer(isReceiving: Boolean) {
        if (transferLocks.endTransfer(isReceiving) && sendJobsByPeer.isEmpty()) scheduleIdleDisconnect()
    }

    private fun releaseTransferLocks() {
        transferLocks.release()
    }

    private fun clearRoomPresentationIfUnpaired() {
        if (currentState().activePeers.isEmpty() &&
            approvedSendTargets().isEmpty() && sendJobsByPeer.isEmpty()
        ) {
            updateState { current ->
                if (current.activePeers.isEmpty() &&
                    approvedSendTargets().isEmpty() && sendJobsByPeer.isEmpty()
                ) resetRoomTransferPresentation(current) else current
            }
        }
    }

    private fun updateState(transform: (UltraShareServiceState) -> UltraShareServiceState) {
        mutableState.update { current ->
            val next = transform(current)
            next.copy(
                sending = sendJobsByPeer.isNotEmpty(),
                roomPeers = next.activePeers,
                roomConnected = next.activePeers.isNotEmpty()
            )
        }
    }

    private fun publish(state: UltraShareServiceState) {
        mutableState.value = state.copy(
            roomPeers = state.activePeers,
            roomConnected = state.activePeers.isNotEmpty()
        )
    }

    private fun currentState(): UltraShareServiceState = mutableState.value

    private fun startSendingQueue(
        uris: List<Uri>,
        host: String,
        secret: String,
        replaceQueue: Boolean = true,
        candidates: List<UltraShareNegotiation.Candidate> = emptyList()
    ) {
        if (uris.isEmpty() || host.isBlank() || !UltraShareCore.isValidPrivateLanHost(host) || !UltraShareCore.isValidPairingSecret(secret)) {
            updateState { current -> current.copy(error = getString(R.string.ultra_share_transfer_failed)) }
            finishService()
            return
        }
        val sendKey = approvedSendTarget(host)?.sessionId ?: host
        val retiringSend = sendJobsByPeer[sendKey]
        if (retiringSend != null && (retiringSend.isCancelled || retiringSend.isCompleted)) {
            pendingReplacementCount.incrementAndGet()
            serviceScope.launch {
                retiringSend.cancelAndJoin()
                sendJobsByPeer.remove(sendKey, retiringSend)
                withContext(Dispatchers.Main.immediate) {
                    pendingReplacementCount.decrementAndGet()
                    startSendingQueue(uris, host, secret, replaceQueue, candidates)
                }
            }
            return
        }
        if (sendJobsByPeer.containsKey(sendKey)) return
        startForegroundCompat()
        lateinit var sendJobForPeer: Job
        sendJobForPeer = serviceScope.launch(start = CoroutineStart.LAZY) {
            var sent = 0
            var failed = 0
            var completedBytes = 0L
            var lastError: String? = null
            val queue = uris.distinctBy { it.toString() }
            var metadataByUri: Map<Uri, Triple<String, String, Long>> = emptyMap()
            var totalBytes = 0L
            var v2ProgressFileId: String? = null
            var v2ProgressCompletedBytes = 0L
            var v2ProgressFileSize = 0L
            try {
                // Persist the complete batch before querying document providers. If Android
                // stops the process during slow metadata lookup, the selected files remain
                // visible as pending instead of disappearing from the durable queue.
                if (replaceQueue) {
                    UltraShareQueueStore.enqueue(this@UltraShareForegroundService, queue)
                }
                metadataByUri = queue.mapNotNull { item ->
                    runCatching { item to readDocumentMetadata(item) }.getOrNull()
                }.toMap()
                totalBytes = metadataByUri.values.fold(0L) { total, metadata ->
                    if (Long.MAX_VALUE - total < metadata.third) Long.MAX_VALUE else total + metadata.third
                }
                updateState { current -> current.copy(queueTotalBytes = totalBytes, queueTransferredBytes = 0L) }
                val v2Candidates = ArrayList<Pair<Uri, UltraShareBatchTransport.Source>>()
                queue.forEach { uri ->
                    val status = UltraShareQueueStore.observe(this@UltraShareForegroundService)
                        .first().firstOrNull { it.uri == uri.toString() }?.status
                    if (status == UltraShareQueueStore.STATUS_SKIPPED) return@forEach
                    val metadata = runCatching { metadataByUri[uri] ?: readDocumentMetadata(uri) }.getOrElse { failure ->
                        failed++
                        lastError = failure.localizedMessage ?: "تعذرت قراءة أحد الملفات."
                        UltraShareQueueStore.update(this@UltraShareForegroundService, uri, status = UltraShareQueueStore.STATUS_FAILED, error = lastError)
                        return@forEach
                    }
                    val claimed = UltraShareQueueStore.claimForSending(this@UltraShareForegroundService, uri, metadata.first)
                    if (!claimed) return@forEach
                    // Stable per queue URI so a retry after interruption can address
                    // the same per-file resume checkpoint, including duplicate-content
                    // files selected from different document-provider locations.
                    val fileId = UltraShareBatchTransport.stableFileId(uri, metadata.first, metadata.third)
                    v2Candidates += uri to UltraShareBatchTransport.Source(
                        uri = uri,
                        name = metadata.first,
                        mimeType = metadata.second,
                        sizeBytes = metadata.third,
                        fileId = fileId
                    )
                }
                if (v2Candidates.isNotEmpty()) {
                    try {
                        beginTransfer(isReceiving = false)
                        var activeV2Host = host
                        v2Candidates.chunked(UltraShareBatchProtocol.MAX_FILES).forEach { candidateChunk ->
                            try {
                                val batch = sendBatchWithFileIsolation(
                                    sources = candidateChunk.map { it.second },
                                    host = activeV2Host,
                                    secret = secret,
                                    candidates = candidates,
                                    expectedPeerId = approvedSendTarget(host)?.peerId,
                                    onHostChanged = { activeV2Host = it }
                                ) { progress ->
                                    if (v2ProgressFileId != null && v2ProgressFileId != progress.fileId) {
                                        v2ProgressCompletedBytes = if (Long.MAX_VALUE - v2ProgressCompletedBytes < v2ProgressFileSize)
                                            Long.MAX_VALUE else v2ProgressCompletedBytes + v2ProgressFileSize
                                    }
                                    v2ProgressFileId = progress.fileId
                                    v2ProgressFileSize = progress.totalBytes
                                    val sendProgress = UltraShareCore.Progress(
                                        progress.fileName, progress.transferredBytes, progress.totalBytes,
                                        progress.bytesPerSecond, false
                                    )
                                    updateState { current ->
                                        val peerTransfer = UltraSharePeerTransfer(
                                            host = activeV2Host,
                                            fileName = progress.fileName,
                                            transferredBytes = progress.transferredBytes,
                                            totalBytes = progress.totalBytes,
                                            bytesPerSecond = progress.bytesPerSecond,
                                            status = "يرسل الآن"
                                        )
                                        val updatedTransfers = current.peerTransfers.filterNot { it.host == activeV2Host } + peerTransfer
                                        val aggregateSendProgress = sendProgress.copy(
                                            bytesPerSecond = aggregateActivePeerBytesPerSecond(updatedTransfers, receiving = false)
                                        )
                                        current.copy(
                                            peerTransfers = updatedTransfers,
                                            progress = if (current.receiving) current.receiveProgress else aggregateSendProgress,
                                            sendProgress = aggregateSendProgress,
                                            queueCurrentName = progress.fileName,
                                            queueTransferredBytes = minOf(totalBytes, v2ProgressCompletedBytes + progress.transferredBytes),
                                            status = "إرسال دفعة V2 عبر اتصال بيانات واحد: ${progress.fileName}"
                                        )
                                    }
                                    updateNotification(currentState().sendProgress ?: sendProgress)
                                }
                                val uriById = candidateChunk.associate { it.second.fileId to it.first }
                                updateState { current ->
                                    val completed = batch.files.count { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED }
                                    val failedInChunk = batch.files.size - completed
                                    val previous = current.peerTransfers.firstOrNull { it.host == activeV2Host }
                                    val summary = (previous ?: UltraSharePeerTransfer(activeV2Host, "", 0L, 0L, 0L, "اكتملت الدفعة"))
                                        .copy(
                                            fileName = "نتيجة الدفعة",
                                            transferredBytes = batch.files.sumOf { it.transferredBytes.coerceAtLeast(0L) },
                                            totalBytes = candidateChunk.sumOf { it.second.sizeBytes.coerceAtLeast(0L) },
                                            bytesPerSecond = 0L,
                                            status = if (failedInChunk == 0) "اكتمل • $completed ملفات" else "اكتمل $completed • فشل $failedInChunk",
                                            updatedAtMillis = (System.nanoTime() / 1_000_000L)
                                        )
                                    val updatedTransfers = current.peerTransfers.filterNot { it.host == activeV2Host } + summary
                                    val aggregateSend = latestActivePeerSendProgress(updatedTransfers)
                                        ?: current.sendProgress?.copy(bytesPerSecond = 0L)
                                    current.copy(
                                        peerTransfers = updatedTransfers,
                                        sendProgress = aggregateSend,
                                        progress = if (current.receiving) current.receiveProgress else aggregateSend
                                    )
                                }
                                batch.files.forEachIndexed { index, outcome ->
                                    val uri = uriById[outcome.fileId] ?: return@forEachIndexed
                                    val metadata = metadataByUri[uri] ?: readDocumentMetadata(uri)
                                    if (outcome.status == UltraShareBatchProtocol.FileStatus.COMPLETED) {
                                        sent++
                                        completedBytes = if (Long.MAX_VALUE - completedBytes < metadata.third) Long.MAX_VALUE else completedBytes + metadata.third
                                        UltraShareQueueStore.update(this@UltraShareForegroundService, uri, metadata.first, UltraShareQueueStore.STATUS_COMPLETED)
                                    } else {
                                        failed++
                                        lastError = outcome.error ?: "فشل إرسال ${metadata.first}"
                                        UltraShareQueueStore.update(this@UltraShareForegroundService, uri, metadata.first, UltraShareQueueStore.STATUS_FAILED, lastError)
                                    }
                                    updateState { current -> current.copy(
                                        queueCompleted = sent + failed,
                                        queueTotal = queue.size,
                                        queueTotalBytes = totalBytes,
                                        queueTransferredBytes = completedBytes
                                    ) }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                UltraShareDiagnostics.failure("SEND_V2_BATCH", failure, "host=" + activeV2Host + "; files=" + candidateChunk.size)
                                failed += candidateChunk.size
                                lastError = failure.localizedMessage ?: "فشلت دفعة V2."
                                candidateChunk.forEach { (uri, source) ->
                                    UltraShareQueueStore.update(
                                        this@UltraShareForegroundService, uri, source.name,
                                        UltraShareQueueStore.STATUS_FAILED, lastError
                                    )
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            v2Candidates.forEach { (uri, source) ->
                                UltraShareQueueStore.update(
                                    this@UltraShareForegroundService, uri, source.name,
                                    UltraShareQueueStore.STATUS_PENDING, "انقطع النقل؛ يمكن استئناف الملف من موضعه."
                                )
                            }
                        }
                        throw cancelled
                    } catch (failure: java.net.ConnectException) {
                        UltraShareDiagnostics.failure("SEND_V2_CONNECT", failure, "host=" + host + "; fallback=disabled")
                        failed += v2Candidates.size
                        lastError = failure.localizedMessage ?: "تعذر الاتصال بمستقبل Ultra Share V2."
                        v2Candidates.forEach { (uri, source) ->
                            UltraShareQueueStore.update(
                                this@UltraShareForegroundService, uri, source.name,
                                UltraShareQueueStore.STATUS_FAILED, lastError
                            )
                        }
                    } catch (failure: Exception) {
                        UltraShareDiagnostics.failure("SEND_V2_BATCH", failure, "host=" + host + "; files=" + v2Candidates.size)
                        failed += v2Candidates.size
                        lastError = failure.localizedMessage ?: "فشلت جلسة V2."
                        v2Candidates.forEach { (uri, source) ->
                            UltraShareQueueStore.update(
                                this@UltraShareForegroundService, uri, source.name,
                                UltraShareQueueStore.STATUS_FAILED, lastError
                            )
                        }
                    } finally {
                        endTransfer(isReceiving = false)
                    }
                }
                // V2 is the only supported file data plane. No per-file V1 fallback.
                UltraShareDiagnostics.event("TRANSFER", "SEND_QUEUE_FINISHED", mapOf("sent" to sent, "failed" to failed, "total" to queue.size))
                updateState { current -> current.copy(sending = sendJobsByPeer.isNotEmpty(), status = "انتهى الطابور: أُرسل " + sent + " من " + queue.size + " ملفات", error = if (failed > 0) "تعذر إرسال " + failed + " ملفات. آخر خطأ: " + lastError else null) }
            } catch (cancelled: CancellationException) {
                updateState { current -> current.copy(
                    sending = sendJobsByPeer.isNotEmpty(),
                    progress = if (current.receiving) current.receiveProgress else null,
                    sendProgress = null,
                    queueTransferredBytes = completedBytes,
                    status = if (pauseRequested)
                        "أُوقف الطابور مؤقتاً بعد إكمال " + sent + " ملفات؛ يمكنك استئناف المتبقي."
                    else "أُوقف طابور الإرسال بعد إكمال " + sent + " ملفات"
                ) }
                throw cancelled
            } finally {
                sendJobsByPeer.remove(sendKey, sendJobForPeer)
                clearRoomPresentationIfUnpaired()
                pauseRequested = false
                updateState { current -> current.copy(sending = sendJobsByPeer.isNotEmpty(), progress = if (current.receiving) current.receiveProgress else null, sendProgress = null, queueCurrentName = null) }
                scheduleIdleDisconnect()
                finishService()
            }
        }
        if (!UltraShareSendJobRegistry.reserve(sendJobsByPeer, listOf(sendKey), sendJobForPeer)) return
        updateState { current -> current.copy(queueTotal = uris.size, status = "جارٍ تجهيز " + uris.size + " ملفات للإرسال", error = null) }
        sendJobForPeer.start()
    }
    private fun startSendingQueueToPeers(
        uris: List<Uri>,
        requestedTargets: List<UltraShareApprovedSendTarget>,
        preserveCompleted: Boolean
    ) {
        if (uris.isEmpty() || requestedTargets.isEmpty()) return
        val retiringJobs = synchronized(sendJobsByPeer) {
            requestedTargets.mapNotNull { target ->
                sendJobsByPeer[target.sessionId]?.takeIf { it.isCancelled || it.isCompleted }
            }.distinct()
        }
        if (retiringJobs.isNotEmpty()) {
            pendingReplacementCount.incrementAndGet()
            serviceScope.launch {
                retiringJobs.forEach { it.cancelAndJoin() }
                requestedTargets.forEach { target ->
                    val currentJob = sendJobsByPeer[target.sessionId]
                    if (currentJob != null && retiringJobs.contains(currentJob)) {
                        sendJobsByPeer.remove(target.sessionId, currentJob)
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    pendingReplacementCount.decrementAndGet()
                    startSendingQueueToPeers(uris, requestedTargets, preserveCompleted)
                }
            }
            return
        }
        val targets = synchronized(sendJobsByPeer) {
            requestedTargets.filterNot { sendJobsByPeer.containsKey(it.sessionId) }
        }
        if (targets.isEmpty()) return
        if (targets.any {
                !UltraShareCore.isValidPrivateLanHost(it.host) ||
                    !UltraShareCore.isValidPairingSecret(it.secret)
            }
        ) {
            updateState { current -> current.copy(error = "بيانات أحد الأجهزة المقترنة غير صالحة.") }
            finishService()
            return
        }
        startForegroundCompat()
        lateinit var multiPeerSendJob: Job
        multiPeerSendJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            val queue = uris.distinctBy { it.toString() }
            val metadata = LinkedHashMap<Uri, Triple<String, String, Long>>()
            var totalBytes = 0L
            var transferLockStarted = false
            try {
                UltraShareQueueStore.enqueue(this@UltraShareForegroundService, queue)
                queue.forEach { uri ->
                    try {
                        metadata[uri] = readDocumentMetadata(uri)
                    } catch (failure: Exception) {
                        UltraShareQueueStore.update(
                            this@UltraShareForegroundService,
                            uri,
                            status = UltraShareQueueStore.STATUS_FAILED,
                            error = failure.localizedMessage ?: "تعذرت قراءة الملف."
                        )
                    }
                }
                totalBytes = metadata.values.fold(0L) { total, item ->
                    if (Long.MAX_VALUE - total < item.third) Long.MAX_VALUE else total + item.third
                }
                updateState { current ->
                    current.copy(
                        queueTotalBytes = totalBytes,
                        queueTransferredBytes = 0L,
                        status = "تم تجهيز " + metadata.size + " ملفات لـ " + targets.size + " أجهزة"
                    )
                }
                if (metadata.isEmpty()) {
                    updateState { current -> current.copy(error = "لم يمكن تجهيز أي ملف للإرسال.") }
                    return@launch
                }

                val peerTargets = targets.map {
                    UltraShareMultiRecipientCoordinator.Target(it.sessionId, it.host)
                }
                UltraSharePeerDeliveryStore.prepare(
                    this@UltraShareForegroundService,
                    metadata.map { (uri, details) -> uri to details.first },
                    peerTargets,
                    preserveCompleted = preserveCompleted
                )

                beginTransfer(isReceiving = false)
                transferLockStarted = true
                UltraShareMultiRecipientCoordinator.execute(
                    targets = peerTargets,
                    onStatus = { delivery ->
                        updateState { current ->
                            current.copy(
                                status = when (delivery.status) {
                                    UltraShareMultiRecipientCoordinator.Status.PENDING ->
                                        "بانتظار دور الجهاز " + delivery.target.host
                                    UltraShareMultiRecipientCoordinator.Status.SENDING ->
                                        "جارٍ الإرسال إلى الجهاز " + delivery.target.host
                                    UltraShareMultiRecipientCoordinator.Status.COMPLETED ->
                                        "اكتمل الإرسال إلى الجهاز " + delivery.target.host
                                    UltraShareMultiRecipientCoordinator.Status.FAILED ->
                                        "تعذر الإرسال إلى الجهاز " + delivery.target.host
                                }
                            )
                        }
                    }
                ) { peer ->
                    val auth = targets.first { it.sessionId == peer.sessionId }
                    val allSources = metadata.map { (uri, details) ->
                        UltraShareBatchTransport.Source(
                            uri = uri,
                            name = details.first,
                            mimeType = details.second,
                            sizeBytes = details.third,
                            fileId = UltraShareBatchTransport.stableFileId(uri, details.first, details.third)
                        )
                    }
                    val previousDeliveries = UltraSharePeerDeliveryStore.observe(this@UltraShareForegroundService).first()
                    val completedUris = previousDeliveries.asSequence()
                        .filter {
                            it.peerSessionId == peer.sessionId &&
                                it.status == UltraSharePeerDeliveryStore.STATUS_COMPLETED
                        }
                        .map { it.fileUri }
                        .toSet()
                    val sources = allSources.filterNot { it.uri.toString() in completedUris }
                    if (sources.isEmpty()) return@execute "كل الملفات مستلمة مسبقاً على هذا الجهاز"
                    sources.forEach { source ->
                        UltraSharePeerDeliveryStore.update(
                            this@UltraShareForegroundService,
                            source.uri,
                            peer.sessionId,
                            UltraSharePeerDeliveryStore.STATUS_SENDING
                        )
                    }
                    try {
                        var activePeerHost = auth.host
                        sources.chunked(UltraShareBatchProtocol.MAX_FILES).forEach { chunk ->
                            val outcome = sendBatchWithFileIsolation(
                                sources = chunk,
                                host = activePeerHost,
                                secret = auth.secret,
                                candidates = auth.candidates,
                                expectedPeerId = auth.peerId,
                                onHostChanged = { newHost ->
                                    val previousHost = activePeerHost
                                    activePeerHost = newHost
                                    updateState { current ->
                                        current.copy(
                                            peerTransfers = current.peerTransfers.filterNot {
                                                it.host == previousHost || it.host == newHost
                                            }
                                        )
                                    }
                                }
                            ) { progress ->
                                val sendProgress = UltraShareCore.Progress(
                                    progress.fileName, progress.transferredBytes, progress.totalBytes,
                                    progress.bytesPerSecond, false
                                )
                                updateState { current ->
                                    val transfer = UltraSharePeerTransfer(
                                        host = activePeerHost,
                                        fileName = progress.fileName,
                                        transferredBytes = progress.transferredBytes,
                                        totalBytes = progress.totalBytes,
                                        bytesPerSecond = progress.bytesPerSecond,
                                        status = "يرسل الآن • V2"
                                    )
                                    val updatedTransfers = current.peerTransfers.filterNot { it.host == activePeerHost } + transfer
                                    val aggregateSendProgress = sendProgress.copy(
                                        bytesPerSecond = aggregateActivePeerBytesPerSecond(updatedTransfers, receiving = false)
                                    )
                                    current.copy(
                                        peerTransfers = updatedTransfers,
                                        progress = if (current.receiving) current.receiveProgress else aggregateSendProgress,
                                        sendProgress = aggregateSendProgress,
                                        queueCurrentName = progress.fileName,
                                        status = "إرسال دفعة V2 إلى $activePeerHost"
                                    )
                                }
                                updateNotification(currentState().sendProgress ?: sendProgress)
                            }
                            outcome.metrics?.let { metrics ->
                                updateState { current -> current.copy(lastTransferMetrics = metrics) }
                            }
                            val outcomes = outcome.files.associateBy { it.fileId }
                            chunk.forEach { source ->
                                val fileOutcome = outcomes[source.fileId]
                                val completed = fileOutcome?.status == UltraShareBatchProtocol.FileStatus.COMPLETED
                                UltraSharePeerDeliveryStore.update(
                                    this@UltraShareForegroundService,
                                    source.uri,
                                    peer.sessionId,
                                    if (completed) UltraSharePeerDeliveryStore.STATUS_COMPLETED
                                    else UltraSharePeerDeliveryStore.STATUS_FAILED,
                                    if (completed) null else fileOutcome?.error ?: "لم يؤكد الجهاز استلام الملف عبر V2."
                                )
                            }
                        }
                        val peerReceipts = UltraSharePeerDeliveryStore.observe(this@UltraShareForegroundService).first()
                            .filter {
                                it.peerSessionId == peer.sessionId &&
                                    it.status == UltraSharePeerDeliveryStore.STATUS_COMPLETED
                            }
                            .mapTo(mutableSetOf()) { it.fileUri }
                        val completedCount = sources.count { it.uri.toString() in peerReceipts }
                        "اكتمل " + completedCount + " من " + sources.size + " ملفات"
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            val delivered = UltraSharePeerDeliveryStore.observe(this@UltraShareForegroundService).first()
                                .filter {
                                    it.peerSessionId == peer.sessionId &&
                                        it.status == UltraSharePeerDeliveryStore.STATUS_COMPLETED
                                }
                                .map { it.fileUri }
                                .toSet()
                            sources.filterNot { it.uri.toString() in delivered }.forEach { source ->
                                UltraSharePeerDeliveryStore.update(
                                    this@UltraShareForegroundService,
                                    source.uri,
                                    peer.sessionId,
                                    UltraSharePeerDeliveryStore.STATUS_PENDING,
                                    "أُوقف النقل؛ يمكن استئناف هذا الجهاز بعد إعادة الاتصال."
                                )
                            }
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        withContext(NonCancellable) {
                            val delivered = UltraSharePeerDeliveryStore.observe(this@UltraShareForegroundService).first()
                                .filter {
                                    it.peerSessionId == peer.sessionId &&
                                        it.status == UltraSharePeerDeliveryStore.STATUS_COMPLETED
                                }
                                .map { it.fileUri }
                                .toSet()
                            sources.filterNot { it.uri.toString() in delivered }.forEach { source ->
                                UltraSharePeerDeliveryStore.update(
                                    this@UltraShareForegroundService,
                                    source.uri,
                                    peer.sessionId,
                                    UltraSharePeerDeliveryStore.STATUS_FAILED,
                                    failure.localizedMessage ?: "فشل الإرسال إلى هذا الجهاز."
                                )
                            }
                        }
                        throw failure
                    }
                }

                val deliveryEntries = UltraSharePeerDeliveryStore.observe(this@UltraShareForegroundService).first()
                var fullyDeliveredFiles = 0
                var failedFiles = queue.size - metadata.size
                var transferredBytes = 0L
                val peerSessionIds = peerTargets.mapTo(HashSet()) { it.sessionId }
                metadata.forEach { (uri, details) ->
                    val perPeer = deliveryEntries.filter {
                        it.fileUri == uri.toString() && it.peerSessionId in peerSessionIds
                    }
                    val completeForAll = perPeer.size == peerTargets.size &&
                        perPeer.all { it.status == UltraSharePeerDeliveryStore.STATUS_COMPLETED }
                    if (completeForAll) {
                        fullyDeliveredFiles++
                        transferredBytes = if (Long.MAX_VALUE - transferredBytes < details.third) {
                            Long.MAX_VALUE
                        } else transferredBytes + details.third
                        UltraShareQueueStore.update(
                            this@UltraShareForegroundService,
                            uri,
                            details.first,
                            UltraShareQueueStore.STATUS_COMPLETED
                        )
                    } else {
                        failedFiles++
                        val reason = perPeer.firstOrNull {
                            it.status != UltraSharePeerDeliveryStore.STATUS_COMPLETED
                        }?.error ?: "لم يكتمل الاستلام على جميع الأجهزة المحددة."
                        UltraShareQueueStore.update(
                            this@UltraShareForegroundService,
                            uri,
                            details.first,
                            UltraShareQueueStore.STATUS_FAILED,
                            reason
                        )
                    }
                }
                updateState { current ->
                    current.copy(
                        queueCompleted = queue.size,
                        queueTotal = queue.size,
                        queueTotalBytes = totalBytes,
                        queueTransferredBytes = transferredBytes,
                        status = "اكتمل الإرسال الجماعي: " + fullyDeliveredFiles + " ملفات وصلت إلى جميع الأجهزة",
                        error = if (failedFiles > 0) {
                            "هناك " + failedFiles + " ملفات لم تصل إلى كل الأجهزة. يمكنك إعادة المحاولة لكل جهاز."
                        } else null
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    UltraSharePeerDeliveryStore.recoverInterrupted(
                        this@UltraShareForegroundService,
                        targets.mapTo(linkedSetOf()) { it.sessionId }
                    )
                }
                updateState { current ->
                    current.copy(
                        sending = sendJobsByPeer.isNotEmpty(),
                        status = "أُوقف الإرسال الجماعي؛ احتُفظ بحالات كل جهاز للاستئناف."
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure("MULTI_PEER_SEND", failure)
                updateState { current ->
                    current.copy(
                        error = failure.localizedMessage ?: "فشل الإرسال الجماعي.",
                        status = "توقف الإرسال الجماعي مع الاحتفاظ بسجل التسليم لكل جهاز."
                    )
                }
            } finally {
                if (transferLockStarted) endTransfer(isReceiving = false)
                targets.forEach { target -> sendJobsByPeer.remove(target.sessionId, multiPeerSendJob) }
                clearRoomPresentationIfUnpaired()
                updateState { current ->
                    current.copy(
                        sending = sendJobsByPeer.isNotEmpty(),
                        progress = if (current.receiving) current.receiveProgress else null,
                        sendProgress = null,
                        queueCurrentName = null
                    )
                }
                scheduleIdleDisconnect()
                finishService()
            }
        }
        // Reserve every target atomically with single-peer jobs as well.
        if (!UltraShareSendJobRegistry.reserve(
                sendJobsByPeer,
                targets.map { it.sessionId },
                multiPeerSendJob
            )
        ) return
        updateState { current ->
            current.copy(
                queueTotal = uris.distinctBy { it.toString() }.size,
                status = "جارٍ تجهيز الإرسال إلى " + targets.size + " أجهزة مقترنة",
                error = null
            )
        }
        multiPeerSendJob.start()
    }


    private fun meshDirectCredentialForPeer(peerId: String?): String? {
        val localId = canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this)) ?: return null
        val remoteId = canonicalMeshPeerId(peerId) ?: return null
        val coordinator = meshRoomCoordinator ?: return null
        return runCatching {
            if (coordinator.state().members.any { it.peerId == remoteId }) {
                coordinator.deriveDirectLinkCredential(localId, remoteId)
            } else null
        }.getOrNull()
    }

    /**
     * Keep the efficient V2 multi-file session as the fast path. If any source or
     * socket fails mid-batch, retry each file in its own existing V2 session:
     * durable receiver receipts make already-published files idempotent, while a
     * bad URI can no longer prevent later healthy files from being attempted.
     */
    private suspend fun sendBatchWithFileIsolation(
        sources: List<UltraShareBatchTransport.Source>,
        host: String,
        secret: String,
        candidates: List<UltraShareNegotiation.Candidate> = emptyList(),
        expectedPeerId: String? = null,
        onHostChanged: (String) -> Unit = {},
        onProgress: (UltraShareBatchTransport.FileProgress) -> Unit
    ): UltraShareBatchTransport.BatchOutcome {
        require(sources.isNotEmpty())
        require(sources.map { it.fileId }.distinct().size == sources.size) {
            "تحتوي قائمة الإرسال على معرّف ملف مكرر."
        }
        var activeHost = host
        var successfulBatch: UltraShareBatchTransport.BatchOutcome? = null
        val outcomes = UltraShareBatchRecovery.execute(
            items = sources,
            sendBatch = { batchSources ->
                val outcome = sendBatchWithRetry(
                    sources = batchSources,
                    host = activeHost,
                    secret = secret,
                    candidates = candidates,
                    expectedPeerId = expectedPeerId,
                    onHostChanged = { newHost ->
                        activeHost = newHost
                        onHostChanged(newHost)
                    },
                    onProgress = onProgress
                )
                successfulBatch = outcome
                outcome.files
            },
            sendSingle = { source ->
                val outcome = sendBatchWithRetry(
                    sources = listOf(source),
                    host = activeHost,
                    secret = secret,
                    candidates = candidates,
                    expectedPeerId = expectedPeerId,
                    onHostChanged = { newHost ->
                        activeHost = newHost
                        onHostChanged(newHost)
                    },
                    onProgress = onProgress
                )
                outcome.files.singleOrNull()
                    ?: throw IOException("لم يُرجع الجهاز المستقبل نتيجة للملف ${source.name}.")
            },
            onItemFailure = { source, failure ->
                UltraShareDiagnostics.failure(
                    "SEND_V2_FILE_RECOVERY",
                    failure,
                    "host=$activeHost;fileId=${source.fileId}"
                )
                val safeName = runCatching {
                    UltraShareCore.sanitizeFileName(source.name)
                }.getOrDefault(source.name.take(180).ifBlank { "ملف غير صالح" })
                UltraShareBatchTransport.FileOutcome(
                    fileId = source.fileId,
                    fileName = safeName,
                    receivedFile = null,
                    status = UltraShareBatchProtocol.FileStatus.FAILED,
                    transferredBytes = 0L,
                    error = failure.localizedMessage?.take(240)
                        ?: "تعذر إرسال الملف."
                )
            },
            onBatchFailure = { failure ->
                successfulBatch = null
                UltraShareDiagnostics.failure(
                    "SEND_V2_BATCH_FILE_RECOVERY",
                    failure,
                    "host=$activeHost;files=${sources.size};fallback=per_file"
                )
                updateState { current ->
                    current.copy(
                        status = "تعثر إرسال الدفعة؛ نعيد محاولة كل ملف منفرداً للحفاظ على بقية الطابور..."
                    )
                }
            }
        )
        return successfulBatch
            ?: UltraShareBatchTransport.BatchOutcome(
                java.util.UUID.randomUUID().toString(),
                outcomes
            )
    }

    private suspend fun sendBatchWithRetry(
        sources: List<UltraShareBatchTransport.Source>,
        host: String,
        secret: String,
        candidates: List<UltraShareNegotiation.Candidate> = emptyList(),
        expectedPeerId: String? = null,
        onHostChanged: (String) -> Unit = {},
        onProgress: (UltraShareBatchTransport.FileProgress) -> Unit
    ): UltraShareBatchTransport.BatchOutcome {
        var activeHost = host
        var activeSecret = secret
        val activeCandidates = candidates.toMutableList()
        val fallbackCandidates = UltraShareNegotiation.orderedCandidates(candidates)
            .filterNot { it.host == host }
            .toMutableList()
        var fallbackIndex = 0
        var retry = 0
        var lastFailure: IOException? = null

        while (true) {
            try {
                val dataPort = UltraShareNegotiation.dataPortForHost(
                    activeHost, activeCandidates, UltraShareBatchTransport.DEFAULT_PORT
                )
                val dataNetworks = UltraShareNetworkRouting.findNetworksForHost(
                    this@UltraShareForegroundService, activeHost
                )
                // The authenticated pairing is the only connection step shown to the user.
                // Do not open a speculative TCP probe or gate the Send button on a second
                // listener check: sendBatch performs the real authenticated data handshake.
                // Its connection failure is retried below, which also covers the short race
                // while the receiver starts its listener after accepting the pairing.
                if (dataPort == UltraShareDiscovery.LEGACY_V1_DATA_PORT) {
                    throw IOException(
                        "هذا الجهاز يستخدم إصدار نقل ملفات قديماً وغير متوافق مع محرك Ultra Share الحالي. حدّث التطبيق على الجهاز الآخر ثم أعد الإرسال."
                    )
                }
                return UltraShareBatchTransport.sendBatch(
                    resolver = contentResolver,
                    sources = sources,
                    host = activeHost,
                    port = dataPort,
                    pairingSecret = activeSecret,
                    network = dataNetworks.firstOrNull(),
                    networks = dataNetworks.drop(1),
                    onProgress = onProgress
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                lastFailure = failure
                val failureMessage = failure.message.orEmpty()
                if (failureMessage.startsWith("ULTRASHARE_LEGACY_PEER:")) {
                    UltraShareDiagnostics.event(
                        "DATA_SEND_RECOVERY",
                        "LEGACY_V1_PEER_DETECTED_PAIRING_KEPT",
                        mapOf(
                            "activeHost" to activeHost,
                            "dataPort" to UltraShareNegotiation.dataPortForHost(
                                activeHost,
                                activeCandidates,
                                UltraShareBatchTransport.DEFAULT_PORT
                            )
                        )
                    )
                    throw IOException(failureMessage.removePrefix("ULTRASHARE_LEGACY_PEER:").trim())
                }
                UltraShareDiagnostics.failure(
                    "DATA_SEND_RECOVERY",
                    failure,
                    "activeHost=" + activeHost + ";retry=" + retry + ";expectedPeerId=" + (expectedPeerId ?: "missing")
                )
                // Retry the real data connection if the receiver listener is still starting,
                // or if Android briefly changes the active Wi-Fi route.
                if (retry < 2) {
                    retry++
                    var samePathReady = UltraShareControlChannel.hasAnyActiveSession(activeHost)
                    if (!samePathReady) {
                        UltraShareDiagnostics.event(
                            "DATA_SEND_RECOVERY",
                            "CONTROL_RECONNECT_STARTED",
                            mapOf("activeHost" to activeHost, "retry" to retry, "peerIdKnown" to !expectedPeerId.isNullOrBlank())
                        )
                        updateState { current -> current.copy(
                            status = "انقطع الاتصال؛ نعيد الاتصال تلقائياً بالجهاز المستقبل قبل استئناف الملفات..."
                        ) }
                        try {
                            val reconnectMeshSecret = meshDirectCredentialForPeer(expectedPeerId)
                            val reconnectSecret = reconnectMeshSecret ?: activeSecret
                            val reconnectPort = if (reconnectMeshSecret != null) {
                                MESH_DIRECT_CONTROL_PORT
                            } else UltraShareControlChannel.DEFAULT_PORT
                            val decision = UltraShareControlChannel.request(
                                host = activeHost,
                                pairingSecret = reconnectSecret,
                                peerName = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" } ?: "MalekPlayer Android",
                                port = reconnectPort,
                                network = UltraShareNetworkRouting.findNetworkForHost(this@UltraShareForegroundService, activeHost),
                                peerId = UltraShareDiscovery.localPeerId(this@UltraShareForegroundService),
                                expectedPeerId = expectedPeerId,
                                notifyClientSessionReady = reconnectMeshSecret == null,
                                onSessionClosed = { disconnectedHost ->
                                    canonicalMeshPeerId(expectedPeerId)?.let { meshDirectEdges.remove(it) }
                                    stopMeshSession(disconnectedHost)
                                    disconnectApprovedPeer(disconnectedHost)
                                    scheduleIdleDisconnect()
                                }
                            )
                            check(decision == UltraShareControlChannel.Decision.ACCEPTED) {
                                "تعذر إكمال إعادة الاتصال التلقائي بالجهاز المستقبل."
                            }
                            check(UltraShareControlChannel.isSessionActive(activeHost)) {
                                "انقطعت قناة التحكم أثناء إعادة الاتصال."
                            }
                            val reconnectedPeerId = canonicalMeshPeerId(
                                UltraShareControlChannel.sessionPeerId(activeHost)
                            ) ?: expectedPeerId
                            if (reconnectMeshSecret != null) {
                                if (reconnectedPeerId != canonicalMeshPeerId(expectedPeerId)) {
                                    UltraShareControlChannel.closeSession(activeHost)
                                    error("هوية الجهاز بعد إعادة اتصال Mesh لا تطابق العضو المطلوب.")
                                }
                                activeSecret = reconnectSecret
                                meshDirectEdges[reconnectedPeerId!!] =
                                    UltraShareMeshTopology.Edge.of(
                                        canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this@UltraShareForegroundService))!!,
                                        reconnectedPeerId
                                    )
                            }
                            val stableSessionId = reconnectedPeerId
                                ?: UltraShareControlChannel.sessionPeerId(activeHost)
                                ?: UltraShareMultiRecipientCoordinator.resolveSessionId(
                                    activeHost,
                                    approvedSendTargets().map { it.host to it.sessionId },
                                    java.util.UUID.randomUUID().toString()
                                )
                            registerApprovedPeer(
                                name = UltraShareControlChannel.sessionPeerName(activeHost) ?: "جهاز MalekPlayer المستقبل",
                                host = activeHost,
                                role = "أعاد الموافقة على الاتصال",
                                sessionId = stableSessionId
                            )
                            if (reconnectMeshSecret != null) {
                                registerApprovedSendTarget(
                                    UltraShareApprovedSendTarget(
                                        sessionId = stableSessionId,
                                        host = activeHost,
                                        secret = activeSecret,
                                        candidates = activeCandidates,
                                        peerId = expectedPeerId
                                    )
                                )
                            }
                            samePathReady = true
                            UltraShareDiagnostics.event(
                                "DATA_SEND_RECOVERY",
                                "CONTROL_RECONNECT_SUCCEEDED",
                                mapOf("activeHost" to activeHost, "retry" to retry)
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (reconnectFailure: Exception) {
                            lastFailure = reconnectFailure as? IOException
                                ?: IOException(reconnectFailure.localizedMessage ?: "تعذرت إعادة الاتصال بالمسار الحالي.", reconnectFailure)
                        }
                    }
                    if (samePathReady) {
                        updateState { current -> current.copy(
                            status = "انقطع اتصال البيانات؛ استئناف الدفعة تلقائياً ${retry} من 2"
                        ) }
                        kotlinx.coroutines.delay(retry * 1_000L)
                        continue
                    }
                }

                // The receiver may have moved to another subnet/IP while the
                // transfer was active. Rediscover only the exact paired peer identity;
                // never select a device by display name or by an unauthenticated IP guess.
                if (!expectedPeerId.isNullOrBlank()) {
                    updateState { current -> current.copy(
                        status = "نبحث عن الجهاز نفسه بعد تغيّر عنوان الشبكة..."
                    ) }
                    UltraShareDiagnostics.event(
                        "DATA_SEND_RECOVERY",
                        "PEER_REDISCOVERY_STARTED",
                        mapOf("expectedPeerId" to expectedPeerId, "previousHost" to activeHost)
                    )
                    val refreshedRoutes = runCatching {
                        UltraShareDiscovery.discoverPeers(
                            scanWindowMs = 2_500L,
                            context = this@UltraShareForegroundService
                        )
                    }.getOrDefault(emptyList()).let {
                        UltraShareNegotiation.candidatesForPeer(expectedPeerId, it)
                    }
                    UltraShareDiagnostics.event(
                        "DATA_SEND_RECOVERY",
                        "PEER_REDISCOVERY_COMPLETED",
                        mapOf(
                            "expectedPeerId" to expectedPeerId,
                            "previousHost" to activeHost,
                            "matchingRouteCount" to refreshedRoutes.size,
                            "matchingHosts" to refreshedRoutes.map { it.host }.distinct().joinToString(",")
                        )
                    )
                    refreshedRoutes.forEach { route ->
                        if (route.host != activeHost && fallbackCandidates.none {
                                it.host == route.host && it.port == route.port
                            }) {
                            fallbackCandidates += route
                            activeCandidates += route
                        }
                    }
                }

                var switched = false
                while (fallbackIndex < fallbackCandidates.size && !switched) {
                    val candidate = fallbackCandidates[fallbackIndex++]
                    UltraShareDiagnostics.event(
                        "DATA_SEND_RECOVERY",
                        "ALTERNATE_ROUTE_ATTEMPT",
                        mapOf(
                            "previousHost" to activeHost,
                            "candidateHost" to candidate.host,
                            "transport" to candidate.transport,
                            "port" to candidate.port
                        )
                    )
                    UltraShareControlChannel.closeSession(activeHost)
                    disconnectApprovedPeer(activeHost)
                    updateState { current -> current.copy(
                        status = "المسار الحالي تعذّر؛ نجرب مسار ${candidate.transport} إلى الجهاز نفسه..."
                    ) }
                    try {
                        val alternateMeshSecret = meshDirectCredentialForPeer(expectedPeerId)
                        val alternateSecret = alternateMeshSecret ?: activeSecret
                        val alternateControlPort = if (alternateMeshSecret != null) {
                            MESH_DIRECT_CONTROL_PORT
                        } else UltraShareControlChannel.DEFAULT_PORT
                        val decision = UltraShareControlChannel.request(
                            host = candidate.host,
                            pairingSecret = alternateSecret,
                            peerName = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" } ?: "MalekPlayer Android",
                            port = alternateControlPort,
                            network = UltraShareNetworkRouting.findNetworkForHost(this@UltraShareForegroundService, candidate.host),
                            networks = UltraShareNetworkRouting.findNetworksForHost(this@UltraShareForegroundService, candidate.host),
                            peerId = UltraShareDiscovery.localPeerId(this@UltraShareForegroundService),
                            expectedPeerId = expectedPeerId,
                            notifyClientSessionReady = alternateMeshSecret == null,
                            onSessionClosed = { disconnectedHost ->
                                canonicalMeshPeerId(expectedPeerId)?.let { meshDirectEdges.remove(it) }
                                stopMeshSession(disconnectedHost)
                                disconnectApprovedPeer(disconnectedHost)
                                scheduleIdleDisconnect()
                            }
                        )
                        check(decision == UltraShareControlChannel.Decision.ACCEPTED) {
                            "تعذر الاتصال تلقائياً عبر المسار البديل."
                        }
                        check(UltraShareControlChannel.isSessionActive(candidate.host)) {
                            "انقطعت قناة التحكم أثناء تفعيل المسار البديل."
                        }
                        activeHost = candidate.host
                        onHostChanged(activeHost)
                        val alternatePeerId = canonicalMeshPeerId(
                            UltraShareControlChannel.sessionPeerId(activeHost)
                        ) ?: expectedPeerId
                        if (alternateMeshSecret != null) {
                            if (alternatePeerId != canonicalMeshPeerId(expectedPeerId)) {
                                UltraShareControlChannel.closeSession(activeHost)
                                error("هوية الجهاز عبر المسار البديل لا تطابق عضو الغرفة.")
                            }
                            activeSecret = alternateSecret
                            meshDirectEdges[alternatePeerId!!] = UltraShareMeshTopology.Edge.of(
                                canonicalMeshPeerId(UltraShareDiscovery.localPeerId(this@UltraShareForegroundService))!!,
                                alternatePeerId
                            )
                        }
                        val alternateSessionId = alternatePeerId
                            ?: UltraShareControlChannel.sessionPeerId(activeHost)
                            ?: UltraShareMultiRecipientCoordinator.resolveSessionId(
                                activeHost,
                                approvedSendTargets().map { it.host to it.sessionId },
                                java.util.UUID.randomUUID().toString()
                            )
                        registerApprovedPeer(
                            name = UltraShareControlChannel.sessionPeerName(activeHost) ?: "جهاز MalekPlayer المستقبل",
                            host = activeHost,
                            role = "اتصال عبر مسار بديل " + candidate.transport,
                            sessionId = alternateSessionId
                        )
                        if (alternateMeshSecret != null) {
                            registerApprovedSendTarget(
                                UltraShareApprovedSendTarget(
                                    sessionId = alternateSessionId,
                                    host = activeHost,
                                    secret = activeSecret,
                                    candidates = activeCandidates,
                                    peerId = expectedPeerId
                                )
                            )
                        }
                        retry = 0
                        switched = true
                        UltraShareDiagnostics.event(
                            "DATA_SEND_RECOVERY",
                            "ALTERNATE_ROUTE_CONNECTED",
                            mapOf("activeHost" to activeHost, "transport" to candidate.transport)
                        )
                        updateState { current -> current.copy(
                            status = "تم التبديل إلى مسار ${candidate.transport}؛ نتابع إرسال الملفات."
                        ) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (fallbackFailure: Exception) {
                        lastFailure = fallbackFailure as? IOException
                            ?: IOException(fallbackFailure.localizedMessage ?: "فشل الاتصال عبر المسار البديل.", fallbackFailure)
                    }
                }
                if (!switched) throw (lastFailure ?: failure)
            }
        }
    }

    private fun readDocumentMetadata(uri: Uri): Triple<String, String, Long> =
        UltraShareDeviceNetworkInfo.readDocumentMetadata(contentResolver, uri)

    private fun findLocalIpv4Addresses(): List<String> =
        UltraShareDeviceNetworkInfo.findLocalIpv4Addresses(this)

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableUri(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key) as? T

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriList(key: String): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java)
        else getParcelableArrayListExtra(key)

    companion object {
        private const val CHANNEL_ID = "malekplay_ultra_share"
        private const val NOTIFICATION_ID = 38741
        private const val SESSION_IDLE_TIMEOUT_MS = 5L * 60L * 1000L
        private const val MESH_DIRECT_CONTROL_PORT = 38_746
        private const val MESH_DIRECT_DIAL_ATTEMPTS = 3
        private const val EXTRA_SECRET = "secret"
        private const val EXTRA_CONTROL_PORT = "control_port"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_ROUTE_OFFER = "route_offer"
        private const val EXTRA_PEER_TARGETS = "peer_targets"
        private const val EXTRA_PRESERVE_DELIVERIES = "preserve_deliveries"
        const val ACTION_STOP = "com.malekplay.ultrashare.STOP"
        const val ACTION_CANCEL = "com.malekplay.ultrashare.CANCEL"
        const val ACTION_CANCEL_SEND = "com.malekplay.ultrashare.CANCEL_SEND"
        const val ACTION_STOP_RECEIVING = "com.malekplay.ultrashare.STOP_RECEIVING"
        const val ACTION_STOP_IF_IDLE = "com.malekplay.ultrashare.STOP_IF_IDLE"
        const val ACTION_START_HOTSPOT = "com.malekplay.ultrashare.START_HOTSPOT"
        const val ACTION_STOP_HOTSPOT = "com.malekplay.ultrashare.STOP_HOTSPOT"
        const val ACTION_PAUSE = "com.malekplay.ultrashare.PAUSE"
        const val ACTION_REFRESH_ADDRESSES = "com.malekplay.ultrashare.REFRESH_ADDRESSES"
        const val ACTION_UPDATE_WIFI_DIRECT_ADDRESS = "com.malekplay.ultrashare.UPDATE_WIFI_DIRECT_ADDRESS"
        private const val EXTRA_WIFI_DIRECT_LOCAL_ADDRESS = "wifi_direct_local_address"
        const val ACTION_RECEIVE = "com.malekplay.ultrashare.RECEIVE"
        const val ACTION_PEER_CONNECTED = "com.malekplay.ultrashare.PEER_CONNECTED"
        const val ACTION_MESH_SESSION_READY = "com.malekplay.ultrashare.MESH_SESSION_READY"
        const val ACTION_ACCEPT_CONNECTION = "com.malekplay.ultrashare.ACCEPT_CONNECTION"
        const val ACTION_REJECT_CONNECTION = "com.malekplay.ultrashare.REJECT_CONNECTION"
        const val ACTION_SEND = "com.malekplay.ultrashare.SEND"
        const val ACTION_SEND_BATCH = "com.malekplay.ultrashare.SEND_BATCH"
        const val ACTION_SEND_MULTI_BATCH = "com.malekplay.ultrashare.SEND_MULTI_BATCH"
        const val ACTION_RETRY_FILE = "com.malekplay.ultrashare.RETRY_FILE"

        private val mutableState = MutableStateFlow(UltraShareServiceState())
        // One process-wide service instance owns these peer-scoped jobs. Keeping
        // the registry in the companion also lets the UI avoid duplicate sends
        // to a busy peer while still offering other peers concurrently.
        private val sendJobsByPeer = java.util.concurrent.ConcurrentHashMap<String, Job>()
        private val approvedSendTargetsByHost = LinkedHashMap<String, UltraShareApprovedSendTarget>()

        internal fun registerApprovedSendTarget(target: UltraShareApprovedSendTarget) {
            require(UltraShareCore.isValidPrivateLanHost(target.host))
            require(UltraShareCore.isValidPairingSecret(target.secret))
            val startsFreshRoom = mutableState.value.activePeers.isEmpty() &&
                approvedSendTargets().isEmpty() && sendJobsByPeer.isEmpty()
            synchronized(approvedSendTargetsByHost) {
                approvedSendTargetsByHost[target.host] = target
            }
            mutableState.update { current ->
                val roomState = if (startsFreshRoom) resetRoomTransferPresentation(current) else current
                roomState.copy(
                    approvedSenderPeerHosts = approvedSendTargets().map { it.host },
                    reconnectableSenderPeerHosts = emptyList()
                )
            }
        }

        internal fun approvedSendTargets(): List<UltraShareApprovedSendTarget> =
            synchronized(approvedSendTargetsByHost) { approvedSendTargetsByHost.values.toList() }

        internal fun approvedSendTarget(host: String): UltraShareApprovedSendTarget? =
            synchronized(approvedSendTargetsByHost) { approvedSendTargetsByHost[host] }

        internal fun isSendingToPeer(host: String): Boolean {
            val sessionId = approvedSendTarget(host)?.sessionId ?: host
            return sendJobsByPeer.containsKey(sessionId)
        }

        internal fun availableSendTargets(): List<UltraShareApprovedSendTarget> =
            approvedSendTargets().filterNot { sendJobsByPeer.containsKey(it.sessionId) }

        internal fun removeApprovedSendTarget(host: String) {
            synchronized(approvedSendTargetsByHost) { approvedSendTargetsByHost.remove(host) }
            mutableState.update { current ->
                current.copy(
                    approvedSenderPeerHosts = approvedSendTargets().map { it.host },
                    reconnectableSenderPeerHosts = emptyList()
                )
            }
        }
        val state = mutableState.asStateFlow()

        fun registerApprovedPeer(
            name: String,
            host: String,
            role: String,
            routeCandidates: List<UltraShareNegotiation.Candidate> = emptyList(),
            sessionId: String = java.util.UUID.randomUUID().toString()
        ) {
            val startsFreshRoom = mutableState.value.activePeers.isEmpty() &&
                approvedSendTargets().isEmpty() && sendJobsByPeer.isEmpty()
            mutableState.update { current ->
                val roomState = if (startsFreshRoom) resetRoomTransferPresentation(current) else current
                val session = UltraSharePeerSession(
                    sessionId = sessionId,
                    name = name.take(96),
                    host = host,
                    role = role,
                    approvedAtMillis = System.currentTimeMillis(),
                    routeCandidates = routeCandidates.toList()
                )
                val updated = roomState.activePeers.filterNot { it.host == host } + session
                roomState.copy(activePeers = updated, reconnectablePeers = emptyList())
            }
        }

        fun disconnectApprovedPeer(host: String) {
            UltraShareControlChannel.closeSession(host)
            UltraShareControlChannel.closeServerSession(host)
            removeApprovedSendTarget(host)
            UltraShareScreenMirrorService.pruneUnauthorizedViewers()
            // A room is temporary. Once its final paired peer leaves, clear every
            // room-only result (completed, failed, pending and receive summaries).
            mutableState.update { current ->
                val remainingPeers = current.activePeers.filterNot { it.host == host }
                val roomEnded = remainingPeers.isEmpty() &&
                    approvedSendTargets().isEmpty() && sendJobsByPeer.isEmpty()
                val clearedOffer = if (current.incomingMirrorOffer?.hostAddress == host) null else current.incomingMirrorOffer
                val updated = current.copy(
                    activePeers = remainingPeers,
                    peerTransfers = if (roomEnded) emptyList() else current.peerTransfers.filterNot { it.host == host },
                    reconnectablePeers = emptyList(),
                    reconnectableSenderPeerHosts = emptyList(),
                    incomingMirrorOffer = clearedOffer
                )
                if (roomEnded) resetRoomTransferPresentation(updated) else updated
            }
        }

        internal fun sendMirrorViewerAction(
            context: Context,
            hostAddress: String,
            sessionId: String,
            action: UltraShareScreenMirrorProtocol.ViewerAction
        ) {
            val localId = UltraShareScreenMirrorProtocol.canonicalId(
                UltraShareDiscovery.localPeerId(context)
            ) ?: return
            val canonicalSession = UltraShareScreenMirrorProtocol.canonicalId(sessionId) ?: return
            if (!UltraShareControlChannel.supportsClientApplicationFrames(hostAddress)) return
            val wire = runCatching {
                UltraShareScreenMirrorProtocol.encodeControl(
                    UltraShareScreenMirrorProtocol.ControlMessage(
                        type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_VIEWER_STATE,
                        sessionId = canonicalSession,
                        senderPeerId = localId,
                        revision = 1L,
                        viewerAction = action
                    )
                )
            }.getOrNull() ?: return
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    UltraShareControlChannel.sendClientApplicationMessage(hostAddress, wire)
                }
            }
        }

        fun forgetApprovedPeer(host: String) {
            removeApprovedSendTarget(host)
            mutableState.update { current ->
                val remainingPeers = current.activePeers.filterNot { it.host == host }
                val roomEnded = remainingPeers.isEmpty() &&
                    approvedSendTargets().isEmpty() && sendJobsByPeer.isEmpty()
                val updated = current.copy(
                    activePeers = remainingPeers,
                    peerTransfers = if (roomEnded) emptyList() else current.peerTransfers,
                    reconnectablePeers = emptyList(),
                    reconnectableSenderPeerHosts = emptyList()
                )
                if (roomEnded) resetRoomTransferPresentation(updated) else updated
            }
        }

        fun acceptConnection(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_ACCEPT_CONNECTION))
        }

        fun rejectConnection(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_REJECT_CONNECTION))
        }

        fun startLocalHotspot(context: Context) {
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_START_HOTSPOT)
            start(context, intent)
        }

        fun notePeerConnected(context: Context) {
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_PEER_CONNECTED)
            start(context, intent)
        }

        /** Keep the service alive to complete Mesh room negotiation immediately after pairing. */
        fun startMeshSession(context: Context, host: String) {
            require(UltraShareCore.isValidPrivateLanHost(host))
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_MESH_SESSION_READY)
                .putExtra(EXTRA_HOST, host)
            start(context, intent)
        }

        fun startReceiving(
            context: Context,
            secret: String,
            createHotspot: Boolean = true,
            controlPort: Int = UltraShareControlChannel.DEFAULT_PORT
        ) {
            require(controlPort in 1024..65535) { "Control port must be between 1024 and 65535." }
            // Clear a previous session's readiness before the asynchronous service
            // start, so a newly generated QR can never reuse stale "ready" state.
            mutableState.update { current -> current.copy(
                controlChannelReady = false,
                controlChannelError = null
            ) }
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_RECEIVE)
                .putExtra(EXTRA_SECRET, secret)
                .putExtra(EXTRA_CONTROL_PORT, controlPort)
            start(context, intent)
            // The QR creator owns the LocalOnlyHotspot. The joining peer must not
            // start a second hotspot while connected as a Wi-Fi client; it only
            // opens its reverse transfer listener on the already-connected LAN.
            if (createHotspot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startLocalHotspot(context)
        }

        fun startSending(context: Context, uri: Uri, host: String, secret: String) {
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_SECRET, secret)
            start(context, intent)
        }

        fun startSendingBatch(
            context: Context,
            uris: List<Uri>,
            host: String,
            secret: String,
            candidates: List<UltraShareNegotiation.Candidate> = emptyList()
        ) {
            val routeOffer = candidates.take(8).takeIf { it.isNotEmpty() }?.let {
                UltraShareNegotiation.encode(
                    UltraShareNegotiation.Offer(
                        peerId = "route-fallback",
                        displayName = "MalekPlayer",
                        candidates = it
                    )
                )
            }
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_SEND_BATCH)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_SECRET, secret)
                .putExtra(EXTRA_ROUTE_OFFER, routeOffer)
            start(context, intent)
        }

        internal fun startSendingBatchToPeers(
            context: Context,
            uris: List<Uri>,
            targets: List<UltraShareApprovedSendTarget>,
            preserveCompleted: Boolean = false
        ) {
            require(uris.isNotEmpty()) { "اختر ملفاً واحداً على الأقل." }
            require(targets.isNotEmpty()) { "لا توجد أجهزة مقترنة للإرسال." }
            require(targets.size <= 64) { "يمكن الإرسال إلى 64 جهازاً كحد أقصى في الدفعة الواحدة." }
            require(targets.map { it.sessionId }.distinct().size == targets.size) {
                "تكررت هوية جلسة جهاز في قائمة الإرسال."
            }
            require(targets.map { it.host }.distinct().size == targets.size) {
                "يوجد عنوان جهاز مكرر في قائمة الإرسال."
            }
            val encodedTargets = org.json.JSONArray().apply {
                targets.forEach { target ->
                    require(target.sessionId.isNotBlank())
                    require(UltraShareCore.isValidPrivateLanHost(target.host))
                    require(UltraShareCore.isValidPairingSecret(target.secret))
                    val routeOffer = target.candidates.take(8).takeIf { it.isNotEmpty() }?.let {
                        UltraShareNegotiation.encode(
                            UltraShareNegotiation.Offer(
                                peerId = "route-fallback",
                                displayName = "MalekPlayer",
                                candidates = it
                            )
                        )
                    }
                    put(org.json.JSONObject().apply {
                        put("sessionId", target.sessionId)
                        put("peerId", target.peerId ?: org.json.JSONObject.NULL)
                        put("host", target.host)
                        put("secret", target.secret)
                        put("routeOffer", routeOffer ?: org.json.JSONObject.NULL)
                    })
                }
            }.toString()
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_SEND_MULTI_BATCH)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris.distinctBy { it.toString() }))
                .putExtra(EXTRA_PEER_TARGETS, encodedTargets)
                .putExtra(EXTRA_PRESERVE_DELIVERIES, preserveCompleted)
            start(context, intent)
        }

        private fun decodeApprovedSendTargets(raw: String): List<UltraShareApprovedSendTarget> =
            runCatching {
                val array = org.json.JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val sessionId = item.optString("sessionId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val host = item.optString("host").takeIf { UltraShareCore.isValidPrivateLanHost(it) } ?: return@mapNotNull null
                    val secret = item.optString("secret").takeIf { UltraShareCore.isValidPairingSecret(it) } ?: return@mapNotNull null
                    val offer = item.optString("routeOffer").takeIf { it.isNotBlank() && it != "null" }
                    UltraShareApprovedSendTarget(
                        sessionId = sessionId,
                        host = host,
                        secret = secret,
                        candidates = offer?.let(UltraShareNegotiation::decode)?.candidates.orEmpty(),
                        peerId = item.optString("peerId").takeIf { it.isNotBlank() && it != "null" }
                    )
                }.distinctBy { it.sessionId }.distinctBy { it.host }
            }.getOrDefault(emptyList())

        fun retryFile(context: Context, uri: Uri, host: String, secret: String) {
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_RETRY_FILE)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_SECRET, secret)
            start(context, intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_PAUSE))
        }

        fun refreshLocalAddresses(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_REFRESH_ADDRESSES))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_CANCEL))
        }

        fun cancelSending(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_CANCEL_SEND))
        }

        fun stopIfUnpaired(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_STOP_IF_IDLE))
        }

        fun stopReceiving(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_STOP_RECEIVING))
        }

        fun updateWifiDirectLocalAddress(context: Context, address: String?) {
            context.startService(
                Intent(context, UltraShareForegroundService::class.java)
                    .setAction(ACTION_UPDATE_WIFI_DIRECT_ADDRESS)
                    .putExtra(EXTRA_WIFI_DIRECT_LOCAL_ADDRESS, address)
            )
        }

        fun startHotspot(context: Context) {
            start(context, Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_START_HOTSPOT))
        }

        fun stopHotspot(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_STOP_HOTSPOT))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
