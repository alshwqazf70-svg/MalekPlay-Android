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
import com.malekplay.network.NetworkCredentialVault
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** Owns the single ExoPlayer instance and MediaSession, including background sleep timer. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var mediaCache: SimpleCache? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerDeadlineMs = 0L
    private var timerStartMs = 0L
    private var timerDurationMs = 0L
    private var originalVolume = 1f
    private var timerActive = false

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
        timerStartMs = SystemClock.elapsedRealtime()
        timerDeadlineMs = timerStartMs + timerDurationMs
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
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val activePlayer = player
        if (activePlayer == null || !activePlayer.playWhenReady) stopSelf()
        // Keep the service alive while playback is active, even after the task is swiped away.
    }

    override fun onDestroy() {
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
    }
}

object PlayerCommands {
    const val ACTION_SLEEP_TIMER = "com.malekplay.command.SLEEP_TIMER"
    const val ACTION_CANCEL_SLEEP_TIMER = "com.malekplay.command.CANCEL_SLEEP_TIMER"
    const val EXTRA_MINUTES = "minutes"
}
