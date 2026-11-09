package com.malekplay.network

import com.malekplay.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

data class UltraShareServiceState(
    val pairingSecret: String = "",
    val localAddresses: List<String> = emptyList(),
    val receiving: Boolean = false,
    val sending: Boolean = false,
    val progress: UltraShareTransport.Progress? = null,
    val receivedFile: UltraShareTransport.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null,
    val queueCompleted: Int = 0,
    val queueTotal: Int = 0,
    val queueCurrentName: String? = null,
    val queueTotalBytes: Long = 0L,
    val queueTransferredBytes: Long = 0L
)

/**
 * Owns transfer sockets and coroutine jobs independently from the navigation/ViewModel lifecycle.
 * The service remains active when the Ultra Share screen is removed from the back stack.
 */
class UltraShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null
    @Volatile private var pauseRequested = false
    private var advertisementJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
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
                if (currentState().sending && currentState().queueTotal > 0 && transferJob != null) {
                    pauseRequested = true
                    publish(currentState().copy(status = "جارٍ إيقاف طابور الإرسال مؤقتاً..."))
                    transferJob?.cancel()
                }
                return START_NOT_STICKY
            }
            ACTION_REFRESH_ADDRESSES -> {
                if (currentState().receiving) {
                    publish(currentState().copy(localAddresses = findLocalIpv4Addresses()))
                }
            }
            ACTION_STOP, ACTION_CANCEL -> {
                if (transferJob == null) startForegroundCompat()
                transferJob?.cancel()
                advertisementJob?.cancel()
                if (transferJob == null) finishService()
                return START_NOT_STICKY
            }
            ACTION_RECEIVE -> {
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                if (!UltraShareTransport.isValidPairingSecret(secret)) {
                    publish(UltraShareServiceState(error = getString(R.string.ultra_share_transfer_failed)))
                    finishService()
                    return START_NOT_STICKY
                }
                if (transferJob != null) return START_NOT_STICKY
                startForegroundCompat()
                val addresses = findLocalIpv4Addresses()
                publish(
                    UltraShareServiceState(
                        pairingSecret = secret,
                        localAddresses = addresses,
                        receiving = true,
                        status = getString(R.string.ultra_share_waiting)
                    )
                )
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    advertisementJob = launch {
                        try {
                            UltraShareDiscovery.advertiseWhileReceiving()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // Receiving remains available through the displayed local IP
                            // even if another app/process already occupies the discovery port.
                            publish(currentState().copy(
                                status = "الاستقبال يعمل، لكن الاكتشاف التلقائي غير متاح. استخدم عنوان IP الظاهر يدوياً.",
                                error = failure.localizedMessage ?: "تعذر تشغيل الاكتشاف التلقائي للأجهزة."
                            ))
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
                                            acquireTransferLocks()
                                            val nextState = currentState().copy(progress = progress, error = null)
                                            publish(nextState)
                                            updateNotification(progress)
                                        }
                                    )
                                } finally {
                                    // The receiver may wait for a peer for hours. Hold wake locks
                                    // only while bytes are actively being transferred.
                                    releaseTransferLocks()
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
                                    sizeBytes = received.sizeBytes
                                )
                                publish(
                                    currentState().copy(
                                        receiving = true,
                                        progress = null,
                                        receivedFile = published,
                                        status = if (publishedUri != null) {
                                            "تم الاستلام والحفظ تلقائياً في التنزيلات: ${received.fileName}"
                                        } else getString(R.string.ultra_share_received, received.fileName),
                                        error = null
                                    )
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                // Keep the receiver available after failures, but apply a small
                                // global backoff so repeated bad pairing attempts cost more time.
                                publish(
                                    currentState().copy(
                                        receiving = true,
                                        progress = null,
                                        error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)
                                    )
                                )
                                kotlinx.coroutines.delay(1_500)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        publish(currentState().copy(receiving = false, pairingSecret = "", localAddresses = emptyList(), progress = null, status = getString(R.string.ultra_share_stopped)))
                        throw cancelled
                    } catch (failure: Exception) {
                        publish(currentState().copy(receiving = false, error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)))
                    } finally {
                        advertisementJob?.cancel()
                        advertisementJob = null
                        transferJob = null
                        publish(currentState().copy(receiving = false, pairingSecret = "", localAddresses = emptyList(), progress = null))
                        finishService()
                    }
                }
                transferJob = job
                job.start()
            }
            ACTION_SEND -> {
                val uri = intent.parcelableUri<Uri>(EXTRA_URI)
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                if (uri == null || host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
                    publish(UltraShareServiceState(error = getString(R.string.ultra_share_transfer_failed)))
                    finishService()
                    return START_NOT_STICKY
                }
                if (transferJob != null) return START_NOT_STICKY
                startForegroundCompat()
                publish(UltraShareServiceState(sending = true, status = getString(R.string.ultra_share_sending)))
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val metadata = readDocumentMetadata(uri)
                        UltraShareTransport.send(
                            resolver = contentResolver,
                            uri = uri,
                            host = host,
                            pairingSecret = secret,
                            fileName = metadata.first,
                            mimeType = metadata.second,
                            sizeBytes = metadata.third,
                            onProgress = { progress ->
                                acquireTransferLocks()
                                publish(currentState().copy(progress = progress))
                                updateNotification(progress)
                            }
                        )
                        publish(currentState().copy(sending = false, progress = null, status = getString(R.string.ultra_share_sent, metadata.first), error = null))
                    } catch (cancelled: CancellationException) {
                        publish(currentState().copy(sending = false, progress = null, status = getString(R.string.ultra_share_cancel_transfer)))
                        throw cancelled
                    } catch (failure: Exception) {
                        publish(currentState().copy(sending = false, error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)))
                    } finally {
                        transferJob = null
                        publish(currentState().copy(sending = false, progress = null))
                        finishService()
                    }
                }
                transferJob = job
                job.start()
            }
            ACTION_SEND_BATCH -> {
                val uris = intent.parcelableUriList(EXTRA_URIS).orEmpty()
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                startSendingQueue(uris, host, secret)
            }
            ACTION_RETRY_FILE -> {
                val uri = intent.parcelableUri<Uri>(EXTRA_URI)
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                if (uri == null) {
                    publish(currentState().copy(error = getString(R.string.ultra_share_transfer_failed)))
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
        publish(
            currentState().copy(
                receiving = false,
                sending = false,
                pairingSecret = "",
                localAddresses = emptyList(),
                progress = null,
                error = "أوقف Android خدمة النقل بعد بلوغ الحد الزمني المسموح. يمكنك بدء النقل مجدداً."
            )
        )
        transferJob?.cancel()
        advertisementJob?.cancel()
        releaseTransferLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        transferJob?.cancel()
        advertisementJob?.cancel()
        serviceScope.cancel()
        releaseTransferLocks()
        if (::notificationManager.isInitialized) notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
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
        notificationManager.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun buildNotification(progress: UltraShareTransport.Progress?): Notification {
        val text = progress?.let {
            "${it.fileName} • ${(it.fraction * 100).toInt()}%"
        } ?: getString(R.string.ultra_share_notification_active)
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
        releaseTransferLocks()
        stopForeground(true)
        stopSelf()
    }

    private fun acquireTransferLocks() {
        runCatching {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL, "MalekPlay:UltraShare").apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        }
        runCatching {
            val manager = getSystemService(POWER_SERVICE) as PowerManager
            cpuWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MalekPlay:UltraShare").apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        }
    }

    private fun releaseTransferLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { cpuWakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        cpuWakeLock = null
    }

    private fun publish(state: UltraShareServiceState) {
        mutableState.value = state
    }

    private fun currentState(): UltraShareServiceState = mutableState.value

    private fun startSendingQueue(
        uris: List<Uri>,
        host: String,
        secret: String,
        replaceQueue: Boolean = true
    ) {
        if (uris.isEmpty() || host.isBlank() || !UltraShareTransport.isValidPrivateLanHost(host) || !UltraShareTransport.isValidPairingSecret(secret)) {
            publish(UltraShareServiceState(error = getString(R.string.ultra_share_transfer_failed)))
            finishService()
            return
        }
        if (transferJob != null) return
        startForegroundCompat()
        publish(UltraShareServiceState(sending = true, queueTotal = uris.size, status = "جارٍ تجهيز " + uris.size + " ملفات للإرسال"))
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var activeUri: Uri? = null
            var sent = 0
            var failed = 0
            var completedBytes = 0L
            var lastError: String? = null
            val queue = uris.distinctBy { it.toString() }
            var metadataByUri: Map<Uri, Triple<String, String, Long>> = emptyMap()
            var totalBytes = 0L
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
                publish(currentState().copy(queueTotalBytes = totalBytes, queueTransferredBytes = 0L))
                queue.forEachIndexed { index, uri ->
                    val storedStatus = UltraShareQueueStore.observe(this@UltraShareForegroundService)
                        .first().firstOrNull { it.uri == uri.toString() }?.status
                    if (storedStatus == UltraShareQueueStore.STATUS_SKIPPED) {
                        // Keep the original denominator: skipped bytes are not transferred,
                        // so the overall progress must not falsely reach 100%.
                        publish(currentState().copy(
                            queueCompleted = index + 1,
                            queueTotal = queue.size,
                            queueTotalBytes = totalBytes
                        ))
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
                        publish(currentState().copy(
                            queueCompleted = index + 1,
                            queueTotal = queue.size,
                            queueTotalBytes = totalBytes,
                            queueTransferredBytes = completedBytes
                        ))
                        return@forEachIndexed
                    }
                    val claimed = UltraShareQueueStore.claimForSending(
                        this@UltraShareForegroundService,
                        uri,
                        metadata.first
                    )
                    if (!claimed) {
                        activeUri = null
                        publish(currentState().copy(
                            queueCompleted = index + 1,
                            queueTotal = queue.size,
                            queueTotalBytes = totalBytes,
                            queueTransferredBytes = completedBytes
                        ))
                        return@forEachIndexed
                    }
                    publish(currentState().copy(queueCurrentName = metadata.first, queueCompleted = index, queueTotal = queue.size, progress = null))
                    try {
                        sendWithRetry(uri, host, secret, metadata) { progress ->
                            acquireTransferLocks()
                            publish(currentState().copy(
                                progress = progress,
                                queueCurrentName = metadata.first,
                                queueTransferredBytes = minOf(totalBytes, completedBytes + progress.transferredBytes)
                            ))
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
                        releaseTransferLocks()
                    }
                    activeUri = null
                    publish(currentState().copy(
                        queueCompleted = index + 1,
                        queueTotal = queue.size,
                        progress = null,
                        queueTotalBytes = totalBytes,
                        queueTransferredBytes = completedBytes
                    ))
                }
                publish(currentState().copy(sending = false, status = "انتهى الطابور: أُرسل " + sent + " من " + queue.size + " ملفات", error = if (failed > 0) "تعذر إرسال " + failed + " ملفات. آخر خطأ: " + lastError else null))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    activeUri?.let { uri ->
                        UltraShareQueueStore.update(this@UltraShareForegroundService, uri, status = UltraShareQueueStore.STATUS_PENDING, error = "أُوقف النقل؛ يمكن استئنافه.")
                    }
                }
                publish(currentState().copy(
                    sending = false,
                    progress = null,
                    queueTransferredBytes = completedBytes,
                    status = if (pauseRequested)
                        "أُوقف الطابور مؤقتاً بعد إكمال " + sent + " ملفات؛ يمكنك استئناف المتبقي."
                    else "أُوقف طابور الإرسال بعد إكمال " + sent + " ملفات"
                ))
                throw cancelled
            } finally {
                transferJob = null
                pauseRequested = false
                publish(currentState().copy(sending = false, progress = null, queueCurrentName = null))
                finishService()
            }
        }
        transferJob = job
        job.start()
    }
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
                    onProgress = onProgress
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (retry >= 2) throw failure
                retry++
                publish(currentState().copy(
                    status = "انقطع الاتصال؛ إعادة المحاولة تلقائياً " + retry + " من 2"
                ))
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
        return Triple(name, contentResolver.getType(uri) ?: "application/octet-stream", size)
    }

    private fun findLocalIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    (name.startsWith("wlan") || name.startsWith("swlan") ||
                        name.startsWith("ap") || name.startsWith("eth") || name.startsWith("p2p"))
            }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && (it.isSiteLocalAddress || it.isLinkLocalAddress) }
                    .map { network.name.lowercase() to it }
            }
            .sortedWith(compareBy<Pair<String, Inet4Address>>(
                { (name, _) ->
                    when {
                        name.startsWith("p2p") -> 0
                        name.startsWith("ap") -> 1
                        name.startsWith("wlan") || name.startsWith("swlan") -> 2
                        else -> 3
                    }
                },
                { (_, address) -> address.hostAddress.orEmpty() }
            ))
            .mapNotNull { (_, address) -> address.hostAddress }
            .distinct()
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
        const val ACTION_STOP = "com.malekplay.ultrashare.STOP"
        const val ACTION_CANCEL = "com.malekplay.ultrashare.CANCEL"
        const val ACTION_PAUSE = "com.malekplay.ultrashare.PAUSE"
        const val ACTION_REFRESH_ADDRESSES = "com.malekplay.ultrashare.REFRESH_ADDRESSES"
        const val ACTION_RECEIVE = "com.malekplay.ultrashare.RECEIVE"
        const val ACTION_SEND = "com.malekplay.ultrashare.SEND"
        const val ACTION_SEND_BATCH = "com.malekplay.ultrashare.SEND_BATCH"
        const val ACTION_RETRY_FILE = "com.malekplay.ultrashare.RETRY_FILE"

        private val mutableState = MutableStateFlow(UltraShareServiceState())
        val state = mutableState.asStateFlow()

        fun startReceiving(context: Context, secret: String) {
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_RECEIVE)
                .putExtra(EXTRA_SECRET, secret)
            start(context, intent)
        }

        fun startSending(context: Context, uri: Uri, host: String, secret: String) {
            // Route one-file sends through the same durable queue as multi-file sends.
            // This keeps interruption recovery and retry behavior consistent.
            startSendingBatch(context, listOf(uri), host, secret)
        }

        fun startSendingBatch(context: Context, uris: List<Uri>, host: String, secret: String) {
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_SEND_BATCH)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_SECRET, secret)
            start(context, intent)
        }

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

        fun stop(context: Context) {
            context.startService(Intent(context, UltraShareForegroundService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
