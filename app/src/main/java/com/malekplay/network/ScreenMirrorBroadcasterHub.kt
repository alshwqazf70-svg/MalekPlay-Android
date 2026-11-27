package com.malekplay.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-viewer, backpressure-managed, encrypted TCP broadcaster for UltraShare Group Screen Mirroring.
 *
 * Key design guarantees:
 * - Independent TCP listener (never blocks MPC2 control frames or V2 file transfers).
 * - Per-viewer bounded ring/deque queue: slow viewers drop stale P/B frames and await the next IDR
 *   without stalling the MediaCodec encoder or faster viewers.
 * - Preserves latest `VIDEO_CONFIG` (SPS/PPS) & `AUDIO_CONFIG` (ASC) and delivers them immediately
 *   when a viewer connects or reconnects, along with triggering an upstream IDR keyframe request.
 * - Mutual authentication via [UltraShareScreenMirrorProtocol.performHostStreamHandshake];
 *   rejects any peer ID not present in [authorizedViewerIds].
 */
class ScreenMirrorBroadcasterHub internal constructor(
    val sessionId: String,
    val hostPeerId: String,
    streamKey: ByteArray,
    private val authorizedViewerIdsProvider: () -> Set<String>,
    private val maxViewers: Int = UltraShareScreenMirrorPolicy.MAX_MIRROR_VIEWERS,
    private val maxQueuedFramesPerViewer: Int = DEFAULT_MAX_QUEUED_FRAMES,
    private val maxQueuedBytesPerViewer: Int = DEFAULT_MAX_QUEUED_BYTES,
    private val allowLoopbackForTest: Boolean = false,
    private val onViewerCountChanged: (Int) -> Unit = {},
    private val onKeyframeRequested: () -> Unit = {}
) : Closeable {

    data class QueuedFrame(
        val frameType: UltraShareScreenMirrorProtocol.FrameType,
        val flags: Int,
        val mediaSeq: Long,
        val timestampUs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val payload: ByteArray
    )

    private val streamKeyCopy = streamKey.copyOf()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextVideoFrameSeq = AtomicLong(0L)
    private val nextAudioFrameSeq = AtomicLong(0L)
    private val nextControlSeq = AtomicLong(0L)
    private val droppedFramesCounter = AtomicLong(0L)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var boundPort: Int = 0
        private set

    private val configLock = Any()
    @Volatile private var latestVideoConfig: QueuedFrame? = null
    @Volatile private var latestAudioConfig: QueuedFrame? = null
    @Volatile private var latestProtectedMarker: QueuedFrame? = null

    private val viewers = ConcurrentHashMap<String, ViewerConnection>()
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    private inner class ViewerConnection(
        val viewerId: String,
        val remoteHost: String,
        val socket: Socket,
        val cipher: UltraShareScreenMirrorProtocol.StreamCipherSession
    ) {
        val queueLock = Object()
        val queue = ArrayDeque<QueuedFrame>()
        var queuedBytes = 0
        var awaitingKeyframe = true
        val closedFlag = AtomicBoolean(false)
        @Volatile var writerJob: Job? = null

        fun enqueue(frame: QueuedFrame, isConfig: Boolean) {
            if (closedFlag.get()) return
            synchronized(queueLock) {
                if (closedFlag.get()) return
                val isVideoIdr = frame.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR
                val isVideoDelta = frame.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA

                if (awaitingKeyframe && isVideoDelta) {
                    droppedFramesCounter.incrementAndGet()
                    return
                }
                if (isVideoIdr) {
                    // Supersede stale un-sent video frames when a fresh IDR arrives so slow viewers catch up cleanly.
                    val iterator = queue.iterator()
                    while (iterator.hasNext()) {
                        val existing = iterator.next()
                        if (existing.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR ||
                            existing.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA
                        ) {
                            iterator.remove()
                            queuedBytes -= existing.payload.size
                        }
                    }
                    awaitingKeyframe = false
                }

                // Backpressure: evict oldest non-config frames if count or byte cap is reached.
                while ((queue.size >= maxQueuedFramesPerViewer || queuedBytes + frame.payload.size > maxQueuedBytesPerViewer) &&
                    queue.isNotEmpty()
                ) {
                    val dropped = popOldestNonConfigLocked() ?: break
                    queuedBytes -= dropped.payload.size
                    droppedFramesCounter.incrementAndGet()
                    if (dropped.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR ||
                        dropped.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA
                    ) {
                        awaitingKeyframe = true
                    }
                }

                if (awaitingKeyframe && isVideoDelta) {
                    return
                }
                if (!isConfig && (queue.size >= maxQueuedFramesPerViewer || queuedBytes + frame.payload.size > maxQueuedBytesPerViewer)) {
                    droppedFramesCounter.incrementAndGet()
                    if (isVideoIdr || isVideoDelta) {
                        awaitingKeyframe = true
                        onKeyframeRequested()
                    }
                    return
                }

                queue.addLast(frame)
                queuedBytes += frame.payload.size
                queueLock.notifyAll()
            }
        }

        private fun popOldestNonConfigLocked(): QueuedFrame? {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.frameType != UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG &&
                    item.frameType != UltraShareScreenMirrorProtocol.FrameType.AUDIO_CONFIG
                ) {
                    iterator.remove()
                    return item
                }
            }
            return null
        }

        fun takeNext(): QueuedFrame? {
            synchronized(queueLock) {
                while (queue.isEmpty() && !closedFlag.get()) {
                    try {
                        queueLock.wait(2_000L)
                    } catch (_: InterruptedException) {
                        return null
                    }
                    if (queue.isEmpty() && !closedFlag.get()) {
                        // Emit lightweight heartbeat to verify liveness when idle.
                        return QueuedFrame(
                            frameType = UltraShareScreenMirrorProtocol.FrameType.HEARTBEAT,
                            flags = 0,
                            mediaSeq = nextControlSeq.get(),
                            timestampUs = System.nanoTime() / 1_000L,
                            width = 0,
                            height = 0,
                            rotation = 0,
                            payload = ByteArray(0)
                        )
                    }
                }
                if (closedFlag.get()) return null
                val next = queue.removeFirst()
                queuedBytes = (queuedBytes - next.payload.size).coerceAtLeast(0)
                return next
            }
        }

        fun close() {
            if (!closedFlag.compareAndSet(false, true)) return
            synchronized(queueLock) {
                queue.clear()
                queuedBytes = 0
                queueLock.notifyAll()
            }
            writerJob?.cancel()
            activeSockets.remove(socket)
            runCatching { socket.close() }
            runCatching { cipher.close() }
        }
    }

    fun start(preferredPort: Int = UltraShareScreenMirrorPolicy.DEFAULT_STREAM_PORT): Int {
        check(!closed.get()) { "ScreenMirrorBroadcasterHub already closed." }
        if (running.get()) return boundPort
        val server = bindPort(preferredPort)
        serverSocket = server
        boundPort = server.localPort
        running.set(true)

        hubScope.launch {
            while (running.get() && isActive) {
                val client = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                if (!running.get()) {
                    runCatching { client.close() }
                    break
                }
                val remoteAddr = client.inetAddress
                val remoteHost = remoteAddr?.hostAddress.orEmpty()
                val validLan = UltraShareCore.isValidPrivateLanHost(remoteHost) ||
                    (allowLoopbackForTest && remoteAddr?.isLoopbackAddress == true)
                if (!validLan) {
                    runCatching { client.close() }
                    continue
                }
                activeSockets.add(client)
                hubScope.launch {
                    handleViewerSocket(client)
                }
            }
        }
        return boundPort
    }

    private fun bindPort(preferredPort: Int): ServerSocket {
        if (preferredPort in 1024..65535) {
            runCatching {
                return ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 750
                    bind(InetSocketAddress(preferredPort), maxViewers * 2)
                }
            }
        }
        return ServerSocket().apply {
            reuseAddress = true
            soTimeout = 750
            bind(InetSocketAddress(0), maxViewers * 2)
        }
    }

    private fun handleViewerSocket(socket: Socket) {
        var registeredViewer: ViewerConnection? = null
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.sendBufferSize = 256 * 1024
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val (viewerId, cipherSession) = UltraShareScreenMirrorProtocol.performHostStreamHandshake(
                input = input,
                output = output,
                sessionId = sessionId,
                hostPeerId = hostPeerId,
                streamKey = streamKeyCopy,
                isAuthorizedViewer = { candidateId ->
                    running.get() && candidateId in authorizedViewerIdsProvider()
                }
            )

            // Reject excess viewers beyond cap if this is a brand new viewer ID.
            if (!viewers.containsKey(viewerId) && viewers.size >= maxViewers) {
                runCatching { cipherSession.close() }
                runCatching { socket.close() }
                return
            }

            socket.soTimeout = STREAM_WRITE_TIMEOUT_MS
            val connection = ViewerConnection(
                viewerId = viewerId,
                remoteHost = socket.inetAddress?.hostAddress.orEmpty(),
                socket = socket,
                cipher = cipherSession
            )
            // Replace any previous connection from the same viewer cleanly (reconnect).
            val previous = viewers.put(viewerId, connection)
            previous?.close()
            registeredViewer = connection
            notifyViewerCount()

            // Immediately enqueue latest configs & protected indicator, and ask encoder for an IDR.
            synchronized(configLock) {
                latestVideoConfig?.let { connection.enqueue(it, isConfig = true) }
                latestAudioConfig?.let { connection.enqueue(it, isConfig = true) }
                latestProtectedMarker?.let { connection.enqueue(it, isConfig = false) }
            }
            onKeyframeRequested()

            val writer = hubScope.launch {
                try {
                    while (running.get() && !connection.closedFlag.get() && !socket.isClosed) {
                        val frame = connection.takeNext() ?: break
                        cipherSession.writeFrame(
                            out = output,
                            frameType = frame.frameType,
                            flags = frame.flags,
                            mediaSeq = frame.mediaSeq,
                            timestampUs = frame.timestampUs,
                            width = frame.width,
                            height = frame.height,
                            rotation = frame.rotation,
                            payload = frame.payload
                        )
                    }
                } catch (_: Exception) {
                    // Disconnecting one viewer never affects the host or other viewers.
                } finally {
                    if (viewers.remove(viewerId, connection)) {
                        notifyViewerCount()
                    }
                    connection.close()
                }
            }
            connection.writerJob = writer
        } catch (_: Exception) {
            if (registeredViewer != null) {
                if (viewers.remove(registeredViewer.viewerId, registeredViewer)) {
                    notifyViewerCount()
                }
                registeredViewer.close()
            } else {
                activeSockets.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    /** Removes a viewer immediately (e.g., when kicked or left MPC2 room). */
    fun disconnectViewer(viewerPeerId: String) {
        val canonical = UltraShareScreenMirrorProtocol.canonicalId(viewerPeerId) ?: return
        val removed = viewers.remove(canonical)
        if (removed != null) {
            removed.close()
            notifyViewerCount()
        }
    }

    /** Ensures only currently authorized peers remain connected. */
    fun pruneUnauthorizedViewers() {
        val allowed = authorizedViewerIdsProvider()
        var changed = false
        viewers.keys.toList().forEach { id ->
            if (id !in allowed) {
                if (viewers.remove(id)?.also { it.close() } != null) {
                    changed = true
                }
            }
        }
        if (changed) notifyViewerCount()
    }

    fun activeViewerCount(): Int = viewers.size
    fun droppedFramesCount(): Long = droppedFramesCounter.get()

    fun publishVideoConfig(csdPayload: ByteArray, width: Int, height: Int, rotation: Int) {
        val frame = QueuedFrame(
            frameType = UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG,
            flags = 0,
            mediaSeq = nextControlSeq.incrementAndGet(),
            timestampUs = 0L,
            width = width,
            height = height,
            rotation = rotation,
            payload = csdPayload.copyOf()
        )
        synchronized(configLock) {
            latestVideoConfig = frame
        }
        viewers.values.forEach { it.enqueue(frame, isConfig = true) }
    }

    fun publishAudioConfig(ascPayload: ByteArray) {
        val frame = QueuedFrame(
            frameType = UltraShareScreenMirrorProtocol.FrameType.AUDIO_CONFIG,
            flags = 0,
            mediaSeq = nextControlSeq.incrementAndGet(),
            timestampUs = 0L,
            width = 0,
            height = 0,
            rotation = 0,
            payload = ascPayload.copyOf()
        )
        synchronized(configLock) {
            latestAudioConfig = frame
        }
        viewers.values.forEach { it.enqueue(frame, isConfig = true) }
    }

    fun publishVideoFrame(
        encodedBytes: ByteArray,
        isKeyframe: Boolean,
        timestampUs: Long,
        width: Int,
        height: Int,
        rotation: Int
    ) {
        if (!running.get() || encodedBytes.isEmpty()) return
        synchronized(configLock) { latestProtectedMarker = null }
        val frame = QueuedFrame(
            frameType = if (isKeyframe) {
                UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR
            } else {
                UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA
            },
            flags = if (isKeyframe) UltraShareScreenMirrorProtocol.FLAG_KEYFRAME else 0,
            mediaSeq = nextVideoFrameSeq.incrementAndGet(),
            timestampUs = timestampUs,
            width = width,
            height = height,
            rotation = rotation,
            payload = encodedBytes.copyOf()
        )
        viewers.values.forEach { it.enqueue(frame, isConfig = false) }
    }

    fun publishAudioFrame(encodedBytes: ByteArray, timestampUs: Long) {
        if (!running.get() || encodedBytes.isEmpty()) return
        val frame = QueuedFrame(
            frameType = UltraShareScreenMirrorProtocol.FrameType.AUDIO_FRAME,
            flags = 0,
            mediaSeq = nextAudioFrameSeq.incrementAndGet(),
            timestampUs = timestampUs,
            width = 0,
            height = 0,
            rotation = 0,
            payload = encodedBytes.copyOf()
        )
        viewers.values.forEach { it.enqueue(frame, isConfig = false) }
    }

    fun publishProtectedScreenState(active: Boolean) {
        if (!running.get()) return
        val frame = QueuedFrame(
            frameType = UltraShareScreenMirrorProtocol.FrameType.SCREEN_PROTECTED,
            flags = if (active) UltraShareScreenMirrorProtocol.FLAG_PROTECTED else 0,
            mediaSeq = nextControlSeq.incrementAndGet(),
            timestampUs = System.nanoTime() / 1_000L,
            width = 0,
            height = 0,
            rotation = 0,
            payload = byteArrayOf(if (active) 1 else 0)
        )
        synchronized(configLock) {
            latestProtectedMarker = if (active) frame else null
        }
        viewers.values.forEach { it.enqueue(frame, isConfig = false) }
    }

    private fun notifyViewerCount() {
        runCatching { onViewerCountChanged(viewers.size) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        viewers.values.forEach { it.close() }
        viewers.clear()
        activeSockets.toList().forEach { sock ->
            runCatching { sock.close() }
        }
        activeSockets.clear()
        hubScope.cancel()
        streamKeyCopy.fill(0)
        notifyViewerCount()
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 8_000
        const val STREAM_WRITE_TIMEOUT_MS = 10_000
        const val DEFAULT_MAX_QUEUED_FRAMES = 16
        const val DEFAULT_MAX_QUEUED_BYTES = 2 * 1024 * 1024
    }
}
