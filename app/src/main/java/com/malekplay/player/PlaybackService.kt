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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import com.malekplay.network.NetworkProtocol
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

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
    private lateinit var profileStore: NetworkProfileStore
    private lateinit var credentialVault: NetworkCredentialVault

    @Inject
    lateinit var mediaRepository: MediaRepository

    // ExoPlayer is application-looper confined; read its state on Main and let
    // MediaRepository switch to IO for database/preferences work.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var lastCountedMediaId: String? = null
    private val playbackMetadataById = ConcurrentHashMap<String, PlaybackMetadata>()
    private val progressSaveMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        profileStore = NetworkProfileStore(this)
        credentialVault = NetworkCredentialVault(this)

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
                dataSourceFactory = buildDataSourceFactory()
            )

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
        val finalSave = saveCurrentProgress()
        mediaSession?.run {
            player.removeListener(progressListener)
            player.release()
            release()
        }
        mediaSession = null
        if (finalSave == null) serviceScope.cancel()
        else finalSave.invokeOnCompletion { serviceScope.cancel() }
        super.onDestroy()
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
            if (isPlaying) markCurrentItemStarted() else saveCurrentProgress()
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

    private fun rememberCurrentPlaybackMetadata(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        playbackMetadataById[mediaId] = PlaybackMetadata(
            durationMs = player.duration.coerceAtLeast(0L),
            speed = player.playbackParameters.speed
        )
        if (playbackMetadataById.size > MAX_CACHED_PLAYBACK_METADATA) {
            playbackMetadataById.keys.firstOrNull { it != mediaId }?.let(playbackMetadataById::remove)
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
        return serviceScope.launch {
            progressSaveMutex.withLock {
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

        // Surface corrupt credential storage instead of silently issuing an unauthenticated request.
        val credentials = credentialVault.load(profile.id) ?: return dataSpec

        val authorization = Credentials.basic(credentials.first, credentials.second)
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("Authorization" to authorization))
            .build()
    }

    private data class PlaybackMetadata(val durationMs: Long, val speed: Float)

    private companion object {
        const val MAX_CACHED_PLAYBACK_METADATA = 16
        const val SESSION_REQUEST_CODE = 1001
        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
    }
}
