@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.malekplay.player.cast

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.malekplay.network.LanNetworkSupport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class WebCastItem(
    val id: String,
    val title: String,
    val uri: Uri,
    val mimeType: String,
    val durationMs: Long = 0L,
    val subtitleUri: Uri? = null,
    val subtitleTitle: String? = null
)

data class WebCastViewer(
    val id: String,
    val label: String,
    val host: String,
    val lastSeenMs: Long
)

data class WebCastUiState(
    val running: Boolean = false,
    val primaryUrl: String? = null,
    val quickConnectUrl: String? = null,
    val allUrls: List<String> = emptyList(),
    val pinCode: String = "",
    val requirePin: Boolean = true,
    val currentTitle: String = "",
    val itemCount: Int = 0,
    val viewers: List<WebCastViewer> = emptyList(),
    val error: String? = null
)

sealed interface WebCastRemoteCommand {
    data object Play : WebCastRemoteCommand
    data object Pause : WebCastRemoteCommand
    data class Seek(val positionMs: Long) : WebCastRemoteCommand
    data class SelectIndex(val index: Int) : WebCastRemoteCommand
    data object Next : WebCastRemoteCommand
    data object Previous : WebCastRemoteCommand
}

/**
 * Built-in, zero-install HTTP Live Cast & Cinema Web Console for PCs and Smart TV browsers.
 *
 * Binds to private LAN interfaces only and serves an embedded dark-purple responsive web player
 * with HTTP Range video/audio streaming, automatic SRT/ASS -> WebVTT subtitle translation,
 * full queue browsing, direct file download, keyboard shortcuts, and live phone sync.
 */
class WebCastServer internal constructor(
    context: Context,
    @Volatile private var upstreamFactory: DataSource.Factory,
    private val privateIpv4Provider: () -> List<Inet4Address>,
    private val allowLoopbackForTest: Boolean = false,
    private val nanoTimeProvider: () -> Long = System::nanoTime,
    private val peerValidator: (remote: InetAddress?, local: InetAddress?) -> Boolean = { remote, local ->
        LanNetworkSupport.isPeerOnActiveLocalLan(remote, local, allowLoopback = allowLoopbackForTest)
    }
) : AutoCloseable {
    constructor(context: Context, upstreamFactory: DataSource.Factory) :
        this(context, upstreamFactory, LanNetworkSupport::findAllPrivateIpv4s, false)
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val random = SecureRandom()
    @Volatile private var accessToken: String = ""
    @Volatile private var pinCode: String = generatePin()
    @Volatile private var requirePinForCleanUrl: Boolean = true

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var workers: ThreadPoolExecutor = LanNetworkSupport.createBoundedHttpExecutor("MalekWebCast")
    @Volatile private var boundPort: Int = 0
    @Volatile private var boundAddress: Inet4Address? = null

    private val itemsLock = Any()
    @Volatile private var queueItems: List<WebCastItem> = emptyList()
    @Volatile private var currentIndex: Int = 0
    @Volatile private var phonePositionMs: Long = 0L
    @Volatile private var phoneDurationMs: Long = 0L
    @Volatile private var phoneIsPlaying: Boolean = false
    @Volatile private var stateRevision: Long = 1L

    private data class PinAttemptState(
        val failCount: Int,
        val lastAttemptNs: Long,
        val lockedUntilNs: Long
    )

    private val lengthByUri = ConcurrentHashMap<String, Long>()
    private val viewersById = ConcurrentHashMap<String, WebCastViewer>()
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val failedPinAttemptsByHost = ConcurrentHashMap<String, PinAttemptState>()
    private val pinLock = Any()
    private var globalPinWindowStartNs: Long = 0L
    private var globalPinFailureCount: Int = 0

    internal fun activeConnectionCountForTest(): Int = activeSockets.size
    internal fun pinAttemptMapSizeForTest(): Int = failedPinAttemptsByHost.size

    private val _state = MutableStateFlow(WebCastUiState())
    val state: StateFlow<WebCastUiState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<WebCastRemoteCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<WebCastRemoteCommand> = _commands.asSharedFlow()

    private fun generatePin(): String = "%06d".format(random.nextInt(1_000_000))
    private fun generateAccessToken(): String =
        ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun start(requirePin: Boolean = true): WebCastUiState {
        requirePinForCleanUrl = requirePin
        if (running.get()) {
            publishUiState()
            return _state.value
        }
        val ips = privateIpv4Provider()
        if (ips.isEmpty()) {
            val err = "اتصل بشبكة Wi-Fi أو فعّل نقطة الاتصال لبدء البث إلى الكمبيوتر."
            _state.value = WebCastUiState(running = false, error = err)
            throw IOException(err)
        }
        accessToken = generateAccessToken()
        pinCode = generatePin()
        synchronized(pinLock) {
            failedPinAttemptsByHost.clear()
            globalPinWindowStartNs = 0L
            globalPinFailureCount = 0
        }
        val socket = bindPreferredPort(ips)
        serverSocket = socket
        boundPort = socket.localPort
        if (workers.isShutdown || workers.isTerminated) {
            workers = LanNetworkSupport.createBoundedHttpExecutor("MalekWebCast")
        }
        running.set(true)
        workers.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    activeSockets.add(client)
                    if (!running.get()) {
                        activeSockets.remove(client)
                        runCatching { client.close() }
                        break
                    }
                    try {
                        workers.execute { handle(client) }
                    } catch (_: RejectedExecutionException) {
                        activeSockets.remove(client)
                        runCatching {
                            client.soTimeout = 2_000
                            val out = BufferedOutputStream(client.getOutputStream())
                            respondText(out, 503, "Service Unavailable", "text/plain", "Server busy")
                        }
                        runCatching { client.close() }
                    }
                } catch (_: IOException) {
                    if (running.get()) stop()
                }
            }
        }
        workers.execute {
            while (running.get()) {
                try {
                    Thread.sleep(4_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                val cutoff = System.currentTimeMillis() - VIEWER_TTL_MS
                var removedAny = false
                val iterator = viewersById.entries.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value.lastSeenMs < cutoff) {
                        iterator.remove()
                        removedAny = true
                    }
                }
                if (removedAny) publishUiState()
            }
        }
        publishUiState()
        return _state.value
    }

    fun updateUpstreamFactory(factory: DataSource.Factory) {
        upstreamFactory = factory
    }

    @Synchronized
    fun stop() {
        val wasRunning = running.compareAndSet(true, false)
        accessToken = ""
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        boundAddress = null
        activeSockets.toList().forEach { sock -> runCatching { sock.close() } }
        activeSockets.clear()
        if (wasRunning) {
            lengthByUri.clear()
            viewersById.clear()
            synchronized(pinLock) {
                failedPinAttemptsByHost.clear()
                globalPinWindowStartNs = 0L
                globalPinFailureCount = 0
            }
            workers.shutdownNow()
            runCatching { workers.awaitTermination(500L, TimeUnit.MILLISECONDS) }
        }
        _state.value = WebCastUiState()
    }

    override fun close() {
        stop()
    }

    fun setRequirePin(requirePin: Boolean) {
        requirePinForCleanUrl = requirePin
        publishUiState()
    }

    @Synchronized
    fun regeneratePin() {
        pinCode = generatePin()
        if (running.get()) {
            accessToken = generateAccessToken()
            synchronized(pinLock) {
                failedPinAttemptsByHost.clear()
                globalPinWindowStartNs = 0L
                globalPinFailureCount = 0
            }
        }
        publishUiState()
    }

    fun updateQueue(items: List<WebCastItem>, activeIndex: Int = 0) {
        synchronized(itemsLock) {
            queueItems = items
            currentIndex = if (items.isEmpty()) 0 else activeIndex.coerceIn(0, items.lastIndex)
            stateRevision++
        }
        publishUiState()
    }

    fun updatePlaybackSnapshot(
        activeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        synchronized(itemsLock) {
            if (queueItems.isNotEmpty()) {
                currentIndex = activeIndex.coerceIn(0, queueItems.lastIndex)
            }
            phonePositionMs = positionMs.coerceAtLeast(0L)
            phoneDurationMs = durationMs.coerceAtLeast(0L)
            phoneIsPlaying = isPlaying
        }
    }

    fun refreshViewers() {
        val cutoff = System.currentTimeMillis() - VIEWER_TTL_MS
        val iterator = viewersById.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.lastSeenMs < cutoff) iterator.remove()
        }
        publishUiState()
    }

    private fun publishUiState() {
        if (!running.get()) {
            _state.value = WebCastUiState()
            return
        }
        val urls = privateIpv4Provider().map { "http://${it.hostAddress}:$boundPort" }
        val primary = urls.firstOrNull()
        val tokenSnapshot = accessToken
        val quick = if (primary != null && tokenSnapshot.isNotEmpty()) "$primary/?k=$tokenSnapshot" else null
        val currentTitle = synchronized(itemsLock) {
            queueItems.getOrNull(currentIndex)?.title.orEmpty()
        }
        val count = synchronized(itemsLock) { queueItems.size }
        val cutoff = System.currentTimeMillis() - VIEWER_TTL_MS
        val activeViewers = viewersById.values
            .filter { it.lastSeenMs >= cutoff }
            .sortedByDescending { it.lastSeenMs }
        _state.value = WebCastUiState(
            running = true,
            primaryUrl = primary,
            quickConnectUrl = quick,
            allUrls = urls,
            pinCode = pinCode,
            requirePin = requirePinForCleanUrl,
            currentTitle = currentTitle,
            itemCount = count,
            viewers = activeViewers,
            error = null
        )
    }

    private fun bindPreferredPort(ips: List<Inet4Address>): ServerSocket {
        // Bind directly to the single private LAN IP when only one LAN interface is active.
        // Technical POSIX/Android constraint: a single ServerSocket cannot bind to multiple discrete
        // interface IPs simultaneously; when multiple LAN interfaces (Wi-Fi + Hotspot + USB) exist,
        // we bind on wildcard and enforce strict active-LAN subnet + local-endpoint validation
        // on every accepted connection via LanNetworkSupport.isPeerOnActiveLocalLan().
        val singleBindIp = if (ips.size == 1) ips.first() else null
        boundAddress = singleBindIp
        for (port in DEFAULT_PORT_START..DEFAULT_PORT_END) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(
                        if (singleBindIp != null) InetSocketAddress(singleBindIp, port)
                        else InetSocketAddress(port),
                        32
                    )
                }
            } catch (_: IOException) {
                continue
            }
        }
        return ServerSocket().apply {
            reuseAddress = true
            bind(
                if (singleBindIp != null) InetSocketAddress(singleBindIp, 0)
                else InetSocketAddress(0),
                32
            )
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                try {
                    client.soTimeout = 10_000
                    val remoteAddr = client.inetAddress
                    if (!running.get() || !peerValidator(remoteAddr, client.localAddress)) {
                        return
                    }
                    val remoteHost = LanNetworkSupport.formatHostPreservingScope(remoteAddr)
                        ?: remoteAddr.hostAddress.orEmpty()

                    val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val requestLine = CastMediaHttpServer.readHttpLine(input, 4096) ?: return
                val request = requestLine.split(' ')
                if (request.size < 2) {
                    respondText(output, 400, "Bad Request", "text/plain", "Bad Request")
                    return
                }
                val method = request[0].uppercase()
                val rawTarget = request[1]
                val parsedUri = Uri.parse(rawTarget)
                val path = parsedUri.path ?: "/"

                var rangeHeader: String? = null
                var cookieHeader: String? = null
                var tokenHeader: String? = null
                var userAgent = ""
                var contentLength = 0
                var headerBytes = 0

                while (true) {
                    val line = CastMediaHttpServer.readHttpLine(input, 8192) ?: break
                    headerBytes += line.length
                    if (headerBytes > 16_384) {
                        respondText(output, 431, "Request Header Fields Too Large", "text/plain", "Headers too large")
                        return
                    }
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        when {
                            name.equals("Range", true) -> rangeHeader = value
                            name.equals("Cookie", true) -> cookieHeader = value
                            name.equals("X-Malek-Token", true) -> tokenHeader = value
                            name.equals("User-Agent", true) -> userAgent = value
                            name.equals("Content-Length", true) -> contentLength = value.toIntOrNull() ?: -1
                        }
                    }
                }

                if (contentLength < 0) {
                    respondText(output, 400, "Bad Request", "text/plain", "Invalid Content-Length")
                    return
                }

                val queryToken = parsedUri.getQueryParameter("k")
                val cookieToken = parseCookieToken(cookieHeader)
                val isAuthorized = !requirePinForCleanUrl ||
                    isTokenValid(queryToken) ||
                    isTokenValid(cookieToken) ||
                    isTokenValid(tokenHeader)

                if (path == "/" || path == "/index.html") {
                    val currentToken = accessToken
                    val setCookie = if (isTokenValid(queryToken) && currentToken.isNotEmpty()) {
                        "malek_cast=$currentToken; Path=/; HttpOnly; SameSite=Lax"
                    } else null
                    val html = if (isAuthorized) {
                        WEB_PLAYER_HTML
                    } else {
                        PIN_LOGIN_HTML
                    }
                    val headers = linkedMapOf(
                        "Content-Type" to "text/html; charset=utf-8",
                        "Cache-Control" to "no-store",
                        "X-Content-Type-Options" to "nosniff",
                        "Connection" to "close"
                    )
                    if (setCookie != null) headers["Set-Cookie"] = setCookie
                    respondBytes(output, 200, "OK", html.toByteArray(StandardCharsets.UTF_8), headers)
                    return
                }

                if (path == "/api/auth" && method == "POST") {
                    handlePinAuth(input, output, contentLength, remoteHost)
                    return
                }

                if (!isAuthorized) {
                    respondJson(output, 401, JSONObject().put("ok", false).put("error", "PIN_REQUIRED").toString())
                    return
                }

                when {
                    path == "/api/state" && method == "GET" -> {
                        recordViewer(remoteHost, userAgent)
                        respondJson(output, 200, buildStateJson().toString())
                    }
                    path == "/api/command" && method == "POST" -> {
                        handleCommand(input, output, contentLength)
                    }
                    path.startsWith("/stream/") && (method == "GET" || method == "HEAD") -> {
                        val segments = parsedUri.pathSegments
                        val itemId = segments.getOrNull(1)
                        val item = synchronized(itemsLock) {
                            queueItems.firstOrNull { it.id == itemId }
                        }
                        if (item == null) {
                            respondText(output, 404, "Not Found", "text/plain", "Media not found")
                            return
                        }
                        client.soTimeout = 30_000
                        val asDownload = parsedUri.getQueryParameter("download") == "1"
                        serveMedia(output, method, item, rangeHeader, asDownload)
                    }
                    path.startsWith("/sub/") && (method == "GET" || method == "HEAD") -> {
                        val segments = parsedUri.pathSegments
                        val itemId = segments.getOrNull(1)
                        val item = synchronized(itemsLock) {
                            queueItems.firstOrNull { it.id == itemId }
                        }
                        val subUri = item?.subtitleUri
                        if (subUri == null) {
                            respondText(output, 404, "Not Found", "text/plain", "Subtitle not found")
                            return
                        }
                        val wantVtt = !path.endsWith(".srt", ignoreCase = true)
                        serveSubtitle(output, method, subUri, item.subtitleTitle.orEmpty(), wantVtt)
                    }
                    else -> {
                        respondText(output, 404, "Not Found", "text/plain", "Not Found")
                    }
                }
            } catch (_: Exception) {
                // Client disconnected or aborted stream.
            }
        }
        } finally {
            activeSockets.remove(socket)
        }
    }

    private fun handlePinAuth(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        contentLength: Int,
        remoteHost: String
    ) {
        if (contentLength <= 0 || contentLength > 1024) {
            respondJson(
                output,
                if (contentLength > 1024) 413 else 400,
                JSONObject().put("ok", false).put("error", "طلب مصادقة غير صالح.").toString()
            )
            return
        }
        val bodyBytes = readFixedBody(input, contentLength, maxBytes = 1024)
        val submittedPin = runCatching {
            JSONObject(String(bodyBytes, StandardCharsets.UTF_8)).optString("pin", "").trim()
        }.getOrDefault("")

        val (status, waitSec) = evaluatePinAttempt(remoteHost, submittedPin)
        when (status) {
            429 -> respondJson(
                output,
                429,
                JSONObject().put("ok", false).put("error", "محاولات كثيرة خاطئة. انتظر $waitSec ثانية.").toString()
            )
            200 -> {
                val currentToken = accessToken
                val jsonBytes = JSONObject().put("ok", true).toString().toByteArray(StandardCharsets.UTF_8)
                val headers = linkedMapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "Cache-Control" to "no-store",
                    "Set-Cookie" to "malek_cast=$currentToken; Path=/; HttpOnly; SameSite=Lax",
                    "Connection" to "close"
                )
                respondBytes(output, 200, "OK", jsonBytes, headers)
            }
            else -> respondJson(
                output,
                403,
                JSONObject().put("ok", false).put("error", "رمز PIN غير صحيح.").toString()
            )
        }
    }

    internal fun evaluatePinAttempt(remoteHost: String, submittedPin: String): Pair<Int, Long> {
        val nowNs = nanoTimeProvider()
        val rateLimitedWaitSec = synchronized(pinLock) {
            pruneExpiredPinAttemptsLocked(nowNs)
            val currentHostState = failedPinAttemptsByHost[remoteHost]
            if (currentHostState != null && nowNs < currentHostState.lockedUntilNs) {
                return@synchronized ((currentHostState.lockedUntilNs - nowNs) / 1_000_000_000L).coerceAtLeast(1L)
            }
            if (currentHostState != null &&
                currentHostState.lockedUntilNs > 0L &&
                nowNs >= currentHostState.lockedUntilNs &&
                nowNs - currentHostState.lastAttemptNs >= PIN_ATTEMPT_WINDOW_NS
            ) {
                failedPinAttemptsByHost.remove(remoteHost)
            }
            if (globalPinWindowStartNs == 0L || nowNs - globalPinWindowStartNs >= GLOBAL_PIN_WINDOW_NS) {
                globalPinWindowStartNs = nowNs
                globalPinFailureCount = 0
            }
            if (globalPinFailureCount >= MAX_GLOBAL_PIN_FAILURES_PER_WINDOW) {
                return@synchronized ((GLOBAL_PIN_WINDOW_NS - (nowNs - globalPinWindowStartNs)) / 1_000_000_000L)
                    .coerceAtLeast(1L)
            }
            0L
        }

        if (rateLimitedWaitSec > 0L) {
            return 429 to rateLimitedWaitSec
        }

        val currentToken = accessToken
        if (currentToken.isNotEmpty() && CastMediaHttpServer.constantTimeEquals(submittedPin, pinCode)) {
            synchronized(pinLock) {
                failedPinAttemptsByHost.remove(remoteHost)
            }
            return 200 to 0L
        } else {
            synchronized(pinLock) {
                pruneExpiredPinAttemptsLocked(nowNs)
                val existing = failedPinAttemptsByHost[remoteHost]
                val prevCount = if (existing != null && nowNs - existing.lastAttemptNs < PIN_ATTEMPT_WINDOW_NS) {
                    existing.failCount
                } else 0
                val nextCount = prevCount + 1
                val lockDurationNs = when {
                    nextCount >= 10 -> 120_000_000_000L
                    nextCount >= 5 -> 30_000_000_000L
                    else -> 0L
                }
                if (existing == null && failedPinAttemptsByHost.size >= MAX_TRACKED_HOSTS) {
                    val oldestKey = failedPinAttemptsByHost.entries
                        .minByOrNull { it.value.lastAttemptNs }?.key
                    if (oldestKey != null) failedPinAttemptsByHost.remove(oldestKey)
                }
                failedPinAttemptsByHost[remoteHost] = PinAttemptState(
                    failCount = nextCount,
                    lastAttemptNs = nowNs,
                    lockedUntilNs = if (lockDurationNs > 0L) nowNs + lockDurationNs else 0L
                )
                if (globalPinWindowStartNs == 0L || nowNs - globalPinWindowStartNs >= GLOBAL_PIN_WINDOW_NS) {
                    globalPinWindowStartNs = nowNs
                    globalPinFailureCount = 1
                } else {
                    globalPinFailureCount++
                }
            }
            return 403 to 0L
        }
    }

    private fun pruneExpiredPinAttemptsLocked(nowNs: Long) {
        val iterator = failedPinAttemptsByHost.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            if (nowNs >= state.lockedUntilNs && nowNs - state.lastAttemptNs >= PIN_ATTEMPT_WINDOW_NS) {
                iterator.remove()
            }
        }
    }

    private fun handleCommand(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        contentLength: Int
    ) {
        if (contentLength <= 0 || contentLength > 4096) {
            respondJson(
                output,
                if (contentLength > 4096) 413 else 400,
                JSONObject().put("ok", false).put("error", "Invalid command payload size").toString()
            )
            return
        }
        val bodyBytes = readFixedBody(input, contentLength, maxBytes = 4096)
        val json = runCatching { JSONObject(String(bodyBytes, StandardCharsets.UTF_8)) }.getOrNull()
        if (json == null) {
            respondJson(output, 400, JSONObject().put("ok", false).put("error", "Invalid JSON").toString())
            return
        }
        val action = json.optString("action", "")
        when (action) {
            "play" -> _commands.tryEmit(WebCastRemoteCommand.Play)
            "pause" -> _commands.tryEmit(WebCastRemoteCommand.Pause)
            "seek" -> {
                val posMs = json.optLong("positionMs", -1L)
                if (posMs >= 0L) _commands.tryEmit(WebCastRemoteCommand.Seek(posMs))
            }
            "next" -> _commands.tryEmit(WebCastRemoteCommand.Next)
            "prev" -> _commands.tryEmit(WebCastRemoteCommand.Previous)
            "select" -> {
                val idx = json.optInt("index", -1)
                val valid = synchronized(itemsLock) {
                    if (idx in queueItems.indices) {
                        currentIndex = idx
                        stateRevision++
                        true
                    } else false
                }
                if (valid) {
                    _commands.tryEmit(WebCastRemoteCommand.SelectIndex(idx))
                    publishUiState()
                }
            }
            else -> {
                respondJson(output, 400, JSONObject().put("ok", false).put("error", "Unknown command").toString())
                return
            }
        }
        respondJson(output, 200, buildStateJson().put("ok", true).toString())
    }

    private fun buildStateJson(): JSONObject {
        val snapshot = synchronized(itemsLock) {
            Tuple4(queueItems, currentIndex, phonePositionMs, phoneDurationMs to phoneIsPlaying)
        }
        val items = snapshot.first
        val idx = snapshot.second
        val posMs = snapshot.third
        val durMs = snapshot.fourth.first
        val playing = snapshot.fourth.second

        val array = JSONArray()
        items.forEachIndexed { index, item ->
            val streamFile = CastMediaHttpServer.buildSafeStreamFileName(item.uri, item.mimeType, item.title)
            val itemObj = JSONObject()
                .put("index", index)
                .put("id", item.id)
                .put("title", item.title)
                .put("mimeType", item.mimeType)
                .put("durationMs", item.durationMs)
                .put("streamUrl", "/stream/${item.id}/$streamFile?k=$accessToken")
                .put("downloadUrl", "/stream/${item.id}/$streamFile?download=1&k=$accessToken")
                .put("hasSubtitle", item.subtitleUri != null)
            if (item.subtitleUri != null) {
                itemObj.put("subtitleVttUrl", "/sub/${item.id}/sub.vtt?k=$accessToken")
                itemObj.put("subtitleSrtUrl", "/sub/${item.id}/sub.srt?k=$accessToken")
            }
            array.put(itemObj)
        }
        return JSONObject()
            .put("ok", true)
            .put("revision", stateRevision)
            .put("currentIndex", idx)
            .put("phonePositionMs", posMs)
            .put("phoneDurationMs", durMs)
            .put("phoneIsPlaying", playing)
            .put("items", array)
    }

    private fun recordViewer(host: String, userAgent: String) {
        val friendly = friendlyBrowserLabel(userAgent)
        val id = "$host|$friendly"
        val now = System.currentTimeMillis()
        val prev = viewersById[id]
        viewersById[id] = WebCastViewer(id = id, label = friendly, host = host, lastSeenMs = now)
        if (prev == null) {
            publishUiState()
        }
    }

    private fun friendlyBrowserLabel(ua: String): String {
        val browser = when {
            ua.contains("Edg/", true) -> "Edge"
            ua.contains("Chrome/", true) -> "Chrome"
            ua.contains("Firefox/", true) -> "Firefox"
            ua.contains("Safari/", true) && !ua.contains("Chrome/", true) -> "Safari"
            ua.contains("Web0S", true) || ua.contains("webOS", true) -> "LG TV Browser"
            ua.contains("Tizen", true) -> "Samsung TV Browser"
            else -> "Browser"
        }
        val os = when {
            ua.contains("Windows", true) -> "Windows"
            ua.contains("Macintosh", true) || ua.contains("Mac OS", true) -> "Mac"
            ua.contains("Linux", true) && !ua.contains("Android", true) -> "Linux"
            ua.contains("Tizen", true) -> "Tizen"
            ua.contains("Web0S", true) || ua.contains("webOS", true) -> "webOS"
            ua.contains("Android", true) -> "Android TV"
            else -> "PC/TV"
        }
        return "$browser • $os"
    }

    private fun serveSubtitle(
        output: BufferedOutputStream,
        method: String,
        subUri: Uri,
        hint: String,
        wantVtt: Boolean
    ) {
        val raw = runCatching {
            val ds = upstreamFactory.createDataSource()
            try {
                ds.open(DataSpec(subUri))
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total < 4 * 1024 * 1024) {
                    val n = ds.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT || n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    total += n
                }
                CastMediaHttpServer.decodeSubtitleBytes(out.toByteArray())
            } finally {
                runCatching { ds.close() }
            }
        }.getOrNull()

        if (raw == null) {
            respondText(output, 404, "Not Found", "text/plain", "Subtitle unavailable")
            return
        }
        val body = if (wantVtt) CastMediaHttpServer.convertSubtitleToWebVtt(raw, hint) else raw
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to if (wantVtt) "text/vtt; charset=utf-8" else "text/srt; charset=utf-8",
            "Cache-Control" to "no-store",
            "Connection" to "close"
        )
        respondBytes(output, 200, "OK", if (method == "HEAD") ByteArray(0) else bytes, headers, explicitLength = bytes.size.toLong())
    }

    private fun serveMedia(
        output: BufferedOutputStream,
        method: String,
        item: WebCastItem,
        range: String?,
        asDownload: Boolean
    ) {
        val uri = item.uri
        val key = uri.toString()
        var totalLength = lengthByUri[key]
        var source: DataSource? = null
        try {
            if (range == null && method == "GET") {
                val created = upstreamFactory.createDataSource()
                val opened = try {
                    created.open(DataSpec(uri))
                } catch (_: IOException) {
                    runCatching { created.close() }
                    lengthByUri.remove(key)
                    respondText(output, 404, "Not Found", "text/plain", "Media not found")
                    return
                }
                source = created
                if (opened >= 0L) {
                    totalLength = opened
                    lengthByUri[key] = opened
                } else {
                    lengthByUri.remove(key)
                    totalLength = null
                }
            } else if (totalLength == null || uri.scheme == ContentResolver.SCHEME_FILE) {
                val probe = upstreamFactory.createDataSource()
                try {
                    val measured = probe.open(DataSpec(uri))
                    if (measured >= 0L) {
                        totalLength = measured
                        lengthByUri[key] = measured
                    }
                } catch (_: IOException) {
                    lengthByUri.remove(key)
                    respondText(output, 404, "Not Found", "text/plain", "Media not found")
                    return
                } finally {
                    runCatching { probe.close() }
                }
            }

            if (range != null && totalLength == null) {
                respondBytes(
                    output,
                    416,
                    "Range Not Satisfiable",
                    ByteArray(0),
                    mapOf("Accept-Ranges" to "none", "Connection" to "close")
                )
                return
            }

            val parsed = if (range != null) CastMediaHttpServer.parseHttpRange(range, totalLength ?: 0L) else null
            if (range != null && parsed == null) {
                respondBytes(
                    output,
                    416,
                    "Range Not Satisfiable",
                    ByteArray(0),
                    mapOf(
                        "Content-Range" to "bytes */${totalLength ?: 0L}",
                        "Accept-Ranges" to "bytes",
                        "Connection" to "close"
                    )
                )
                return
            }

            val start = parsed?.first ?: 0L
            val knownCount = if (parsed != null) parsed.last - parsed.first + 1L else totalLength
            val status = if (parsed != null) 206 else 200
            val mime = item.mimeType.ifBlank {
                resolver.getType(uri) ?: CastMediaHttpServer.inferMimeFromUriOrName(uri, item.title)
            }
            val headers = linkedMapOf(
                "Content-Type" to mime,
                "Accept-Ranges" to if (totalLength != null) "bytes" else "none",
                "Cache-Control" to "no-store",
                "Connection" to "close"
            )
            if (asDownload) {
                val safeDownloadName = item.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "media" }
                val ext = CastMediaHttpServer.buildSafeStreamFileName(uri, mime, item.title).substringAfterLast('.', "mp4")
                val finalName = if (safeDownloadName.endsWith(".$ext", true)) safeDownloadName else "$safeDownloadName.$ext"
                headers["Content-Disposition"] = "attachment; filename=\"$finalName\""
            }
            if (knownCount != null) headers["Content-Length"] = knownCount.toString()
            else headers["Transfer-Encoding"] = "chunked"
            if (status == 206 && parsed != null) {
                headers["Content-Range"] = "bytes ${parsed.first}-${parsed.last}/$totalLength"
            }

            if (method == "HEAD") {
                CastMediaHttpServer.writeHttpHeaders(output, status, if (status == 206) "Partial Content" else "OK", headers)
                output.flush()
                return
            }

            val mediaSource = source ?: upstreamFactory.createDataSource().also { created ->
                val spec = if (parsed != null) {
                    DataSpec.Builder()
                        .setUri(uri)
                        .setPosition(start)
                        .setLength(knownCount!!)
                        .build()
                } else DataSpec(uri)
                try {
                    created.open(spec)
                } catch (_: IOException) {
                    runCatching { created.close() }
                    lengthByUri.remove(key)
                    respondText(output, 404, "Not Found", "text/plain", "Media not found")
                    return
                }
                source = created
            }

            CastMediaHttpServer.writeHttpHeaders(output, status, if (status == 206) "Partial Content" else "OK", headers)
            CastMediaHttpServer.streamMediaPayload(mediaSource, output, knownCount)
            output.flush()
        } finally {
            runCatching { source?.close() }
        }
    }

    private fun isTokenValid(candidate: String?): Boolean {
        val currentToken = accessToken
        return currentToken.isNotEmpty() &&
            !candidate.isNullOrBlank() &&
            CastMediaHttpServer.constantTimeEquals(candidate, currentToken)
    }

    private fun parseCookieToken(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("malek_cast=") }
            ?.substringAfter("malek_cast=")
            ?.trim()
    }

    private fun readFixedBody(input: BufferedInputStream, contentLength: Int, maxBytes: Int): ByteArray {
        val len = contentLength.coerceIn(0, maxBytes)
        val out = ByteArray(len)
        var readTotal = 0
        while (readTotal < len) {
            val n = input.read(out, readTotal, len - readTotal)
            if (n <= 0) break
            readTotal += n
        }
        return if (readTotal == len) out else out.copyOf(readTotal)
    }

    private fun respondJson(output: BufferedOutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        respondBytes(
            output,
            status,
            if (status == 200) "OK" else "Error",
            bytes,
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Cache-Control" to "no-store",
                "Connection" to "close"
            )
        )
    }

    private fun respondText(output: BufferedOutputStream, status: Int, reason: String, mime: String, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        respondBytes(
            output,
            status,
            reason,
            bytes,
            mapOf("Content-Type" to "$mime; charset=utf-8", "Connection" to "close")
        )
    }

    private fun respondBytes(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        body: ByteArray,
        headers: Map<String, String>,
        explicitLength: Long = body.size.toLong()
    ) {
        val merged = LinkedHashMap<String, String>(headers.size + 1)
        merged.putAll(headers)
        merged["Content-Length"] = explicitLength.toString()
        CastMediaHttpServer.writeHttpHeaders(output, status, reason, merged)
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        private const val DEFAULT_PORT_START = 8780
        private const val DEFAULT_PORT_END = 8795
        private const val VIEWER_TTL_MS = 12_000L
        internal const val MAX_TRACKED_HOSTS = 64
        internal const val MAX_GLOBAL_PIN_FAILURES_PER_WINDOW = 25
        internal const val GLOBAL_PIN_WINDOW_NS = 60_000_000_000L
        internal const val PIN_ATTEMPT_WINDOW_NS = 180_000_000_000L

        private const val PIN_LOGIN_HTML = """<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MalekPlay • البث المباشر</title>
<style>
:root{color-scheme:dark;--bg:#0b0912;--card:#181226;--line:#3d2958;--purple:#a855f7;--text:#f8f3ff;--muted:#b8a8ce}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:grid;place-items:center;padding:20px;background:radial-gradient(circle at 80% 0,#3b1d5e 0,transparent 45%),var(--bg);color:var(--text);font:16px system-ui,-apple-system,sans-serif}
.card{width:100%;max-width:410px;background:var(--card);border:1px solid var(--line);border-radius:24px;padding:28px;box-shadow:0 24px 60px #0009;text-align:center}
.logo{width:56px;height:56px;margin:0 auto 14px;border-radius:18px;display:grid;place-items:center;font-weight:900;font-size:26px;background:linear-gradient(135deg,#c084fc,#7c3aed);box-shadow:0 10px 28px #a855f755}
h1{margin:0 0 6px;font-size:22px}
p{margin:0 0 20px;color:var(--muted);font-size:14px;line-height:1.6}
input{width:100%;padding:14px;border-radius:14px;border:1px solid #53367a;background:#0e0a16;color:#fff;font-size:24px;letter-spacing:8px;text-align:center;outline:none}
input:focus{border-color:var(--purple);box-shadow:0 0 0 3px #a855f733}
button{width:100%;margin-top:14px;padding:13px;border:0;border-radius:14px;background:linear-gradient(100deg,#a855f7,#7c3aed);color:#fff;font-weight:800;font-size:16px;cursor:pointer}
.err{margin-top:12px;color:#ff7b93;font-size:13px;min-height:20px}
</style>
</head>
<body>
<div class="card">
  <div class="logo">M</div>
  <h1>MalekPlay Live Cast</h1>
  <p>أدخل رمز PIN المكوّن من 6 أرقام الظاهر على شاشة الهاتف لفتح البث المباشر.</p>
  <form id="f">
    <input id="pin" inputmode="numeric" maxlength="6" autocomplete="off" placeholder="••••••" autofocus>
    <button type="submit">دخول ومشاهدة البث</button>
  </form>
  <div id="err" class="err"></div>
</div>
<script>
document.getElementById('f').addEventListener('submit', async e => {
  e.preventDefault();
  const err = document.getElementById('err');
  err.textContent = '';
  const pin = document.getElementById('pin').value.trim();
  if (!pin) { err.textContent = 'أدخل رمز PIN أولاً'; return; }
  const r = await fetch('/api/auth', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ pin }) });
  const data = await r.json();
  if (r.ok && data.ok) location.reload();
  else err.textContent = data.error || 'رمز PIN غير صحيح';
});
</script>
</body>
</html>"""

        private const val WEB_PLAYER_HTML = """<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MalekPlay • سينما الكمبيوتر والتلفزيون</title>
<style>
:root{color-scheme:dark;--bg:#09070f;--panel:#151021;--panel2:#1d162e;--line:#382652;--purple:#a855f7;--purple2:#c084fc;--text:#f8f3ff;--muted:#b4a4ca;--good:#4ade80}
*{box-sizing:border-box}
body{margin:0;background:radial-gradient(circle at 82% -10%,#4c1d9555 0,transparent 42%),radial-gradient(circle at 12% 110%,#6b21a833 0,transparent 40%),var(--bg);color:var(--text);font:15px system-ui,-apple-system,Segoe UI,sans-serif}
.wrap{max-width:1380px;margin:auto;padding:18px;display:grid;grid-template-columns:1fr 340px;gap:18px;min-height:100vh}
.header{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;gap:12px;background:#151021cc;backdrop-filter:blur(12px);border:1px solid var(--line);border-radius:18px;padding:12px 18px}
.brand{display:flex;align-items:center;gap:12px}.logo{width:42px;height:42px;border-radius:13px;background:linear-gradient(135deg,#c084fc,#7c3aed);display:grid;place-items:center;font-weight:900;font-size:20px;box-shadow:0 8px 24px #a855f744}
.brand h1{margin:0;font-size:18px}.brand small{color:var(--muted);font-size:12px;display:block}
.pills{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.pill{background:#241a38;border:1px solid var(--line);border-radius:999px;padding:6px 12px;font-size:12px;color:var(--text);display:flex;align-items:center;gap:6px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--good);box-shadow:0 0 10px var(--good)}
.stage{background:var(--panel);border:1px solid var(--line);border-radius:22px;padding:16px;display:flex;flex-direction:column;gap:14px;box-shadow:0 20px 50px #0008}
.video-box{position:relative;background:#000;border-radius:16px;overflow:hidden;border:1px solid #2b1d42;aspect-ratio:16/9;display:grid;place-items:center}
video,img{width:100%;height:100%;max-height:74vh;object-fit:contain;background:#000}
audio{width:90%;max-width:560px}
.audio-card{text-align:center;padding:30px;width:100%}.audio-art{width:96px;height:96px;border-radius:26px;margin:0 auto 14px;display:grid;place-items:center;font-size:38px;background:linear-gradient(135deg,#a855f7,#4c1d95)}
.title-bar{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.title-bar h2{margin:0;font-size:19px;overflow-wrap:anywhere}
.toolbar{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
button,.btn{border:1px solid #4d3570;background:#221836;color:#fff;border-radius:12px;padding:9px 13px;font:inherit;font-size:13px;font-weight:700;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:6px;transition:.15s}
button:hover,.btn:hover{background:#31224d;border-color:var(--purple2)}
button.primary,.btn.primary{background:linear-gradient(100deg,#a855f7,#7c3aed);border-color:transparent}
button.active{background:#6d28d9;border-color:#c084fc}
select{background:#120d1d;color:#fff;border:1px solid #4d3570;border-radius:10px;padding:8px 10px;font:inherit;font-size:13px}
.sync-bar{background:#1b142a;border:1px solid #352450;border-radius:14px;padding:10px 14px;display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;font-size:13px;color:var(--muted)}
.sidebar{background:var(--panel);border:1px solid var(--line);border-radius:22px;padding:16px;display:flex;flex-direction:column;gap:12px;max-height:calc(100vh - 115px)}
.sidebar h3{margin:0;font-size:16px;display:flex;justify-content:space-between;align-items:center}
.search{width:100%;background:#0e0a16;border:1px solid #3f2b5e;border-radius:12px;padding:10px 12px;color:#fff;font:inherit;font-size:13px}
.queue{overflow:auto;display:flex;flex-direction:column;gap:8px;padding-left:2px}
.qitem{background:var(--panel2);border:1px solid transparent;border-radius:14px;padding:10px 12px;cursor:pointer;display:flex;justify-content:space-between;align-items:center;gap:8px;transition:.15s}
.qitem:hover{border-color:#5b3b88}
.qitem.current{border-color:var(--purple);background:linear-gradient(120deg,#2e1b4d,#1b142c);box-shadow:0 0 0 1px #a855f755}
.qtitle{font-weight:700;font-size:13px;overflow-wrap:anywhere}.qmeta{font-size:11px;color:var(--muted);margin-top:3px}
.kbd{display:flex;gap:10px;flex-wrap:wrap;font-size:11px;color:var(--muted)}
.kbd span{background:#120d1d;border:1px solid #31214a;border-radius:7px;padding:3px 7px}
body.theater .sidebar{display:none}
body.theater .wrap{grid-template-columns:1fr}
video::cue{background:rgba(10,7,18,.78);color:#fff;font-size:1.08em;text-shadow:0 2px 4px #000}
@media(max-width:960px){.wrap{grid-template-columns:1fr}.sidebar{max-height:420px}}
</style>
</head>
<body>
<div class="wrap">
  <header class="header">
    <div class="brand">
      <div class="logo">M</div>
      <div>
        <h1>MalekPlay Live Cast</h1>
        <small>بث مباشر فائق السرعة إلى الكمبيوتر والتلفزيون مع الترجمة</small>
      </div>
    </div>
    <div class="pills">
      <div class="pill"><span class="dot"></span><span id="connText">متصل بالهاتف</span></div>
      <button id="langBtn" type="button">EN / عربي</button>
      <button id="theaterBtn" type="button">وضع المسرح</button>
    </div>
  </header>

  <section class="stage">
    <div class="video-box" id="mediaHost">
      <div style="color:#b4a4ca">جارٍ تحميل البث...</div>
    </div>

    <div class="title-bar">
      <h2 id="mediaTitle">في انتظار اختيار ملف...</h2>
      <div class="toolbar">
        <button id="prevBtn" type="button">⏮ السابق</button>
        <button id="nextBtn" type="button">⏭ التالي</button>
        <button id="syncPhoneBtn" type="button" class="primary">🔄 مزامنة من موضع الهاتف</button>
        <button id="subToggleBtn" type="button">💬 الترجمة</button>
        <select id="speedSel" title="السرعة">
          <option value="0.75">0.75x</option>
          <option value="1" selected>1.0x</option>
          <option value="1.25">1.25x</option>
          <option value="1.5">1.5x</option>
          <option value="2">2.0x</option>
        </select>
        <button id="pipBtn" type="button">نافذة عائمة</button>
        <a id="dlLink" class="btn" href="#" download>⬇ تنزيل</a>
      </div>
    </div>

    <div class="sync-bar">
      <div id="phoneStateLabel">حالة الهاتف: جارٍ الاتصال...</div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button id="remoteToggleBtn" type="button">⏯ تشغيل/إيقاف على الهاتف</button>
        <button id="pushPosBtn" type="button">📲 إرسال موضعي الحالي للهاتف</button>
      </div>
    </div>

    <div class="kbd">
      <span>Space: تشغيل/إيقاف</span>
      <span>← / →: تقديم 10ث</span>
      <span>↑ / ↓: الصوت</span>
      <span>F: ملء الشاشة</span>
      <span>M: كتم</span>
      <span>C: الترجمة</span>
      <span>N / P: التالي/السابق</span>
    </div>
  </section>

  <aside class="sidebar">
    <h3><span>قائمة التشغيل والملفات</span><span id="qCount" style="color:var(--muted);font-size:12px">0</span></h3>
    <input id="searchBox" class="search" placeholder="ابحث في القائمة...">
    <div id="queueList" class="queue"></div>
  </aside>
</div>

<script>
const $ = id => document.getElementById(id);
let state = { items: [], currentIndex: 0, revision: 0, phonePositionMs: 0, phoneIsPlaying: false };
let activeItemId = null;
let activeMediaEl = null;
let subtitlesVisible = true;

function fmtTime(ms) {
  const s = Math.max(0, Math.floor((ms || 0) / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h > 0 ? h + ':' + String(m).padStart(2,'0') : m) + ':' + String(sec).padStart(2,'0');
}

async function sendCommand(action, extra = {}) {
  try {
    const r = await fetch('/api/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action, ...extra })
    });
    if (r.ok) {
      const next = await r.json();
      applyState(next, false);
    }
  } catch (_) {}
}

function renderQueue() {
  const q = $('searchBox').value.trim().toLowerCase();
  const root = $('queueList');
  root.replaceChildren();
  const list = state.items.filter(it => !q || (it.title || '').toLowerCase().includes(q));
  $('qCount').textContent = state.items.length + ' ملف';
  if (!list.length) {
    const empty = document.createElement('div');
    empty.style.color = '#b4a4ca';
    empty.style.padding = '16px 4px';
    empty.textContent = 'لا توجد عناصر مطابقة في القائمة.';
    root.append(empty);
    return;
  }
  for (const item of list) {
    const card = document.createElement('div');
    card.className = 'qitem' + (item.index === state.currentIndex ? ' current' : '');
    const left = document.createElement('div');
    const title = document.createElement('div');
    title.className = 'qtitle';
    title.textContent = (item.index + 1) + '. ' + item.title;
    const meta = document.createElement('div');
    meta.className = 'qmeta';
    meta.textContent = (item.mimeType || '') + (item.durationMs > 0 ? ' • ' + fmtTime(item.durationMs) : '') + (item.hasSubtitle ? ' • ترجمة' : '');
    left.append(title, meta);
    const dl = document.createElement('a');
    dl.className = 'btn';
    dl.style.padding = '5px 9px';
    dl.style.fontSize = '11px';
    dl.href = item.downloadUrl;
    dl.textContent = '⬇';
    dl.title = 'تنزيل';
    dl.addEventListener('click', e => e.stopPropagation());
    card.append(left, dl);
    card.addEventListener('click', () => {
      state.currentIndex = item.index;
      loadCurrentItem(0);
      renderQueue();
      sendCommand('select', { index: item.index });
    });
    root.append(card);
  }
}

function loadCurrentItem(seekMs) {
  const item = state.items[state.currentIndex];
  const host = $('mediaHost');
  if (!item) {
    activeItemId = null;
    activeMediaEl = null;
    host.replaceChildren();
    const msg = document.createElement('div');
    msg.style.color = '#b4a4ca';
    msg.textContent = 'اختر فيديو أو صوتاً من الهاتف أو من القائمة الجانبية.';
    host.append(msg);
    $('mediaTitle').textContent = 'لا يوجد ملف قيد البث';
    $('dlLink').href = '#';
    return;
  }
  if (activeItemId === item.id && activeMediaEl) return;
  activeItemId = item.id;
  $('mediaTitle').textContent = item.title;
  $('dlLink').href = item.downloadUrl;
  host.replaceChildren();

  const mime = (item.mimeType || '').toLowerCase();
  if (mime.startsWith('image/')) {
    const img = document.createElement('img');
    img.src = item.streamUrl;
    img.alt = item.title;
    host.append(img);
    activeMediaEl = null;
    return;
  }
  if (mime.startsWith('audio/')) {
    const card = document.createElement('div');
    card.className = 'audio-card';
    const art = document.createElement('div');
    art.className = 'audio-art';
    art.textContent = '🎵';
    const heading = document.createElement('h3');
    heading.textContent = item.title;
    const audio = document.createElement('audio');
    audio.controls = true;
    audio.autoplay = true;
    audio.src = item.streamUrl;
    if (seekMs > 1000) audio.addEventListener('loadedmetadata', () => { audio.currentTime = seekMs / 1000; }, { once: true });
    audio.addEventListener('ended', () => { if (state.currentIndex + 1 < state.items.length) sendCommand('next'); });
    card.append(art, heading, audio);
    host.append(card);
    activeMediaEl = audio;
    return;
  }

  const video = document.createElement('video');
  video.controls = true;
  video.autoplay = true;
  video.playsInline = true;
  video.src = item.streamUrl;
  video.playbackRate = parseFloat($('speedSel').value) || 1;
  if (item.hasSubtitle && item.subtitleVttUrl) {
    const track = document.createElement('track');
    track.kind = 'subtitles';
    track.label = 'الترجمة';
    track.srclang = 'ar';
    track.src = item.subtitleVttUrl;
    track.default = true;
    video.append(track);
    video.addEventListener('loadedmetadata', () => {
      if (video.textTracks[0]) {
        video.textTracks[0].mode = subtitlesVisible ? 'showing' : 'hidden';
      }
    });
  }
  if (seekMs > 1000) {
    video.addEventListener('loadedmetadata', () => { video.currentTime = seekMs / 1000; }, { once: true });
  }
  video.addEventListener('ended', () => {
    if (state.currentIndex + 1 < state.items.length) {
      state.currentIndex++;
      loadCurrentItem(0);
      renderQueue();
      sendCommand('next');
    }
  });
  host.append(video);
  activeMediaEl = video;
}

function applyState(next, allowItemReload) {
  const prevRev = state.revision;
  const prevIdx = state.currentIndex;
  state = next;
  $('connText').textContent = 'متصل بالهاتف';
  $('phoneStateLabel').textContent = 'حالة الهاتف: ' + (next.phoneIsPlaying ? 'يعمل' : 'متوقف مؤقتاً') + ' • ' + fmtTime(next.phonePositionMs) + (next.phoneDurationMs > 0 ? ' / ' + fmtTime(next.phoneDurationMs) : '');
  if (allowItemReload && (prevRev !== next.revision || prevIdx !== next.currentIndex || !activeItemId)) {
    const current = next.items[next.currentIndex];
    if (!current || current.id !== activeItemId) {
      loadCurrentItem(next.phonePositionMs);
    }
    renderQueue();
  }
}

async function pollState() {
  try {
    const r = await fetch('/api/state', { cache: 'no-store' });
    if (r.status === 401) { location.reload(); return; }
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const data = await r.json();
    applyState(data, true);
  } catch (_) {
    $('connText').textContent = 'إعادة الاتصال بالهاتف...';
  }
}

$('searchBox').addEventListener('input', renderQueue);
$('theaterBtn').addEventListener('click', () => document.body.classList.toggle('theater'));
$('langBtn').addEventListener('click', () => {
  const html = document.documentElement;
  const isAr = html.dir === 'rtl';
  html.dir = isAr ? 'ltr' : 'rtl';
  html.lang = isAr ? 'en' : 'ar';
});
$('speedSel').addEventListener('change', () => {
  if (activeMediaEl) activeMediaEl.playbackRate = parseFloat($('speedSel').value) || 1;
});
$('subToggleBtn').addEventListener('click', () => {
  subtitlesVisible = !subtitlesVisible;
  $('subToggleBtn').classList.toggle('active', subtitlesVisible);
  if (activeMediaEl && activeMediaEl.textTracks && activeMediaEl.textTracks[0]) {
    activeMediaEl.textTracks[0].mode = subtitlesVisible ? 'showing' : 'hidden';
  }
});
$('pipBtn').addEventListener('click', async () => {
  if (activeMediaEl && activeMediaEl.requestPictureInPicture) {
    try { await activeMediaEl.requestPictureInPicture(); } catch (_) {}
  }
});
$('syncPhoneBtn').addEventListener('click', () => {
  if (activeMediaEl && state.phonePositionMs >= 0) {
    activeMediaEl.currentTime = state.phonePositionMs / 1000;
  }
});
$('pushPosBtn').addEventListener('click', () => {
  if (activeMediaEl) sendCommand('seek', { positionMs: Math.round(activeMediaEl.currentTime * 1000) });
});
$('remoteToggleBtn').addEventListener('click', () => {
  sendCommand(state.phoneIsPlaying ? 'pause' : 'play');
});
$('prevBtn').addEventListener('click', () => {
  if (state.currentIndex > 0) {
    state.currentIndex--;
    loadCurrentItem(0);
    renderQueue();
    sendCommand('prev');
  }
});
$('nextBtn').addEventListener('click', () => {
  if (state.currentIndex + 1 < state.items.length) {
    state.currentIndex++;
    loadCurrentItem(0);
    renderQueue();
    sendCommand('next');
  }
});
document.addEventListener('keydown', e => {
  if (e.target && ['INPUT','TEXTAREA','SELECT'].includes(e.target.tagName)) return;
  if (!activeMediaEl) return;
  switch (e.key.toLowerCase()) {
    case ' ': e.preventDefault(); if (activeMediaEl.paused) activeMediaEl.play(); else activeMediaEl.pause(); break;
    case 'arrowright': activeMediaEl.currentTime += 10; break;
    case 'arrowleft': activeMediaEl.currentTime = Math.max(0, activeMediaEl.currentTime - 10); break;
    case 'arrowup': e.preventDefault(); activeMediaEl.volume = Math.min(1, activeMediaEl.volume + 0.1); break;
    case 'arrowdown': e.preventDefault(); activeMediaEl.volume = Math.max(0, activeMediaEl.volume - 0.1); break;
    case 'm': activeMediaEl.muted = !activeMediaEl.muted; break;
    case 'f': if (activeMediaEl.requestFullscreen) activeMediaEl.requestFullscreen(); break;
    case 'c': $('subToggleBtn').click(); break;
    case 'n': $('nextBtn').click(); break;
    case 'p': $('prevBtn').click(); break;
  }
});

$('subToggleBtn').classList.add('active');
pollState();
setInterval(pollState, 1800);
</script>
</body>
</html>"""
    }
}
