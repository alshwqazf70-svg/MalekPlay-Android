package com.malekplay.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.malekplay.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the UI-side MediaController connection. The PlaybackService remains
 * the owner of the actual ExoPlayer instance.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller = _controller.asStateFlow()

    private val _connectionError = MutableStateFlow(false)
    val connectionError = _connectionError.asStateFlow()

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    fun connect() {
        if (_controller.value != null || controllerFuture != null) return
        val app = getApplication<Application>()
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

    fun playQueue(uris: List<Uri>, startIndex: Int, startPositionMs: Long) {
        val player = _controller.value ?: return
        if (uris.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, uris.lastIndex)
        player.setMediaItems(
            uris.map { MediaItem.fromUri(it) },
            safeIndex,
            startPositionMs.coerceAtLeast(0L)
        )
        player.prepare()
        player.playWhenReady = true
    }

    override fun onCleared() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        _controller.value = null
        super.onCleared()
    }
}
