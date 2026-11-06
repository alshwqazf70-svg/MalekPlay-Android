package com.malekplay.network

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val queueCurrentName: String? = null
)

/**
 * Owns transfer sockets and coroutine jobs independently from the navigation/ViewModel lifecycle.
 * The service remains active when the Ultra Share screen is removed from the back stack.
 */
class UltraShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null
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
            ACTION_STOP, ACTION_CANCEL -> {
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
                acquireTransferLocks()
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
                        runCatching { UltraShareDiscovery.advertiseWhileReceiving() }
                    }
                    try {
                        while (true) {
                            try {
                                val received = UltraShareTransport.receiveOnce(
                                    pairingSecret = secret,
                                    destinationDirectory = UltraShareTransport.defaultReceiveDirectory(this@UltraShareForegroundService),
                                    onProgress = { progress ->
                                        val nextState = currentState().copy(progress = progress, error = null)
                                        publish(nextState)
                                        updateNotification(progress)
                                    }
                                )
                                publish(
                                    currentState().copy(
                                        receiving = true,
                                        progress = null,
                                        receivedFile = received,
                                        status = getString(R.string.ultra_share_received, received.fileName),
                                        error = null
                                    )
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                // A rejected peer or a dropped connection must not disable the receiver.
                                publish(
                                    currentState().copy(
                                        receiving = true,
                                        progress = null,
                                        error = failure.localizedMessage ?: getString(R.string.ultra_share_transfer_failed)
                                    )
                                )
                                kotlinx.coroutines.delay(500)
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
                acquireTransferLocks()
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
            else -> {
                // A plain start keeps the service alive only long enough to establish its notification.
                startForegroundCompat()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun startSendingQueue(uris: List<Uri>, host: String, secret: String) {
        if (uris.isEmpty() || host.isBlank() || !UltraShareTransport.isValidPrivateLanHost(host) || !UltraShareTransport.isValidPairingSecret(secret)) {
            publish(UltraShareServiceState(error = getString(R.string.ultra_share_transfer_failed)))
            finishService()
            return
        }
        if (transferJob != null) return
        startForegroundCompat()
        acquireTransferLocks()
        publish(UltraShareServiceState(sending = true, queueTotal = uris.size, status = "جارٍ تجهيز " + uris.size + " ملفات للإرسال"))
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var sent = 0
            var failed = 0
            var lastError: String? = null
            try {
                uris.forEachIndexed { index, uri ->
                    val metadata = try { readDocumentMetadata(uri) } catch (failure: Exception) {
                        failed++
                        lastError = failure.localizedMessage ?: "تعذرت قراءة أحد الملفات."
                        publish(currentState().copy(queueCompleted = index + 1, queueTotal = uris.size))
                        return@forEachIndexed
                    }
                    publish(currentState().copy(queueCurrentName = metadata.first, queueCompleted = index, queueTotal = uris.size, progress = null))
                    try {
                        UltraShareTransport.send(resolver = contentResolver, uri = uri, host = host, pairingSecret = secret, fileName = metadata.first, mimeType = metadata.second, sizeBytes = metadata.third, onProgress = { progress ->
                            publish(currentState().copy(progress = progress, queueCurrentName = metadata.first))
                            updateNotification(progress)
                        })
                        sent++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failed++
                        lastError = metadata.first + ": " + (failure.localizedMessage ?: "فشل الإرسال.")
                    }
                    publish(currentState().copy(queueCompleted = index + 1, queueTotal = uris.size, progress = null))
                }
                publish(currentState().copy(sending = false, status = "انتهى الطابور: أُرسل " + sent + " من " + uris.size + " ملفات", error = if (failed > 0) "تعذر إرسال " + failed + " ملفات. آخر خطأ: " + lastError else null))
            } catch (cancelled: CancellationException) {
                publish(currentState().copy(sending = false, progress = null, status = "أُوقف طابور الإرسال بعد إكمال " + sent + " ملفات"))
                throw cancelled
            } finally {
                transferJob = null
                publish(currentState().copy(sending = false, progress = null, queueCurrentName = null))
                finishService()
            }
        }
        transferJob = job
        job.start()
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
                    (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("eth"))
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sorted()
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
        const val ACTION_RECEIVE = "com.malekplay.ultrashare.RECEIVE"
        const val ACTION_SEND = "com.malekplay.ultrashare.SEND"
        const val ACTION_SEND_BATCH = "com.malekplay.ultrashare.SEND_BATCH"

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
