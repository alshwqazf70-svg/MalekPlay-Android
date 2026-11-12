package com.malekplay.network

import com.malekplay.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

data class UltraShareServiceState(
    val pairingSecret: String = "",
    val localAddresses: List<String> = emptyList(),
    val receiving: Boolean = false,
    val sending: Boolean = false,
    val progress: UltraShareTransport.Progress? = null,
    val sendProgress: UltraShareTransport.Progress? = null,
    val receiveProgress: UltraShareTransport.Progress? = null,
    val hotspotSsid: String? = null,
    val hotspotPassword: String? = null,
    val hotspotStarting: Boolean = false,
    val hotspotActive: Boolean = false,
    val receivedFile: UltraShareTransport.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null,
    val queueCompleted: Int = 0,
    val queueTotal: Int = 0,
    val queueCurrentName: String? = null,
    val queueTotalBytes: Long = 0L,
    val queueTransferredBytes: Long = 0L,
    val pendingConnection: UltraShareControlChannel.Request? = null,
    val activePeers: List<UltraSharePeerSession> = emptyList(),
    val reconnectablePeers: List<UltraSharePeerSession> = emptyList(),
    val peerTransfers: List<UltraSharePeerTransfer> = emptyList()
)

internal data class UltraShareApprovedSendTarget(
    val sessionId: String,
    val host: String,
    val secret: String,
    val candidates: List<UltraShareNegotiation.Candidate> = emptyList()
) {
    override fun toString(): String =
        "UltraShareApprovedSendTarget(sessionId=" + sessionId +
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
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** Keeps the shared progress indicator truthful while other peers are still receiving. */
internal fun latestActivePeerReceiveProgress(
    transfers: List<UltraSharePeerTransfer>
): UltraShareTransport.Progress? =
    transfers.asSequence()
        .filter { it.status == "يستقبل الآن" }
        .maxByOrNull { it.updatedAtMillis }
        ?.let { active ->
            UltraShareTransport.Progress(
                active.fileName,
                active.transferredBytes,
                active.totalBytes,
                active.bytesPerSecond,
                true
            )
        }

/**
 * Owns transfer sockets and coroutine jobs independently from the navigation/ViewModel lifecycle.
 * The service remains active when the Ultra Share screen is removed from the back stack.
 */
class UltraShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class ActiveDFileServer(
        val sessionId: Int,
        val listener: NstackxNativeBridge.DFileListener,
        val activeTransferIds: MutableSet<Int>
    )
    private val dfileServerSessions = java.util.concurrent.ConcurrentHashMap<String, ActiveDFileServer>()
    @Volatile private var receiveJob: Job? = null
    @Volatile private var batchReceiveJob: Job? = null
    @Volatile private var activeBatchTransferPort: Int = 0
    @Volatile private var sendJob: Job? = null
    @Volatile private var pauseRequested = false
    private var advertisementJob: Job? = null
    @Volatile private var controlJob: Job? = null
    @Volatile private var pendingConnectionDecision: CompletableDeferred<UltraShareControlChannel.Decision>? = null
    // Serialize approval prompts, not incoming sockets: concurrent peers wait in order
    // instead of having every request after the first one rejected.
    private val pendingConnectionMutex = kotlinx.coroutines.sync.Mutex()
    private var hotspotReservation: AutoCloseable? = null
    private var hotspotCallback: Any? = null
    private lateinit var notificationManager: NotificationManager
    private val transferLockGuard = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var sendTransferActive = false
    private var receiveTransferActive = false
    private var receiveTransferCount = 0

    override fun onCreate() {
        super.onCreate()
        // Remove abandoned DFile staging artifacts left by process death or force-stop.
        UltraShareDFileStager.cleanupStale(cacheDir)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ultra_share_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.ultra_share_notification_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (currentState().sending && currentState().queueTotal > 0 && sendJob != null) {
                    pauseRequested = true
                    updateState { current -> current.copy(status = "جارٍ إيقاف طابور الإرسال مؤقتاً...") }
                    sendJob?.cancel()
                }
                return START_NOT_STICKY
            }
            ACTION_REFRESH_ADDRESSES -> {
                if (currentState().receiving) {
                    updateState { current -> current.copy(localAddresses = findLocalIpv4Addresses()) }
                }
            }
            ACTION_CANCEL_SEND -> {
                sendJob?.cancel()
                return START_NOT_STICKY
            }
            ACTION_STOP_RECEIVING -> {
                receiveJob?.cancel()
                batchReceiveJob?.cancel()
                advertisementJob?.cancel()
                controlJob?.cancel()
                pendingConnectionDecision?.cancel()
                currentState().activePeers.forEach {
                    closeDFileServerForPeer(it.host)
                    UltraShareControlChannel.closeServerSession(it.host)
                }
                dfileServerSessions.keys.toList().forEach(::closeDFileServerForPeer)
                updateState { current -> current.copy(activePeers = emptyList(), pendingConnection = null) }
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
            ACTION_STOP, ACTION_CANCEL -> {
                if (sendJob == null && receiveJob == null && batchReceiveJob == null) startForegroundCompat()
                sendJob?.cancel()
                receiveJob?.cancel()
                batchReceiveJob?.cancel()
                advertisementJob?.cancel()
                controlJob?.cancel()
                pendingConnectionDecision?.cancel()
                dfileServerSessions.keys.toList().forEach(::closeDFileServerForPeer)
                currentState().activePeers.forEach { UltraShareControlChannel.closeServerSession(it.host) }
                stopLocalHotspot()
                if (sendJob == null && receiveJob == null && batchReceiveJob == null) finishService()
                return START_NOT_STICKY
            }
            ACTION_RECEIVE -> {
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                if (!UltraShareTransport.isValidPairingSecret(secret)) {
                    updateState { current -> current.copy(error = getString(R.string.ultra_share_transfer_failed)) }
                    finishService()
                    return START_NOT_STICKY
                }
                if (receiveJob != null) return START_NOT_STICKY
                activeBatchTransferPort = 0
                startForegroundCompat()
                val addresses = findLocalIpv4Addresses()
                updateState { current -> current.copy(
                        pairingSecret = secret,
                        localAddresses = addresses,
                        receiving = true,
                        pendingConnection = null,
                        activePeers = emptyList(),
                        status = getString(R.string.ultra_share_waiting)
                    ) }
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    advertisementJob = launch {
                        try {
                            UltraShareDiscovery.advertiseWhileReceiving(
                                context = this@UltraShareForegroundService,
                                transferPortProvider = { activeBatchTransferPort }
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // Receiving remains available through the displayed local IP
                            // even if another app/process already occupies the discovery port.
                            updateState { current -> current.copy(
                                status = "الاستقبال يعمل، لكن الاكتشاف التلقائي غير متاح. استخدم عنوان IP الظاهر يدوياً.",
                                error = failure.localizedMessage ?: "تعذر تشغيل الاكتشاف التلقائي للأجهزة."
                            ) }
                        }
                    }
                    try {
                        while (true) {
                            try {
                                val received = try {
                                    UltraShareTransport.receiveOnce(
                                        pairingSecret = secret,
                                        destinationDirectory = UltraShareTransport.defaultReceiveDirectory(this@UltraShareForegroundService),
                                        onProgress = { progress ->
                                            beginTransfer(isReceiving = true)
                                            updateState { current ->
                                                current.copy(progress = progress, receiveProgress = progress, error = null)
                                            }
                                            updateNotification(progress)
                                        }
                                    )
                                } finally {
                                    // Release the receive-side lock after this file, without affecting an active send.
                                    endTransfer(isReceiving = true)
                                }
                                // Publish to the user's public Downloads folder automatically on Android 10+.
                                val publishedUri = UltraShareReceivedPublisher.publish(
                                    this@UltraShareForegroundService,
                                    received
                                )
                                val published = received.copy(publishedUri = publishedUri)
                                UltraShareReceivedHistoryStore.record(
                                    context = this@UltraShareForegroundService,
                                    file = received.file,
                                    fileName = received.fileName,
                                    mimeType = received.mimeType,
                                    sizeBytes = received.sizeBytes,
                                    publishedUri = publishedUri?.toString()
                                )
                                updateState { current -> current.copy(
                                        receiving = true,
                                        progress = if (current.sending) current.sendProgress else null,
                                        receiveProgress = null,
                                        receivedFile = published,
                                        status = if (publishedUri != null) {
                                            "تم الاستلام والحفظ تلقائياً في التنزيلات: ${received.fileName}"
                                        } else getString(R.string.ultra_share_received, received.fileName),
                                        error = null
                                    ) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                // Keep the receiver available after failures, but apply a small
                                // global backoff so repeated bad pairing attempts cost more time.
                                updateState { current -> current.copy(
                                        receiving = true,
                                        progress = if (current.sending) current.sendProgress else null,
                                        receiveProgress = null,
                                        error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)
                                    ) }
                                kotlinx.coroutines.delay(1_500)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        updateState { current -> current.copy(receiving = false, pairingSecret = "", localAddresses = emptyList(), progress = if (current.sending) current.sendProgress else null, receiveProgress = null, status = getString(R.string.ultra_share_stopped)) }
                        throw cancelled
                    } catch (failure: Exception) {
                        updateState { current -> current.copy(receiving = false, error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)) }
                    } finally {
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
                                port = 0,
                                pairingSecret = secret,
                                destinationDirectory = UltraShareTransport.defaultReceiveDirectory(this@UltraShareForegroundService),
                                maxPeers = UltraShareBatchTransport.DEFAULT_MAX_PEERS,
                                onPeerStarted = { peer ->
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
                                    updateState { current ->
                                        val existing = current.peerTransfers.firstOrNull { it.host == peer }
                                        val updatedTransfers = if (existing == null || existing.status == "جارٍ الاستقبال") {
                                            val idle = (existing ?: UltraSharePeerTransfer(peer, "", 0L, 0L, 0L, "اكتملت الجلسة"))
                                                .copy(status = "اكتملت الجلسة", bytesPerSecond = 0L, updatedAtMillis = System.currentTimeMillis())
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
                                        current.copy(
                                            peerTransfers = current.peerTransfers.filterNot { it.host == peer } + item,
                                            receiveProgress = UltraShareTransport.Progress(
                                                progress.fileName, progress.transferredBytes, progress.totalBytes,
                                                progress.bytesPerSecond, true
                                            ),
                                            progress = if (current.sending) current.sendProgress else UltraShareTransport.Progress(
                                                progress.fileName, progress.transferredBytes, progress.totalBytes,
                                                progress.bytesPerSecond, true
                                            ),
                                            status = "استقبال ملفات عبر جلسة V2 من $peer",
                                            error = null
                                        )
                                    }
                                },
                                onBatchFinished = { peer, batch ->
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
                                                status = "استُلم ${received.fileName} ضمن دفعة V2 من $peer",
                                                error = null
                                            ) }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            updateState { current -> current.copy(error = failure.localizedMessage ?: "تعذر حفظ الملف المستلم.") }
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
                                                updatedAtMillis = System.currentTimeMillis()
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
                                    updateState { current ->
                                        val previous = current.peerTransfers.firstOrNull { it.host == peer }
                                        val failed = (previous ?: UltraSharePeerTransfer(peer, "", 0L, 0L, 0L, "فشل النقل"))
                                            .copy(status = "فشل النقل", bytesPerSecond = 0L, updatedAtMillis = System.currentTimeMillis())
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
                                onListening = { actualPort -> activeBatchTransferPort = actualPort }
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            updateState { current -> current.copy(error = "تعذر تشغيل مستقبل V2: ${failure.localizedMessage}") }
                        } finally {
                            activeBatchTransferPort = 0
                            batchReceiveJob = null
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
                                localPeerName = Build.MODEL?.take(96)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                                localPeerId = UltraShareDiscovery.localPeerId(this@UltraShareForegroundService),
                                onSessionClosed = { request ->
                                    closeDFileServerForPeer(request.peerAddress)
                                    disconnectApprovedPeer(request.peerAddress)
                                },
                                onDFileServerReady = { request, ephemeralKey ->
                                    try {
                                        startDFileServerForPeer(request.peerAddress, request.localAddress)
                                    } finally {
                                        ephemeralKey.fill(0)
                                    }
                                }
                            ) { request ->
                                // Pairing is temporary: each new session receives one
                                // visible approval. Device identity never grants silent consent.
                                pendingConnectionMutex.lock()
                                try {
                                    val decision = CompletableDeferred<UltraShareControlChannel.Decision>()
                                    pendingConnectionDecision = decision
                                    updateState { current -> current.copy(
                                        pendingConnection = request,
                                        status = "طلب اتصال وارد من " + request.peerName
                                    ) }
                                    try {
                                        val result = kotlinx.coroutines.withTimeoutOrNull(110_000L) {
                                            decision.await()
                                        } ?: UltraShareControlChannel.Decision.REJECTED
                                        if (result == UltraShareControlChannel.Decision.ACCEPTED) {
                                            registerApprovedPeer(
                                                name = request.peerName,
                                                host = request.peerAddress,
                                                role = "اتصال مؤقت عبر QR",
                                                sessionId = request.peerId ?: request.requestId
                                            )
                                        }
                                        result
                                    } finally {
                                        if (pendingConnectionDecision === decision) {
                                            pendingConnectionDecision = null
                                        }
                                        updateState { current ->
                                            if (current.pendingConnection?.requestId == request.requestId) {
                                                current.copy(pendingConnection = null)
                                            } else current
                                        }
                                    }
                                } finally {
                                    pendingConnectionMutex.unlock()
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            updateState { current -> current.copy(error = failure.localizedMessage ?: "تعذر تشغيل قناة طلبات الاتصال.") }
                            kotlinx.coroutines.delay(1_000)
                        }
                    }
                }
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

    private fun startDFileServerForPeer(peerAddress: String, localAddress: String?): Int? {
        if (!NstackxNativeBridge.isAvailable) return null
        val localIpv4 = localAddress?.takeIf { UltraShareTransport.isValidPrivateLanHost(it) }
            ?: return null

        // Never reinitialize the process-global NSTACKX engine on another interface:
        // release() would silently destroy every other peer's active DFile session.
        val currentDiscovery = NstackxAndroidDiscoverySession.current()
        val discovery = when {
            currentDiscovery != null && currentDiscovery.localIpv4 == localIpv4 -> currentDiscovery
            currentDiscovery != null -> return null
            else -> runCatching {
                NstackxAndroidDiscoverySession.start(
                    this,
                    preferredLocalIpv4 = localIpv4,
                    onPeerFound = {}
                )
            }.getOrNull() ?: return null
        }

        val destination = UltraShareTransport.defaultReceiveDirectory(this)
        val base = (getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(filesDir, "downloads")).canonicalFile
        if (!destination.exists() && !destination.mkdirs()) return null
        val safeDestination = runCatching { destination.canonicalFile }.getOrNull() ?: return null
        if (!safeDestination.isDirectory || !safeDestination.canWrite() ||
            safeDestination.path != File(base, "MalekPlayer/Received").canonicalPath
        ) return null

        // Probe an OS-assigned port; NSTACKX binds it before ACCEPT is emitted.
        val port = runCatching { ServerSocket(0).use { it.localPort } }
            .getOrNull()?.takeIf { it in 1..65535 } ?: return null

        // Replace only this peer's receiver and detach its old listener.
        closeDFileServerForPeer(peerAddress)
        val sessionIdRef = java.util.concurrent.atomic.AtomicInteger(-1)
        val earlyEvents = java.util.concurrent.ConcurrentLinkedQueue<NstackxNativeBridge.DFileEvent>()
        val activeTransferIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
        val listener = NstackxNativeBridge.DFileListener { event ->
            val expected = sessionIdRef.get()
            if (expected <= 0) earlyEvents.add(event)
            else if (event.sessionId == expected) {
                handleDFileServerEvent(peerAddress, safeDestination, event, activeTransferIds)
            }
        }
        NstackxNativeBridge.addDFileListener(listener)
        val sessionId = try {
            NstackxNativeBridge.startApprovedDFileServer(
                peerIpv4 = peerAddress,
                localIpv4 = discovery.localIpv4,
                port = port,
                destinationDirectory = safeDestination
            )
        } catch (failure: Exception) {
            -1
        }
        if (sessionId <= 0) {
            NstackxNativeBridge.removeDFileListener(listener)
            return null
        }
        sessionIdRef.set(sessionId)
        dfileServerSessions[peerAddress] = ActiveDFileServer(sessionId, listener, activeTransferIds)
        while (true) {
            val event = earlyEvents.poll() ?: break
            if (event.sessionId == sessionId) {
                handleDFileServerEvent(peerAddress, safeDestination, event, activeTransferIds)
            }
        }
        return port
    }

    private fun handleDFileServerEvent(
        peerAddress: String,
        destination: File,
        event: NstackxNativeBridge.DFileEvent,
        activeTransferIds: MutableSet<Int>
    ) {
        when (event.eventType) {
            NstackxNativeBridge.DFILE_ON_TRANS_IN_PROGRESS -> {
                if (event.transferId > 0 && activeTransferIds.add(event.transferId)) {
                    beginTransfer(isReceiving = true)
                }
                val fileName = event.fileNames.firstOrNull() ?: "استقبال DFile"
                val progress = UltraShareTransport.Progress(
                    fileName = fileName,
                    transferredBytes = event.transferredBytes,
                    totalBytes = event.totalBytes,
                    bytesPerSecond = 0L,
                    receiving = true
                )
                updateState { current -> current.copy(
                    receiving = true,
                    progress = if (current.sending) current.sendProgress else progress,
                    receiveProgress = progress,
                    error = null
                ) }
                updateNotification(progress)
            }
            NstackxNativeBridge.DFILE_ON_FILE_RECEIVE_SUCCESS -> {
                if (event.transferId > 0 && activeTransferIds.remove(event.transferId)) {
                    endTransfer(isReceiving = true)
                }
                event.fileNames.forEach { receivedName ->
                    serviceScope.launch {
                        val safeName = UltraShareTransport.sanitizeFileName(receivedName)
                        val receivedFile = File(destination, safeName)
                        val canonical = runCatching { receivedFile.canonicalFile }.getOrNull()
                            ?: return@launch
                        if (canonical.parentFile != destination.canonicalFile ||
                            !canonical.isFile || canonical.length() < 0L
                        ) return@launch
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(canonical.extension.lowercase())
                            ?: "application/octet-stream"
                        val receivedSize = canonical.length()
                        if (receivedSize !in 0..UltraShareTransport.MAX_FILE_BYTES ||
                            !UltraShareTransport.isAllowedShareFile(safeName, mimeType)
                        ) {
                            // Enforce the same file policy on the receiving device; never
                            // trust a sender-side filter when the native engine is exposed.
                            runCatching { canonical.delete() }
                            updateState { current -> current.copy(
                                error = "رُفض ملف DFile غير مسموح: $safeName",
                                status = "تم رفض الملف قبل إضافته إلى سجل الاستلام."
                            ) }
                            return@launch
                        }
                        val received = UltraShareTransport.ReceivedFile(
                            file = canonical,
                            fileName = safeName,
                            sizeBytes = receivedSize,
                            mimeType = mimeType
                        )
                        val publishedUri = UltraShareReceivedPublisher.publish(this@UltraShareForegroundService, received)
                        UltraShareReceivedHistoryStore.record(
                            context = this@UltraShareForegroundService,
                            file = canonical,
                            fileName = safeName,
                            mimeType = mimeType,
                            sizeBytes = receivedSize,
                            publishedUri = publishedUri?.toString()
                        )
                        updateState { current -> current.copy(
                            receiving = true,
                            receivedFile = received.copy(publishedUri = publishedUri),
                            receiveProgress = null,
                            progress = if (current.sending) current.sendProgress else null,
                            status = "تم استلام $safeName عبر DFile من $peerAddress",
                            error = null
                        ) }
                    }
                }
            }
            NstackxNativeBridge.DFILE_ON_FILE_RECEIVE_FAIL -> {
                if (event.transferId > 0 && activeTransferIds.remove(event.transferId)) {
                    endTransfer(isReceiving = true)
                }
                updateState { current -> current.copy(
                    receiveProgress = null,
                    progress = if (current.sending) current.sendProgress else null,
                    error = event.message.ifBlank { "تعذر استلام الملفات عبر DFile من $peerAddress" },
                    status = "فشل استقبال ملف عبر DFile؛ لم نكرر الدفعة عبر TCP تلقائياً."
                ) }
            }
            NstackxNativeBridge.DFILE_ON_FATAL_ERROR -> {
                activeTransferIds.toList().forEach { id ->
                    if (activeTransferIds.remove(id)) endTransfer(isReceiving = true)
                }
                updateState { current -> current.copy(
                    receiveProgress = null,
                    progress = if (current.sending) current.sendProgress else null,
                    error = event.message.ifBlank { "انتهت جلسة DFile بصورة غير متوقعة." },
                    status = "انتهت جلسة DFile؛ لن نكرر البيانات تلقائياً."
                ) }
                serviceScope.launch { closeDFileServerForPeer(peerAddress) }
            }
        }
    }

    private fun closeDFileServerForPeer(peerAddress: String) {
        dfileServerSessions.remove(peerAddress)?.let { active ->
            NstackxNativeBridge.removeDFileListener(active.listener)
            active.activeTransferIds.toList().forEach { id ->
                if (active.activeTransferIds.remove(id)) endTransfer(isReceiving = true)
            }
            NstackxNativeBridge.closeDFileSession(active.sessionId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15 enforces a time limit for dataSync foreground services.
     * Stop all work immediately when the system reports that the limit was reached.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        updateState { current -> current.copy(
                receiving = false,
                sending = false,
                pairingSecret = "",
                localAddresses = emptyList(),
                progress = null,
                sendProgress = null,
                receiveProgress = null,
                hotspotStarting = false,
                hotspotActive = false,
                hotspotSsid = null,
                hotspotPassword = null,
                pendingConnection = null,
                error = "أوقف Android خدمة النقل بعد بلوغ الحد الزمني المسموح. يمكنك بدء النقل مجدداً."
            ) }
        sendJob?.cancel()
        receiveJob?.cancel()
        batchReceiveJob?.cancel()
        advertisementJob?.cancel()
        controlJob?.cancel()
        pendingConnectionDecision?.cancel()
        dfileServerSessions.keys.toList().forEach(::closeDFileServerForPeer)
        currentState().activePeers.forEach { UltraShareControlChannel.closeServerSession(it.host) }
        releaseTransferLocks()
        activeBatchTransferPort = 0
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        hotspotCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (currentState().receiving) {
            currentState().activePeers.forEach { UltraShareControlChannel.closeServerSession(it.host) }
        }
        sendJob?.cancel()
        receiveJob?.cancel()
        batchReceiveJob?.cancel()
        advertisementJob?.cancel()
        controlJob?.cancel()
        pendingConnectionDecision?.cancel()
        dfileServerSessions.keys.toList().forEach(::closeDFileServerForPeer)
        runCatching { NstackxAndroidDiscoverySession.stop() }
        serviceScope.cancel()
        releaseTransferLocks()
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        hotspotCallback = null
        updateState { current ->
            current.copy(
                receiving = false,
                sending = false,
                pairingSecret = "",
                localAddresses = emptyList(),
                progress = null,
                sendProgress = null,
                receiveProgress = null,
                hotspotStarting = false,
                hotspotActive = false,
                hotspotSsid = null,
                hotspotPassword = null,
                pendingConnection = null,
                activePeers = if (current.receiving) emptyList() else current.activePeers
            )
        }
        if (::notificationManager.isInitialized) notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startLocalHotspot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            updateState { it.copy(error = "إنشاء نقطة اتصال داخل MalekPlay يتطلب Android 8 أو أحدث. استخدم نقطة اتصال النظام يدوياً.") }
            finishService()
            return
        }
        if (hotspotReservation != null || currentState().hotspotStarting) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            updateState { it.copy(error = "خدمة Wi-Fi غير متاحة على هذا الجهاز.") }
            finishService()
            return
        }
        updateState { it.copy(hotspotStarting = true, error = null, status = "جارٍ إنشاء شبكة MalekPlay المحلية...") }
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                if (hotspotCallback !== this) {
                    runCatching { reservation.close() }
                    return
                }
                hotspotReservation = reservation
                val credentials = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        config.ssid.orEmpty() to (config.passphrase ?: "")
                    } else {
                        @Suppress("DEPRECATION")
                        val config = reservation.wifiConfiguration
                        config?.SSID?.trim('"').orEmpty() to config?.preSharedKey?.trim('"').orEmpty()
                    }
                }.getOrDefault("" to "")
                updateState { current ->
                    current.copy(
                        hotspotStarting = false,
                        hotspotActive = true,
                        hotspotSsid = credentials.first.takeIf { it.isNotBlank() },
                        hotspotPassword = credentials.second,
                        localAddresses = findLocalIpv4Addresses(),
                        status = if (credentials.first.isNotBlank()) "تم إنشاء شبكة MalekPlay المحلية. صِل الجهاز الآخر بها ثم ابحث عن الأجهزة القريبة."
                        else "تم إنشاء نقطة الاتصال، لكن تعذر قراءة بياناتها. افتح إعدادات Wi-Fi لعرضها.",
                        error = null
                    )
                }
            }

            override fun onStopped() {
                if (hotspotCallback !== this) return
                hotspotReservation = null
                hotspotCallback = null
                updateState { current ->
                    current.copy(
                        hotspotStarting = false,
                        hotspotActive = false,
                        hotspotSsid = null,
                        hotspotPassword = null,
                        status = "أوقِفت نقطة اتصال MalekPlay."
                    )
                }
                finishService()
            }

            override fun onFailed(reason: Int) {
                if (hotspotCallback !== this) return
                hotspotReservation = null
                hotspotCallback = null
                val message = when (reason) {
                    WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                        "تعذر إنشاء الشبكة لأن نقطة اتصال النظام أو وضع Wi-Fi الحالي غير متوافق. أوقف نقطة الاتصال الأخرى أو استخدم شبكة Wi-Fi مشتركة."
                    WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                        "النظام أو سياسة الجهاز تمنع إنشاء نقطة اتصال محلية."
                    WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                        "لا تتوفر قناة Wi-Fi لإنشاء نقطة الاتصال حالياً."
                    else -> "تعذر إنشاء نقطة اتصال MalekPlay. جرّب إيقاف نقطة اتصال النظام ثم أعد المحاولة."
                }
                updateState { it.copy(hotspotStarting = false, hotspotActive = false, hotspotSsid = null, hotspotPassword = null, error = message) }
                finishService()
            }
        }
        hotspotCallback = callback
        try {
            wifiManager.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
        } catch (failure: SecurityException) {
            hotspotCallback = null
            updateState { it.copy(hotspotStarting = false, error = "يلزم منح إذن الأجهزة القريبة لإنشاء شبكة Wi-Fi محلية.") }
            finishService()
        } catch (failure: Exception) {
            hotspotCallback = null
            updateState { it.copy(hotspotStarting = false, error = failure.localizedMessage ?: "تعذر بدء نقطة الاتصال.") }
            finishService()
        }
    }

    private fun stopLocalHotspot() {
        val reservation = hotspotReservation
        hotspotReservation = null
        hotspotCallback = null
        runCatching { reservation?.close() }
        updateState { it.copy(hotspotStarting = false, hotspotActive = false, hotspotSsid = null, hotspotPassword = null) }
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

    private fun updateNotification(progress: UltraShareTransport.Progress) {
        val current = currentState()
        // A single notification progress bar cannot truthfully represent two simultaneous
        // transfers. Show a neutral concurrent status instead of oscillating percentages.
        val visibleProgress = when {
            current.sending && current.receiving -> null
            current.sending -> current.sendProgress ?: progress.takeUnless { it.receiving }
            current.receiving -> current.receiveProgress ?: progress.takeIf { it.receiving }
            else -> progress
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(visibleProgress))
    }

    private fun buildNotification(progress: UltraShareTransport.Progress?): Notification {
        val current = currentState()
        val text = when {
            current.sending && current.receiving -> "الإرسال والاستقبال يعملان معاً"
            progress != null -> "${progress.fileName} • ${(progress.fraction * 100).toInt()}%"
            else -> getString(R.string.ultra_share_notification_active)
        }
        val cancelIntent = PendingIntent.getService(this, 38742, Intent(this, UltraShareForegroundService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ultra_share_notification)
            .addAction(0, getString(R.string.ultra_share_cancel_transfer), cancelIntent)
            .setContentTitle(getString(R.string.ultra_share_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress?.let { (it.fraction * 100).toInt() } ?: 0, progress == null)
            .build()
    }

    private fun finishService() {
        // Keep the foreground service alive while either direction still owns a socket.
        if (sendJob != null || receiveJob != null || batchReceiveJob != null || hotspotReservation != null || currentState().hotspotStarting) return
        releaseTransferLocks()
        stopForeground(true)
        stopSelf()
    }

    private fun beginTransfer(isReceiving: Boolean) {
        synchronized(transferLockGuard) {
            if (isReceiving) { receiveTransferCount++; receiveTransferActive = true } else sendTransferActive = true
            runCatching {
                if (wifiLock?.isHeld != true) {
                    val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL, "MalekPlay:UltraShare").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }
            runCatching {
                if (cpuWakeLock?.isHeld != true) {
                    val manager = getSystemService(POWER_SERVICE) as PowerManager
                    cpuWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MalekPlay:UltraShare").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }
        }
    }

    private fun endTransfer(isReceiving: Boolean) {
        synchronized(transferLockGuard) {
            if (isReceiving) { receiveTransferCount = (receiveTransferCount - 1).coerceAtLeast(0); receiveTransferActive = receiveTransferCount > 0 } else sendTransferActive = false
            if (!sendTransferActive && receiveTransferCount == 0) releaseTransferLocksLocked()
        }
    }

    private fun releaseTransferLocks() {
        synchronized(transferLockGuard) { releaseTransferLocksLocked() }
    }

    private fun releaseTransferLocksLocked() {
        sendTransferActive = false
        receiveTransferActive = false
        receiveTransferCount = 0
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { cpuWakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        cpuWakeLock = null
    }

    private fun updateState(transform: (UltraShareServiceState) -> UltraShareServiceState) {
        mutableState.update(transform)
    }

    private fun publish(state: UltraShareServiceState) {
        mutableState.value = state
    }

    private fun currentState(): UltraShareServiceState = mutableState.value

    private fun startSendingQueue(
        uris: List<Uri>,
        host: String,
        secret: String,
        replaceQueue: Boolean = true,
        candidates: List<UltraShareNegotiation.Candidate> = emptyList()
    ) {
        if (uris.isEmpty() || host.isBlank() || !UltraShareTransport.isValidPrivateLanHost(host) || !UltraShareTransport.isValidPairingSecret(secret)) {
            updateState { current -> current.copy(error = getString(R.string.ultra_share_transfer_failed)) }
            finishService()
            return
        }
        if (sendJob != null) return
        startForegroundCompat()
        updateState { current -> current.copy(sending = true, queueTotal = uris.size, status = "جارٍ تجهيز " + uris.size + " ملفات للإرسال", error = null) }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var activeUri: Uri? = null
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
                var useLegacyV1 = false
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
                    val fileId = java.util.UUID.randomUUID().toString()
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
                        val batch = sendBatchWithRetry(
                            sources = v2Candidates.map { it.second },
                            host = host,
                            secret = secret,
                            candidates = candidates,
                            expectedPeerId = reconnectableSendTarget(host)?.sessionId,
                            onHostChanged = { activeV2Host = it }
                        ) { progress ->
                            if (v2ProgressFileId != null && v2ProgressFileId != progress.fileId) {
                                v2ProgressCompletedBytes = if (Long.MAX_VALUE - v2ProgressCompletedBytes < v2ProgressFileSize)
                                    Long.MAX_VALUE else v2ProgressCompletedBytes + v2ProgressFileSize
                            }
                            v2ProgressFileId = progress.fileId
                            v2ProgressFileSize = progress.totalBytes
                            updateState { current ->
                                val peerTransfer = UltraSharePeerTransfer(
                                    host = activeV2Host,
                                    fileName = progress.fileName,
                                    transferredBytes = progress.transferredBytes,
                                    totalBytes = progress.totalBytes,
                                    bytesPerSecond = progress.bytesPerSecond,
                                    status = "يرسل الآن"
                                )
                                current.copy(
                                    peerTransfers = current.peerTransfers.filterNot { it.host == activeV2Host } + peerTransfer,
                                    progress = UltraShareTransport.Progress(
                                        progress.fileName, progress.transferredBytes, progress.totalBytes,
                                        progress.bytesPerSecond, false
                                    ),
                                    sendProgress = UltraShareTransport.Progress(
                                        progress.fileName, progress.transferredBytes, progress.totalBytes,
                                        progress.bytesPerSecond, false
                                    ),
                                    queueCurrentName = progress.fileName,
                                    queueTransferredBytes = minOf(totalBytes, v2ProgressCompletedBytes + progress.transferredBytes),
                                    status = "إرسال دفعة V2 عبر اتصال بيانات واحد: ${progress.fileName}"
                                )
                            }
                        }
                        val uriById = v2Candidates.associate { it.second.fileId to it.first }
                        updateState { current ->
                            val completed = batch.files.count { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED }
                            val failed = batch.files.size - completed
                            val previous = current.peerTransfers.firstOrNull { it.host == activeV2Host }
                            val summary = (previous ?: UltraSharePeerTransfer(activeV2Host, "", 0L, 0L, 0L, "اكتملت الدفعة"))
                                .copy(
                                    fileName = "نتيجة الدفعة",
                                    transferredBytes = batch.files.sumOf { it.transferredBytes.coerceAtLeast(0L) },
                                    totalBytes = v2Candidates.sumOf { it.second.sizeBytes.coerceAtLeast(0L) },
                                    bytesPerSecond = 0L,
                                    status = if (failed == 0) "اكتمل • $completed ملفات" else "اكتمل $completed • فشل $failed",
                                    updatedAtMillis = System.currentTimeMillis()
                                )
                            current.copy(peerTransfers = current.peerTransfers.filterNot { it.host == activeV2Host } + summary)
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
                                queueCompleted = index + 1,
                                queueTotal = queue.size,
                                queueTotalBytes = totalBytes,
                                queueTransferredBytes = completedBytes
                            ) }
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
                        // An older installed receiver has no v2 listener. Return claimed
                        // entries to pending and use the compatible v1 per-file path.
                        useLegacyV1 = true
                        v2Candidates.forEach { (uri, source) ->
                            UltraShareQueueStore.update(
                                this@UltraShareForegroundService, uri, source.name,
                                UltraShareQueueStore.STATUS_PENDING, "المستقبل لا يدعم V2؛ استخدام V1."
                            )
                        }
                    } catch (failure: Exception) {
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
                if (useLegacyV1 || v2Candidates.isEmpty()) {
                    queue.forEachIndexed { index, uri ->
                        val storedStatus = UltraShareQueueStore.observe(this@UltraShareForegroundService)
                            .first().firstOrNull { it.uri == uri.toString() }?.status
                        if (storedStatus == UltraShareQueueStore.STATUS_SKIPPED) {
                            // Keep the original denominator: skipped bytes are not transferred,
                            // so the overall progress must not falsely reach 100%.
                            updateState { current -> current.copy(
                                queueCompleted = index + 1,
                                queueTotal = queue.size,
                                queueTotalBytes = totalBytes
                            ) }
                            return@forEachIndexed
                        }
                        activeUri = uri
                        val metadata = try {
                            metadataByUri[uri] ?: readDocumentMetadata(uri)
                        } catch (failure: Exception) {
                            // Preserve the original total so failed bytes remain visible as incomplete.
                            failed++
                            lastError = failure.localizedMessage ?: "تعذرت قراءة أحد الملفات."
                            UltraShareQueueStore.update(this@UltraShareForegroundService, uri, status = UltraShareQueueStore.STATUS_FAILED, error = lastError)
                            activeUri = null
                            updateState { current -> current.copy(
                                queueCompleted = index + 1,
                                queueTotal = queue.size,
                                queueTotalBytes = totalBytes,
                                queueTransferredBytes = completedBytes
                            ) }
                            return@forEachIndexed
                        }
                        val claimed = UltraShareQueueStore.claimForSending(
                            this@UltraShareForegroundService,
                            uri,
                            metadata.first
                        )
                        if (!claimed) {
                            activeUri = null
                            updateState { current -> current.copy(
                                queueCompleted = index + 1,
                                queueTotal = queue.size,
                                queueTotalBytes = totalBytes,
                                queueTransferredBytes = completedBytes
                            ) }
                            return@forEachIndexed
                        }
                        updateState { current -> current.copy(
                            queueCurrentName = metadata.first,
                            queueCompleted = index,
                            queueTotal = queue.size,
                            progress = if (current.receiving) current.receiveProgress else null,
                            sendProgress = null,
                            status = "جارٍ تجهيز وفحص " + metadata.first + " قبل الإرسال..."
                        ) }
                        try {
                            // Protect long source hashing, connection setup, and retries too.
                            beginTransfer(isReceiving = false)
                            sendWithRetry(uri, host, secret, metadata) { progress ->
                                updateState { current -> current.copy(
                                    progress = progress,
                                    sendProgress = progress,
                                    queueCurrentName = metadata.first,
                                    queueTransferredBytes = minOf(totalBytes, completedBytes + progress.transferredBytes)
                                ) }
                                updateNotification(progress)
                            }
                            sent++
                            completedBytes = if (Long.MAX_VALUE - completedBytes < metadata.third) Long.MAX_VALUE else completedBytes + metadata.third
                            UltraShareQueueStore.update(this@UltraShareForegroundService, uri, metadata.first, UltraShareQueueStore.STATUS_COMPLETED)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            failed++
                            // Do not shrink the denominator on failure; otherwise the bar can
                            // report 100% even though this file still needs a retry.
                            lastError = metadata.first + ": " + (failure.localizedMessage ?: "فشل الإرسال.")
                            UltraShareQueueStore.update(this@UltraShareForegroundService, uri, metadata.first, UltraShareQueueStore.STATUS_FAILED, lastError)
                        } finally {
                            endTransfer(isReceiving = false)
                        }
                        activeUri = null
                        updateState { current -> current.copy(
                            queueCompleted = index + 1,
                            queueTotal = queue.size,
                            progress = if (current.receiving) current.receiveProgress else null,
                            sendProgress = null,
                            queueTotalBytes = totalBytes,
                            queueTransferredBytes = completedBytes
                        ) }
                    }
    
                }
                updateState { current -> current.copy(sending = false, status = "انتهى الطابور: أُرسل " + sent + " من " + queue.size + " ملفات", error = if (failed > 0) "تعذر إرسال " + failed + " ملفات. آخر خطأ: " + lastError else null) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    activeUri?.let { uri ->
                        UltraShareQueueStore.update(this@UltraShareForegroundService, uri, status = UltraShareQueueStore.STATUS_PENDING, error = "أُوقف النقل؛ يمكن استئنافه.")
                    }
                }
                updateState { current -> current.copy(
                    sending = false,
                    progress = if (current.receiving) current.receiveProgress else null,
                    sendProgress = null,
                    queueTransferredBytes = completedBytes,
                    status = if (pauseRequested)
                        "أُوقف الطابور مؤقتاً بعد إكمال " + sent + " ملفات؛ يمكنك استئناف المتبقي."
                    else "أُوقف طابور الإرسال بعد إكمال " + sent + " ملفات"
                ) }
                throw cancelled
            } finally {
                sendJob = null
                pauseRequested = false
                updateState { current -> current.copy(sending = false, progress = if (current.receiving) current.receiveProgress else null, sendProgress = null, queueCurrentName = null) }
                finishService()
            }
        }
        sendJob = job
        job.start()
    }
    private fun startSendingQueueToPeers(
        uris: List<Uri>,
        targets: List<UltraShareApprovedSendTarget>,
        preserveCompleted: Boolean
    ) {
        if (uris.isEmpty() || targets.isEmpty() || sendJob != null) return
        if (targets.any {
                !UltraShareTransport.isValidPrivateLanHost(it.host) ||
                    !UltraShareTransport.isValidPairingSecret(it.secret)
            }
        ) {
            updateState { current -> current.copy(error = "بيانات أحد الأجهزة المقترنة غير صالحة.") }
            finishService()
            return
        }
        startForegroundCompat()
        updateState { current ->
            current.copy(
                sending = true,
                queueTotal = uris.distinctBy { it.toString() }.size,
                queueCompleted = 0,
                queueTransferredBytes = 0L,
                queueTotalBytes = 0L,
                status = "جارٍ تجهيز الإرسال إلى " + targets.size + " أجهزة مقترنة",
                error = null
            )
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
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
                            fileId = java.util.UUID.randomUUID().toString()
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
                        var useLegacyV1 = false
                        var activePeerHost = auth.host
                        sources.chunked(UltraShareBatchProtocol.MAX_FILES).forEach { chunk ->
                            val outcome = if (useLegacyV1) null else {
                                try {
                                    sendBatchWithRetry(
                                        sources = chunk,
                                        host = auth.host,
                                        secret = auth.secret,
                                        candidates = auth.candidates,
                                        expectedPeerId = auth.sessionId,
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
                                        updateState { current ->
                                            val transfer = UltraSharePeerTransfer(
                                                host = activePeerHost,
                                                fileName = progress.fileName,
                                                transferredBytes = progress.transferredBytes,
                                                totalBytes = progress.totalBytes,
                                                bytesPerSecond = progress.bytesPerSecond,
                                                status = "يرسل الآن"
                                            )
                                            current.copy(
                                                peerTransfers = current.peerTransfers.filterNot { it.host == activePeerHost } + transfer,
                                                queueCurrentName = progress.fileName,
                                                status = "إرسال " + progress.fileName + " إلى " + activePeerHost
                                            )
                                        }
                                    }
                                } catch (connectFailure: java.net.ConnectException) {
                                    // Switch this peer to V1 for the remaining chunks.
                                    useLegacyV1 = true
                                    null
                                }
                            }
                            if (outcome == null) {
                                // Compatibility path for peers that expose the V1 listener only.
                                chunk.forEach { source ->
                                    val details = metadata.getValue(source.uri)
                                    sendWithRetry(source.uri, auth.host, auth.secret, details) { progress ->
                                        updateState { current ->
                                            val transfer = UltraSharePeerTransfer(
                                                host = auth.host,
                                                fileName = progress.fileName,
                                                transferredBytes = progress.transferredBytes,
                                                totalBytes = progress.totalBytes,
                                                bytesPerSecond = progress.bytesPerSecond,
                                                status = "يرسل الآن • V1"
                                            )
                                            current.copy(
                                                peerTransfers = current.peerTransfers.filterNot { it.host == auth.host } + transfer,
                                                queueCurrentName = progress.fileName
                                            )
                                        }
                                    }
                                    UltraSharePeerDeliveryStore.update(
                                        this@UltraShareForegroundService,
                                        source.uri,
                                        peer.sessionId,
                                        UltraSharePeerDeliveryStore.STATUS_COMPLETED
                                    )
                                }
                            } else {
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
                                        if (completed) null else fileOutcome?.error ?: "لم يؤكد الجهاز استلام الملف."
                                    )
                                }
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
                metadata.forEach { (uri, details) ->
                    val perPeer = deliveryEntries.filter {
                        it.fileUri == uri.toString() && it.peerSessionId in peerTargets.map { target -> target.sessionId }
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
                    UltraSharePeerDeliveryStore.recoverInterrupted(this@UltraShareForegroundService)
                }
                updateState { current ->
                    current.copy(
                        sending = false,
                        status = "أُوقف الإرسال الجماعي؛ احتُفظ بحالات كل جهاز للاستئناف."
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                updateState { current ->
                    current.copy(
                        error = failure.localizedMessage ?: "فشل الإرسال الجماعي.",
                        status = "توقف الإرسال الجماعي مع الاحتفاظ بسجل التسليم لكل جهاز."
                    )
                }
            } finally {
                if (transferLockStarted) endTransfer(isReceiving = false)
                sendJob = null
                updateState { current ->
                    current.copy(
                        sending = false,
                        progress = if (current.receiving) current.receiveProgress else null,
                        sendProgress = null,
                        queueCurrentName = null
                    )
                }
                finishService()
            }
        }
        sendJob = job
        job.start()
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
        // DFile is attempted only when the authenticated ACCEPT frame negotiated a
        // live receiver port. Any failure after NSTACKX accepts the file list is
        // terminal for this batch: falling back to TCP could duplicate partial files.
        trySendDFileBatch(host, sources, onProgress)?.let { return it }
        var activeHost = host
        val activeCandidates = candidates.toMutableList()
        val fallbackCandidates = UltraShareNegotiation.orderedCandidates(candidates)
            .filterNot { it.host == host }
            .toMutableList()
        var fallbackIndex = 0
        var retry = 0
        var lastFailure: IOException? = null

        while (true) {
            try {
                return UltraShareBatchTransport.sendBatch(
                    resolver = contentResolver,
                    sources = sources,
                    host = activeHost,
                    port = UltraShareNegotiation.dataPortForHost(
                        activeHost, activeCandidates, UltraShareBatchTransport.DEFAULT_PORT
                    ),
                    pairingSecret = secret,
                    network = UltraShareNetworkRouting.findNetworkForHost(this@UltraShareForegroundService, activeHost),
                    onProgress = onProgress
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DFilePayloadStartedException) {
                // Do not retry this batch over TCP: some DFile payload may already exist remotely.
                throw failure
            } catch (failure: IOException) {
                lastFailure = failure
                val isImmediateConnectFailure = failure is java.net.ConnectException
                if (!isImmediateConnectFailure && retry < 2) {
                    retry++
                    var samePathReady = UltraShareControlChannel.isSessionActive(activeHost)
                    if (!samePathReady) {
                        updateState { current -> current.copy(
                            status = "انقطع الاتصال؛ نعيد طلب الموافقة من الجهاز المستقبل قبل استئناف الملفات..."
                        ) }
                        try {
                            val decision = UltraShareControlChannel.request(
                                host = activeHost,
                                pairingSecret = secret,
                                peerName = Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                                network = UltraShareNetworkRouting.findNetworkForHost(this@UltraShareForegroundService, activeHost),
                                peerId = UltraShareDiscovery.localPeerId(this@UltraShareForegroundService),
                                onSessionClosed = { disconnectedHost -> disconnectApprovedPeer(disconnectedHost) }
                            )
                            check(decision == UltraShareControlChannel.Decision.ACCEPTED) {
                                "لم يوافق الجهاز المستقبل على إعادة الاتصال."
                            }
                            check(UltraShareControlChannel.isSessionActive(activeHost)) {
                                "انقطعت قناة التحكم أثناء إعادة الاتصال."
                            }
                            registerApprovedPeer(
                                name = UltraShareControlChannel.sessionPeerName(activeHost) ?: "جهاز MalekPlay المستقبل",
                                host = activeHost,
                                role = "أعاد الموافقة على الاتصال",
                                sessionId = UltraShareControlChannel.sessionPeerId(activeHost)
                                    ?: UltraShareMultiRecipientCoordinator.resolveSessionId(
                                        activeHost,
                                        reconnectableSendTargets().map { it.host to it.sessionId },
                                        java.util.UUID.randomUUID().toString()
                                    )
                            )
                            samePathReady = true
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
                    val refreshedRoutes = runCatching {
                        UltraShareDiscovery.discoverPeers(
                            scanWindowMs = 2_500L,
                            context = this@UltraShareForegroundService
                        )
                    }.getOrDefault(emptyList()).let {
                        UltraShareNegotiation.candidatesForPeer(expectedPeerId, it)
                    }
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
                    UltraShareControlChannel.closeSession(activeHost)
                    disconnectApprovedPeer(activeHost)
                    updateState { current -> current.copy(
                        status = "المسار الحالي تعذّر؛ نجرب مسار ${candidate.transport} إلى الجهاز نفسه..."
                    ) }
                    try {
                        val decision = UltraShareControlChannel.request(
                            host = candidate.host,
                            pairingSecret = secret,
                            peerName = Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                            network = UltraShareNetworkRouting.findNetworkForHost(this@UltraShareForegroundService, candidate.host),
                            peerId = UltraShareDiscovery.localPeerId(this@UltraShareForegroundService),
                            onSessionClosed = { disconnectedHost -> disconnectApprovedPeer(disconnectedHost) }
                        )
                        check(decision == UltraShareControlChannel.Decision.ACCEPTED) {
                            "لم يوافق الجهاز المستقبل على المسار البديل."
                        }
                        check(UltraShareControlChannel.isSessionActive(candidate.host)) {
                            "انقطعت قناة التحكم أثناء تفعيل المسار البديل."
                        }
                        activeHost = candidate.host
                        onHostChanged(activeHost)
                        registerApprovedPeer(
                            name = UltraShareControlChannel.sessionPeerName(activeHost) ?: "جهاز MalekPlay المستقبل",
                            host = activeHost,
                            role = "اتصال عبر مسار بديل ${candidate.transport}",
                            sessionId = UltraShareControlChannel.sessionPeerId(activeHost)
                                ?: UltraShareMultiRecipientCoordinator.resolveSessionId(
                                    activeHost,
                                    reconnectableSendTargets().map { it.host to it.sessionId },
                                    java.util.UUID.randomUUID().toString()
                                )
                        )
                        retry = 0
                        switched = true
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

    private suspend fun trySendDFileBatch(
        host: String,
        sources: List<UltraShareBatchTransport.Source>,
        onProgress: (UltraShareBatchTransport.FileProgress) -> Unit
    ): UltraShareBatchTransport.BatchOutcome? {
        val port = UltraShareControlChannel.clientDFilePort(host) ?: return null
        if (sources.isEmpty() || sources.size > 500) return null
        if (!UltraShareControlChannel.isSessionActive(host)) return null

        val safeNames = sources.map { UltraShareTransport.sanitizeFileName(it.name) }
        // DFile's upstream rename hook resolves collisions on the receiver, but the
        // completion callback reports basenames. Use the deterministic TCP protocol
        // for duplicate basenames rather than guessing which item completed.
        if (safeNames.distinct().size != safeNames.size) return null

        val network = UltraShareNetworkRouting.findNetworkForHost(this, host) ?: return null
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val localIpv4 = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress ?: return null
        val currentDiscovery = NstackxAndroidDiscoverySession.current()
        val discovery = when {
            currentDiscovery != null && currentDiscovery.localIpv4 == localIpv4 -> currentDiscovery
            currentDiscovery != null -> return null
            else -> runCatching {
                NstackxAndroidDiscoverySession.start(
                    this,
                    preferredLocalIpv4 = localIpv4,
                    onPeerFound = {}
                )
            }.getOrNull() ?: return null
        }

        val staged = ArrayList<UltraShareDFileStager.StagedFile>(sources.size)
        var nativeSessionId = -1
        val payloadAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val sessionIdRef = java.util.concurrent.atomic.AtomicInteger(-1)
        val earlyEvents = java.util.concurrent.ConcurrentLinkedQueue<NstackxNativeBridge.DFileEvent>()
        val connected = CompletableDeferred<Boolean>()
        val completed = CompletableDeferred<Boolean>()
        val completedNames = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        var lastProgressBytes = 0L
        var lastProgressAt = System.nanoTime()

        fun handleEvent(event: NstackxNativeBridge.DFileEvent) {
            if (event.sessionId != sessionIdRef.get()) return
            when (event.eventType) {
                NstackxNativeBridge.DFILE_ON_CONNECT_SUCCESS -> connected.complete(true)
                NstackxNativeBridge.DFILE_ON_CONNECT_FAIL,
                NstackxNativeBridge.DFILE_ON_FATAL_ERROR -> {
                    if (!connected.isCompleted) connected.complete(false)
                    else if (payloadAccepted.get()) completed.complete(false)
                }
                NstackxNativeBridge.DFILE_ON_FILE_SEND_SUCCESS -> {
                    event.fileNames.forEach { completedNames += it.substringAfterLast('/') }
                    if (completedNames.containsAll(safeNames)) completed.complete(true)
                }
                NstackxNativeBridge.DFILE_ON_FILE_SEND_FAIL -> completed.complete(false)
                NstackxNativeBridge.DFILE_ON_TRANS_IN_PROGRESS,
                NstackxNativeBridge.DFILE_ON_SESSION_IN_PROGRESS -> {
                    val name = event.fileNames.firstOrNull()?.substringAfterLast('/')
                        ?: safeNames.first()
                    val source = sources.firstOrNull {
                        UltraShareTransport.sanitizeFileName(it.name) == name
                    }
                    val now = System.nanoTime()
                    val elapsed = ((now - lastProgressAt) / 1_000_000_000L).coerceAtLeast(1L)
                    val speed = ((event.transferredBytes - lastProgressBytes).coerceAtLeast(0L) / elapsed)
                    lastProgressBytes = event.transferredBytes
                    lastProgressAt = now
                    onProgress(
                        UltraShareBatchTransport.FileProgress(
                            fileId = source?.fileId ?: sources.first().fileId,
                            fileName = name,
                            transferredBytes = event.transferredBytes.coerceAtLeast(0L),
                            totalBytes = event.totalBytes.coerceAtLeast(0L),
                            bytesPerSecond = speed,
                            receiving = false
                        )
                    )
                }
            }
        }

        val listener = NstackxNativeBridge.DFileListener { event ->
            if (sessionIdRef.get() <= 0) earlyEvents.add(event) else handleEvent(event)
        }
        NstackxNativeBridge.addDFileListener(listener)
        try {
            sources.forEach { source ->
                staged += UltraShareDFileStager.stage(
                    resolver = contentResolver,
                    cacheDirectory = cacheDir,
                    uri = source.uri,
                    displayName = source.name,
                    mimeType = source.mimeType,
                    expectedSizeBytes = source.sizeBytes
                )
            }
            nativeSessionId = NstackxNativeBridge.startApprovedDFileClient(host, port)
            if (nativeSessionId <= 0) return null
            sessionIdRef.set(nativeSessionId)
            while (true) {
                val queued = earlyEvents.poll() ?: break
                handleEvent(queued)
            }
            val isConnected = withTimeoutOrNull(20_000L) { connected.await() } ?: false
            if (!isConnected) return null

            val sendResult = NstackxNativeBridge.sendDFileFiles(
                nativeSessionId,
                staged.map { it.file }
            )
            if (sendResult != 0) return null
            payloadAccepted.set(true)
            val totalBytes = sources.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            val timeoutMillis = (60_000L + totalBytes / (256L * 1024L) * 1_000L)
                .coerceAtMost(30L * 60L * 1_000L)
            val transferSucceeded = withTimeoutOrNull(timeoutMillis) {
                completed.await()
            } ?: false
            if (!transferSucceeded) {
                throw DFilePayloadStartedException(
                    "بدأ DFile إرسال البيانات ثم لم يؤكد اكتمال الدفعة؛ أوقفنا التحويل إلى TCP لتجنب تكرار الملفات."
                )
            }
            return UltraShareBatchTransport.BatchOutcome(
                batchId = java.util.UUID.randomUUID().toString(),
                files = sources.map { source ->
                    UltraShareBatchTransport.FileOutcome(
                        fileId = source.fileId,
                        fileName = UltraShareTransport.sanitizeFileName(source.name),
                        receivedFile = null,
                        status = UltraShareBatchProtocol.FileStatus.COMPLETED,
                        transferredBytes = source.sizeBytes
                    )
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DFilePayloadStartedException) {
            throw failure
        } catch (failure: Exception) {
            if (payloadAccepted.get()) {
                throw DFilePayloadStartedException(
                    failure.localizedMessage ?: "فشل DFile بعد بدء نقل البيانات."
                )
            }
            return null
        } finally {
            NstackxNativeBridge.removeDFileListener(listener)
            if (nativeSessionId > 0) NstackxNativeBridge.closeDFileSession(nativeSessionId)
            staged.forEach(UltraShareDFileStager.StagedFile::close)
        }
    }

    private class DFilePayloadStartedException(message: String) : IOException(message)

    private suspend fun sendWithRetry(
        uri: Uri,
        host: String,
        secret: String,
        metadata: Triple<String, String, Long>,
        onProgress: (UltraShareTransport.Progress) -> Unit
    ) {
        var retry = 0
        while (true) {
            try {
                UltraShareTransport.send(
                    resolver = contentResolver,
                    uri = uri,
                    host = host,
                    pairingSecret = secret,
                    fileName = metadata.first,
                    mimeType = metadata.second,
                    sizeBytes = metadata.third,
                    network = UltraShareNetworkRouter.networkForHost(this@UltraShareForegroundService, host),
                    onProgress = onProgress
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (retry >= 2) throw failure
                retry++
                updateState { current -> current.copy(
                    status = "انقطع الاتصال؛ إعادة المحاولة تلقائياً " + retry + " من 2"
                ) }
                kotlinx.coroutines.delay(retry * 1_000L)
            }
        }
    }

    private fun readDocumentMetadata(uri: Uri): Triple<String, String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        require(size >= 0L) { "تعذر معرفة حجم الملف؛ اختر ملفاً من مدير الملفات يدعم عرض الحجم." }
        val mimeType = UltraShareTransport.sanitizeMimeType(
            contentResolver.getType(uri) ?: "application/octet-stream"
        )
        require(UltraShareTransport.isAllowedShareFile(name, mimeType)) {
            "لا يمكن مشاركة ملفات APK أو PDF."
        }
        return Triple(name, mimeType, size)
    }

    private fun findLocalIpv4Addresses(): List<String> = runCatching {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // The active Wi-Fi network is the reliable default for QR pairing.
        // A stale p2p interface may still report 192.168.49.1 after its group
        // disappeared, even while the phone is reachable through a 10.x WLAN.
        val activeWifiAddresses = connectivity?.activeNetwork?.let { active ->
            if (connectivity.getNetworkCapabilities(active)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) {
                val properties = connectivity.getLinkProperties(active)
                // A Wi-Fi Direct group may be reported as TRANSPORT_WIFI. Its
                // 192.168.49.x address is not a LAN fallback unless P2P is joined.
                if (UltraShareNetworkRouting.isWifiDirectInterfaceName(properties?.interfaceName.orEmpty())) {
                    emptyList()
                } else {
                    properties?.linkAddresses.orEmpty()
                        .mapNotNull { it.address as? Inet4Address }
                        .filter {
                            !it.isLoopbackAddress &&
                                (it.isSiteLocalAddress || it.isLinkLocalAddress) &&
                                !UltraShareNetworkRouting.isWifiDirectGroupHost(it.hostAddress.orEmpty())
                        }
                        .mapNotNull { it.hostAddress }
                }
            } else emptyList()
        }.orEmpty()

        val interfaceAddresses = NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    (name.startsWith("wlan") || name.startsWith("swlan") ||
                        name.startsWith("ap") || name.startsWith("eth"))
            }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter {
                        !it.isLoopbackAddress &&
                            (it.isSiteLocalAddress || it.isLinkLocalAddress) &&
                            !UltraShareNetworkRouting.isWifiDirectGroupHost(it.hostAddress.orEmpty())
                    }
                    .map { network.name.lowercase() to it }
            }
            .sortedWith(compareBy<Pair<String, Inet4Address>>(
                { (name, _) ->
                    when {
                        name.startsWith("wlan") || name.startsWith("swlan") -> 0
                        name.startsWith("ap") -> 1
                        name.startsWith("p2p") -> 2
                        else -> 3
                    }
                },
                { (_, address) -> address.hostAddress.orEmpty() }
            ))
            .mapNotNull { (_, address) -> address.hostAddress }

        (activeWifiAddresses + interfaceAddresses).distinct()
    }.getOrDefault(emptyList())

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
        private const val EXTRA_SECRET = "secret"
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
        const val ACTION_START_HOTSPOT = "com.malekplay.ultrashare.START_HOTSPOT"
        const val ACTION_STOP_HOTSPOT = "com.malekplay.ultrashare.STOP_HOTSPOT"
        const val ACTION_PAUSE = "com.malekplay.ultrashare.PAUSE"
        const val ACTION_REFRESH_ADDRESSES = "com.malekplay.ultrashare.REFRESH_ADDRESSES"
        const val ACTION_RECEIVE = "com.malekplay.ultrashare.RECEIVE"
        const val ACTION_ACCEPT_CONNECTION = "com.malekplay.ultrashare.ACCEPT_CONNECTION"
        const val ACTION_REJECT_CONNECTION = "com.malekplay.ultrashare.REJECT_CONNECTION"
        const val ACTION_SEND = "com.malekplay.ultrashare.SEND"
        const val ACTION_SEND_BATCH = "com.malekplay.ultrashare.SEND_BATCH"
        const val ACTION_SEND_MULTI_BATCH = "com.malekplay.ultrashare.SEND_MULTI_BATCH"
        const val ACTION_RETRY_FILE = "com.malekplay.ultrashare.RETRY_FILE"

        private val mutableState = MutableStateFlow(UltraShareServiceState())
        private val approvedSendTargetsByHost = LinkedHashMap<String, UltraShareApprovedSendTarget>()
        private val reconnectableSendTargetsByHost = LinkedHashMap<String, UltraShareApprovedSendTarget>()

        internal fun registerApprovedSendTarget(target: UltraShareApprovedSendTarget) {
            require(UltraShareTransport.isValidPrivateLanHost(target.host))
            require(UltraShareTransport.isValidPairingSecret(target.secret))
            synchronized(approvedSendTargetsByHost) {
                approvedSendTargetsByHost[target.host] = target
                reconnectableSendTargetsByHost[target.host] = target
            }
        }

        internal fun approvedSendTargets(): List<UltraShareApprovedSendTarget> =
            synchronized(approvedSendTargetsByHost) { approvedSendTargetsByHost.values.toList() }

        internal fun approvedSendTarget(host: String): UltraShareApprovedSendTarget? =
            synchronized(approvedSendTargetsByHost) { approvedSendTargetsByHost[host] }

        internal fun reconnectableSendTarget(host: String): UltraShareApprovedSendTarget? =
            synchronized(approvedSendTargetsByHost) { reconnectableSendTargetsByHost[host] }

        internal fun reconnectableSendTargets(): List<UltraShareApprovedSendTarget> =
            synchronized(approvedSendTargetsByHost) { reconnectableSendTargetsByHost.values.toList() }

        internal fun removeApprovedSendTarget(host: String) {
            synchronized(approvedSendTargetsByHost) { approvedSendTargetsByHost.remove(host) }
        }
        val state = mutableState.asStateFlow()

        fun registerApprovedPeer(
            name: String,
            host: String,
            role: String,
            routeCandidates: List<UltraShareNegotiation.Candidate> = emptyList(),
            sessionId: String = java.util.UUID.randomUUID().toString()
        ) {
            mutableState.update { current ->
                val session = UltraSharePeerSession(
                    sessionId = sessionId,
                    name = name.take(96),
                    host = host,
                    role = role,
                    approvedAtMillis = System.currentTimeMillis(),
                    routeCandidates = routeCandidates.toList()
                )
                val updated = current.activePeers.filterNot { it.host == host } + session
                val reconnectable = current.reconnectablePeers.filterNot { it.host == host } + session
                current.copy(activePeers = updated, reconnectablePeers = reconnectable)
            }
        }

        fun disconnectApprovedPeer(host: String) {
            UltraShareControlChannel.closeSession(host)
            UltraShareControlChannel.closeServerSession(host)
            removeApprovedSendTarget(host)
            // Keep the authenticated pairing material only in process memory so the
            // user can explicitly reconnect. It is never persisted to DataStore or disk.
            mutableState.update { current ->
                current.copy(activePeers = current.activePeers.filterNot { it.host == host })
            }
        }

        fun forgetApprovedPeer(host: String) {
            removeApprovedSendTarget(host)
            synchronized(approvedSendTargetsByHost) { reconnectableSendTargetsByHost.remove(host) }
            mutableState.update { current ->
                current.copy(
                    activePeers = current.activePeers.filterNot { it.host == host },
                    reconnectablePeers = current.reconnectablePeers.filterNot { it.host == host }
                )
            }
        }

        fun acceptConnection(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_ACCEPT_CONNECTION))
        }

        fun rejectConnection(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_REJECT_CONNECTION))
        }

        fun startReceiving(context: Context, secret: String) {
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_RECEIVE)
                .putExtra(EXTRA_SECRET, secret)
            start(context, intent)
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
                        displayName = "MalekPlay",
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
                    require(UltraShareTransport.isValidPrivateLanHost(target.host))
                    require(UltraShareTransport.isValidPairingSecret(target.secret))
                    val routeOffer = target.candidates.take(8).takeIf { it.isNotEmpty() }?.let {
                        UltraShareNegotiation.encode(
                            UltraShareNegotiation.Offer(
                                peerId = "route-fallback",
                                displayName = "MalekPlay",
                                candidates = it
                            )
                        )
                    }
                    put(org.json.JSONObject().apply {
                        put("sessionId", target.sessionId)
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
                    val host = item.optString("host").takeIf { UltraShareTransport.isValidPrivateLanHost(it) } ?: return@mapNotNull null
                    val secret = item.optString("secret").takeIf { UltraShareTransport.isValidPairingSecret(it) } ?: return@mapNotNull null
                    val offer = item.optString("routeOffer").takeIf { it.isNotBlank() && it != "null" }
                    UltraShareApprovedSendTarget(
                        sessionId = sessionId,
                        host = host,
                        secret = secret,
                        candidates = offer?.let(UltraShareNegotiation::decode)?.candidates.orEmpty()
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

        fun stopReceiving(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_STOP_RECEIVING))
        }

        fun startHotspot(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_START_HOTSPOT))
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
