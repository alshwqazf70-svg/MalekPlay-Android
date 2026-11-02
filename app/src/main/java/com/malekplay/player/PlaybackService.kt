package com.malekplay.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.malekplay.MainActivity
import com.malekplay.data.local.entity.TrackPreferenceEntity
import com.malekplay.data.repository.MediaRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.malekplay.network.NetworkCredentialVault
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

/** Owns the single ExoPlayer instance and MediaSession, including background sleep timer. */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var mediaRepository: MediaRepository

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var mediaCache: SimpleCache? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerDeadlineMs = 0L
    private var timerDurationMs = 0L
    private var originalVolume = 1f
    private var timerActive = false

    private val progressTicker = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            if (activePlayer.isPlaying) {
                persistCurrentState()
                progressHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
            }
        }
    }

    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            progressHandler.removeCallbacks(progressTicker)
            if (isPlaying) {
                progressHandler.postDelayed(progressTicker, PROGRESS_SAVE_INTERVAL_MS)
            } else {
                persistCurrentState()
            }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            persistCurrentState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                persistCurrentState()
            }
        }
    }

    private fun persistCurrentState(): Job? {
        val activePlayer = player ?: return null
        val item = activePlayer.currentMediaItem ?: return null
        val mediaId = item.mediaId
        // Persist only MediaStore-indexed local items; document-picker and network
        // sources continue using the UI's SharedPreferences fallback.
        if (!mediaId.startsWith("content://")) return null
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        val duration = activePlayer.duration.takeIf { it > 0L } ?: 0L
        val speed = activePlayer.playbackParameters.speed
        val audioTrack = currentTrackId(C.TRACK_TYPE_AUDIO)
        val subtitleTrack = currentTrackId(C.TRACK_TYPE_TEXT)
        return persistenceScope.launch {
            runCatching {
                if (mediaRepository.getMediaWithDetails(mediaId) != null) {
                    mediaRepository.savePlayback(mediaId, position, speed, duration)
                    if (audioTrack != null || subtitleTrack != null) {
                        mediaRepository.saveTrackPreference(
                            TrackPreferenceEntity(
                                mediaId = mediaId,
                                audioTrackId = audioTrack,
                                subtitleTrackId = subtitleTrack,
                                subtitleDelayMs = 0L,
                                isSubtitleEnabled = subtitleTrack != null
                            )
                        )
                    }
                }
            }
        }
    }

    private fun currentTrackId(type: Int): String? {
        val activePlayer = player ?: return null
        for (group in activePlayer.currentTracks.groups) {
            if (group.type != type) continue
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) return "${group.type}:$index"
            }
        }
        return null
    }

    private val sleepTimerTick = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            if (!timerActive) return
            val remaining = (timerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            // Smoothly fade the final 30 seconds instead of stopping abruptly.
            val fadeWindow = minOf(30_000L, timerDurationMs / 5L).coerceAtLeast(1_000L)
            val fadeStart = timerDeadlineMs - fadeWindow
            if (SystemClock.elapsedRealtime() >= fadeStart) {
                activePlayer.volume = originalVolume *
                    (remaining.toFloat() / fadeWindow.toFloat()).coerceIn(0f, 1f)
            }
            PlaybackBus.sleepTimerRemaining.value = remaining
            if (remaining == 0L) {
                timerActive = false
                activePlayer.volume = originalVolume
                activePlayer.pause()
            } else {
                timerHandler.postDelayed(this, minOf(1_000L, remaining))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val httpClient = OkHttpClient.Builder()
            // Refuse redirects so Authorization is never forwarded to another origin.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(httpClient))
        val vault = NetworkCredentialVault(this)
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            val uri = dataSpec.uri
            if (!uri.scheme.equals("https", true) || uri.userInfo != null) {
                dataSpec
            } else {
                val raw = getSharedPreferences("malekplay_network_profiles", MODE_PRIVATE)
                    .getString("profiles", "[]") ?: "[]"
                val profiles = runCatching { JSONArray(raw) }.getOrNull()
                var authorization: String? = null

                if (profiles != null) {
                    for (index in 0 until profiles.length()) {
                        val profile = profiles.optJSONObject(index) ?: continue
                        if (!profile.optString("protocol").equals("WEBDAV", true)) continue
                        val host = profile.optString("host")
                        val port = profile.optInt("port", 443)
                        val uriPort = if (uri.port == -1) 443 else uri.port
                        if (host.equals(uri.host, true) && port == uriPort) {
                            val credentials = runCatching {
                                vault.load(profile.optString("id"))
                            }.getOrNull()
                            if (credentials != null) {
                                authorization = Credentials.basic(credentials.first, credentials.second)
                            }
                            break
                        }
                    }
                }

                if (authorization == null) dataSpec
                else dataSpec.buildUpon()
                    .setHttpRequestHeaders(
                        dataSpec.httpRequestHeaders + ("Authorization" to authorization!!)
                    )
                    .build()
            }
        }

        val resolvingFactory = ResolvingDataSource.Factory(upstream, resolver)
        // A bounded disk cache helps replay and seeking without allowing unbounded growth.
        val cache = SimpleCache(
            File(cacheDir, "media_cache"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(this)
        ).also { mediaCache = it }
        val cachedFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val activePlayer = ExoPlayer.Builder(
            this,
            DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedFactory))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                addListener(playbackListener)
            }
        player = activePlayer

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, activePlayer)
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(PlayerCommands.ACTION_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(PlayerCommands.ACTION_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .build()
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
            when (customCommand.customAction) {
                PlayerCommands.ACTION_SLEEP_TIMER ->
                    startSleepTimer(args.getInt(PlayerCommands.EXTRA_MINUTES, 30).coerceIn(1, 240))
                PlayerCommands.ACTION_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val activePlayer = player ?: return
        timerDurationMs = minutes * 60_000L
        timerDeadlineMs = SystemClock.elapsedRealtime() + timerDurationMs
        PlaybackBus.sleepTimerRemaining.value = timerDurationMs
        originalVolume = activePlayer.volume
        timerActive = true
        timerHandler.postDelayed(sleepTimerTick, 1_000L)
    }

    private fun cancelSleepTimer() {
        timerActive = false
        timerHandler.removeCallbacks(sleepTimerTick)
        player?.volume = originalVolume
        timerDeadlineMs = 0L
        timerDurationMs = 0L
        PlaybackBus.sleepTimerRemaining.value = 0L
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val activePlayer = player
        if (activePlayer == null || !activePlayer.playWhenReady) stopSelf()
        // Keep the service alive while playback is active, even after the task is swiped away.
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressTicker)
        val finalSave = persistCurrentState()
        finalSave?.invokeOnCompletion { persistenceScope.cancel() } ?: persistenceScope.cancel()
        player?.removeListener(playbackListener)
        cancelSleepTimer()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        mediaCache?.release()
        mediaCache = null
        super.onDestroy()
    }

    private companion object {
        const val CACHE_BYTES = 256L * 1024L * 1024L
        const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
    }
}

object PlayerCommands {
    const val ACTION_SLEEP_TIMER = "com.malekplay.command.SLEEP_TIMER"
    const val ACTION_CANCEL_SLEEP_TIMER = "com.malekplay.command.CANCEL_SLEEP_TIMER"
    const val EXTRA_MINUTES = "minutes"
}

/** Same-process timer state observed by the player UI. */
object PlaybackBus {
    val sleepTimerRemaining = MutableStateFlow(0L)
}
