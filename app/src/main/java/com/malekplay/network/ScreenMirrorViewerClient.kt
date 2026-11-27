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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent encrypted TCP media-stream receiver for UltraShare Group Screen Mirroring viewers.
 *
 * Strictly receive-only: viewers never send host input/control commands; the only upstream
 * signal available is requesting an IDR keyframe via MPC2 when recovering from packet loss.
 *
 * Distinguishes all 7 viewer progression stages explicitly:
 *   1. [ConnectionPhase.PAIRED] — Peer paired in group session
 *   2. [ConnectionPhase.OFFER_ACCEPTED] — Broadcast offer validated & accepted
 *   3. [ConnectionPhase.CONNECTED_WAITING_CONFIG] — TCP + mutual AEAD handshake succeeded («متصل، بانتظار الصورة»)
 *   4. [ConnectionPhase.CONFIG_RECEIVED_WAITING_FRAME] — `VIDEO_CONFIG` received («متصل، بانتظار الصورة»)
 *   5. [ConnectionPhase.FRAME_RECEIVED_RENDERING] — Encoded video frame received from network
 *   6. [ConnectionPhase.FRAME_DECODED_WAITING_RENDER] — Frame decoded by `MediaCodec`, awaiting `Surface` render
 *   7. [ConnectionPhase.STREAMING] — First frame confirmed rendered onto a valid `Surface`
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
    private val onFirstFrameTimeoutRetry: () -> Unit = {},
    private val allowLoopbackForTest: Boolean = false,
    private val maxReconnectAttempts: Int = 8,
    private val firstFrameTimeoutMs: Long = DEFAULT_FIRST_FRAME_TIMEOUT_MS,
    private val maxFirstFrameTimeoutRetries: Int = DEFAULT_MAX_FIRST_FRAME_TIMEOUT_RETRIES
) : Closeable {

    enum class ConnectionPhase {
        PAIRED,
        OFFER_ACCEPTED,
        CONNECTING,
        CONNECTED_WAITING_CONFIG,
        CONFIG_RECEIVED_WAITING_FRAME,
        FRAME_RECEIVED_RENDERING,
        FRAME_DECODED_WAITING_RENDER,
        STREAMING,
        RECONNECTING,
        SCREEN_PROTECTED,
        STOPPED,
        ERROR
    }

    data class ViewerStatus(
        val phase: ConnectionPhase = ConnectionPhase.PAIRED,
        val paired: Boolean = true,
        val offerAccepted: Boolean = false,
        val connectedToSource: Boolean = false,
        val videoConfigReceived: Boolean = false,
        val firstFrameReceived: Boolean = false,
        val firstFrameDecoded: Boolean = false,
        val firstFrameRendered: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0,
        val estimatedFps: Int = 0,
        val bitrateKbps: Int = 0,
        val reconnectAttempt: Int = 0,
        val firstFrameTimeoutRetryCount: Int = 0,
        val droppedMediaFrames: Long = 0L,
        val protectedScreenActive: Boolean = false,
        val audioPacketsReceived: Long = 0L,
        val errorMessage: String? = null
    ) {
        /**
         * Strictly `true` ONLY at Stage 7 ([ConnectionPhase.STREAMING] with [firstFrameRendered]).
         * Pairing, handshake, config, packet receipt, and decode alone NEVER satisfy this property.
         */
        val isDisplayingStream: Boolean
            get() = phase == ConnectionPhase.STREAMING && firstFrameRendered && !protectedScreenActive

        fun arabicStageText(): String = when (phase) {
            ConnectionPhase.PAIRED -> "تم الاقتران، بانتظار دعوة البث"
            ConnectionPhase.OFFER_ACCEPTED -> "تم قبول دعوة البث، جاري فتح الاتصال..."
            ConnectionPhase.CONNECTING -> "جاري الاتصال ببث المضيف..."
            ConnectionPhase.CONNECTED_WAITING_CONFIG,
            ConnectionPhase.CONFIG_RECEIVED_WAITING_FRAME -> "متصل، بانتظار الصورة"
            ConnectionPhase.FRAME_RECEIVED_RENDERING -> "وصل إطار الفيديو، جاري فك الترميز..."
            ConnectionPhase.FRAME_DECODED_WAITING_RENDER -> "تم فك ترميز الإطار، جاري عرضه على الشاشة..."
            ConnectionPhase.STREAMING -> "المشاهدة تعمل"
            ConnectionPhase.RECONNECTING -> "انقطع البث مؤقتاً، جاري إعادة المحاولة..."
            ConnectionPhase.SCREEN_PROTECTED -> "الشاشة متوقفة مؤقتاً أو محمية على جهاز المضيف"
            ConnectionPhase.STOPPED -> "توقف البث"
            ConnectionPhase.ERROR -> errorMessage ?: "تعذر عرض بث الشاشة."
        }
    }

    private val streamKeyCopy = streamKey.copyOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val decodedFrameFlag = AtomicBoolean(false)
    private val renderedOnSurface = AtomicBoolean(false)
    private val terminalErrorFlag = AtomicBoolean(false)
    private val firstFrameTimeoutRetries = AtomicInteger(0)
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var readerJob: Job? = null

    private val _status = MutableStateFlow(ViewerStatus())
    val status: StateFlow<ViewerStatus> = _status.asStateFlow()

    /** Stage 2: Marks that a validated broadcast offer was accepted for this viewer session. */
    fun notifyOfferAccepted() {
        if (closed.get() || terminalErrorFlag.get()) return
        val cur = _status.value
        if (cur.phase == ConnectionPhase.PAIRED) {
            _status.value = cur.copy(
                phase = ConnectionPhase.OFFER_ACCEPTED,
                offerAccepted = true,
                errorMessage = null
            )
        } else {
            _status.value = cur.copy(offerAccepted = true)
        }
    }

    /**
     * Stage 6: Called when `MediaCodec.dequeueOutputBuffer` produces a decoded video frame,
     * BEFORE confirming that the frame was rendered onto a valid `Surface`.
     * Never transitions to [ConnectionPhase.STREAMING].
     */
    fun notifyFirstFrameDecoded() {
        if (closed.get() || terminalErrorFlag.get()) return
        decodedFrameFlag.set(true)
        val cur = _status.value
        if (cur.phase == ConnectionPhase.STOPPED || cur.phase == ConnectionPhase.ERROR) return
        val nextPhase = when {
            cur.protectedScreenActive -> ConnectionPhase.SCREEN_PROTECTED
            renderedOnSurface.get() -> ConnectionPhase.STREAMING
            else -> ConnectionPhase.FRAME_DECODED_WAITING_RENDER
        }
        _status.value = cur.copy(
            firstFrameReceived = true,
            firstFrameDecoded = true,
            firstFrameRendered = renderedOnSurface.get(),
            phase = nextPhase
        )
    }

    /**
     * Stage 7: Called strictly after `MediaCodec.OnFrameRenderedListener` (or guarded valid-Surface
     * latch confirmation) confirms that a decoded frame was rendered onto a valid viewer `Surface`.
     */
    fun notifyFirstFrameRendered() {
        if (closed.get() || terminalErrorFlag.get()) return
        decodedFrameFlag.set(true)
        renderedOnSurface.set(true)
        firstFrameTimeoutRetries.set(0)
        val cur = _status.value
        if (cur.phase != ConnectionPhase.STOPPED && cur.phase != ConnectionPhase.ERROR) {
            _status.value = cur.copy(
                firstFrameReceived = true,
                firstFrameDecoded = true,
                firstFrameRendered = true,
                errorMessage = null,
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
        decodedFrameFlag.set(false)
        renderedOnSurface.set(false)
        val cur = _status.value
        if (cur.phase == ConnectionPhase.STREAMING || cur.phase == ConnectionPhase.FRAME_DECODED_WAITING_RENDER) {
            _status.value = cur.copy(
                firstFrameDecoded = false,
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

    /**
     * Surfaces a decoder or `Surface` failure truthfully. If [fatal] is `true`, transitions
     * immediately to [ConnectionPhase.ERROR] and closes the active stream socket cleanly.
     */
    fun notifyDecoderError(message: String, fatal: Boolean = false) {
        if (closed.get()) return
        renderedOnSurface.set(false)
        if (fatal) {
            terminalErrorFlag.set(true)
            _status.value = _status.value.copy(
                phase = ConnectionPhase.ERROR,
                firstFrameRendered = false,
                errorMessage = message
            )
            runCatching { activeSocket?.close() }
        } else {
            val cur = _status.value
            val degradedPhase = when {
                cur.phase == ConnectionPhase.ERROR || cur.phase == ConnectionPhase.STOPPED -> cur.phase
                cur.firstFrameDecoded -> ConnectionPhase.FRAME_DECODED_WAITING_RENDER
                cur.firstFrameReceived -> ConnectionPhase.FRAME_RECEIVED_RENDERING
                cur.videoConfigReceived -> ConnectionPhase.CONFIG_RECEIVED_WAITING_FRAME
                cur.connectedToSource -> ConnectionPhase.CONNECTED_WAITING_CONFIG
                else -> cur.phase
            }
            _status.value = cur.copy(
                phase = degradedPhase,
                firstFrameRendered = false,
                errorMessage = message
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
        notifyOfferAccepted()

        readerJob = scope.launch {
            var attempt = 0
            var expectedVideoSeq = -1L
            var expectedAudioSeq = -1L
            var droppedTotal = 0L
            var audioCount = 0L
            while (!closed.get() && !terminalErrorFlag.get() && isActive) {
                val timedOutThisSocket = AtomicBoolean(false)
                if (attempt > 0 || firstFrameTimeoutRetries.get() > 0) {
                    if (attempt > maxReconnectAttempts) {
                        _status.value = _status.value.copy(
                            phase = ConnectionPhase.ERROR,
                            connectedToSource = false,
                            errorMessage = "تعذر إعادة الاتصال ببث شاشة المنشئ."
                        )
                        break
                    }
                    decodedFrameFlag.set(false)
                    renderedOnSurface.set(false)
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.RECONNECTING,
                        offerAccepted = true,
                        connectedToSource = false,
                        videoConfigReceived = false,
                        firstFrameReceived = false,
                        firstFrameDecoded = false,
                        firstFrameRendered = false,
                        reconnectAttempt = attempt,
                        firstFrameTimeoutRetryCount = firstFrameTimeoutRetries.get(),
                        errorMessage = null
                    )
                    val backoffAttempt = maxOf(attempt, firstFrameTimeoutRetries.get())
                    delay(minOf(2_000L, 350L * backoffAttempt))
                } else {
                    decodedFrameFlag.set(false)
                    renderedOnSurface.set(false)
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.CONNECTING,
                        offerAccepted = true,
                        connectedToSource = false,
                        videoConfigReceived = false,
                        firstFrameReceived = false,
                        firstFrameDecoded = false,
                        firstFrameRendered = false,
                        errorMessage = null
                    )
                }

                var keyframeWatchdog: Job? = null
                var firstFrameDeadlineJob: Job? = null
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
                            offerAccepted = true,
                            connectedToSource = true,
                            videoConfigReceived = false,
                            firstFrameReceived = false,
                            firstFrameDecoded = decodedFrameFlag.get(),
                            firstFrameRendered = renderedOnSurface.get(),
                            reconnectAttempt = 0,
                            firstFrameTimeoutRetryCount = firstFrameTimeoutRetries.get(),
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

                        // Bounded first-frame timeout with limited safe retries so waiting never hangs forever.
                        if (firstFrameTimeoutMs > 0L) {
                            firstFrameDeadlineJob = scope.launch {
                                delay(firstFrameTimeoutMs)
                                if (closed.get() || terminalErrorFlag.get() || renderedOnSurface.get()) {
                                    return@launch
                                }
                                if (_status.value.protectedScreenActive) {
                                    return@launch
                                }
                                val retryNum = firstFrameTimeoutRetries.incrementAndGet()
                                timedOutThisSocket.set(true)
                                if (retryNum <= maxFirstFrameTimeoutRetries) {
                                    _status.value = _status.value.copy(
                                        phase = ConnectionPhase.RECONNECTING,
                                        firstFrameTimeoutRetryCount = retryNum,
                                        errorMessage = "تأخر ظهور الصورة؛ جاري إعادة طلب إطار مفتاحي وإعادة المزامنة ($retryNum/$maxFirstFrameTimeoutRetries)..."
                                    )
                                    runCatching { onFirstFrameTimeoutRetry() }
                                    runCatching { onRequestKeyframe() }
                                    runCatching { socket.close() }
                                } else {
                                    terminalErrorFlag.set(true)
                                    _status.value = _status.value.copy(
                                        phase = ConnectionPhase.ERROR,
                                        connectedToSource = false,
                                        firstFrameRendered = false,
                                        firstFrameTimeoutRetryCount = maxFirstFrameTimeoutRetries,
                                        errorMessage = "انتهت مهلة انتظار ظهور الصورة من المضيف. تحقق من استمرار البث أو اضغط إعادة المحاولة."
                                    )
                                    runCatching { socket.close() }
                                }
                            }
                        }

                        var windowStartNs = System.nanoTime()
                        var framesInWindow = 0
                        var bytesInWindow = 0L

                        cipher.use { session ->
                            while (!closed.get() && !terminalErrorFlag.get() && isActive && !socket.isClosed) {
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
                                            decodedFrameFlag.get() -> ConnectionPhase.FRAME_DECODED_WAITING_RENDER
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
                                            decodedFrameFlag.get() -> ConnectionPhase.FRAME_DECODED_WAITING_RENDER
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
                                        val isDecoded = decodedFrameFlag.get()
                                        val targetPhase = when {
                                            isRendered -> ConnectionPhase.STREAMING
                                            isDecoded -> ConnectionPhase.FRAME_DECODED_WAITING_RENDER
                                            else -> ConnectionPhase.FRAME_RECEIVED_RENDERING
                                        }
                                        val cur = _status.value
                                        if (cur.phase != targetPhase || cur.protectedScreenActive || !cur.firstFrameReceived) {
                                            _status.value = cur.copy(
                                                phase = targetPhase,
                                                firstFrameReceived = true,
                                                firstFrameDecoded = isDecoded,
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
                                            val liveDecoded = decodedFrameFlag.get()
                                            _status.value = _status.value.copy(
                                                phase = when {
                                                    liveRendered -> ConnectionPhase.STREAMING
                                                    liveDecoded -> ConnectionPhase.FRAME_DECODED_WAITING_RENDER
                                                    else -> ConnectionPhase.FRAME_RECEIVED_RENDERING
                                                },
                                                firstFrameReceived = true,
                                                firstFrameDecoded = liveDecoded,
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
                        firstFrameDeadlineJob?.cancel()
                        activeSocket = null
                        runCatching { socket.close() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (sec: SecurityException) {
                    terminalErrorFlag.set(true)
                    // Authentication/integrity errors must stop immediately without retrying.
                    _status.value = _status.value.copy(
                        phase = ConnectionPhase.ERROR,
                        connectedToSource = false,
                        errorMessage = sec.localizedMessage ?: "فشل التحقق الأمني من بث الشاشة."
                    )
                    break
                } catch (failure: Exception) {
                    if (closed.get() || terminalErrorFlag.get()) break
                    if (!timedOutThisSocket.get()) {
                        attempt++
                        _status.value = _status.value.copy(
                            phase = ConnectionPhase.RECONNECTING,
                            connectedToSource = false,
                            reconnectAttempt = attempt,
                            errorMessage = failure.localizedMessage
                        )
                    }
                }
            }
            if (closed.get() && !terminalErrorFlag.get()) {
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
        const val DEFAULT_FIRST_FRAME_TIMEOUT_MS = 8_000L
        const val DEFAULT_MAX_FIRST_FRAME_TIMEOUT_RETRIES = 2

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
