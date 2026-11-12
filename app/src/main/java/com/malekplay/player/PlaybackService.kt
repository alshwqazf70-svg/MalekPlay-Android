package com.malekplay.player

import android.app.ActivityManager
import android.app.PendingIntent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Intent
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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.effect.LanczosResample
import com.malekplay.player.effects.SpatialNoiseReductionEffect
import com.malekplay.player.effects.TemporalMotionSmoothingEffect
import com.malekplay.player.effects.VideoDetailEnhancementEffect
import com.malekplay.player.effects.VideoEnhancementProfile
import com.malekplay.player.effects.VideoEnhancementPolicy
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
    private var videoUpscalingEnabled = false
    private var videoNoiseReductionEnabled = false
    private var videoMotionSmoothingEnabled = false
    private var videoEnhancementProfile = VideoEnhancementProfile.BALANCED
    private var performanceQualityCap: VideoEnhancementProfile? = null
    private var thermalPowerManager: PowerManager? = null
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var castMediaHttpServer: CastMediaHttpServer? = null
    private lateinit var profileStore: NetworkProfileStore
    private lateinit var credentialVault: NetworkCredentialVault

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

        // Keep Media3's decoder fallback enabled for devices whose preferred
        // AVC decoder advertises support but fails during initialization.
        // buildWithAssSupport wraps this factory, preserving ASS/SSA rendering.
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

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
                dataSourceFactory = buildDataSourceFactory(),
                renderersFactory = renderersFactory
            )

        // CastPlayer wraps the existing ASS-capable ExoPlayer. MediaController,
        // notification controls and the video UI now share one local/remote Player.
        // The bridge starts lazily only when a local file is handed to a Cast receiver.
        // It exposes tokenized, seekable LAN URLs rather than leaking content:// URIs.
        localExoPlayer = player
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
        val mediaBridge = CastMediaHttpServer(this, buildDataSourceFactory())
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
            // Start from the supported default session commands for every controller.
            // The custom GPU effect command is exposed only to trusted app controllers.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                if (isTrustedAppController(controller)) PlaybackVideoEffects.COMMANDS.forEach { add(it) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
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
            val requested = args.getBoolean(PlaybackVideoEffects.KEY_ENABLED, false)
            val accepted = when (customCommand.customAction) {
                PlaybackVideoEffects.ACTION_UPSCALING -> setVideoUpscaling(requested)
                PlaybackVideoEffects.ACTION_NOISE_REDUCTION -> setVideoNoiseReduction(requested)
                PlaybackVideoEffects.ACTION_MOTION_SMOOTHING -> setVideoMotionSmoothing(requested)
                PlaybackVideoEffects.ACTION_QUALITY_PROFILE -> setVideoEnhancementProfile(
                    VideoEnhancementProfile.fromWireValue(args.getString(PlaybackVideoEffects.KEY_PROFILE))
                )
                else -> false
            }
            return Futures.immediateFuture(
                SessionResult(
                    if (accepted) SessionResult.RESULT_SUCCESS
                    else SessionError.ERROR_NOT_SUPPORTED
                )
            )
        }
    }

    private val videoEffectRouteListener = object : Player.Listener {
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                disableAllVideoEnhancements()
                localExoPlayer?.let { AssVideoEffectRegistry.setAdditionalEffects(it, emptyList()) }
            }
        }
    }

    private val enhancementAnalyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            if (!hasAnyVideoEnhancementEnabled() || droppedFrames <= 0) return
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
            // Effects are opt-in per video. Do not keep a GPU pipeline running
            // after the user advances to another item.
            disableAllVideoEnhancements()
            localExoPlayer?.let { AssVideoEffectRegistry.setAdditionalEffects(it, emptyList()) }
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
        videoUpscalingEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoUpscalingEnabled = previous
        applyVideoEnhancements()
        return false
    }

    private fun setVideoNoiseReduction(enabled: Boolean): Boolean {
        if (enabled && !isLocalClearVideoSession()) return false
        val previous = videoNoiseReductionEnabled
        videoNoiseReductionEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoNoiseReductionEnabled = previous
        applyVideoEnhancements()
        return false
    }

    private fun setVideoMotionSmoothing(enabled: Boolean): Boolean {
        if (enabled && !isLocalClearVideoSession()) return false
        // Temporal history blending is intentionally omitted on the Economy tier
        // to protect legacy/low-memory devices; it is not true frame interpolation.
        if (enabled && resolveVideoEnhancementTuning().effective.previousFrameWeight <= 0f) return false
        val previous = videoMotionSmoothingEnabled
        videoMotionSmoothingEnabled = enabled
        if (applyVideoEnhancements()) return true
        videoMotionSmoothingEnabled = previous
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
        videoUpscalingEnabled || videoNoiseReductionEnabled || videoMotionSmoothingEnabled

    private fun disableAllVideoEnhancements() {
        videoUpscalingEnabled = false
        videoNoiseReductionEnabled = false
        videoMotionSmoothingEnabled = false
    }

    private fun applyVideoEnhancements(): Boolean {
        val player = localExoPlayer ?: return false
        if (!hasAnyVideoEnhancementEnabled()) {
            AssVideoEffectRegistry.setAdditionalEffects(player, emptyList())
            return true
        }
        if (!isLocalClearVideoSession()) {
            disableAllVideoEnhancements()
            runCatching { AssVideoEffectRegistry.setAdditionalEffects(player, emptyList()) }
            return false
        }
        // A user may toggle an effect while Media3 is still selecting tracks or
        // reporting the first frame size. Accept it as pending; listeners above
        // will apply it as soon as the selected video format becomes available.
        if (!canApplyLocalVideoEnhancement()) return true

        val tuning = resolveVideoEnhancementTuning()
        val profile = tuning.effective
        if (tuning.downgradeReason != null) {
            Log.w(TAG, "Video enhancement profile capped: ${tuning.downgradeReason}")
        }
        val effects = ArrayList<Effect>(4)
        if (videoUpscalingEnabled) {
            val videoSize = player.videoSize
            val metrics = resources.displayMetrics
            val targetLong = maxOf(metrics.widthPixels, metrics.heightPixels)
            val targetShort = minOf(metrics.widthPixels, metrics.heightPixels)
            val sourceLong = maxOf(videoSize.width, videoSize.height)
            val sourceShort = minOf(videoSize.width, videoSize.height)
            // Resample only when the source is smaller than the physical display target.
            // High-resolution sources remain valid: they still pass through detail enhancement
            // rather than having the user's request silently rejected.
            if (sourceLong < targetLong && sourceShort < targetShort) {
                effects += LanczosResample.scaleToFitWithFlexibleOrientation(targetLong, targetShort)
            }
        }
        if (videoNoiseReductionEnabled) effects += SpatialNoiseReductionEffect(profile.denoiseStrength)
        if (videoUpscalingEnabled || videoNoiseReductionEnabled) {
            effects += VideoDetailEnhancementEffect(profile.detailStrength)
        }
        if (videoMotionSmoothingEnabled && profile.previousFrameWeight > 0f) {
            effects += TemporalMotionSmoothingEffect(profile.previousFrameWeight)
        }
        return runCatching {
            AssVideoEffectRegistry.setAdditionalEffects(player, effects)
        }.onFailure { error ->
            Log.e(TAG, "Failed to apply local video enhancement effects", error)
            disableAllVideoEnhancements()
            runCatching { AssVideoEffectRegistry.setAdditionalEffects(player, emptyList()) }
        }.isSuccess
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
        // Only WebDAV over HTTPS receives credentials, and only to the stored host.
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return dataSpec

        val profile = profileStore.load().firstOrNull {
            it.protocol == NetworkProtocol.WEBDAV &&
                it.host.equals(uri.host, ignoreCase = true) &&
                it.port == (if (uri.port == -1) 443 else uri.port)
        } ?: return dataSpec

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

    private data class PlaybackMetadata(val durationMs: Long, val speed: Float)

    private companion object {
        const val TAG = "MalekPlayPlayback"
        const val MAX_CACHED_PLAYBACK_METADATA = 16
        const val SESSION_REQUEST_CODE = 1001
        const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
    }
}
