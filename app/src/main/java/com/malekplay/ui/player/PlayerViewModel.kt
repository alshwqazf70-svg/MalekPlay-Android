package com.malekplay.ui.player

import android.content.Context
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.malekplay.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/**
 * Owns the UI-side MediaController connection. The PlaybackService remains
 * the owner of the actual ExoPlayer instance.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val app: Context
) : ViewModel() {
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller = _controller.asStateFlow()

    private val _connectionError = MutableStateFlow(false)
    val connectionError = _connectionError.asStateFlow()

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var sleepTimerJob: Job? = null
    private val _sleepTimerActive = MutableStateFlow(false)
    val sleepTimerActive = _sleepTimerActive.asStateFlow()

    fun connect() {
        if (_controller.value != null || controllerFuture != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrNull()
                _controller.value = connected
                _connectionError.value = connected == null
            },
            ContextCompat.getMainExecutor(app)
        )
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

    fun playQueue(uris: List<Uri>, startIndex: Int, startPositionMs: Long, speed: Float = 1f) {
        val player = _controller.value ?: return
        if (uris.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, uris.lastIndex)
        player.setMediaItems(
            uris.map { MediaItem.fromUri(it) },
            safeIndex,
            startPositionMs.coerceAtLeast(0L)
        )
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
