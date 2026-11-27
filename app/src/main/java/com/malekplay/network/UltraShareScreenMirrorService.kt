package com.malekplay.network

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.malekplay.MainActivity
import com.malekplay.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID

/**
 * Dedicated `mediaProjection` (and optional `microphone`) Foreground Service owning the host's
 * screen-mirroring lifecycle.
 *
 * Kept intentionally separate from [UltraShareForegroundService] (`dataSync`) so:
 * - Android 14+ (API 34+) strict `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` rules are met
 *   (foreground promotion occurs strictly with an active MediaProjection consent token, and
 *   `MediaProjection.Callback` is registered before `createVirtualDisplay`).
 * - File transfers, QR pairing, and Android 15 `dataSync` limits remain completely unaffected.
 */
class UltraShareScreenMirrorService : Service() {

    data class MirrorHostState(
        val active: Boolean = false,
        val starting: Boolean = false,
        val sessionId: String = "",
        val streamPort: Int = 0,
        val viewerCount: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val bitrateBps: Int = 0,
        val audioRequested: Boolean = false,
        val audioActive: Boolean = false,
        val audioMode: UltraShareScreenMirrorPolicy.MirrorAudioMode =
            UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
        val audioNote: String? = null,
        val firstFrameEncoded: Boolean = false,
        val error: String? = null,
        val statusMessage: String? = null
    )

    private var broadcasterHub: ScreenMirrorBroadcasterHub? = null
    private var screenCapturer: AndroidScreenMirrorCapturer? = null
    private var mediaProjection: MediaProjection? = null

    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeStreamKey: ByteArray? = null
    @Volatile private var activeConfig: UltraShareScreenMirrorProtocol.StreamConfig? = null
    @Volatile private var activeRevision: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MIRRORING -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val wireAudioMode = intent.getStringExtra(EXTRA_AUDIO_MODE)
                val requestedAudioMode = if (wireAudioMode != null) {
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.fromWireId(wireAudioMode)
                } else if (intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, false)) {
                    if (UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(Build.VERSION.SDK_INT)) {
                        UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO
                    } else {
                        UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
                    }
                } else {
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
                }

                if (resultCode != Activity.RESULT_OK || dataIntent == null) {
                    mutableHostState.value = MirrorHostState(
                        active = false,
                        error = "لم يتم منح إذن التقاط الشاشة من النظام."
                    )
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val wantMicFgs = requestedAudioMode == UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE &&
                    UltraShareScreenMirrorPolicy.hasMicrophoneCapturePermission(this)
                val micFgsPermitted = startForegroundForMediaProjection(includeMicrophone = wantMicFgs)
                val safeRequestedMode = if (
                    requestedAudioMode == UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE &&
                    wantMicFgs && !micFgsPermitted
                ) {
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
                } else {
                    requestedAudioMode
                }
                startMirroringSession(
                    resultCode = resultCode,
                    resultData = dataIntent,
                    requestedAudioMode = safeRequestedMode,
                    fgsMicFallbackNote = if (wantMicFgs && !micFgsPermitted) {
                        "منع النظام تفعيل الميكروفون في الخلفية؛ يستمر بث الشاشة بالفيديو فقط."
                    } else null
                )
            }
            ACTION_STOP_MIRRORING -> {
                stopMirroringSession(reason = "أوقفت مشاركة الشاشة الجماعية.")
                stopSelf()
            }
            else -> {
                startForegroundForMediaProjection(includeMicrophone = false)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Promotes the service to Foreground before `getMediaProjection(...)` / `createVirtualDisplay(...)`.
     * Returns `true` if `includeMicrophone` was requested and accepted, or `false` if Android 14+
     * rejected `FOREGROUND_SERVICE_TYPE_MICROPHONE` and we fell back cleanly to `MEDIA_PROJECTION`.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private fun startForegroundForMediaProjection(includeMicrophone: Boolean): Boolean {
        ensureNotificationChannel()
        val notification = buildNotification("جارٍ بث شاشة الهاتف للمجموعة…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val desiredType = UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(
                sdkInt = Build.VERSION.SDK_INT,
                includeMicrophone = includeMicrophone
            )
            return try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, desiredType)
                includeMicrophone
            } catch (sec: SecurityException) {
                if (includeMicrophone) {
                    UltraShareDiagnostics.failure("SCREEN_MIRROR_MIC_FGS_FALLBACK", sec)
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                    false
                } else {
                    throw sec
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
            return includeMicrophone
        }
    }

    private fun startMirroringSession(
        resultCode: Int,
        resultData: Intent,
        requestedAudioMode: UltraShareScreenMirrorPolicy.MirrorAudioMode,
        fgsMicFallbackNote: String?
    ) {
        stopMirroringSessionInternal(notifyStop = false)
        val audioRequested = requestedAudioMode != UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
        mutableHostState.value = MirrorHostState(
            active = false,
            starting = true,
            audioRequested = audioRequested,
            audioNote = fgsMicFallbackNote,
            statusMessage = "تم منح إذن التقاط الشاشة؛ جارٍ تهيئة المشفر والبث…"
        )
        try {
            val hostPeerId = UltraShareScreenMirrorProtocol.canonicalId(
                UltraShareDiscovery.localPeerId(this)
            ) ?: error("تعذر تحديد هوية الجهاز للبث الجماعي.")
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, resultData)
                ?: error("تعذر إنشاء جلسة MediaProjection من النظام.")
            mediaProjection = projection

            val sessionId = UltraShareScreenMirrorProtocol.canonicalId(UUID.randomUUID().toString())
                ?: error("Invalid session ID")
            val streamKey = ByteArray(UltraShareScreenMirrorProtocol.STREAM_KEY_BYTES)
                .also { SecureRandom().nextBytes(it) }
            activeSessionId = sessionId
            activeStreamKey = streamKey
            activeRevision = 1L

            val hub = ScreenMirrorBroadcasterHub(
                sessionId = sessionId,
                hostPeerId = hostPeerId,
                streamKey = streamKey,
                authorizedViewerIdsProvider = { authorizedViewersProvider() },
                onViewerCountChanged = { count ->
                    mutableHostState.value = mutableHostState.value.copy(viewerCount = count)
                    updateNotificationText(
                        if (count > 0) "الشاشة الجماعية نشطة • $count مشاهد متصل"
                        else "الشاشة الجماعية نشطة • بانتظار المشاهدين"
                    )
                },
                onKeyframeRequested = {
                    screenCapturer?.requestKeyframe()
                }
            )
            val boundPort = hub.start(UltraShareScreenMirrorPolicy.DEFAULT_STREAM_PORT)
            broadcasterHub = hub

            val capturer = AndroidScreenMirrorCapturer(
                context = this,
                mediaProjection = projection,
                hub = hub,
                requestedAudioMode = requestedAudioMode,
                onConfigChanged = { cfg ->
                    activeConfig = cfg
                    val rev = ++activeRevision
                    val prev = mutableHostState.value
                    val note = prev.audioNote ?: fgsMicFallbackNote
                    mutableHostState.value = prev.copy(
                        active = true,
                        starting = false,
                        sessionId = sessionId,
                        streamPort = boundPort,
                        width = cfg.width,
                        height = cfg.height,
                        fps = cfg.fps,
                        bitrateBps = cfg.bitrateBps,
                        audioActive = cfg.audioEnabled,
                        error = null,
                        statusMessage = when {
                            !note.isNullOrBlank() -> note
                            prev.firstFrameEncoded ->
                                "الشاشة الجماعية تبث الآن — يمكن فتح أي تطبيق آخر مع استمرار البث"
                            else ->
                                "بدأ التقاط الشاشة؛ جارٍ إرسال أول إطار للمجموعة…"
                        }
                    )
                    buildOfferControlMessage(
                        type = if (rev <= 2L) {
                            UltraShareScreenMirrorProtocol.ControlType.MIRROR_START
                        } else {
                            UltraShareScreenMirrorProtocol.ControlType.MIRROR_UPDATE
                        },
                        hostPeerId = hostPeerId
                    )?.let { bytes -> onControlBroadcastRequired?.invoke(bytes) }
                },
                onProjectionStopped = { reason ->
                    stopMirroringSession(reason ?: "توقف بث الشاشة.")
                    stopSelf()
                },
                onFirstVideoFrameEncoded = {
                    val prev = mutableHostState.value
                    if (prev.sessionId == sessionId) {
                        mutableHostState.value = prev.copy(
                            firstFrameEncoded = true,
                            statusMessage = prev.audioNote
                                ?: "الشاشة الجماعية تبث الآن — يمكن فتح أي تطبيق آخر مع استمرار البث"
                        )
                    }
                },
                onAudioStatusNote = { activeMode, note ->
                    val prev = mutableHostState.value
                    mutableHostState.value = prev.copy(
                        audioMode = activeMode,
                        audioActive = activeMode != UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                        audioNote = note ?: fgsMicFallbackNote
                    )
                    updateNotificationText(
                        "${UltraShareScreenMirrorPolicy.audioModeLabelAr(activeMode)} • المشاهدون: ${prev.connectedViewers.size}"
                    )
                }
            )
            screenCapturer = capturer
            activeInstance = this
            capturer.start()
        } catch (failure: Exception) {
            UltraShareDiagnostics.failure("SCREEN_MIRROR_START", failure)
            stopMirroringSessionInternal(notifyStop = false)
            mutableHostState.value = MirrorHostState(
                active = false,
                starting = false,
                error = failure.localizedMessage ?: "تعذر بدء مشاركة الشاشة."
            )
            stopSelf()
        }
    }

    private fun buildOfferControlMessage(
        type: UltraShareScreenMirrorProtocol.ControlType,
        hostPeerId: String
    ): ByteArray? {
        val sid = activeSessionId ?: return null
        val key = activeStreamKey ?: return null
        val cfg = activeConfig ?: return null
        val port = broadcasterHub?.boundPort?.takeIf { it in 1024..65535 } ?: return null
        return runCatching {
            UltraShareScreenMirrorProtocol.encodeControl(
                UltraShareScreenMirrorProtocol.ControlMessage(
                    type = type,
                    sessionId = sid,
                    senderPeerId = hostPeerId,
                    revision = activeRevision.coerceAtLeast(1L),
                    streamPort = port,
                    streamKey = key,
                    config = cfg
                )
            )
        }.getOrNull()
    }

    private fun stopMirroringSession(reason: String?) {
        stopMirroringSessionInternal(notifyStop = true)
        mutableHostState.value = MirrorHostState(
            active = false,
            starting = false,
            statusMessage = reason
        )
    }

    private fun stopMirroringSessionInternal(notifyStop: Boolean) {
        if (activeInstance === this) activeInstance = null
        val sid = activeSessionId
        val hostId = UltraShareScreenMirrorProtocol.canonicalId(
            UltraShareDiscovery.localPeerId(this)
        )
        if (notifyStop && sid != null && hostId != null) {
            val stopBytes = runCatching {
                UltraShareScreenMirrorProtocol.encodeControl(
                    UltraShareScreenMirrorProtocol.ControlMessage(
                        type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_STOP,
                        sessionId = sid,
                        senderPeerId = hostId,
                        revision = ++activeRevision
                    )
                )
            }.getOrNull()
            if (stopBytes != null) {
                runCatching { onControlBroadcastRequired?.invoke(stopBytes) }
            }
        }
        runCatching { screenCapturer?.stop() }
        screenCapturer = null
        runCatching { broadcasterHub?.close() }
        broadcasterHub = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        activeSessionId = null
        activeConfig = null
        activeStreamKey?.fill(0)
        activeStreamKey = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMirroringSessionInternal(notifyStop = true)
        mutableHostState.value = MirrorHostState(active = false)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "الشاشة الجماعية (UltraShare)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار دائم أثناء مشاركة شاشة الهاتف مع أجهزة المجموعة"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, UltraShareScreenMirrorService::class.java).setAction(ACTION_STOP_MIRRORING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ultra_share_notification)
            .setContentTitle("مشاركة الشاشة الجماعية نشطة")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "إيقاف المشاركة", stopIntent)
            .build()
    }

    private fun updateNotificationText(text: String) {
        if (activeInstance !== this || activeSessionId == null) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START_MIRRORING = "com.malekplay.network.action.START_SCREEN_MIRROR"
        const val ACTION_STOP_MIRRORING = "com.malekplay.network.action.STOP_SCREEN_MIRROR"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ENABLE_AUDIO = "extra_enable_audio"
        const val EXTRA_AUDIO_MODE = "extra_audio_mode"

        private const val CHANNEL_ID = "ultra_share_screen_mirror"
        private const val NOTIFICATION_ID = 4207

        private val mutableHostState = MutableStateFlow(MirrorHostState())
        val hostState: StateFlow<MirrorHostState> = mutableHostState.asStateFlow()

        @Volatile private var activeInstance: UltraShareScreenMirrorService? = null
        @Volatile var authorizedViewersProvider: () -> Set<String> = { emptySet() }
        @Volatile var onControlBroadcastRequired: ((ByteArray) -> Unit)? = null

        fun startMirroring(
            context: Context,
            resultCode: Int,
            data: Intent,
            audioMode: UltraShareScreenMirrorPolicy.MirrorAudioMode
        ) {
            val intent = Intent(context, UltraShareScreenMirrorService::class.java).apply {
                action = ACTION_START_MIRRORING
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_AUDIO_MODE, audioMode.wireId)
                putExtra(
                    EXTRA_ENABLE_AUDIO,
                    audioMode != UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startMirroring(
            context: Context,
            resultCode: Int,
            data: Intent,
            enableAudio: Boolean
        ) {
            val mode = if (!enableAudio) {
                UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
            } else if (UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(Build.VERSION.SDK_INT)) {
                UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO
            } else {
                UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
            }
            startMirroring(context, resultCode, data, mode)
        }

        fun stopMirroring(context: Context) {
            if (!mutableHostState.value.active && !mutableHostState.value.starting && activeInstance == null) {
                return
            }
            val intent = Intent(context, UltraShareScreenMirrorService::class.java).setAction(ACTION_STOP_MIRRORING)
            runCatching { context.startService(intent) }
        }

        internal fun currentOfferPayloadForHost(hostPeerId: String): ByteArray? =
            activeInstance?.buildOfferControlMessage(
                UltraShareScreenMirrorProtocol.ControlType.MIRROR_START,
                hostPeerId
            )

        internal fun pruneUnauthorizedViewers() {
            activeInstance?.broadcasterHub?.pruneUnauthorizedViewers()
        }

        internal fun disconnectViewer(viewerPeerId: String) {
            activeInstance?.broadcasterHub?.disconnectViewer(viewerPeerId)
        }

        internal fun requestKeyframeFromViewer(viewerPeerId: String) {
            val allowed = authorizedViewersProvider()
            if (viewerPeerId in allowed) {
                activeInstance?.screenCapturer?.requestKeyframe()
            }
        }
    }
}
