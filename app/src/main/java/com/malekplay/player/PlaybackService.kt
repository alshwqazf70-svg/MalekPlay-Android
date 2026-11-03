package com.malekplay.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.malekplay.MainActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.NetworkProfileStore
import com.malekplay.data.repository.MediaRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.malekplay.network.NetworkProtocol
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owns the single ExoPlayer instance and its MediaSession.
 *
 * Keeping playback in a service means audio survives Activity rotation, the
 * Recents swipe and leaving the app entirely — the same model MX Player uses.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var profileStore: NetworkProfileStore
    private lateinit var credentialVault: NetworkCredentialVault

    @Inject
    lateinit var mediaRepository: MediaRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var lastCountedMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        profileStore = NetworkProfileStore(this)
        credentialVault = NetworkCredentialVault(this)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(buildDataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(progressListener)
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(buildSessionActivityIntent())
            .build()
        startProgressPersistence()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        progressJob?.cancel()
        mediaSession?.run {
            player.removeListener(progressListener)
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Progress belongs to the playback service, so it continues after player UI closes. */
    private val progressListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) markCurrentItemStarted() else saveCurrentProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) saveCurrentProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A transition and isPlaying callback can arrive in either order.
            // Keep the same id marked if the playing callback already counted it.
            if (mediaItem?.mediaId != lastCountedMediaId) {
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
                saveProgressSnapshot(
                    oldId,
                    oldPosition.positionMs,
                    mediaSession?.player?.duration?.coerceAtLeast(0L) ?: 0L,
                    mediaSession?.player?.playbackParameters?.speed ?: 1f
                )
            }
        }
    }

    private fun startProgressPersistence() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                val player = mediaSession?.player ?: break
                if (player.isPlaying) saveCurrentProgress()
            }
        }
    }

    private fun markCurrentItemStarted() {
        val mediaId = mediaSession?.player?.currentMediaItem?.mediaId ?: return
        if (mediaId == lastCountedMediaId) return
        lastCountedMediaId = mediaId
        saveCurrentProgress(markPlayed = true)
    }

    private fun saveCurrentProgress(markPlayed: Boolean = false) {
        val player = mediaSession?.player ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        saveProgressSnapshot(
            mediaId,
            player.currentPosition,
            player.duration.coerceAtLeast(0L),
            player.playbackParameters.speed,
            markPlayed
        )
    }

    private fun saveProgressSnapshot(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        markPlayed: Boolean = false
    ) {
        serviceScope.launch {
            runCatching {
                mediaRepository.saveProgress(
                    mediaId = mediaId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    markPlayed = markPlayed
                )
            }
        }
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
    private fun buildDataSourceFactory(): ResolvingDataSource.Factory {
        val httpClient = OkHttpClient.Builder()
            // Refuse redirects so Authorization is never forwarded to another origin.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(httpClient))
        val resolver = ResolvingDataSource.Resolver { dataSpec -> resolve(dataSpec) }
        return ResolvingDataSource.Factory(upstream, resolver)
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

        val credentials = runCatching { credentialVault.load(profile.id) }.getOrNull()
            ?: return dataSpec

        val authorization = Credentials.basic(credentials.first, credentials.second)
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("Authorization" to authorization))
            .build()
    }

    private companion object {
        const val SESSION_REQUEST_CODE = 1001
        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
    }
}
