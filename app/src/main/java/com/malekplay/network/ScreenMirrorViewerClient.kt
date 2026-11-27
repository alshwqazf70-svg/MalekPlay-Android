package com.malekplay.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Independent encrypted TCP media-stream receiver for UltraShare Group Screen Mirroring viewers.
 *
 * Strictly receive-only: viewers never send host input/control commands; the only upstream
 * signal available is requesting an IDR keyframe via MPC2 when recovering from packet loss.
 */
class ScreenMirrorViewerClient internal constructor(
    private val context: Context?,
    private val hostAddress: String,
    private val streamPort: Int,
    private val sessionId: String,
    private val hostPeerId: String,
    private val localViewerPeerId: String,
    streamKey: ByteArray,
    private val frameSink: (UltraShareScreenMirrorProtocol.MediaFrame) -> Unit,
    private val onRequestKeyframe: () -> Unit = {},
    private val allowLoopbackForTest: Boolean = false,
    private val maxReconnectAttempts: Int = 8
) : Closeable {

    enum class ConnectionPhase {
        CONNECTING,
        CONNECTED_AWAITING_FIRST_FRAME,
        STREAMING,
        RECONNECTING,
        SCREEN_PROTECTED,
        STOPPED,
        ERROR
    }

    data class ViewerStatus(
        val phase: ConnectionPhase = ConnectionPhase.CONNECTING,
        val stage: MirrorViewerStage = MirrorViewerStage.OFFER_ACCEPTED,
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0,
        val estimatedFps: Int = 0,
        val bitrateKbps: Int = 0,
        val reconnectAttempt: Int = 0,
        val droppedMediaFrames: Long = 0L,
        val protectedScreenActive: Boolean = false,
        val audioPacketsReceived: Long = 0L,
        val errorMessage: String? = null
    )

    private val streamKeyCopy = streamKey.copyOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var watchdogJob: Job? = null
    private val playbackLock = Any()
    private val playbackTracker = MirrorViewerPlaybackTracker(MirrorViewerStage.OFFER_ACCEPTED)

    private val _status = MutableStateFlow(ViewerStatus())
    val status: StateFlow<ViewerStatus> = _status.asStateFlow()

    fun notifyVideoConfigApplied() {
        synchronized(playbackLock) {
            playbackTracker.onVideoConfigReceived()
            syncStageLocked()
        }
    }

    fun notifyVideoFrameReceived() {
        synchronized(playbackLock) {
            playbackTracker.onVideoFrameReceived()
            syncStageLocked()
        }
    }

    fun notifyFrameDecoded(renderedToSurface: Boolean) {
        synchronized(playbackLock) {
            playbackTracker.onFrameDecoded(renderedToSurface)
            syncStageLocked()
        }
    }

    fun notifyFirstFrameRendered() {
        synchronized(playbackLock) {
            val newlyRendered = playbackTracker.onFirstFrameRendered()
            if (newlyRendered || _status.value.phase != ConnectionPhase.STREAMING) {
                val cur = _status.value
                if (cur.phase != ConnectionPhase.STOPPED && cur.phase != ConnectionPhase.ERROR) {
                    _status.value = cur.copy(
                        phase = if (cur.protectedScreenActive) {
                            ConnectionPhase.SCREEN_PROTECTED
                        } else {
                            ConnectionPhase.STREAMING
                        },
                        stage = MirrorViewerStage.FIRST_FRAME_RENDERED,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun notifySurfaceOrDecoderReset() {
        synchronized(playbackLock) {
            playbackTracker.onSurfaceOrDecoderReset(monotonicNowMs())
            val cur = _status.value
            if (cur.phase == ConnectionPhase.STREAMING || cur.phase == ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME) {
                _status.value = cur.copy(
                    phase = ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME,
                    stage = playbackTracker.stage
                )
            }
        }
    }

    fun notifyDecoderFailure(message: String) {
        synchronized(playbackLock) {
            playbackTracker.onDecoderFailure(message)
            _status.value = _status.value.copy(
                phase = ConnectionPhase.ERROR,
                errorMessage = message
            )
        }
        watchdogJob?.cancel()
        runCatching { activeSocket?.close() }
    }

    private fun syncStageLocked() {
        val cur = _status.value
        val nextPhase = when {
            cur.phase == ConnectionPhase.ERROR || cur.phase == ConnectionPhase.STOPPED -> cur.phase
            cur.protectedScreenActive -> ConnectionPhase.SCREEN_PROTECTED
            playbackTracker.firstFrameRendered -> ConnectionPhase.STREAMING
            cur.phase == ConnectionPhase.RECONNECTING || cur.phase == ConnectionPhase.CONNECTING -> cur.phase
            else -> ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME
        }
        if (cur.stage != playbackTracker.stage || cur.phase != nextPhase) {
            _status.value = cur.copy(
                phase = nextPhase,
                stage = playbackTracker.stage
            )
        }
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    fun start() {
        check(!closed.get()) { "ScreenMirrorViewerClient is closed." }
        if (readerJob != null) return
        val isValidHost = UltraShareCore.isValidPrivateLanHost(hostAddress) ||
            (allowLoopbackForTest && hostAddress == "127.0.0.1")
        require(isValidHost) { "عنوان المضيف غير صالح لشبكة محلية." }
        require(streamPort in 1024..65535) { "منفذ البث غير صالح." }

        readerJob = scope.launch {
            var attempt = 0
            var expectedVideoSeq = -1L
            var expectedAudioSeq = -1L
            var droppedTotal = 0L
            var audioCount = 0L
            var terminalFirstFrameTimeout = false
            while (!closed.get() && isActive && !terminalFirstFrameTimeout) {
                if (attempt > 0) {
                    if (attempt > maxReconnectAttempts) {
                        _status.value = _status.value.copy(
                            phase = ConnectionPhase.ERROR,
                            errorMessage = "تعذر إعادة الاتصال ببث شاشة المنشئ."
                        )
                        break
                    }
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.RECONNECTING,
                        reconnectAttempt = attempt,
                        errorMessage = null
                    )
                    delay(minOf(2_500L, 400L * attempt))
                } else {
                    _status.value = _status.value.copy(phase = ConnectionPhase.CONNECTING, errorMessage = null)
                }

                try {
                    val socket = openLanSocket()
                    activeSocket = socket
                    try {
                        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                        socket.tcpNoDelay = true
                        val input = BufferedInputStream(socket.getInputStream())
                        val output = BufferedOutputStream(socket.getOutputStream())
                        val cipher = UltraShareScreenMirrorProtocol.performViewerStreamHandshake(
                            input = input,
                            output = output,
                            expectedSessionId = sessionId,
                            expectedHostPeerId = hostPeerId,
                            localViewerPeerId = localViewerPeerId,
                            streamKey = streamKeyCopy
                        )
                        socket.soTimeout = STREAM_READ_TIMEOUT_MS
                        attempt = 0
                        expectedVideoSeq = -1L
                        expectedAudioSeq = -1L
                        synchronized(playbackLock) {
                            playbackTracker.onConnectedToSource(monotonicNowMs())
                            _status.value = _status.value.copy(
                                phase = ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME,
                                stage = playbackTracker.stage,
                                reconnectAttempt = 0,
                                errorMessage = null
                            )
                        }
                        onRequestKeyframe()

                        watchdogJob?.cancel()
                        watchdogJob = scope.launch {
                            while (!closed.get() && isActive && !socket.isClosed) {
                                delay(500L)
                                val action = synchronized(playbackLock) {
                                    playbackTracker.evaluateFirstFrameWatchdog(monotonicNowMs())
                                }
                                when (action) {
                                    MirrorViewerPlaybackTracker.WatchdogAction.REQUEST_KEYFRAME -> {
                                        onRequestKeyframe()
                                    }
                                    MirrorViewerPlaybackTracker.WatchdogAction.TIMED_OUT -> {
                                        terminalFirstFrameTimeout = true
                                        _status.value = _status.value.copy(
                                            phase = ConnectionPhase.ERROR,
                                            errorMessage = "تم الاتصال بمصدر الفيديو، لكن تعذر وصول وعرض الإطار الأول."
                                        )
                                        runCatching { socket.close() }
                                        break
                                    }
                                    MirrorViewerPlaybackTracker.WatchdogAction.NONE -> Unit
                                }
                            }
                        }

                        var windowStartNs = System.nanoTime()
                        var framesInWindow = 0
                        var bytesInWindow = 0L

                        cipher.use { session ->
                            while (!closed.get() && isActive && !socket.isClosed) {
                                val frame = session.readFrame(input)
                                val hdr = frame.header
                                val prevDropped = droppedTotal
                                when (hdr.frameType) {
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR,
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA -> {
                                        if (expectedVideoSeq >= 0L && hdr.mediaSeq > expectedVideoSeq) {
                                            droppedTotal += (hdr.mediaSeq - expectedVideoSeq)
                                        }
                                        expectedVideoSeq = hdr.mediaSeq + 1L
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.AUDIO_FRAME -> {
                                        if (expectedAudioSeq >= 0L && hdr.mediaSeq > expectedAudioSeq) {
                                            droppedTotal += (hdr.mediaSeq - expectedAudioSeq)
                                        }
                                        expectedAudioSeq = hdr.mediaSeq + 1L
                                    }
                                    else -> Unit
                                }
                                if (droppedTotal != prevDropped) {
                                    _status.value = _status.value.copy(droppedMediaFrames = droppedTotal)
                                }
                                when (hdr.frameType) {
                                    UltraShareScreenMirrorProtocol.FrameType.SCREEN_PROTECTED -> {
                                        val protectedActive = frame.payload.firstOrNull() == 1.toByte()
                                        synchronized(playbackLock) {
                                            val curRendered = playbackTracker.firstFrameRendered
                                            if (!protectedActive && !curRendered) {
                                                playbackTracker.onSurfaceOrDecoderReset(monotonicNowMs())
                                            }
                                            _status.value = _status.value.copy(
                                                phase = when {
                                                    protectedActive -> ConnectionPhase.SCREEN_PROTECTED
                                                    curRendered -> ConnectionPhase.STREAMING
                                                    else -> ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME
                                                },
                                                stage = playbackTracker.stage,
                                                protectedScreenActive = protectedActive
                                            )
                                        }
                                        if (!protectedActive) {
                                            onRequestKeyframe()
                                        }
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG -> {
                                        synchronized(playbackLock) {
                                            playbackTracker.onVideoConfigReceived()
                                            _status.value = _status.value.copy(
                                                stage = playbackTracker.stage,
                                                width = hdr.width,
                                                height = hdr.height,
                                                rotation = hdr.rotation
                                            )
                                        }
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR,
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA -> {
                                        synchronized(playbackLock) {
                                            playbackTracker.onVideoFrameReceived()
                                            val curRendered = playbackTracker.firstFrameRendered
                                            val nextPhase = if (curRendered) {
                                                ConnectionPhase.STREAMING
                                            } else {
                                                ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME
                                            }
                                            if (_status.value.phase != nextPhase || _status.value.protectedScreenActive || _status.value.stage != playbackTracker.stage) {
                                                _status.value = _status.value.copy(
                                                    phase = nextPhase,
                                                    stage = playbackTracker.stage,
                                                    protectedScreenActive = false,
                                                    width = if (hdr.width > 0) hdr.width else _status.value.width,
                                                    height = if (hdr.height > 0) hdr.height else _status.value.height,
                                                    rotation = hdr.rotation
                                                )
                                            }
                                        }
                                        framesInWindow++
                                        bytesInWindow += frame.payload.size
                                        val nowNs = System.nanoTime()
                                        val elapsedNs = nowNs - windowStartNs
                                        if (elapsedNs >= 1_000_000_000L) {
                                            val fps = ((framesInWindow * 1_000_000_000L) / elapsedNs).toInt()
                                            val kbps = ((bytesInWindow * 8L * 1_000_000L) / elapsedNs).toInt()
                                            synchronized(playbackLock) {
                                                val nextPhase = if (_status.value.protectedScreenActive) {
                                                    ConnectionPhase.SCREEN_PROTECTED
                                                } else if (playbackTracker.firstFrameRendered) {
                                                    ConnectionPhase.STREAMING
                                                } else {
                                                    ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME
                                                }
                                                _status.value = _status.value.copy(
                                                    phase = nextPhase,
                                                    stage = playbackTracker.stage,
                                                    protectedScreenActive = false,
                                                    width = if (hdr.width > 0) hdr.width else _status.value.width,
                                                    height = if (hdr.height > 0) hdr.height else _status.value.height,
                                                    rotation = hdr.rotation,
                                                    estimatedFps = fps,
                                                    bitrateKbps = kbps,
                                                    droppedMediaFrames = droppedTotal
                                                )
                                            }
                                            windowStartNs = nowNs
                                            framesInWindow = 0
                                            bytesInWindow = 0L
                                        }
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.AUDIO_FRAME -> {
                                        audioCount++
                                        _status.value = _status.value.copy(audioPacketsReceived = audioCount)
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.AUDIO_CONFIG,
                                    UltraShareScreenMirrorProtocol.FrameType.HEARTBEAT -> Unit
                                }
                                frameSink(frame)
                            }
                        }
                    } finally {
                        watchdogJob?.cancel()
                        watchdogJob = null
                        activeSocket = null
                        runCatching { socket.close() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (sec: SecurityException) {
                    // Authentication/integrity errors must stop immediately without retrying.
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.ERROR,
                        errorMessage = sec.localizedMessage ?: "فشل التحقق الأمني من بث الشاشة."
                    )
                    break
                } catch (failure: Exception) {
                    if (closed.get() || terminalFirstFrameTimeout || synchronized(playbackLock) { playbackTracker.decoderError != null }) break
                    attempt++
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.RECONNECTING,
                        reconnectAttempt = attempt,
                        errorMessage = failure.localizedMessage
                    )
                }
            }
            if (closed.get()) {
                _status.value = _status.value.copy(phase = ConnectionPhase.STOPPED)
            }
        }
    }

    private fun openLanSocket(): Socket {
        val network = context?.let { LanNetworkSupport.findLanNetworkForHost(it, hostAddress) }
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.connect(InetSocketAddress(hostAddress, streamPort), HANDSHAKE_TIMEOUT_MS)
        return socket
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watchdogJob?.cancel()
        watchdogJob = null
        readerJob?.cancel()
        readerJob = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        scope.cancel()
        streamKeyCopy.fill(0)
        _status.value = _status.value.copy(phase = ConnectionPhase.STOPPED)
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 8_000
        const val STREAM_READ_TIMEOUT_MS = 8_000
    }
}
