@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.malekplay.player.cast

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-wide coordinator for the Live Web Cast & PC Browser server.
 *
 * When PlaybackService is alive it attaches the full Media3 DataSource factory
 * (supporting SMB/SFTP/WebDAV/DLNA); otherwise callers can still start Web Cast
 * for local device media using DefaultDataSource.Factory.
 */
object WebCastRegistry {
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var server: WebCastServer? = null
    @Volatile private var relayJob: Job? = null

    private val _state = MutableStateFlow(WebCastUiState())
    val state: StateFlow<WebCastUiState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<WebCastRemoteCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<WebCastRemoteCommand> = _commands.asSharedFlow()

    @Synchronized
    fun attachServiceFactory(context: Context, upstreamFactory: DataSource.Factory): WebCastServer {
        server?.let { existing ->
            existing.updateUpstreamFactory(upstreamFactory)
            return existing
        }
        val created = WebCastServer(context.applicationContext, upstreamFactory)
        bindServer(created)
        return created
    }

    @Synchronized
    fun detachServiceFactory(context: Context) {
        server?.updateUpstreamFactory(DefaultDataSource.Factory(context.applicationContext))
    }

    @Synchronized
    fun getOrCreateServer(context: Context): WebCastServer {
        server?.let { return it }
        val fallbackFactory = DefaultDataSource.Factory(context.applicationContext)
        val created = WebCastServer(context.applicationContext, fallbackFactory)
        bindServer(created)
        return created
    }

    private fun bindServer(created: WebCastServer) {
        server = created
        relayJob?.cancel()
        relayJob = relayScope.launch {
            launch {
                created.state.collect { _state.value = it }
            }
            launch {
                created.commands.collect { _commands.emit(it) }
            }
        }
    }

    fun currentServer(): WebCastServer? = server

    @Synchronized
    fun start(context: Context, requirePin: Boolean = true): WebCastUiState {
        val srv = getOrCreateServer(context)
        val result = srv.start(requirePin)
        _state.value = result
        return result
    }

    @Synchronized
    fun stop() {
        server?.stop()
        _state.value = WebCastUiState()
    }

    fun setRequirePin(requirePin: Boolean) {
        server?.setRequirePin(requirePin)
    }

    fun regeneratePin() {
        server?.regeneratePin()
    }

    fun updateQueue(items: List<WebCastItem>, activeIndex: Int = 0) {
        server?.updateQueue(items, activeIndex)
    }

    fun updatePlaybackSnapshot(
        activeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        server?.updatePlaybackSnapshot(activeIndex, positionMs, durationMs, isPlaying)
    }

    @Synchronized
    fun release() {
        relayJob?.cancel()
        relayJob = null
        server?.close()
        server = null
        _state.value = WebCastUiState()
    }
}
