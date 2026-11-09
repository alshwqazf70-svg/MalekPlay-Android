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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.flow.update
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
    val queueTransferredBytes: Long = 0L
)

/**
 * Owns transfer sockets and coroutine jobs independently from the navigation/ViewModel lifecycle.
 * The service remains active when the Ultra Share screen is removed from the back stack.
 */
class UltraShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var receiveJob: Job? = null
    @Volatile private var sendJob: Job? = null
    @Volatile private var pauseRequested = false
    private var advertisementJob: Job? = null
    private var hotspotReservation: AutoCloseable? = null
    private var hotspotCallback: Any? = null
    private lateinit var notificationManager: NotificationManager
    private val transferLockGuard = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var sendTransferActive = false
    private var receiveTransferActive = false

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
                advertisementJob?.cancel()
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
                if (sendJob == null && receiveJob == null) startForegroundCompat()
                sendJob?.cancel()
                receiveJob?.cancel()
                advertisementJob?.cancel()
                stopLocalHotspot()
                if (sendJob == null && receiveJob == null) finishService()
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
                startForegroundCompat()
                val addresses = findLocalIpv4Addresses()
                updateState { current -> current.copy(
                        pairingSecret = secret,
                        localAddresses = addresses,
                        receiving = true,
                        status = getString(R.string.ultra_share_waiting)
                    ) }
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    advertisementJob = launch {
                        try {
                            UltraShareDiscovery.advertiseWhileReceiving()
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
                        receiveJob = null
                        updateState { current -> current.copy(receiving = false, pairingSecret = "", localAddresses = emptyList(), progress = if (current.sending) current.sendProgress else null, receiveProgress = null) }
                        finishService()
                    }
                }
                receiveJob = job
                job.start()
            }
            ACTION_SEND -> {
                val uri = intent.parcelableUri<Uri>(EXTRA_URI)
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
                if (uri == null || host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
                    updateState { current -> current.copy(error = getString(R.string.ultra_share_transfer_failed)) }
                    finishService()
                    return START_NOT_STICKY
                }
                if (sendJob != null) return START_NOT_STICKY
                startForegroundCompat()
                updateState { current -> current.copy(sending = true, status = "جارٍ تجهيز الملف وفحص سلامته قبل الإرسال...", error = null) }
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val metadata = readDocumentMetadata(uri)
                        // Acquire the CPU/Wi-Fi locks before hashing the source file or
                        // opening the socket; the first progress callback may arrive minutes later.
                        beginTransfer(isReceiving = false)
                        sendWithRetry(uri, host, secret, metadata) { progress ->
                            updateState { current -> current.copy(progress = progress, sendProgress = progress) }
                            updateNotification(progress)
                        }
                        updateState { current -> current.copy(sending = false, progress = if (current.receiving) current.receiveProgress else null, sendProgress = null, status = getString(R.string.ultra_share_sent, metadata.first), error = null) }
                    } catch (cancelled: CancellationException) {
                        updateState { current -> current.copy(sending = false, progress = if (current.receiving) current.receiveProgress else null, sendProgress = null, status = getString(R.string.ultra_share_cancel_transfer)) }
                        throw cancelled
                    } catch (failure: Exception) {
                        updateState { current -> current.copy(sending = false, progress = if (current.receiving) current.receiveProgress else null, sendProgress = null, error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)) }
                    } finally {
                        endTransfer(isReceiving = false)
                        sendJob = null
                        updateState { current -> current.copy(sending = false, progress = if (current.receiving) current.receiveProgress else null, sendProgress = null) }
                        finishService()
                    }
                }
                sendJob = job
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
                error = "أوقف Android خدمة النقل بعد بلوغ الحد الزمني المسموح. يمكنك بدء النقل مجدداً."
            ) }
        sendJob?.cancel()
        receiveJob?.cancel()
        advertisementJob?.cancel()
        releaseTransferLocks()
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
        sendJob?.cancel()
        receiveJob?.cancel()
        advertisementJob?.cancel()
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
                hotspotPassword = null
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
        if (sendJob != null || receiveJob != null || hotspotReservation != null || currentState().hotspotStarting) return
        releaseTransferLocks()
        stopForeground(true)
        stopSelf()
    }

    private fun beginTransfer(isReceiving: Boolean) {
        synchronized(transferLockGuard) {
            if (isReceiving) receiveTransferActive = true else sendTransferActive = true
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
            if (isReceiving) receiveTransferActive = false else sendTransferActive = false
            if (!sendTransferActive && !receiveTransferActive) releaseTransferLocksLocked()
        }
    }

    private fun releaseTransferLocks() {
        synchronized(transferLockGuard) { releaseTransferLocksLocked() }
    }

    private fun releaseTransferLocksLocked() {
        sendTransferActive = false
        receiveTransferActive = false
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
        replaceQueue: Boolean = true
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
        const val ACTION_CANCEL_SEND = "com.malekplay.ultrashare.CANCEL_SEND"
        const val ACTION_STOP_RECEIVING = "com.malekplay.ultrashare.STOP_RECEIVING"
        const val ACTION_START_HOTSPOT = "com.malekplay.ultrashare.START_HOTSPOT"
        const val ACTION_STOP_HOTSPOT = "com.malekplay.ultrashare.STOP_HOTSPOT"
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
            val intent = Intent(context, UltraShareForegroundService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_SECRET, secret)
            start(context, intent)
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
