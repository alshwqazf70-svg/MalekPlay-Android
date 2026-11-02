package com.malekplay.ui.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.malekplay.data.repository.MediaRepository
import com.malekplay.player.PlaybackService
import com.malekplay.player.PlayerCommands
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private var controllerFuture: ListenableFuture<MediaController>? = null
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

    /** Read the saved Room state for an indexed local media item. */
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

    /** Timer runs in PlaybackService and survives the Activity being removed. */
    fun setSleepTimer(minutes: Int) {
        val controller = _controller.value ?: return
        val args = Bundle().apply { putInt(PlayerCommands.EXTRA_MINUTES, minutes.coerceIn(1, 240)) }
        val command = SessionCommand(PlayerCommands.ACTION_SLEEP_TIMER, Bundle.EMPTY)
        val future = runCatching { controller.sendCustomCommand(command, args) }.getOrNull() ?: return
        future.addListener(
            {
                val result = runCatching { future.get() }.getOrNull()
                _sleepTimerActive.value = result?.resultCode == SessionResult.RESULT_SUCCESS
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    fun cancelSleepTimer() {
        val controller = _controller.value
        if (controller == null) {
            _sleepTimerActive.value = false
            return
        }
        val command = SessionCommand(PlayerCommands.ACTION_CANCEL_SLEEP_TIMER, Bundle.EMPTY)
        val future = runCatching {
            controller.sendCustomCommand(command, Bundle.EMPTY)
        }.getOrNull()
        if (future == null) {
            _sleepTimerActive.value = false
            return
        }
        future.addListener(
            {
                val result = runCatching { future.get() }.getOrNull()
                if (result?.resultCode == SessionResult.RESULT_SUCCESS) {
                    _sleepTimerActive.value = false
                }
            },
            ContextCompat.getMainExecutor(app)
        )
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _controller.value = null
        super.onCleared()
    }
}
