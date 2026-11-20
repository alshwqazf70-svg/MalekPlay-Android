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
import com.malekplay.player.effects.VideoEnhancementMetrics
import com.malekplay.player.effects.VideoEnhancementPipelineEffect
import com.malekplay.player.effects.VideoEnhancementPipelineConfig
import com.malekplay.player.effects.VideoUpscaleTargetPolicy
import com.malekplay.player.effects.GoogleMediaEnhancementCapability
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
    private var videoEnhancementPipeline: VideoEnhancementPipelineEffect? = null
    private var videoEnhancementPipelineAttached = false
    @Volatile private var videoEffectsBridgeAttached = false
    @Volatile private var videoLanczosSubmitted = false
    private var appliedLanczosEffect = false
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
        // Install the mutable GPU stage before any media item can be prepared.
        // All later profile/toggle changes update its atomic shader config rather
        // than replacing the GlEffect and recreating Media3's frame processor.
        val pipeline = VideoEnhancementPipelineEffect()
        videoEnhancementPipeline = pipeline
        videoEnhancementPipelineAttached =
            AssVideoEffectRegistry.setAdditionalEffects(player, listOf(pipeline))
        if (!videoEnhancementPipelineAttached) {
            Log.e(TAG, "Could not attach the always-ready GPU enhancement pipeline")
        }

        // Probe Google AI capability without touching the active decoder or surface.
        // The opt-in UI bridge owns the short-lived surface session and restores this
        // player's normal output whenever the view, media item, or session is released.
        GoogleMediaEnhancementCapability.inspect(this) { status ->
            Log.i(TAG, "Google AI media enhancement capability: $status")
        }
        // Bind audio effects directly to the service-owned ExoPlayer session;
        // the UI listener is a secondary observer, not the only attachment path.
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                audioEffectsController.attach(audioSessionId)
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
        putString(PlaybackVideoEffects.KEY_STATE_PROFILE, videoEnhancementProfile.wireValue)
        val metrics = VideoEnhancementMetrics.snapshot()
        // Read the live registry state instead of exposing a stale service-side flag.
        videoEffectsBridgeAttached = localExoPlayer?.let(AssVideoEffectRegistry::isAttached) == true
        putBoolean(PlaybackVideoEffects.KEY_DIAG_UPSCALE_SUBMITTED, videoLanczosSubmitted && videoEffectsBridgeAttached)
        putLong(PlaybackVideoEffects.KEY_DIAG_DENOISE_DRAWS, metrics.denoiseDraws)
        putLong(PlaybackVideoEffects.KEY_DIAG_DETAIL_DRAWS, metrics.detailDraws)
        putLong(PlaybackVideoEffects.KEY_DIAG_TOTAL_DRAWS, metrics.processedDraws)
        putString(PlaybackVideoEffects.KEY_DIAG_LAST_EFFECT, metrics.lastEffectName)
        val bridgeFailure = localExoPlayer?.let(AssVideoEffectRegistry::lastFailure)
        putString(
            PlaybackVideoEffects.KEY_DIAG_LAST_FAILURE,
            metrics.lastFailure ?: bridgeFailure ?: if (!videoEffectsBridgeAttached) {
                "effects_bridge_not_registered_or_not_operational"
            } else null
        )
        putLong(PlaybackVideoEffects.KEY_DIAG_LAST_PTS_US, metrics.lastPresentationTimeUs)
        putLong(PlaybackVideoEffects.KEY_DIAG_RENDERER_FRAMES, lastRendererFrameCount)
        putLong(PlaybackVideoEffects.KEY_DIAG_AVG_OFFSET_US, lastAverageProcessingOffsetUs)
        putLong(PlaybackVideoEffects.KEY_DIAG_DROPPED_FRAMES, droppedVideoFramesTotal)
        putBoolean(PlaybackVideoEffects.KEY_DIAG_BRIDGE_ATTACHED, videoEffectsBridgeAttached)
    }

    private val videoEffectRouteListener = object : Player.Listener {
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                disableAllVideoEnhancements()
                localExoPlayer?.let {
                    applyEffectChain(it, listOfNotNull(videoEnhancementPipeline), hasLanczos = false)
                }
            }
        }
    }

    private val enhancementAnalyticsListener = object : AnalyticsListener {
        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int
        ) {
            if (!hasAnyVideoEnhancementEnabled() || frameCount <= 0) return
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
            VideoEnhancementMetrics.reset()
            lastRendererFrameCount = 0L
            lastAverageProcessingOffsetUs = 0L
            droppedVideoFramesTotal = 0L
            // Keep the same always-attached shader in passthrough mode for the
            // next item; per-video enhancement state is reset without removing
            // the VideoFrameProcessor's configured effect graph.
            disableAllVideoEnhancements()
            localExoPlayer?.let {
                applyEffectChain(it, listOfNotNull(videoEnhancementPipeline), hasLanczos = false)
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
            if (hasAnyVideoEnhancementEnabled()) applyVideoEnhancements()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
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
        if (enabled && !hasAnyVideoEnhancementEnabled()) VideoEnhancementMetrics.reset()
        videoUpscalingEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoUpscalingEnabled = previous
        applyVideoEnhancements()
        return false
    }

    private fun setVideoNoiseReduction(enabled: Boolean): Boolean {
        if (enabled && !isLocalClearVideoSession()) return false
        val previous = videoNoiseReductionEnabled
        if (enabled && !hasAnyVideoEnhancementEnabled()) VideoEnhancementMetrics.reset()
        videoNoiseReductionEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoNoiseReductionEnabled = previous
        applyVideoEnhancements()
        return false
    }

    private fun setVideoEnhancementProfile(profile: VideoEnhancementProfile): Boolean {
        val previous = videoEnhancementProfile
        videoEnhancementProfile = profile
        if (applyVideoEnhancements()) return true
        videoEnhancementProfile = previous
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
        videoUpscalingEnabled || videoNoiseReductionEnabled

    private fun disableAllVideoEnhancements() {
        videoUpscalingEnabled = false
        videoNoiseReductionEnabled = false
        videoEnhancementPipeline?.update(VideoEnhancementPipelineConfig())
        videoEffectsBridgeAttached = false
        videoLanczosSubmitted = false
    }

    private fun applyVideoEnhancements(): Boolean {
        val player = localExoPlayer ?: return false
        if (!hasAnyVideoEnhancementEnabled()) {
            videoLanczosSubmitted = false
            videoEnhancementPipeline?.update(VideoEnhancementPipelineConfig())
            videoEffectsBridgeAttached = applyEffectChain(
                player,
                listOfNotNull(videoEnhancementPipeline),
                hasLanczos = false
            )
            return videoEffectsBridgeAttached
        }
        if (!isLocalClearVideoSession()) {
            disableAllVideoEnhancements()
            videoEffectsBridgeAttached = applyEffectChain(
                player,
                listOfNotNull(videoEnhancementPipeline),
                hasLanczos = false
            )
            return false
        }
        // A user may toggle an effect while Media3 is still selecting tracks or
        // reporting the first frame size. Accept it as pending; listeners above
        // will apply it as soon as the selected video format becomes available.
        if (!canApplyLocalVideoEnhancement()) {
            // The request is pending, not applied. Keep the diagnostics honest:
            // bridge connection is reported independently from selected-track readiness.
            videoEffectsBridgeAttached = AssVideoEffectRegistry.isAttached(player)
            return true
        }

        val tuning = resolveVideoEnhancementTuning()
        val profile = tuning.effective
        if (tuning.downgradeReason != null) {
            Log.w(TAG, "Video enhancement profile capped: ${tuning.downgradeReason}")
        }
        videoEnhancementPipeline?.update(
            VideoEnhancementPipelineConfig(
                denoiseEnabled = videoNoiseReductionEnabled,
                detailEnabled = videoUpscalingEnabled || videoNoiseReductionEnabled,
                aiBoostEnabled = videoUpscalingEnabled && videoNoiseReductionEnabled,
                denoiseStrength = profile.denoiseStrength,
                detailStrength = profile.detailStrength,
                contrastBoost = profile.contrastBoost,
                clarityStrength = profile.clarityStrength
            )
        )
        val effects = ArrayList<Effect>(4)
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
                maxLongEdge = maxLongEdge
            )
            if (target != null) {
                effects += LanczosResample.scaleToFitWithFlexibleOrientation(
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
        // The same GlEffect instance remains in the graph; only its atomic
        // parameters change, so toggles and profile changes affect the next frame.
        videoEnhancementPipeline?.let(effects::add)
        val applied = runCatching {
            applyEffectChain(player, effects, hasLanczos = videoLanczosSubmitted)
        }.onFailure { error ->
            Log.e(TAG, "Failed to apply local video enhancement effects", error)
        }.getOrDefault(false)
        videoEffectsBridgeAttached = applied
        if (!applied) {
            // AssOverlayManager preserves its last known-good effect list when a replacement
            // is rejected. Clear that list explicitly after disabling the request so a stale
            // GPU chain cannot keep running while the UI reports that enhancement is off.
            Log.e(TAG, "Video enhancement chain was rejected; disabling the previous effect list")
            AssVideoEffectRegistry.lastFailure(player)?.let { reason ->
                VideoEnhancementMetrics.recordFailure(
                    "effect_apply",
                    IllegalStateException(reason)
                )
            }
            disableAllVideoEnhancements()
            val cleared = runCatching {
                applyEffectChain(
                    player,
                    listOfNotNull(videoEnhancementPipeline),
                    hasLanczos = false
                )
            }.getOrDefault(false)
            videoEffectsBridgeAttached = cleared
            if (!cleared) {
                Log.e(TAG, "Could not clear the previous video enhancement chain after rejection")
            }
            return false
        }
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
     * Lanczos changes the Media3 effect graph structure. Rebuild only when that
     * structural stage is added/removed, preserving the active queue item, exact
     * position and play intent. Parameter-only effect changes remain in-place.
     */
    private fun applyEffectChain(
        player: ExoPlayer,
        effects: List<Effect>,
        hasLanczos: Boolean
    ): Boolean {
        val hasCurrentItem = player.currentMediaItem != null
        val structuralChange = appliedLanczosEffect != hasLanczos
        val needsRebind = hasCurrentItem &&
            player.playbackState != Player.STATE_IDLE &&
            structuralChange
        // Denoise/detail/profile changes only mutate the already-installed
        // pipeline's AtomicReference. Do not call setVideoEffects again for them.
        val pipelineIncluded = videoEnhancementPipeline != null &&
            effects.any { it === videoEnhancementPipeline }
        if (!structuralChange &&
            pipelineIncluded == videoEnhancementPipelineAttached &&
            AssVideoEffectRegistry.isAttached(player)
        ) return true

        val itemIndex = player.currentMediaItemIndex
        val positionMs = player.currentPosition
        val shouldPlay = player.playWhenReady

        if (needsRebind) player.stop()
        val accepted = AssVideoEffectRegistry.setAdditionalEffects(player, effects)
        if (!accepted) {
            if (needsRebind) {
                // The registry retains the last known-good effect list on rejection.
                player.prepare()
                if (itemIndex != C.INDEX_UNSET) player.seekTo(itemIndex, positionMs)
                player.playWhenReady = shouldPlay
            }
            return false
        }

        appliedLanczosEffect = hasLanczos
        videoEnhancementPipelineAttached = pipelineIncluded
        if (needsRebind) {
            player.prepare()
            if (itemIndex != C.INDEX_UNSET) player.seekTo(itemIndex, positionMs)
            player.playWhenReady = shouldPlay
        }
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
