package com.malekplay.player

import android.app.ActivityManager
import android.app.PendingIntent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import com.malekplay.MainActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import com.malekplay.player.cast.CastMediaHttpServer
import com.malekplay.player.cast.LocalMediaBridgeRegistry
import com.malekplay.player.cast.WebCastRegistry
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.effect.LanczosResample
import com.malekplay.player.effects.VideoEnhancementProfile
import com.malekplay.player.effects.VideoEnhancementPolicy
import com.malekplay.player.effects.VideoEnhancementStage
import com.malekplay.player.effects.VideoEnhancementMetrics
import com.malekplay.player.effects.VideoEnhancementPipelineEffect
import com.malekplay.player.effects.VideoEnhancementPipelineConfig
import com.malekplay.player.effects.VideoUpscaleTargetPolicy
import com.malekplay.player.effects.VideoUpscaleTarget
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.render.AssVideoEffectRegistry
import io.github.peerless2012.ass.media.type.AssRenderType
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaSessionService
import com.malekplay.player.audio.AudioEffectsController
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.NetworkProfileStore
import com.malekplay.data.repository.MediaRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import com.malekplay.network.NetworkProtocol
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.LinkedHashMap
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Owns the single ExoPlayer instance and its MediaSession.
 *
 * Keeping playback in a service means audio survives Activity rotation, the
 * Recents swipe and leaving the app entirely — the same model MX Player uses.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var localExoPlayer: ExoPlayer? = null
    private var assHandler: AssHandler? = null
    private var videoUpscalingEnabled = false
    private var videoNoiseReductionEnabled = false
    private var videoVibranceEnabled = false
    private var videoAiBoostEnabled = false
    private var videoEnhancementBypassed = false
    private var videoCustomTuning = false
    private var customDenoiseStrength = VideoEnhancementProfile.BALANCED.denoiseStrength
    private var customDetailStrength = VideoEnhancementProfile.BALANCED.detailStrength
    private var customContrastBoost = VideoEnhancementProfile.BALANCED.contrastBoost
    private var customVibranceStrength = VideoEnhancementProfile.BALANCED.vibranceStrength
    private var lastEffectiveProfile = VideoEnhancementProfile.BALANCED
    private var lastDowngradeReason: String? = null
    // Denoise runs on decoded source pixels; detail enhancement runs after optional upscaling.
    private var videoDenoisePipeline: VideoEnhancementPipelineEffect? = null
    private var videoEnhancementPipeline: VideoEnhancementPipelineEffect? = null
    private var videoEnhancementPipelineAttached = false
    @Volatile private var videoEffectsBridgeAttached = false
    @Volatile private var videoLanczosSubmitted = false
    private var appliedLanczosEffect = false
    private var appliedLanczosTarget: Pair<Int, Int>? = null
    private var lastAppliedEnhancementSignature: String? = null
    private var lastObservedVideoTrackSignature: String? = null
    @Volatile private var lastRendererFrameCount = 0L
    @Volatile private var lastAverageProcessingOffsetUs = 0L
    @Volatile private var droppedVideoFramesTotal = 0L
    private var videoEnhancementProfile = VideoEnhancementProfile.BALANCED
    private var performanceQualityCap: VideoEnhancementProfile? = null
    private var thermalPowerManager: PowerManager? = null
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var castMediaHttpServer: CastMediaHttpServer? = null
    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private lateinit var profileStore: NetworkProfileStore
    private lateinit var credentialVault: NetworkCredentialVault
    private lateinit var networkProfilesPreferences: SharedPreferences
    @Volatile private var cachedWebDavProfiles: List<com.malekplay.network.NetworkProfile>? = null
    private val profilePreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "profiles") cachedWebDavProfiles = null
    }

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var audioEffectsController: AudioEffectsController

    // ExoPlayer is application-looper confined; read its state on Main and let
    // MediaRepository switch to IO for database/preferences work.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    // Keep the newest queued checkpoint so service shutdown can drain pending writes.
    private var lastProgressSaveJob: Job? = null
    private var lastCountedMediaId: String? = null
    private val playbackMetadataById = LinkedHashMap<String, PlaybackMetadata>(MAX_CACHED_PLAYBACK_METADATA, 0.75f, true)
    private val progressSaveMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        // Once playback is paused/stopped, immediately leave foreground mode so
        // Android allows the user to swipe the media notification away. Active
        // playback remains foreground and keeps its notification as expected.
        setForegroundServiceTimeoutMs(0L)
        setShowNotificationForIdlePlayer(
            MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER
        )
        profileStore = NetworkProfileStore(this)
        credentialVault = NetworkCredentialVault(this)
        networkProfilesPreferences = getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
        networkProfilesPreferences.registerOnSharedPreferenceChangeListener(profilePreferencesListener)
        val sharedPlaybackDataSourceFactory = buildDataSourceFactory()

        // Install the software ten-band PCM equalizer in the real Media3 audio path.
        // Float output is disabled intentionally because the processor currently
        // guarantees lossless pass-through only for decoded PCM16 samples.
        // buildWithAssSupport wraps this factory, preserving ASS/SSA rendering.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                    .setAudioProcessors(arrayOf(audioEffectsController.softwareEqualizerProcessor))
                    .build()
            }
        }.setEnableDecoderFallback(true)

        registerAudioOutputObserver()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .buildWithAssSupport(
                context = this,
                renderType = AssRenderType.EFFECTS_OPEN_GL,
                dataSourceFactory = sharedPlaybackDataSourceFactory,
                renderersFactory = renderersFactory,
                assHandlerCallback = { handler -> assHandler = handler }
            )

        // CastPlayer wraps the existing ASS-capable ExoPlayer. MediaController,
        // notification controls and the video UI now share one local/remote Player.
        // The bridge starts lazily only when a local file is handed to a Cast receiver.
        // It exposes tokenized, seekable LAN URLs rather than leaking content:// URIs.
        localExoPlayer = player
        // Create the mutable GPU shader holders lazily in memory only. Do not
        // attach them to ExoPlayer until the user manually enables video
        // enhancement so normal playback keeps the zero-copy hardware surface
        // path and does not consume GPU or battery.
        videoDenoisePipeline = VideoEnhancementPipelineEffect()
        videoEnhancementPipeline = VideoEnhancementPipelineEffect()
        videoEnhancementPipelineAttached = false
        videoEffectsBridgeAttached = false

        // Bind audio effects directly to the service-owned ExoPlayer session;
        // the UI listener is a secondary observer, not the only attachment path.
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                audioEffectsController.attach(audioSessionId)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Media3 routes VideoFrameProcessor initialization/draw failures through
                // Player.Listener after PlaybackVideoGraphWrapper -> VideoSink. Capture only
                // these GPU-path failures so diagnostics do not mislabel unrelated playback
                // or decoder errors as enhancement failures.
                if (error.errorCode ==
                    androidx.media3.common.PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                    error.errorCode ==
                    androidx.media3.common.PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                ) {
                    VideoEnhancementMetrics.recordFailure(
                        "media3_video_frame_processing",
                        error
                    )
                    Log.e(TAG, "Media3 video frame processing failed", error)
                }
            }
        })
        // Re-evaluate GPU quality whenever Android reports a thermal-state change.
        // Android 6-9 simply use the static low-RAM / memory-class safeguards.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java)?.let { powerManager ->
                val listener = PowerManager.OnThermalStatusChangedListener {
                    if (hasAnyVideoEnhancementEnabled()) applyVideoEnhancements()
                }
                thermalPowerManager = powerManager
                thermalStatusListener = listener
                powerManager.addThermalStatusListener(
                    androidx.core.content.ContextCompat.getMainExecutor(this),
                    listener
                )
            }
        }
        val mediaBridge = CastMediaHttpServer(this, sharedPlaybackDataSourceFactory)
        castMediaHttpServer = mediaBridge
        LocalMediaBridgeRegistry.attach(mediaBridge)
        WebCastRegistry.attachServiceFactory(this, sharedPlaybackDataSourceFactory)
        val remotePlayer = RemoteCastPlayer.Builder(this)
            .setMediaItemConverter(CastMediaHttpServer.BridgeMediaItemConverter(mediaBridge))
            .build()
        val castPlayer = CastPlayer.Builder(this)
            .setLocalPlayer(player)
            .setRemotePlayer(remotePlayer)
            .build()

        castPlayer.addListener(progressListener)
        castPlayer.addListener(videoEffectRouteListener)
        player.addListener(localVideoEffectListener)
        player.addAnalyticsListener(enhancementAnalyticsListener)
        mediaSession = MediaSession.Builder(this, castPlayer)
            .setCallback(videoEffectSessionCallback)
            .setSessionActivity(buildSessionActivityIntent())
            .build()
    }

    /** Tracks connected output types so the equalizer UI can explain route changes. */
    private fun registerAudioOutputObserver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                publishAvailableAudioOutputs(manager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                publishAvailableAudioOutputs(manager)
            }
        }
        audioDeviceCallback = callback
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        publishAvailableAudioOutputs(manager)
    }

    private fun publishAvailableAudioOutputs(manager: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val labels = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "سماعة الهاتف"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "سماعة سلكية"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                else -> null
            }
        }.distinct()
        // AudioManager reports connected/available outputs, not necessarily the
        // currently selected route. The UI deliberately labels this as availability.
        audioEffectsController.updateOutputRoute(
            labels.takeIf { it.isNotEmpty() }?.joinToString(" • ")
                ?: "لا توجد مخارج صوت معروفة"
        )
    }

    private fun unregisterAudioOutputObserver() {
        val manager = audioManager
        val callback = audioDeviceCallback
        if (manager != null && callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { manager.unregisterAudioDeviceCallback(callback) }
        }
        audioDeviceCallback = null
        audioManager = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        progressJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatusListener?.let { listener ->
                runCatching { thermalPowerManager?.removeThermalStatusListener(listener) }
            }
        }
        thermalStatusListener = null
        thermalPowerManager = null
        val finalSave = saveCurrentProgress() ?: lastProgressSaveJob
        assHandler?.release()
        assHandler = null
        mediaSession?.run {
            player.removeListener(progressListener)
            player.removeListener(videoEffectRouteListener)
            localExoPlayer?.removeListener(localVideoEffectListener)
            localExoPlayer?.removeAnalyticsListener(enhancementAnalyticsListener)
            localExoPlayer?.let(AssVideoEffectRegistry::detach)
            player.release()
            release()
        }
        mediaSession = null
        localExoPlayer = null
        disableAllVideoEnhancements()
        castMediaHttpServer?.let { bridge ->
            LocalMediaBridgeRegistry.detach(bridge)
            bridge.close()
        }
        castMediaHttpServer = null
        com.malekplay.player.cast.WebCastRegistry.detachServiceFactory(applicationContext)
        if (::networkProfilesPreferences.isInitialized) {
            networkProfilesPreferences.unregisterOnSharedPreferenceChangeListener(profilePreferencesListener)
        }
        cachedWebDavProfiles = null
        unregisterAudioOutputObserver()
        // Audio effects share the application's active audio session. Their lifetime
        // follows the playback service, not the transient player screen ViewModel.
        audioEffectsController.release()
        if (finalSave == null) serviceScope.cancel()
        else finalSave.invokeOnCompletion { serviceScope.cancel() }
        super.onDestroy()
    }


    /**
     * Media3 may classify an in-process MediaController differently across Android
     * versions. The controller package identity is authenticated by the platform,
     * so explicitly allow this app's own controller while keeping external clients
     * behind Media3's trusted-controller policy.
     */
    private fun isTrustedAppController(controller: MediaSession.ControllerInfo): Boolean =
        controller.isTrusted || controller.packageName == packageName

    private val videoEffectSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Keep trusted and external controllers on their respective Media3 command
            // sets. In particular, only this app's trusted controller receives the video
            // surface-routing command required by the Google AI enhancement bridge.
            val trustedAppController = isTrustedAppController(controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                if (trustedAppController) PlaybackVideoEffects.COMMANDS.forEach { add(it) }
            }.build()
            // Preserve the existing media-control permissions for external controllers,
            // but never let them replace the app's active video output Surface.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .apply {
                    if (!trustedAppController) remove(Player.COMMAND_SET_VIDEO_SURFACE)
                }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (!isTrustedAppController(controller)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            if (customCommand.customAction == PlaybackVideoEffects.ACTION_GET_STATE) {
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS, videoEffectStateBundle())
                )
            }
            val requested = args.getBoolean(PlaybackVideoEffects.KEY_ENABLED, false)
            val accepted = when (customCommand.customAction) {
                PlaybackVideoEffects.ACTION_UPSCALING -> setVideoUpscaling(requested)
                PlaybackVideoEffects.ACTION_NOISE_REDUCTION -> setVideoNoiseReduction(requested)
                PlaybackVideoEffects.ACTION_QUALITY_PROFILE -> setVideoEnhancementProfile(
                    VideoEnhancementProfile.fromWireValue(args.getString(PlaybackVideoEffects.KEY_PROFILE))
                )
                PlaybackVideoEffects.ACTION_INTERNAL_GPU_ENHANCEMENT -> setInternalGpuEnhancement(requested)
                PlaybackVideoEffects.ACTION_VIBRANCE -> setVideoVibrance(requested)
                PlaybackVideoEffects.ACTION_COMPARISON_BYPASS -> setVideoComparisonBypass(requested)
                PlaybackVideoEffects.ACTION_CUSTOM_TUNING -> setCustomVideoEnhancement(
                    customMode = args.getBoolean(PlaybackVideoEffects.KEY_CUSTOM_MODE, true),
                    denoiseStrength = args.getFloat(
                        PlaybackVideoEffects.KEY_DENOISE_STRENGTH,
                        customDenoiseStrength
                    ),
                    detailStrength = args.getFloat(
                        PlaybackVideoEffects.KEY_DETAIL_STRENGTH,
                        customDetailStrength
                    ),
                    contrastBoost = args.getFloat(
                        PlaybackVideoEffects.KEY_CONTRAST_BOOST,
                        customContrastBoost
                    ),
                    vibranceStrength = args.getFloat(
                        PlaybackVideoEffects.KEY_VIBRANCE_STRENGTH,
                        customVibranceStrength
                    )
                )
                else -> false
            }
            return Futures.immediateFuture(
                SessionResult(
                    if (accepted) SessionResult.RESULT_SUCCESS
                    else SessionError.ERROR_NOT_SUPPORTED,
                    videoEffectStateBundle()
                )
            )
        }
    }

    private fun videoEffectStateBundle(): Bundle = Bundle().apply {
        putBoolean(PlaybackVideoEffects.KEY_STATE_UPSCALING, videoUpscalingEnabled)
        putBoolean(PlaybackVideoEffects.KEY_STATE_NOISE_REDUCTION, videoNoiseReductionEnabled)
        putBoolean(PlaybackVideoEffects.KEY_STATE_VIBRANCE, videoVibranceEnabled)
        putBoolean(PlaybackVideoEffects.KEY_STATE_AI_BOOST, videoAiBoostEnabled)
        putBoolean(PlaybackVideoEffects.KEY_STATE_BYPASSED, videoEnhancementBypassed)
        putBoolean(PlaybackVideoEffects.KEY_STATE_CUSTOM_MODE, videoCustomTuning)
        putString(PlaybackVideoEffects.KEY_STATE_PROFILE, videoEnhancementProfile.wireValue)
        putString(PlaybackVideoEffects.KEY_STATE_EFFECTIVE_PROFILE, lastEffectiveProfile.wireValue)
        putString(PlaybackVideoEffects.KEY_STATE_DOWNGRADE_REASON, lastDowngradeReason)
        putFloat(PlaybackVideoEffects.KEY_STATE_DETAIL_STRENGTH, if (videoCustomTuning) customDetailStrength else lastEffectiveProfile.detailStrength)
        putFloat(PlaybackVideoEffects.KEY_STATE_DENOISE_STRENGTH, if (videoCustomTuning) customDenoiseStrength else lastEffectiveProfile.denoiseStrength)
        putFloat(PlaybackVideoEffects.KEY_STATE_CONTRAST_BOOST, if (videoCustomTuning) customContrastBoost else lastEffectiveProfile.contrastBoost)
        putFloat(PlaybackVideoEffects.KEY_STATE_VIBRANCE_STRENGTH, if (videoCustomTuning) customVibranceStrength else lastEffectiveProfile.vibranceStrength)
        // Read the live registry state; only report the enhancement bridge as
        // attached when enhancement effects are actually installed by the user.
        val registryAttached = localExoPlayer?.let(AssVideoEffectRegistry::isAttached) == true
        videoEffectsBridgeAttached = videoEnhancementPipelineAttached && registryAttached
        val bridgeFailure = localExoPlayer?.let(AssVideoEffectRegistry::lastFailure)
        if (bridgeFailure != null &&
            VideoEnhancementMetrics.snapshot().lastFailure?.contains(bridgeFailure) != true
        ) {
            VideoEnhancementMetrics.recordFailure("ass_overlay_manager", IllegalStateException(bridgeFailure))
        }
        val metrics = VideoEnhancementMetrics.snapshot()
        putBoolean(PlaybackVideoEffects.KEY_DIAG_UPSCALE_SUBMITTED, videoLanczosSubmitted && videoEffectsBridgeAttached)
        putLong(PlaybackVideoEffects.KEY_DIAG_DENOISE_DRAWS, metrics.denoiseDraws)
        putLong(PlaybackVideoEffects.KEY_DIAG_DETAIL_DRAWS, metrics.detailDraws)
        putLong(PlaybackVideoEffects.KEY_DIAG_TOTAL_DRAWS, metrics.processedDraws)
        putString(PlaybackVideoEffects.KEY_DIAG_LAST_EFFECT, metrics.lastEffectName)
        putString(
            PlaybackVideoEffects.KEY_DIAG_LAST_FAILURE,
            // Report a bridge failure only when enhancement was requested or ASS failed.
            bridgeFailure ?: metrics.lastFailure ?: if (hasAnyVideoEnhancementEnabled() && !videoEffectsBridgeAttached) {
                "effects_bridge_not_registered_or_not_operational"
            } else null
        )
        putLong(PlaybackVideoEffects.KEY_DIAG_LAST_PTS_US, metrics.lastPresentationTimeUs)
        putLong(PlaybackVideoEffects.KEY_DIAG_RENDERER_FRAMES, lastRendererFrameCount)
        putLong(PlaybackVideoEffects.KEY_DIAG_AVG_OFFSET_US, lastAverageProcessingOffsetUs)
        putLong(PlaybackVideoEffects.KEY_DIAG_DROPPED_FRAMES, droppedVideoFramesTotal)
        putBoolean(PlaybackVideoEffects.KEY_DIAG_BRIDGE_ATTACHED, videoEffectsBridgeAttached)
        putLong(PlaybackVideoEffects.KEY_DIAG_PROGRAM_CREATIONS, metrics.programCreations)
        putLong(PlaybackVideoEffects.KEY_DIAG_CONFIGURE_CALLS, metrics.configureCalls)
        putLong(PlaybackVideoEffects.KEY_DIAG_DRAW_FRAME_CALLS, metrics.drawFrameCalls)
        putLong(PlaybackVideoEffects.KEY_DIAG_DRAW_FRAME_SUCCESSES, metrics.drawFrameSuccesses)
        putInt(PlaybackVideoEffects.KEY_DIAG_CONFIGURED_WIDTH, metrics.lastConfiguredWidth)
        putInt(PlaybackVideoEffects.KEY_DIAG_CONFIGURED_HEIGHT, metrics.lastConfiguredHeight)
        putString(PlaybackVideoEffects.KEY_DIAG_PIPELINE_STAGE, metrics.lastPipelineStage)
        putLong(PlaybackVideoEffects.KEY_DIAG_RENDERED_FIRST_FRAMES, metrics.renderedFirstFrames)
        putString(PlaybackVideoEffects.KEY_DIAG_LAST_RENDERED_OUTPUT, metrics.lastRenderedOutputType)
        putLong(PlaybackVideoEffects.KEY_DIAG_LAST_RENDER_TIME_MS, metrics.lastRenderedFirstFrameTimeMs)
    }

    private val videoEffectRouteListener = object : Player.Listener {
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                disableAllVideoEnhancements()
                localExoPlayer?.let {
                    applyEffectChain(
                        it,
                        emptyList(),
                        hasLanczos = false
                    )
                }
            }
        }
    }

    private val enhancementAnalyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            // Media3 confirms a frame reached the configured renderer output.
            // This is a stronger signal than successful shader submission alone.
            VideoEnhancementMetrics.recordRenderedFirstFrame(output, renderTimeMs)
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int
        ) {
            if (frameCount <= 0 || (!hasAnyVideoEnhancementEnabled() && !videoEnhancementPipelineAttached)) return
            val gpu = VideoEnhancementMetrics.snapshot()
            val averageOffsetUs = totalProcessingOffsetUs / frameCount
            lastRendererFrameCount += frameCount.toLong()
            lastAverageProcessingOffsetUs = averageOffsetUs
            Log.d(
                TAG,
                "Video processing sample: rendererFrames=" + frameCount +
                    ", avgOffsetUs=" + averageOffsetUs +
                    ", gpuDraws=" + gpu.processedDraws +
                    ", lastGpuEffect=" + gpu.lastEffectName +
                    ", lastPtsUs=" + gpu.lastPresentationTimeUs +
                    ", shaderFailure=" + (gpu.lastFailure ?: "none")
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            if (!hasAnyVideoEnhancementEnabled() || droppedFrames <= 0) return
            droppedVideoFramesTotal += droppedFrames.toLong()
            // Ignore isolated frame drops; react only to a sustained/burst regression.
            if ((droppedFrames >= 3 && elapsedMs <= 1_500L) ||
                (droppedFrames >= 8 && elapsedMs <= 5_000L)
            ) {
                val suggestedCap = if (droppedFrames >= 8) {
                    VideoEnhancementProfile.ECONOMY
                } else {
                    VideoEnhancementProfile.BALANCED
                }
                val currentCap = performanceQualityCap
                if (currentCap == null || suggestedCap.ordinal < currentCap.ordinal) {
                    performanceQualityCap = suggestedCap
                    Log.w(TAG, "Video effects capped after dropped-frame burst: $droppedFrames")
                    applyVideoEnhancements()
                }
            }
        }
    }

    private val localVideoEffectListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            performanceQualityCap = null
            lastObservedVideoTrackSignature = null
            VideoEnhancementMetrics.resetPlaybackSession()
            lastRendererFrameCount = 0L
            lastAverageProcessingOffsetUs = 0L
            droppedVideoFramesTotal = 0L
            // Keep enhancement disabled by default on every queue item transition and
            // detach GPU enhancement shaders so battery-saving hardware playback is restored.
            val wasPipelineAttached = videoEnhancementPipelineAttached
            disableAllVideoEnhancements()
            if (wasPipelineAttached) {
                localExoPlayer?.let {
                    applyEffectChain(
                        it,
                        emptyList(),
                        hasLanczos = false,
                        allowRebind = false
                    )
                }
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // Media3 can publish an empty/initial track snapshot before selection.
            // Keep requested effects pending until the video track is actually known.
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            // Audio-only/initial snapshots can arrive before the video group is published.
            // Do not discard a pending user request until Media3 reports video tracks.
            if (videoGroups.isEmpty()) return
            val hasSelectedVideo = videoGroups.any { it.isSelected }
            if (!hasSelectedVideo) return
            val player = localExoPlayer ?: return
            val videoSize = player.videoSize
            val requestSignature = listOf(
                videoSize.width,
                videoSize.height,
                videoGroups.joinToString(",") { "${it.isSelected}:${it.length}" },
                videoUpscalingEnabled,
                videoNoiseReductionEnabled,
                videoVibranceEnabled,
                videoAiBoostEnabled,
                videoCustomTuning,
                videoEnhancementProfile.wireValue,
                performanceQualityCap?.wireValue.orEmpty()
            ).joinToString("|")
            if (requestSignature == lastObservedVideoTrackSignature) return
            lastObservedVideoTrackSignature = requestSignature
            if (hasAnyVideoEnhancementEnabled()) applyVideoEnhancements()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && hasAnyVideoEnhancementEnabled()) {
                applyVideoEnhancements()
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            lastObservedVideoTrackSignature = null
            if (hasAnyVideoEnhancementEnabled()) applyVideoEnhancements()
        }
    }

    /** Effects are explicit, per-video opt-ins and only run on local clear video playback. */
    private fun isLocalClearVideoSession(): Boolean {
        val player = localExoPlayer ?: return false
        val sessionPlayer = mediaSession?.player ?: return false
        return sessionPlayer.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE &&
            player.currentMediaItem?.localConfiguration?.drmConfiguration == null
    }

    private fun canApplyLocalVideoEnhancement(): Boolean {
        val player = localExoPlayer ?: return false
        return isLocalClearVideoSession() &&
            player.currentTracks.groups.any { it.isSelected && it.type == C.TRACK_TYPE_VIDEO } &&
            player.videoSize.width > 0 && player.videoSize.height > 0
    }

    private fun setVideoUpscaling(enabled: Boolean): Boolean {
        if (enabled && !isLocalClearVideoSession()) return false
        val previous = videoUpscalingEnabled
        if (enabled && !hasAnyVideoEnhancementEnabled()) VideoEnhancementMetrics.resetPlaybackSession()
        videoUpscalingEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoUpscalingEnabled = previous
        applyVideoEnhancements()
        return false
    }

    private fun setVideoNoiseReduction(enabled: Boolean): Boolean {
        if (enabled && !isLocalClearVideoSession()) return false
        val previous = videoNoiseReductionEnabled
        if (enabled && !hasAnyVideoEnhancementEnabled()) VideoEnhancementMetrics.resetPlaybackSession()
        videoNoiseReductionEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoNoiseReductionEnabled = previous
        applyVideoEnhancements()
        return false
    }

    private fun setVideoVibrance(enabled: Boolean): Boolean {
        if (enabled && !isLocalClearVideoSession()) return false
        val previous = videoVibranceEnabled
        if (enabled && !hasAnyVideoEnhancementEnabled()) VideoEnhancementMetrics.resetPlaybackSession()
        videoVibranceEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoVibranceEnabled = previous
        applyVideoEnhancements()
        return false
    }

    private fun setVideoComparisonBypass(bypassed: Boolean): Boolean {
        videoEnhancementBypassed = bypassed
        return applyVideoEnhancements()
    }

    private fun setVideoEnhancementProfile(profile: VideoEnhancementProfile): Boolean {
        val previousProfile = videoEnhancementProfile
        val previousUpscaling = videoUpscalingEnabled
        val previousCustom = videoCustomTuning
        videoEnhancementProfile = profile
        videoCustomTuning = false
        // Selecting a preset only changes the saved tuning. The master switch is
        // the sole control that activates GPU processing, so browsing presets while
        // enhancement is OFF must never attach a video-effect chain.
        if (!hasAnyVideoEnhancementEnabled()) {
            applyVideoEnhancements()
            return true
        }
        if (applyVideoEnhancements()) return true
        videoEnhancementProfile = previousProfile
        videoUpscalingEnabled = previousUpscaling
        videoCustomTuning = previousCustom
        applyVideoEnhancements()
        return false
    }

    private fun setCustomVideoEnhancement(
        customMode: Boolean,
        denoiseStrength: Float,
        detailStrength: Float,
        contrastBoost: Float,
        vibranceStrength: Float
    ): Boolean {
        if (!isLocalClearVideoSession()) return false
        val prevCustom = videoCustomTuning
        val prevDenoise = customDenoiseStrength
        val prevDetail = customDetailStrength
        val prevContrast = customContrastBoost
        val prevVibrance = customVibranceStrength
        videoCustomTuning = customMode
        customDenoiseStrength = denoiseStrength.coerceIn(0f, 0.60f)
        customDetailStrength = detailStrength.coerceIn(0f, 1.20f)
        customContrastBoost = contrastBoost.coerceIn(0f, 0.22f)
        customVibranceStrength = vibranceStrength.coerceIn(0f, 0.35f)
        // Fine-tuning values are preferences, not an activation command. Keep
        // every processing stage OFF until the user enables the master switch.
        if (!hasAnyVideoEnhancementEnabled()) {
            applyVideoEnhancements()
            return true
        }
        if (applyVideoEnhancements()) return true
        videoCustomTuning = prevCustom
        customDenoiseStrength = prevDenoise
        customDetailStrength = prevDetail
        customContrastBoost = prevContrast
        customVibranceStrength = prevVibrance
        applyVideoEnhancements()
        return false
    }

    /** Switch both internal GPU stages (plus AI boost & vibrance) in one service transaction. */
    private fun setInternalGpuEnhancement(enabled: Boolean): Boolean {
        if (enabled && !isLocalClearVideoSession()) return false
        val previousUpscaling = videoUpscalingEnabled
        val previousDenoise = videoNoiseReductionEnabled
        val previousVibrance = videoVibranceEnabled
        val previousAiBoost = videoAiBoostEnabled
        if (enabled && !hasAnyVideoEnhancementEnabled()) VideoEnhancementMetrics.resetPlaybackSession()
        videoUpscalingEnabled = enabled
        videoNoiseReductionEnabled = enabled
        videoVibranceEnabled = enabled
        videoAiBoostEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoUpscalingEnabled = previousUpscaling
        videoNoiseReductionEnabled = previousDenoise
        videoVibranceEnabled = previousVibrance
        videoAiBoostEnabled = previousAiBoost
        applyVideoEnhancements()
        return false
    }

    private fun resolveVideoEnhancementTuning() = run {
        val activityManager = getSystemService(ActivityManager::class.java)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                getSystemService(PowerManager::class.java)?.currentThermalStatus
            }.getOrNull()
        } else null
        VideoEnhancementPolicy.resolve(
            requested = videoEnhancementProfile,
            sdkInt = Build.VERSION.SDK_INT,
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: 256,
            thermalStatus = thermalStatus,
            performanceCap = performanceQualityCap
        )
    }

    private fun hasAnyVideoEnhancementEnabled(): Boolean =
        videoUpscalingEnabled || videoNoiseReductionEnabled || videoVibranceEnabled

    private fun disableAllVideoEnhancements() {
        videoUpscalingEnabled = false
        videoNoiseReductionEnabled = false
        videoVibranceEnabled = false
        videoAiBoostEnabled = false
        videoEnhancementBypassed = false
        lastAppliedEnhancementSignature = null
        lastDowngradeReason = null
        videoDenoisePipeline?.update(VideoEnhancementPipelineConfig())
        videoEnhancementPipeline?.update(VideoEnhancementPipelineConfig())
        videoEffectsBridgeAttached = false
        videoLanczosSubmitted = false
    }

    private fun applyVideoEnhancements(): Boolean {
        val player = localExoPlayer ?: return false
        if (!hasAnyVideoEnhancementEnabled()) {
            videoLanczosSubmitted = false
            videoAiBoostEnabled = false
            videoEnhancementBypassed = false
            lastDowngradeReason = null
            videoDenoisePipeline?.update(VideoEnhancementPipelineConfig())
            videoEnhancementPipeline?.update(VideoEnhancementPipelineConfig())
            val detached = applyEffectChain(
                player,
                emptyList(),
                hasLanczos = false,
                lanczosTarget = null
            )
            videoEffectsBridgeAttached = false
            lastAppliedEnhancementSignature = if (detached) "disabled" else null
            return detached
        }
        if (!isLocalClearVideoSession()) {
            disableAllVideoEnhancements()
            applyEffectChain(
                player,
                emptyList(),
                hasLanczos = false
            )
            videoEffectsBridgeAttached = false
            return false
        }
        // Resolve and publish the requested shader configuration before checking
        // track readiness. The first decoded frame can race the track/size callbacks;
        // it must never enter an attached-but-still-pass-through pipeline after the
        // user has already enabled denoise/detail enhancement.
        val tuning = resolveVideoEnhancementTuning()
        val profile = tuning.effective
        lastEffectiveProfile = profile
        lastDowngradeReason = tuning.downgradeReason
        if (tuning.downgradeReason != null) {
            Log.w(TAG, "Video enhancement profile capped: ${tuning.downgradeReason}")
        }
        val activeDenoiseStrength = if (videoCustomTuning) customDenoiseStrength else profile.denoiseStrength
        val activeDetailStrength = if (videoCustomTuning) customDetailStrength else profile.detailStrength
        val activeContrastBoost = if (videoCustomTuning) customContrastBoost else profile.contrastBoost
        val activeClarityStrength = if (videoCustomTuning) {
            (customDetailStrength * 0.21f).coerceIn(0f, 0.30f)
        } else profile.clarityStrength
        val activeVibranceStrength = when {
            !videoVibranceEnabled -> 0f
            videoCustomTuning -> customVibranceStrength
            else -> profile.vibranceStrength
        }
        // Denoise consumes original decoded pixels. The second stage receives
        // the optional upscaled texture and performs detail/contrast/vibrance work.
        videoDenoisePipeline?.update(
            VideoEnhancementPipelineConfig(
                denoiseEnabled = videoNoiseReductionEnabled,
                detailEnabled = false,
                aiBoostEnabled = videoAiBoostEnabled && videoNoiseReductionEnabled,
                denoiseStrength = activeDenoiseStrength,
                bypassed = videoEnhancementBypassed
            )
        )
        val detailStageActive = videoUpscalingEnabled ||
            (videoCustomTuning && (customDetailStrength > 0.01f || customContrastBoost > 0.005f))
        videoEnhancementPipeline?.update(
            VideoEnhancementPipelineConfig(
                denoiseEnabled = false,
                detailEnabled = detailStageActive,
                aiBoostEnabled = videoAiBoostEnabled && detailStageActive,
                detailStrength = activeDetailStrength,
                contrastBoost = activeContrastBoost,
                clarityStrength = activeClarityStrength,
                vibranceStrength = activeVibranceStrength,
                bypassed = videoEnhancementBypassed
            )
        )

        // A user may toggle an effect while Media3 is still selecting tracks or
        // reporting the first frame size. Accept it as pending; listeners above
        // will apply the size-dependent Lanczos stage when the selected format is known.
        if (!canApplyLocalVideoEnhancement()) {
            // A request may be accepted before Media3 selects a video track, but it
            // must never be reported as successful unless the always-on GPU stage is
            // actually installed in the service-owned player's effect graph.
            val denoisePipeline = videoDenoisePipeline
            val detailPipeline = videoEnhancementPipeline
            if (denoisePipeline == null || detailPipeline == null) {
                VideoEnhancementMetrics.recordFailure(
                    "pending_effect_graph_attach",
                    IllegalStateException("GPU enhancement pipelines were not created")
                )
                return false
            }
            val pipelineReady = VideoEnhancementPolicy.canAcceptPendingEnhancementRequest(
                pipelineCreated = true,
                pipelineAttached = videoEnhancementPipelineAttached,
                registryOperational = AssVideoEffectRegistry.isAttached(player)
            )
            val attached = pipelineReady || applyEffectChain(
                player,
                listOf(denoisePipeline, detailPipeline),
                hasLanczos = false
            )
            videoEffectsBridgeAttached = attached
            if (!attached) {
                val reason = AssVideoEffectRegistry.lastFailure(player)
                    ?: "GPU enhancement pipeline is not attached while video track selection is pending"
                VideoEnhancementMetrics.recordFailure(
                    "pending_effect_graph_attach",
                    IllegalStateException(reason)
                )
                Log.e(TAG, "Cannot accept pending video enhancement request: $reason")
                return false
            }
            // The request is now safely queued in an operational graph. Track and
            // frame readiness are separate and will be re-evaluated by player listeners.
            return true
        }

        val effects = ArrayList<Effect>(4)
        var lanczosEffect: Effect? = null
        var lanczosTarget: VideoUpscaleTarget? = null
        videoLanczosSubmitted = false
        if (videoUpscalingEnabled) {
            val videoSize = player.videoSize
            val metrics = resources.displayMetrics
            // Keep older GPUs within a predictable texture budget rather than
            // allocating a native-resolution intermediate surface unconditionally.
            val maxLongEdge = profile.upscaleMaxLongEdge
            val target = VideoUpscaleTargetPolicy.resolve(
                sourceWidth = videoSize.width,
                sourceHeight = videoSize.height,
                displayWidth = metrics.widthPixels,
                displayHeight = metrics.heightPixels,
                maxLongEdge = maxLongEdge,
                maxPixels = profile.upscaleMaxPixels
            )
            lanczosTarget = target
            if (target != null) {
                lanczosEffect = LanczosResample.scaleToFitWithFlexibleOrientation(
                    target.longEdge,
                    target.shortEdge
                )
                videoLanczosSubmitted = true
                Log.i(TAG, "Lanczos upscale target=" + target.longEdge + "x" + target.shortEdge +
                    "; source=" + videoSize.width + "x" + videoSize.height)
            } else {
                Log.i(TAG, "Lanczos upscale skipped: source already meets the capped display target")
            }
        }
        // Enforce source-space denoise -> optional resampling -> output-space detail.
        // Both shader instances remain installed; only their atomic configs change.
        VideoEnhancementPolicy.processingOrder(includeUpscale = lanczosEffect != null).forEach { stage ->
            when (stage) {
                VideoEnhancementStage.DENOISE -> videoDenoisePipeline?.let(effects::add)
                VideoEnhancementStage.UPSCALE -> lanczosEffect?.let(effects::add)
                VideoEnhancementStage.DETAIL -> videoEnhancementPipeline?.let(effects::add)
            }
        }
        val videoSize = player.videoSize
        val signature = listOf(
            videoSize.width,
            videoSize.height,
            videoUpscalingEnabled,
            videoNoiseReductionEnabled,
            videoVibranceEnabled,
            videoAiBoostEnabled,
            videoCustomTuning,
            videoEnhancementBypassed,
            profile.wireValue,
            tuning.downgradeReason.orEmpty(),
            lanczosTarget?.longEdge ?: 0,
            lanczosTarget?.shortEdge ?: 0
        ).joinToString("|")
        if (signature == lastAppliedEnhancementSignature &&
            videoEffectsBridgeAttached && AssVideoEffectRegistry.isAttached(player)
        ) {
            return true
        }
        val applied = runCatching {
            applyEffectChain(
                player,
                effects,
                hasLanczos = videoLanczosSubmitted,
                lanczosTarget = lanczosTarget
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to apply local video enhancement effects", error)
        }.getOrDefault(false)
        videoEffectsBridgeAttached = applied
        if (!applied) {
            // AssOverlayManager preserves its last known-good effect list when a replacement
            // is rejected. Clear that list explicitly after disabling the request so a stale
            // GPU chain cannot keep running while the UI reports that enhancement is off.
            val rejectionReason = AssVideoEffectRegistry.lastFailure(player)
                ?: "AssVideoEffectRegistry rejected the effect list (manager missing, non-operational, or wrong player looper)"
            VideoEnhancementMetrics.recordFailure(
                "effect_apply",
                IllegalStateException(rejectionReason)
            )
            Log.e(TAG, "Video enhancement chain was rejected: $rejectionReason")
            disableAllVideoEnhancements()
            val cleared = runCatching {
                applyEffectChain(
                    player,
                    emptyList(),
                    hasLanczos = false
                )
            }.getOrDefault(false)
            videoEffectsBridgeAttached = false
            if (!cleared) {
                Log.e(TAG, "Could not clear the previous video enhancement chain after rejection")
            }
            return false
        }
        lastAppliedEnhancementSignature = signature
        Log.i(
            TAG,
            "Video effects applied: " + effects.joinToString { it.javaClass.simpleName } +
                "; source=" + player.videoSize.width + "x" + player.videoSize.height +
                "; profile=" + profile.wireValue +
                "; api=" + Build.VERSION.SDK_INT +
                "; downgrade=" + (tuning.downgradeReason ?: "none")
        )
        return true
    }

    /**
     * Lanczos changes the Media3 effect-graph structure, but ExoPlayer supports
     * replacing video effects while prepared and during playback. Let Media3
     * reconfigure its VideoFrameProcessor instead of stopping the player and
     * racing prepare/seek against track and surface callbacks.
     *
     * Parameter-only enhancement changes continue to update the existing
     * pipeline's AtomicReference and do not replace the effect list.
     */
    private fun applyEffectChain(
        player: ExoPlayer,
        effects: List<Effect>,
        hasLanczos: Boolean,
        lanczosTarget: VideoUpscaleTarget? = null,
        allowRebind: Boolean = true
    ): Boolean {
        val targetKey = lanczosTarget?.let { it.longEdge to it.shortEdge }
        val structuralChange = appliedLanczosEffect != hasLanczos ||
            appliedLanczosTarget != targetKey
        // Denoise/detail/profile changes only mutate the already-installed
        // pipeline's AtomicReference. Do not call setVideoEffects again for them.
        val pipelineIncluded = videoDenoisePipeline != null &&
            videoEnhancementPipeline != null &&
            effects.any { it === videoDenoisePipeline } &&
            effects.any { it === videoEnhancementPipeline }
        if (!structuralChange &&
            pipelineIncluded == videoEnhancementPipelineAttached &&
            AssVideoEffectRegistry.isAttached(player)
        ) return true

        // Detaching or attaching the OpenGL graph transitions between emptyList()
        // and the active shader pipeline; AssOverlayManager rebinds cleanly when needed.
        if (!AssVideoEffectRegistry.setAdditionalEffects(player, effects, allowRebind)) {
            return false
        }

        appliedLanczosEffect = hasLanczos
        appliedLanczosTarget = targetKey
        videoEnhancementPipelineAttached = pipelineIncluded
        return true
    }

    /** Progress belongs to the playback service, so it continues after player UI closes. */
    private val progressListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                mediaSession?.player?.let(::rememberCurrentPlaybackMetadata)
            }
            // Replaying the same item after it reached the end is a new play.
            if (playbackState == Player.STATE_ENDED) {
                saveCurrentProgress()
                lastCountedMediaId = null
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            mediaSession?.player?.let(::rememberCurrentPlaybackMetadata)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                markCurrentItemStarted()
                startProgressPersistence()
            } else {
                progressJob?.cancel()
                progressJob = null
                saveCurrentProgress()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) saveCurrentProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Retain metadata by media id so the outgoing item's final snapshot
            // remains correct regardless of callback ordering.
            mediaSession?.player?.let(::rememberCurrentPlaybackMetadata)

            // A repeated queue entry can have the same mediaId but is still a
            // distinct play session. Ignore only transitions that are not starts.
            val isNewQueuePlay = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            if (mediaItem?.mediaId != lastCountedMediaId || isNewQueuePlay) {
                lastCountedMediaId = null
                if (mediaSession?.player?.isPlaying == true) markCurrentItemStarted()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val oldId = oldPosition.mediaItem?.mediaId
            val newId = newPosition.mediaItem?.mediaId
            if (!oldId.isNullOrBlank() && oldId != newId) {
                val oldMetadata = playbackMetadataById[oldId]
                val oldDuration = oldMetadata?.durationMs ?: 0L
                val oldSpeed = oldMetadata?.speed ?: 1f
                saveProgressSnapshot(
                    oldId,
                    oldPosition.positionMs,
                    oldDuration,
                    oldSpeed
                )
            }
        }
    }

    /**
     * Persist playback checkpoints only while media is actually playing.
     * A paused/idle media session must not keep waking the process on a timer.
     */
    private fun startProgressPersistence() {
        if (progressJob?.isActive == true) return
        progressJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                val player = mediaSession?.player ?: break
                if (!player.isPlaying) break
                // Wait for each checkpoint before scheduling another one. This
                // prevents a slow database from accumulating periodic save jobs.
                saveCurrentProgress()?.join()
            }
        }
    }

    private fun markCurrentItemStarted() {
        val mediaId = mediaSession?.player?.currentMediaItem?.mediaId ?: return
        if (mediaId == lastCountedMediaId) return
        lastCountedMediaId = mediaId
        saveCurrentProgress(markPlayed = true)
    }

    private fun rememberCurrentPlaybackMetadata(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        playbackMetadataById[mediaId] = PlaybackMetadata(
            durationMs = player.duration.coerceAtLeast(0L),
            speed = player.playbackParameters.speed
        )
        while (playbackMetadataById.size > MAX_CACHED_PLAYBACK_METADATA) {
            val iterator = playbackMetadataById.keys.iterator()
            if (!iterator.hasNext()) break
            val eldestId = iterator.next()
            if (eldestId == mediaId) break
            iterator.remove()
        }
    }

    private fun saveCurrentProgress(markPlayed: Boolean = false): Job? {
        val player = mediaSession?.player ?: return null
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        rememberCurrentPlaybackMetadata(player)
        val metadata = playbackMetadataById[mediaId]
        return saveProgressSnapshot(
            mediaId,
            player.currentPosition,
            metadata?.durationMs ?: player.duration.coerceAtLeast(0L),
            metadata?.speed ?: player.playbackParameters.speed,
            markPlayed
        )
    }

    private fun saveProgressSnapshot(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        markPlayed: Boolean = false
    ): Job {
        val saveJob = serviceScope.launch {
            progressSaveMutex.withLock {
                try {
                    mediaRepository.saveProgress(
                        mediaId = mediaId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        speed = speed,
                        markPlayed = markPlayed
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Do not silently lose resume-point failures. Never log the media id/URI.
                    Log.e(TAG, "Could not persist playback progress", error)
                }
            }
        }
        lastProgressSaveJob = saveJob
        return saveJob
    }

    /** Tapping the notification reopens the app in its existing task. */
    private fun buildSessionActivityIntent(): PendingIntent {
        val intent = (packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            SESSION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Injects stored credentials for matching HTTPS requests.
     *
     * Credentials are added per-request instead of being embedded in the Uri so
     * they never appear in logs, in the MediaItem or in a redirect target.
     */
    private fun buildDataSourceFactory(): DataSource.Factory {
        val httpClient = OkHttpClient.Builder()
            // Refuse redirects so Authorization is never forwarded to another origin.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(httpClient))
        val resolver = ResolvingDataSource.Resolver { dataSpec -> resolve(dataSpec) }
        val httpFactory = ResolvingDataSource.Factory(upstream, resolver)
        return DataSource.Factory {
            NetworkRoutingDataSource(this, profileStore, credentialVault, httpFactory)
        }
    }

    private fun resolve(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        // Generic HTTP playback would bypass the LAN-only DLNA validation path.
        // DLNA streams use malekdlna-http and are separately host/network constrained.
        validatePlaybackTransportScheme(uri.scheme)
        // Only WebDAV over HTTPS receives credentials, and only to the stored host.
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return dataSpec

        val requestPath = uri.path.orEmpty().ifBlank { "/" }
        val profiles = cachedWebDavProfiles ?: synchronized(this) {
            cachedWebDavProfiles ?: profileStore.load()
                .filter { it.protocol == NetworkProtocol.WEBDAV }
                .also { cachedWebDavProfiles = it }
        }
        // Several WebDAV accounts may share one authority. Restrict each profile
        // to its own root and choose the most specific matching root.
        val profile = profiles.asSequence()
            .filter {
                it.host.equals(uri.host, ignoreCase = true) &&
                    it.port == (if (uri.port == -1) 443 else uri.port) &&
                    isWithinWebDavRoot(requestPath, it.rootPath)
            }
            .maxByOrNull { normalizeWebDavRoot(it.rootPath)?.length ?: 0 }
            ?: return dataSpec

        // Never downgrade a configured WebDAV profile to an unauthenticated request.
        val credentials = credentialVault.load(profile.id)
            ?: throw IOException("بيانات اعتماد WebDAV غير متاحة؛ أعد حفظ بيانات الدخول للمصدر")

        val authorization = Credentials.basic(
            credentials.first,
            credentials.second,
            StandardCharsets.UTF_8
        )
        val headers = dataSpec.httpRequestHeaders
            .filterKeys { !it.equals("Authorization", ignoreCase = true) } +
            ("Authorization" to authorization)
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(headers)
            .build()
    }

    private fun normalizeWebDavRoot(path: String): String? {
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "." || it == ".." || '\\' in it }) return null
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private fun isWithinWebDavRoot(requestPath: String, rootPath: String): Boolean {
        // Reject traversal rather than allowing a URI such as /share/../private
        // to inherit credentials from the /share profile.
        val root = normalizeWebDavRoot(rootPath) ?: return false
        val path = normalizeWebDavRoot(requestPath) ?: return false
        return root == "/" || path == root || path.startsWith("$root/")
    }

    private data class PlaybackMetadata(val durationMs: Long, val speed: Float)

    private companion object {
        const val TAG = "MalekPlayPlayback"
        const val MAX_CACHED_PLAYBACK_METADATA = 16
        const val SESSION_REQUEST_CODE = 1001
        const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
    }
}
