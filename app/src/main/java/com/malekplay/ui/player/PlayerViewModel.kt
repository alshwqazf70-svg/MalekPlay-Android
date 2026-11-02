package com.malekplay.ui.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.malekplay.data.repository.MediaRepository
import com.malekplay.player.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Owns the UI-side MediaController connection. PlaybackService owns ExoPlayer. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller = _controller.asStateFlow()

    private val _connectionError = MutableStateFlow(false)
    val connectionError = _connectionError.asStateFlow()

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var sleepTimerJob: Job? = null
    private val _sleepTimerActive = MutableStateFlow(false)
    val sleepTimerActive = _sleepTimerActive.asStateFlow()

    /** Safe to call repeatedly; a failed connection can be retried by the UI. */
    fun connect() {
        if (_controller.value != null) return
        val pending = controllerFuture
        if (pending != null && !pending.isDone) return

        _connectionError.value = false
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture !== future) return@addListener
                val connected = runCatching { future.get() }.getOrNull()
                if (connected != null) {
                    _controller.value = connected
                    _connectionError.value = false
                } else {
                    controllerFuture = null
                    _connectionError.value = true
                }
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    /**
     * Persist progress only for items already indexed in the local Room library.
     * Network streams and one-off document-picker URIs are intentionally skipped.
     */
    suspend fun getSavedPlaybackState(mediaId: String) =
        runCatching { mediaRepository.getPlaybackState(mediaId) }.getOrNull()

    fun savePlaybackState(mediaId: String, positionMs: Long, speed: Float, durationMs: Long) {
        viewModelScope.launch {
            runCatching {
                if (mediaRepository.getMediaWithDetails(mediaId) != null) {
                    mediaRepository.savePlayback(mediaId, positionMs, speed, durationMs)
                }
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        require(minutes in 1..240) { "Sleep timer must be between 1 and 240 minutes" }
        sleepTimerJob?.cancel()
        _sleepTimerActive.value = true
        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            _controller.value?.pause()
            _sleepTimerActive.value = false
            sleepTimerJob = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerActive.value = false
    }

    fun playQueue(
        uris: List<Uri>,
        startIndex: Int,
        startPositionMs: Long,
        speed: Float = 1f,
        currentTitle: String? = null
    ) {
        val player = _controller.value ?: return
        if (uris.isEmpty()) return

        val safeIndex = startIndex.coerceIn(0, uris.lastIndex)
        val items = uris.mapIndexed { index, uri ->
            val fallbackTitle = uri.lastPathSegment
                ?.let(Uri::decode)
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "وسائط"
            val itemTitle = if (index == safeIndex) {
                currentTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle
            } else {
                fallbackTitle
            }
            MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(itemTitle).build())
                .build()
        }

        player.setMediaItems(items, safeIndex, startPositionMs.coerceAtLeast(0L))
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
        player.prepare()
        player.playWhenReady = true
    }

    override fun onCleared() {
        sleepTimerJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _controller.value = null
        super.onCleared()
    }
}
