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
        STREAMING,
        RECONNECTING,
        SCREEN_PROTECTED,
        STOPPED,
        ERROR
    }

    data class ViewerStatus(
        val phase: ConnectionPhase = ConnectionPhase.CONNECTING,
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

    private val _status = MutableStateFlow(ViewerStatus())
    val status: StateFlow<ViewerStatus> = _status.asStateFlow()

    fun start() {
        check(!closed.get()) { "ScreenMirrorViewerClient is closed." }
        if (readerJob != null) return
        val isValidHost = UltraShareCore.isValidPrivateLanHost(hostAddress) ||
            (allowLoopbackForTest && hostAddress == "127.0.0.1")
        require(isValidHost) { "عنوان المضيف غير صالح لشبكة محلية." }
        require(streamPort in 1024..65535) { "منفذ البث غير صالح." }

        readerJob = scope.launch {
            var attempt = 0
            var expectedMediaSeq = -1L
            var droppedTotal = 0L
            var audioCount = 0L
            while (!closed.get() && isActive) {
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
                        expectedMediaSeq = -1L
                        _status.value = _status.value.copy(
                            phase = ConnectionPhase.STREAMING,
                            reconnectAttempt = 0,
                            errorMessage = null
                        )
                        onRequestKeyframe()

                        var windowStartNs = System.nanoTime()
                        var framesInWindow = 0
                        var bytesInWindow = 0L

                        cipher.use { session ->
                            while (!closed.get() && isActive && !socket.isClosed) {
                                val frame = session.readFrame(input)
                                val hdr = frame.header
                                if (hdr.frameType != UltraShareScreenMirrorProtocol.FrameType.HEARTBEAT) {
                                    if (expectedMediaSeq >= 0L && hdr.mediaSeq > expectedMediaSeq) {
                                        droppedTotal += (hdr.mediaSeq - expectedMediaSeq)
                                    }
                                    expectedMediaSeq = hdr.mediaSeq + 1L
                                }
                                when (hdr.frameType) {
                                    UltraShareScreenMirrorProtocol.FrameType.SCREEN_PROTECTED -> {
                                        val protectedActive = frame.payload.firstOrNull() == 1.toByte()
                                        _status.value = _status.value.copy(
                                            phase = if (protectedActive) ConnectionPhase.SCREEN_PROTECTED else ConnectionPhase.STREAMING,
                                            protectedScreenActive = protectedActive
                                        )
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG -> {
                                        _status.value = _status.value.copy(
                                            width = hdr.width,
                                            height = hdr.height,
                                            rotation = hdr.rotation
                                        )
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR,
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA -> {
                                        framesInWindow++
                                        bytesInWindow += frame.payload.size
                                        val nowNs = System.nanoTime()
                                        val elapsedNs = nowNs - windowStartNs
                                        if (elapsedNs >= 1_000_000_000L) {
                                            val fps = ((framesInWindow * 1_000_000_000L) / elapsedNs).toInt()
                                            val kbps = ((bytesInWindow * 8L * 1_000_000L) / elapsedNs).toInt()
                                            _status.value = _status.value.copy(
                                                phase = ConnectionPhase.STREAMING,
                                                protectedScreenActive = false,
                                                width = if (hdr.width > 0) hdr.width else _status.value.width,
                                                height = if (hdr.height > 0) hdr.height else _status.value.height,
                                                rotation = hdr.rotation,
                                                estimatedFps = fps,
                                                bitrateKbps = kbps,
                                                droppedMediaFrames = droppedTotal
                                            )
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
                    if (closed.get()) break
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
