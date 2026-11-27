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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Independent encrypted TCP media-stream receiver for UltraShare Group Screen Mirroring viewers.
 *
 * Strictly receive-only: viewers never send host input/control commands; the only upstream
 * signal available is requesting an IDR keyframe via MPC2 when recovering from packet loss.
 *
 * State reporting is strictly truthful:
 *   [ConnectionPhase.CONNECTING] -> [ConnectionPhase.CONNECTED_WAITING_CONFIG] ->
 *   [ConnectionPhase.CONFIG_RECEIVED_WAITING_FRAME] -> [ConnectionPhase.FRAME_RECEIVED_RENDERING] ->
 *   [ConnectionPhase.STREAMING] (entered ONLY after [notifyFirstFrameRendered] confirms the decoder
 *   actually rendered a frame onto the viewer `Surface`).
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
        CONNECTED_WAITING_CONFIG,
        CONFIG_RECEIVED_WAITING_FRAME,
        FRAME_RECEIVED_RENDERING,
        STREAMING,
        RECONNECTING,
        SCREEN_PROTECTED,
        STOPPED,
        ERROR
    }

    data class ViewerStatus(
        val phase: ConnectionPhase = ConnectionPhase.CONNECTING,
        val videoConfigReceived: Boolean = false,
        val firstFrameReceived: Boolean = false,
        val firstFrameRendered: Boolean = false,
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
    private val renderedOnSurface = AtomicBoolean(false)
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var readerJob: Job? = null

    private val _status = MutableStateFlow(ViewerStatus())
    val status: StateFlow<ViewerStatus> = _status.asStateFlow()

    /**
     * Called by [AndroidScreenMirrorDecoder] strictly after `MediaCodec.releaseOutputBuffer(idx, true)`
     * renders the first decoded frame onto the attached `Surface`. Never called speculatively.
     */
    fun notifyFirstFrameRendered() {
        if (closed.get()) return
        renderedOnSurface.set(true)
        val cur = _status.value
        if (cur.phase != ConnectionPhase.STOPPED && cur.phase != ConnectionPhase.ERROR) {
            _status.value = cur.copy(
                firstFrameRendered = true,
                phase = if (cur.protectedScreenActive) {
                    ConnectionPhase.SCREEN_PROTECTED
                } else {
                    ConnectionPhase.STREAMING
                }
            )
        }
    }

    /** Resets surface-render confirmation when the underlying `Surface` is destroyed/recreated. */
    fun notifySurfaceDetached() {
        if (closed.get()) return
        renderedOnSurface.set(false)
        val cur = _status.value
        if (cur.phase == ConnectionPhase.STREAMING) {
            _status.value = cur.copy(
                firstFrameRendered = false,
                phase = if (cur.firstFrameReceived) {
                    ConnectionPhase.FRAME_RECEIVED_RENDERING
                } else if (cur.videoConfigReceived) {
                    ConnectionPhase.CONFIG_RECEIVED_WAITING_FRAME
                } else {
                    ConnectionPhase.CONNECTED_WAITING_CONFIG
                }
            )
        }
    }

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
            while (!closed.get() && isActive) {
                if (attempt > 0) {
                    if (attempt > maxReconnectAttempts) {
                        _status.value = _status.value.copy(
                            phase = ConnectionPhase.ERROR,
                            errorMessage = "تعذر إعادة الاتصال ببث شاشة المنشئ."
                        )
                        break
                    }
                    renderedOnSurface.set(false)
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.RECONNECTING,
                        videoConfigReceived = false,
                        firstFrameReceived = false,
                        firstFrameRendered = false,
                        reconnectAttempt = attempt,
                        errorMessage = null
                    )
                    delay(minOf(2_500L, 400L * attempt))
                } else {
                    renderedOnSurface.set(false)
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.CONNECTING,
                        videoConfigReceived = false,
                        firstFrameReceived = false,
                        firstFrameRendered = false,
                        errorMessage = null
                    )
                }

                var keyframeWatchdog: Job? = null
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
                        _status.value = _status.value.copy(
                            phase = ConnectionPhase.CONNECTED_WAITING_CONFIG,
                            videoConfigReceived = false,
                            firstFrameReceived = false,
                            firstFrameRendered = renderedOnSurface.get(),
                            reconnectAttempt = 0,
                            errorMessage = null
                        )
                        onRequestKeyframe()

                        // Periodically re-request an IDR while waiting for first rendered frame
                        // so a lost initial keyframe or late Surface creation recovers promptly.
                        keyframeWatchdog = scope.launch {
                            var pulses = 0
                            while (isActive && !closed.get() && !renderedOnSurface.get() && pulses < 15) {
                                delay(1_200L)
                                if (!closed.get() && !renderedOnSurface.get() && !_status.value.protectedScreenActive) {
                                    pulses++
                                    runCatching { onRequestKeyframe() }
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
                                        val resumedPhase = when {
                                            protectedActive -> ConnectionPhase.SCREEN_PROTECTED
                                            renderedOnSurface.get() -> ConnectionPhase.STREAMING
                                            _status.value.firstFrameReceived -> ConnectionPhase.FRAME_RECEIVED_RENDERING
                                            _status.value.videoConfigReceived -> ConnectionPhase.CONFIG_RECEIVED_WAITING_FRAME
                                            else -> ConnectionPhase.CONNECTED_WAITING_CONFIG
                                        }
                                        _status.value = _status.value.copy(
                                            phase = resumedPhase,
                                            protectedScreenActive = protectedActive
                                        )
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG -> {
                                        val cur = _status.value
                                        val nextPhase = when {
                                            cur.protectedScreenActive -> ConnectionPhase.SCREEN_PROTECTED
                                            renderedOnSurface.get() -> ConnectionPhase.STREAMING
                                            cur.firstFrameReceived -> ConnectionPhase.FRAME_RECEIVED_RENDERING
                                            else -> ConnectionPhase.CONFIG_RECEIVED_WAITING_FRAME
                                        }
                                        _status.value = cur.copy(
                                            phase = nextPhase,
                                            videoConfigReceived = true,
                                            width = hdr.width,
                                            height = hdr.height,
                                            rotation = hdr.rotation
                                        )
                                    }
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR,
                                    UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA -> {
                                        val isRendered = renderedOnSurface.get()
                                        val targetPhase = if (isRendered) {
                                            ConnectionPhase.STREAMING
                                        } else {
                                            ConnectionPhase.FRAME_RECEIVED_RENDERING
                                        }
                                        val cur = _status.value
                                        if (cur.phase != targetPhase || cur.protectedScreenActive || !cur.firstFrameReceived) {
                                            _status.value = cur.copy(
                                                phase = targetPhase,
                                                firstFrameReceived = true,
                                                firstFrameRendered = isRendered,
                                                protectedScreenActive = false,
                                                width = if (hdr.width > 0) hdr.width else cur.width,
                                                height = if (hdr.height > 0) hdr.height else cur.height,
                                                rotation = hdr.rotation
                                            )
                                        }
                                        framesInWindow++
                                        bytesInWindow += frame.payload.size
                                        val nowNs = System.nanoTime()
                                        val elapsedNs = nowNs - windowStartNs
                                        if (elapsedNs >= 1_000_000_000L) {
                                            val fps = ((framesInWindow * 1_000_000_000L) / elapsedNs).toInt()
                                            val kbps = ((bytesInWindow * 8L * 1_000_000L) / elapsedNs).toInt()
                                            val liveRendered = renderedOnSurface.get()
                                            _status.value = _status.value.copy(
                                                phase = if (liveRendered) {
                                                    ConnectionPhase.STREAMING
                                                } else {
                                                    ConnectionPhase.FRAME_RECEIVED_RENDERING
                                                },
                                                firstFrameReceived = true,
                                                firstFrameRendered = liveRendered,
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
                        keyframeWatchdog?.cancel()
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

    /**
     * Connects to [hostAddress]:[streamPort] over the exact interface/network that routes to
     * [hostAddress], avoiding Android's default-route (`wlan0` / mobile data) misrouting when
     * paired over Wi-Fi Direct (`p2p*`), Android 10+ `WifiNetworkSpecifier`, or LocalOnlyHotspot.
     */
    private fun openLanSocket(): Socket {
        var lastError: IOException? = null
        val ctx = context
        if (ctx != null) {
            val candidateNetworks = runCatching {
                UltraShareNetworkRouting.findNetworksForHost(ctx, hostAddress)
            }.getOrDefault(emptyList())
            for (network in candidateNetworks) {
                try {
                    val socket = network.socketFactory.createSocket()
                    socket.connect(InetSocketAddress(hostAddress, streamPort), HANDSHAKE_TIMEOUT_MS)
                    return socket
                } catch (io: IOException) {
                    lastError = io
                }
            }
        }

        val matchingLocalBind = findMatchingLocalInterfaceAddress(hostAddress)
        if (matchingLocalBind != null) {
            var boundSocket: Socket? = null
            try {
                boundSocket = Socket()
                boundSocket.bind(InetSocketAddress(matchingLocalBind, 0))
                boundSocket.connect(InetSocketAddress(hostAddress, streamPort), HANDSHAKE_TIMEOUT_MS)
                return boundSocket
            } catch (io: IOException) {
                runCatching { boundSocket?.close() }
                lastError = io
            }
        }

        val fallback = Socket()
        try {
            fallback.connect(InetSocketAddress(hostAddress, streamPort), HANDSHAKE_TIMEOUT_MS)
            return fallback
        } catch (io: IOException) {
            runCatching { fallback.close() }
            throw lastError ?: io
        }
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

        /**
         * Finds a local up non-loopback IPv4 interface address whose subnet prefix contains
         * [targetHost] (such as `p2p-wlan0-0` on `192.168.49.x/24`), without assuming interface names.
         */
        internal fun findMatchingLocalInterfaceAddress(targetHost: String): Inet4Address? {
            val targetBytes = runCatching {
                (InetAddress.getByName(targetHost) as? Inet4Address)?.address
            }.getOrNull() ?: return null
            val targetInt = ipv4BytesToInt(targetBytes)
            val interfaces = runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces())
            }.getOrDefault(emptyList())
            for (nif in interfaces) {
                val up = runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
                if (!up) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    if (addr.isLoopbackAddress) continue
                    val prefix = ia.networkPrefixLength.toInt().coerceIn(8, 30)
                    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val localInt = ipv4BytesToInt(addr.address)
                    if ((localInt and mask) == (targetInt and mask)) {
                        return addr
                    }
                }
            }
            return null
        }

        internal fun ipv4BytesToInt(bytes: ByteArray): Int {
            require(bytes.size == 4)
            return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        }
    }
}
