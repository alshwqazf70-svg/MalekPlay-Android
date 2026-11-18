package com.malekplay.network

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated request/accept/reject channel plus a persistent authenticated
 * control socket. All file bytes travel through UltraShareBatchTransport V2;
 * negotiated MPC2 application frames carry bounded authenticated control messages.
 */
object UltraShareControlChannel {
    const val DEFAULT_PORT = 38_744
    private const val MAGIC = 0x4D504332 // MPC2
    private const val VERSION = 1
    private const val CHALLENGE_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAC_BYTES = 32
    private const val MAX_NAME_BYTES = 96
    private const val MAX_ID_BYTES = 64
    private const val MAX_FRAME_BYTES = 512
    private const val HANDSHAKE_TIMEOUT_MS = 8_000
    private const val DECISION_TIMEOUT_MS = 125_000
    private const val SESSION_TIMEOUT_MS = 18_000
    private const val HEARTBEAT_INTERVAL_MS = 5_000L

    enum class Decision { ACCEPTED, REJECTED }

    data class Request(
        val requestId: String,
        val peerName: String,
        val peerAddress: String,
        val peerId: String? = null,
        /** Local interface address selected by the accepted control socket. */
        val localAddress: String? = null
    )

    private data class ClientSession(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val peerName: String,
        val peerId: String?,
        val applicationFramesEnabled: Boolean,
        val frames: UltraShareControlFrames.Session,
        val incoming: Channel<ByteArray> = Channel(32),
        val lock: Any = Any(),
        var heartbeat: Job? = null
    )

    private data class ServerSession(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val applicationFramesEnabled: Boolean,
        val frames: UltraShareControlFrames.Session,
        val incoming: Channel<ByteArray> = Channel(32),
        val lock: Any = Any(),
        @Volatile var ready: Boolean = false
    )

    private val clientSessions = ConcurrentHashMap<String, ClientSession>()
    // Serialize replacement, explicit close, and stale-heartbeat cleanup so an old
    // session callback can never tear down a newly approved session for the same host.
    private val clientSessionGuard = Any()
    private val serverSessions = ConcurrentHashMap<String, Socket>()
    private val serverApplicationSessions = ConcurrentHashMap<String, ServerSession>()
    private val serverSessionPeerIds = ConcurrentHashMap<String, String>()
    private val pairingAttemptLimiter = UltraSharePairingAttemptLimiter()
    private val diagnosticEvents = java.util.ArrayDeque<String>()
    private const val MAX_DIAGNOSTIC_EVENTS = 100

    private fun diagnostic(event: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(diagnosticEvents) {
            diagnosticEvents.addLast("$timestamp $event")
            while (diagnosticEvents.size > MAX_DIAGNOSTIC_EVENTS) diagnosticEvents.removeFirst()
        }
    }

    /** Bounded, in-memory connection trace. Pairing secrets and authentication bytes are never recorded. */
    fun diagnosticReport(): String = synchronized(diagnosticEvents) {
        buildString {
            appendLine("MalekPlay Ultra Share connection diagnostics")
            appendLine("Generated: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))
            if (diagnosticEvents.isEmpty()) appendLine("No connection events recorded.")
            else diagnosticEvents.forEach(::appendLine)
        }
    }

    fun clearDiagnosticReport() {
        synchronized(diagnosticEvents) { diagnosticEvents.clear() }
    }

    internal fun isPairingAttemptAllowed(peerAddress: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        pairingAttemptLimiter.isAllowed(peerAddress, nowMillis)

    internal fun recordPairingFailure(peerAddress: String, nowMillis: Long = System.currentTimeMillis()) =
        pairingAttemptLimiter.recordFailure(peerAddress, nowMillis)

    internal fun recordPairingSuccess(peerAddress: String): Unit {
        pairingAttemptLimiter.recordSuccess(peerAddress)
    }

    suspend fun serveOnce(
        pairingSecret: String,
        port: Int = DEFAULT_PORT,
        localPeerName: String = "MalekPlay Android",
        localPeerId: String? = null,
        onSessionClosed: suspend (Request) -> Unit = {},
        onListening: (Int) -> Unit = {},
        onRequest: suspend (Request) -> Decision
    ) = withContext(Dispatchers.IO) {
        require(UltraShareCore.isValidPairingSecret(pairingSecret))
        require(port in 1..65535)
        val permits = Semaphore(4)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port), 8)
            onListening(server.localPort)
            server.soTimeout = 750
            coroutineScope {
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (!permits.tryAcquire()) {
                        runCatching { socket.close() }
                        continue
                    }
                    diagnostic("SERVER accepted TCP from " + socket.inetAddress.hostAddress + ":" + socket.port)
                    UltraShareDiagnostics.event(
                        "CONTROL_CHANNEL", "SERVER_SOCKET_ACCEPTED",
                        mapOf("peerHost" to socket.inetAddress.hostAddress, "peerPort" to socket.port)
                    )
                    launch {
                        try {
                            socket.use { client ->
                                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                                client.tcpNoDelay = true
                                client.keepAlive = true
                                handleIncoming(
                                    client, pairingSecret, localPeerName, localPeerId,
                                    onRequest, onSessionClosed
                                )
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            diagnostic("SERVER handshake cancelled")
                            throw cancelled
                        } catch (failure: Exception) {
                            UltraShareDiagnostics.failure(
                                "CONTROL_CHANNEL_SERVER_HANDSHAKE",
                                failure,
                                "peerHost=" + socket.inetAddress.hostAddress
                            )
                            diagnostic("SERVER handshake failed: " + failure.javaClass.simpleName +
                                (failure.message?.take(120)?.let { " - " + it } ?: ""))
                            // A malformed or disconnected peer cannot stop other sessions.
                        } finally {
                            permits.release()
                        }
                    }
                }
            }
        }
    }

    suspend fun request(
        host: String,
        pairingSecret: String,
        peerName: String,
        port: Int = DEFAULT_PORT,
        network: Network? = null,
        networks: List<Network> = emptyList(),
        peerId: String? = null,
        onSessionClosed: (String) -> Unit = {}
    ): Decision = withContext(Dispatchers.IO) {
        require(UltraShareCore.isValidPrivateLanHost(host) || host == "127.0.0.1") {
            "عنوان الجهاز غير صالح."
        }
        require(UltraShareCore.isValidPairingSecret(pairingSecret)) {
            "رمز الاقتران غير صالح."
        }
        require(port in 1..65535)
        val nameBytes = peerName.trim().toByteArray(Charsets.UTF_8)
        require(nameBytes.isNotEmpty() && nameBytes.size <= MAX_NAME_BYTES) {
            "اسم الجهاز طويل أو فارغ."
        }
        closeSession(host)
        val endpoint = InetSocketAddress(InetAddress.getByName(host), port)
        var socket = Socket()
        var retained = false
        var diagnosticStage = "socket-create"
        diagnostic("CLIENT begin target=" + host + ":" + port +
            " route=" + (network?.toString() ?: "Android-default"))
        UltraShareDiagnostics.event(
            "CONTROL_CHANNEL", "CLIENT_REQUEST_STARTED",
            mapOf("peerHost" to host, "port" to port, "networkHandle" to (network?.networkHandle ?: 0L))
        )
        try {
            // A peer can be reachable through more than one Android Network
            // (WLAN, SoftAP or Wi-Fi Direct). Never assume the first matching
            // route is usable: try each route with a fresh, unbound socket.
            val routeCandidates = UltraShareNetworkRouting.orderedDistinctRoutes(network, networks)
            UltraShareDiagnostics.event(
                "CONTROL_CHANNEL",
                "ROUTE_CANDIDATES",
                mapOf("host" to host, "port" to port, "routeCount" to routeCandidates.size)
            )
            var routeIndex = 0
            var lastRouteFailure: java.io.IOException? = null
            val connectedNetwork = UltraShareNetworkRouting.firstSuccessfulRoute(routeCandidates) { candidateNetwork ->
                val index = routeIndex++
                runCatching { socket.close() }
                socket = Socket()
                try {
                    UltraShareDiagnostics.event(
                        "CONTROL_CHANNEL",
                        "ANDROID_NETWORK_ROUTE_ATTEMPT",
                        mapOf("routeIndex" to index, "networkHandle" to candidateNetwork.networkHandle)
                    )
                    diagnosticStage = "network-bind-$index"
                    candidateNetwork.bindSocket(socket)
                    diagnostic("CLIENT network bind succeeded: " + candidateNetwork)
                    UltraShareDiagnostics.event(
                        "CONTROL_CHANNEL",
                        "ANDROID_NETWORK_BOUND",
                        mapOf("routeIndex" to index, "networkHandle" to candidateNetwork.networkHandle)
                    )
                    diagnosticStage = "tcp-connect-$index"
                    socket.connect(endpoint, HANDSHAKE_TIMEOUT_MS)
                    diagnostic("CLIENT TCP connected via Android Network local=" +
                        socket.localAddress?.hostAddress + ":" + socket.localPort +
                        " remote=" + socket.inetAddress?.hostAddress + ":" + socket.port)
                    UltraShareDiagnostics.event(
                        "CONTROL_CHANNEL",
                        "TCP_CONNECTED",
                        mapOf("route" to "android_network", "routeIndex" to index, "networkHandle" to candidateNetwork.networkHandle, "localAddress" to socket.localAddress?.hostAddress)
                    )
                    true
                } catch (routeFailure: java.io.IOException) {
                    lastRouteFailure = routeFailure
                    UltraShareDiagnostics.failure(
                        "CONTROL_CHANNEL_ANDROID_ROUTE_$index",
                        routeFailure,
                        "host=" + host + ";port=" + port + ";networkHandle=" + candidateNetwork.networkHandle + ";stage=" + diagnosticStage
                    )
                    diagnostic("CLIENT Android Network attempt failed at " + diagnosticStage + ": " +
                        routeFailure.javaClass.simpleName +
                        (routeFailure.message?.take(100)?.let { " - " + it } ?: ""))
                    false
                } catch (failure: RuntimeException) {
                    // An OEM may invalidate a Network between discovery and bindSocket().
                    // Skip only stale-state/argument failures; preserve cancellation,
                    // permission failures and unrelated programming defects.
                    if (failure is CancellationException ||
                        (failure !is IllegalStateException && failure !is IllegalArgumentException)
                    ) throw failure
                    val routeFailure = java.io.IOException("Android network route is no longer usable.", failure)
                    lastRouteFailure = routeFailure
                    UltraShareDiagnostics.failure(
                        "CONTROL_CHANNEL_ANDROID_ROUTE_$index",
                        routeFailure,
                        "host=" + host + ";port=" + port + ";networkHandle=" + candidateNetwork.networkHandle + ";stage=" + diagnosticStage
                    )
                    diagnostic("CLIENT Android Network attempt failed at " + diagnosticStage + ": " +
                        routeFailure.javaClass.simpleName +
                        (routeFailure.message?.take(100)?.let { " - " + it } ?: ""))
                    false
                }
            }
            val connected = connectedNetwork != null
            if (!connected) {
                runCatching { socket.close() }
                socket = Socket()
                try {
                    // Some OEM P2P implementations do not publish a matching
                    // Network object. Preserve the system-route fallback, but only
                    // after every peer-specific Android Network has been attempted.
                    UltraShareDiagnostics.event(
                        "CONTROL_CHANNEL",
                        "DEFAULT_ROUTE_FALLBACK",
                        mapOf("host" to host, "port" to port, "routeCount" to routeCandidates.size)
                    )
                    diagnosticStage = "default-route-connect"
                    if (routeCandidates.isEmpty()) {
                        diagnostic("CLIENT no peer-specific Android Network; trying default route")
                    } else {
                        diagnostic("CLIENT all " + routeCandidates.size +
                            " peer-specific routes failed; trying default route")
                    }
                    socket.connect(endpoint, HANDSHAKE_TIMEOUT_MS)
                    diagnostic("CLIENT default-route TCP connected local=" +
                        socket.localAddress?.hostAddress + ":" + socket.localPort)
                    UltraShareDiagnostics.event(
                        "CONTROL_CHANNEL",
                        "TCP_CONNECTED",
                        mapOf("route" to "default", "localAddress" to socket.localAddress?.hostAddress)
                    )
                } catch (fallbackFailure: java.io.IOException) {
                    UltraShareDiagnostics.failure(
                        "CONTROL_CHANNEL_DEFAULT_ROUTE",
                        fallbackFailure,
                        "host=" + host + ";port=" + port + ";routeCount=" + routeCandidates.size + ";stage=" + diagnosticStage
                    )
                    diagnostic("CLIENT default-route failed: " + fallbackFailure.javaClass.simpleName +
                        (fallbackFailure.message?.take(100)?.let { " - " + it } ?: ""))
                    lastRouteFailure?.let(fallbackFailure::addSuppressed)
                    throw java.io.IOException(
                        "تعذر الوصول إلى الجهاز عبر شبكات Wi-Fi المتاحة. تحقق من اتصال الهاتفين ثم أعد المحاولة.",
                        fallbackFailure
                    )
                }
            }
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            diagnosticStage = "read-greeting"
            val receivedMagic = input.readInt()
            val receivedVersion = input.readUnsignedByte()
            require(receivedMagic == MAGIC && receivedVersion == VERSION) {
                "إصدار قناة التحكم غير متوافق."
            }
            diagnostic("CLIENT MPC2 greeting received; version=" + receivedVersion)
            diagnosticStage = "read-challenge"
            val challenge = ByteArray(CHALLENGE_BYTES)
            input.readFully(challenge)
            diagnostic("CLIENT challenge received: " + challenge.size + " bytes")
            val key = UltraShareCore.derivePairingKey(pairingSecret, challenge)
            try {
            // The hexadecimal capability marker preserves legacy peers' all-hex request-ID
            // validation; the final 32 characters remain the stable device token.
            val stableToken = peerId?.replace("-", "")?.lowercase()
                ?.takeIf { it.length == 32 && it.all { char -> char.digitToIntOrNull(16) != null } }
                .orEmpty()
            val requestId = randomId().take(16) + APPLICATION_CAPABILITY_MARKER + stableToken
            val authenticated = requestAuthentication(key, challenge, requestId, nameBytes)
            output.writeUTF(requestId)
            output.writeShort(nameBytes.size)
            output.write(nameBytes)
            output.write(authenticated)
            output.flush()
            diagnosticStage = "read-decision"
            diagnostic("CLIENT authentication frame sent; waiting for receiver decision")
            socket.soTimeout = DECISION_TIMEOUT_MS

            val encryptedLength = input.readInt()
            require(encryptedLength in (NONCE_BYTES + 1)..MAX_FRAME_BYTES) {
                "إطار قرار الاتصال غير صالح."
            }
            val encrypted = ByteArray(encryptedLength)
            input.readFully(encrypted)
            val nonce = encrypted.copyOfRange(0, NONCE_BYTES)
            val ciphertext = encrypted.copyOfRange(NONCE_BYTES, encrypted.size)
            val response = decrypt(key, nonce, ciphertext)
            val responseParts = response.split('|', limit = 5)
            require(responseParts.size >= 2 && responseParts[1] == requestId &&
                (responseParts[0] == "ACCEPT" || responseParts[0] == "REJECT")
            ) { "استجابة قبول الاتصال غير صالحة." }
            diagnostic("CLIENT authenticated decision received: " + responseParts[0])
            if (responseParts[0] == "ACCEPT") {
                val remoteName = responseParts.getOrNull(2)?.let {
                    runCatching { String(decodeHex(it), Charsets.UTF_8) }.getOrNull()
                }?.takeIf { it.isNotBlank() } ?: "جهاز MalekPlay"
                val remotePeerId = responseParts.getOrNull(3)?.takeIf(::isStablePeerId)
                val applicationFramesEnabled = responseParts.getOrNull(4) == APPLICATION_CAPABILITY_MARKER
                socket.soTimeout = SESSION_TIMEOUT_MS
                val frameSession = createApplicationFrameSession(
                    key, challenge, requestId, nameBytes, isClient = true
                )
                val session = ClientSession(
                    socket, input, output, remoteName, remotePeerId, applicationFramesEnabled, frameSession
                )
                synchronized(clientSessionGuard) { clientSessions[host] = session }
                session.heartbeat = CoroutineScope(Dispatchers.IO).launch {
                    while (isActive && !socket.isClosed) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        try {
                            synchronized(session.lock) {
                                session.output.writeUTF("PING")
                                session.output.flush()
                                var receivedPong = false
                                while (!receivedPong) {
                                    when (session.input.readUTF()) {
                                        "PONG" -> receivedPong = true
                                        "APP" -> {
                                            check(session.applicationFramesEnabled) { "الطرف الآخر لا يدعم إطارات التطبيق المشفرة." }
                                            val length = session.input.readInt()
                                            require(length in (APPLICATION_FRAME_HEADER_BYTES + APPLICATION_FRAME_TAG_BYTES)..UltraShareControlFrames.MAX_WIRE_BYTES)
                                            val wire = ByteArray(length)
                                            session.input.readFully(wire)
                                            val plaintext = session.frames.open(wire)
                                            check(session.incoming.trySend(plaintext).isSuccess) {
                                                "قائمة رسائل الجهاز ممتلئة؛ أُغلقت قناة التحكم لحماية الذاكرة."
                                            }
                                        }
                                        "CLOSE" -> error("أغلق الجهاز الآخر قناة التحكم.")
                                        else -> error("استجابة قناة التحكم غير معروفة.")
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // A stale heartbeat may finish after a replacement session has
                            // already been installed for this host. Remove only the exact
                            // session that owns this coroutine; never close its replacement.
                            synchronized(clientSessionGuard) {
                                val wasCurrentSession = clientSessions.remove(host, session)
                                if (wasCurrentSession) {
                                    runCatching { session.socket.close() }
                                    synchronized(session.lock) {
                                        session.frames.close()
                                        session.incoming.close()
                                    }
                                    onSessionClosed(host)
                                }
                            }
                            break
                        }
                    }
                }
                retained = true
                UltraShareDiagnostics.event(
                    "CONTROL_CHANNEL", "CLIENT_SESSION_APPROVED",
                    mapOf("peerHost" to host, "peerName" to remoteName, "peerIdPresent" to (remotePeerId != null))
                )
                Decision.ACCEPTED
            } else {
                UltraShareDiagnostics.event(
                    "CONTROL_CHANNEL", "CLIENT_SESSION_REJECTED",
                    mapOf("peerHost" to host)
                )
                Decision.REJECTED
            }
            } finally {
                key.fill(0)
            }
        } catch (cancelled: CancellationException) {
            UltraShareDiagnostics.event(
                "CONTROL_CHANNEL", "CLIENT_REQUEST_CANCELLED",
                mapOf("peerHost" to host, "stage" to diagnosticStage)
            )
            diagnostic("CLIENT cancelled at " + diagnosticStage)
            throw cancelled
        } catch (failure: Exception) {
            UltraShareDiagnostics.failure(
                "CONTROL_CHANNEL_CLIENT",
                failure,
                "host=" + host + ";port=" + port + ";stage=" + diagnosticStage
            )
            diagnostic("CLIENT failed at " + diagnosticStage + ": " +
                failure.javaClass.simpleName + (failure.message?.take(160)?.let { " - " + it } ?: ""))
            throw failure
        } finally {
            if (!retained) runCatching { socket.close() }
        }
    }

    fun closeSession(host: String) {
        synchronized(clientSessionGuard) {
            val session = clientSessions.remove(host) ?: return
            session.heartbeat?.cancel()
            // Close first to unblock a heartbeat reader that currently owns the lock.
            runCatching { session.socket.close() }
            synchronized(session.lock) {
                session.frames.close()
                session.incoming.close()
            }
        }
    }

    fun closeServerSession(host: String) {
        // Close first to unblock the server's single socket reader.
        serverSessions.remove(host)?.let { socket -> runCatching { socket.close() } }
        serverApplicationSessions.remove(host)?.let { active ->
            synchronized(active.lock) {
                active.frames.close()
                active.incoming.close()
            }
        }
        serverSessionPeerIds.remove(host)
    }

    /** Send an authenticated application message over the approved client-side MPC2 socket. */
    fun sendClientApplicationMessage(host: String, payload: ByteArray) {
        val session = clientSessions[host] ?: error("لا توجد جلسة إرسال معتمدة لهذا الجهاز.")
        synchronized(session.lock) {
            check(session.applicationFramesEnabled) { "الجهاز الآخر لا يدعم رسائل التطبيق المشفرة." }
            check(session.socket.isConnected && !session.socket.isClosed)
            val frame = session.frames.seal(payload)
            session.output.writeUTF("APP")
            session.output.writeInt(frame.size)
            session.output.write(frame)
            session.output.flush()
        }
    }

    /** Receive the next authenticated application message from the approved client-side socket. */
    suspend fun receiveClientApplicationMessage(host: String): ByteArray? {
        val session = clientSessions[host] ?: return null
        if (!session.applicationFramesEnabled) return null
        return session.incoming.receiveCatching().getOrNull()
    }

    /** Send an authenticated application message over the approved server-side MPC2 socket. */
    fun sendServerApplicationMessage(host: String, payload: ByteArray) {
        val session = serverApplicationSessions[host] ?: error("لا توجد جلسة استقبال معتمدة لهذا الجهاز.")
        synchronized(session.lock) {
            check(session.applicationFramesEnabled && session.ready && session.socket.isConnected && !session.socket.isClosed) {
                "جلسة الجهاز لم تكتمل تهيئتها بعد."
            }
            val frame = session.frames.seal(payload)
            session.output.writeUTF("APP")
            session.output.writeInt(frame.size)
            session.output.write(frame)
            session.output.flush()
        }
    }

    /** Receive the next authenticated application message from the approved server-side socket. */
    suspend fun receiveServerApplicationMessage(host: String): ByteArray? {
        val session = serverApplicationSessions[host] ?: return null
        if (!session.applicationFramesEnabled) return null
        return session.incoming.receiveCatching().getOrNull()
    }

    fun sessionPeerName(host: String): String? = clientSessions[host]?.peerName

    /** Authenticated stable identity announced by the remote device during approval. */
    fun sessionPeerId(host: String): String? = clientSessions[host]?.peerId

    fun isSessionActive(host: String): Boolean {
        val session = clientSessions[host] ?: return false
        val socket = session.socket
        return socket.isConnected && !socket.isClosed &&
            !socket.isInputShutdown && !socket.isOutputShutdown
    }

    /**
     * Data transfer is bidirectional: the approved control session may be the
     * client-side session on one phone and the server-side session on the other.
     */
    fun hasAnyActiveSession(host: String): Boolean =
        isDataSessionAuthorized(
            clientSessionActive = isSessionActive(host),
            serverSessionActive = isServerSessionActive(host)
        )

    internal fun isDataSessionAuthorized(
        clientSessionActive: Boolean,
        serverSessionActive: Boolean
    ): Boolean = clientSessionActive || serverSessionActive

    fun isServerSessionActive(host: String): Boolean =
        serverSessions[host]?.isConnected == true && serverSessions[host]?.isClosed == false

    /** Stable authenticated sender identity survives WLAN/Hotspot/Wi-Fi Direct IP changes. */
    fun serverSessionIdentity(host: String): String? = serverSessionPeerIds[host]

    private suspend fun handleIncoming(
        socket: Socket,
        pairingSecret: String,
        localPeerName: String,
        localPeerId: String?,
        onRequest: suspend (Request) -> Decision,
        onSessionClosed: suspend (Request) -> Unit
    ) {
        val peerAddress = socket.inetAddress.hostAddress.orEmpty()
        check(pairingAttemptLimiter.isAllowed(peerAddress)) {
            "تم إيقاف محاولات الاقتران مؤقتاً لهذا الجهاز بسبب تكرار المحاولات الفاشلة."
        }
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val challenge = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.write(challenge)
        output.flush()

        val requestId = try {
            input.readUTF()
        } catch (_: java.io.EOFException) {
            // Discovery probes read the MPC2 greeting and close without authenticating.
            // This is not a pairing failure and must not be reported as a broken handshake.
            UltraShareDiagnostics.event(
                "CONTROL_CHANNEL", "PRE_AUTH_PROBE_DISCONNECTED",
                mapOf("peerHost" to peerAddress)
            )
            return
        }
        require(requestId.length in 16..MAX_ID_BYTES &&
            requestId.all { it.isLetterOrDigit() || it == '-' }
        ) { "معرّف طلب الاتصال غير صالح." }
        val nameLength = input.readUnsignedShort()
        require(nameLength in 1..MAX_NAME_BYTES)
        val nameBytes = ByteArray(nameLength)
        input.readFully(nameBytes)
        val suppliedMac = ByteArray(MAC_BYTES)
        input.readFully(suppliedMac)
        val name = nameBytes.toString(Charsets.UTF_8)
        require(name.isNotBlank() && name.none { it.isISOControl() }) {
            "اسم الجهاز غير صالح."
        }

        val key = UltraShareCore.derivePairingKey(pairingSecret, challenge)
        var publishedRequest: Request? = null
        try {
        val expectedMac = requestAuthentication(key, challenge, requestId, nameBytes)
        if (!MessageDigest.isEqual(expectedMac, suppliedMac)) {
            pairingAttemptLimiter.recordFailure(peerAddress)
            error("فشل التحقق من رمز الاقتران.")
        }
        pairingAttemptLimiter.recordSuccess(peerAddress)
        UltraShareDiagnostics.event(
            "CONTROL_CHANNEL", "PEER_AUTHENTICATION_SUCCEEDED",
            mapOf("peerHost" to peerAddress, "peerName" to name)
        )

        val peerId = requestId.takeIf { value ->
            value.length == MAX_ID_BYTES && value.takeLast(32).all { it.digitToIntOrNull(16) != null }
        }?.takeLast(32)
        val applicationFramesEnabled =
            requestId.length in 32..MAX_ID_BYTES &&
                requestId.substring(16, 32) == APPLICATION_CAPABILITY_MARKER
        val request = Request(
            requestId = requestId,
            peerName = name,
            peerAddress = peerAddress,
            peerId = peerId,
            localAddress = socket.localAddress?.hostAddress
        )
        val decision = withTimeoutOrNull(120_000L) { onRequest(request) } ?: Decision.REJECTED
        UltraShareDiagnostics.event(
            "CONTROL_CHANNEL",
            if (decision == Decision.ACCEPTED) "PEER_APPROVED" else "PEER_REJECTED",
            mapOf("peerHost" to peerAddress, "peerName" to name)
        )
        var acceptedServerSession: ServerSession? = null
        if (decision == Decision.ACCEPTED) {
            socket.soTimeout = SESSION_TIMEOUT_MS
            val frameSession = createApplicationFrameSession(
                key, challenge, requestId, nameBytes, isClient = false
            )
            val serverSession = ServerSession(socket, input, output, applicationFramesEnabled, frameSession)
            acceptedServerSession = serverSession
            serverSessions.put(peerAddress, socket)?.takeIf { it !== socket }?.let { previous ->
                // Retire the old socket first, then zero its frame keys under its writer lock.
                runCatching { previous.close() }
                serverApplicationSessions.remove(peerAddress)?.let { old ->
                    synchronized(old.lock) {
                        old.frames.close()
                        old.incoming.close()
                    }
                }
            }
            serverApplicationSessions[peerAddress] = serverSession
            // Publish the authenticated session before the ACCEPT frame so the
            // data plane can validate it immediately after the client receives approval.
            publishedRequest = request
            serverSessionPeerIds.remove(peerAddress)
            request.peerId?.let { serverSessionPeerIds[peerAddress] = it }
        }
        val encodedLocalName = encodeHex(localPeerName.take(48).toByteArray(Charsets.UTF_8))
        val stableLocalPeerId = localPeerId?.replace("-", "")?.lowercase()
            ?.takeIf(::isStablePeerId)
        val payload = if (decision == Decision.ACCEPTED) {
            val identity = stableLocalPeerId.orEmpty()
            val capabilities = if (applicationFramesEnabled) "|$APPLICATION_CAPABILITY_MARKER" else ""
            "ACCEPT|$requestId|$encodedLocalName|$identity$capabilities"
        } else "REJECT|$requestId"
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val ciphertext = encrypt(key, nonce, payload)
        val frame = nonce + ciphertext
        require(frame.size <= MAX_FRAME_BYTES)

        if (decision != Decision.ACCEPTED) {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
            return
        }

        val activeSession = requireNotNull(acceptedServerSession)
        synchronized(activeSession.lock) {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
            activeSession.ready = true
        }
        try {
            while (!socket.isClosed) {
                when (input.readUTF()) {
                    "PING" -> synchronized(acceptedServerSession?.lock ?: output) {
                        output.writeUTF("PONG")
                        output.flush()
                    }
                    "APP" -> {
                        val active = acceptedServerSession ?: error("جلسة التطبيق غير جاهزة.")
                        check(active.applicationFramesEnabled) { "الطرف الآخر لا يدعم إطارات التطبيق المشفرة." }
                        val length = input.readInt()
                        require(length in (APPLICATION_FRAME_HEADER_BYTES + APPLICATION_FRAME_TAG_BYTES)..UltraShareControlFrames.MAX_WIRE_BYTES)
                        val wire = ByteArray(length)
                        input.readFully(wire)
                        val plaintext = active.frames.open(wire)
                        check(active.incoming.trySend(plaintext).isSuccess) {
                            "قائمة رسائل الجهاز ممتلئة؛ أُغلقت قناة التحكم لحماية الذاكرة."
                        }
                    }
                    "CLOSE" -> return
                    else -> error("أمر قناة التحكم غير معروف.")
                }
            }
        } catch (_: java.io.EOFException) {
            // A peer closing its authenticated control socket is a normal session
            // termination, not a protocol crash or a failed file transfer.
            UltraShareDiagnostics.event(
                "CONTROL_CHANNEL",
                "PEER_CONTROL_CHANNEL_EOF",
                mapOf("peerHost" to request.peerAddress, "peerName" to request.peerName)
            )
        } catch (failure: java.net.SocketException) {
            // Android may close the socket concurrently with session cancellation.
            // Retire the session through the common finally path without a noisy
            // uncaught SocketException; retain a diagnostic breadcrumb.
            UltraShareDiagnostics.event(
                "CONTROL_CHANNEL",
                "PEER_CONTROL_CHANNEL_SOCKET_CLOSED",
                mapOf(
                    "peerHost" to request.peerAddress,
                    "peerName" to request.peerName,
                    "message" to (failure.message ?: "socket closed")
                )
            )
        }
        } finally {
            key.fill(0)
            // Also covers exceptions while preparing the authenticated response.
            // remove(key, value) ensures an old coroutine cannot erase a replacement.
            val requestToClose = publishedRequest
            if (requestToClose != null &&
                serverSessions.remove(requestToClose.peerAddress, socket)
            ) {
                serverSessionPeerIds.remove(requestToClose.peerAddress)
                serverApplicationSessions.remove(requestToClose.peerAddress)?.let { active ->
                    synchronized(active.lock) {
                        active.frames.close()
                        active.incoming.close()
                    }
                }
                UltraShareDiagnostics.event(
                    "CONTROL_CHANNEL", "SERVER_SESSION_CLOSED",
                    mapOf("peerHost" to requestToClose.peerAddress, "peerName" to requestToClose.peerName)
                )
                withContext(NonCancellable) {
                    try {
                        onSessionClosed(requestToClose)
                    } catch (failure: Exception) {
                        UltraShareDiagnostics.failure(
                            "CONTROL_CHANNEL_SESSION_CLEANUP",
                            failure,
                            "peerHost=" + requestToClose.peerAddress
                        )
                    }
                }
            }
        }
    }


    private const val APPLICATION_CAPABILITY_MARKER = "4d504d5041505031"
    private const val APPLICATION_FRAME_HEADER_BYTES = 22
    private const val APPLICATION_FRAME_TAG_BYTES = 16

    private fun createApplicationFrameSession(
        pairingKey: ByteArray,
        challenge: ByteArray,
        requestId: String,
        peerName: ByteArray,
        isClient: Boolean
    ): UltraShareControlFrames.Session {
        val master = deriveSubkey(pairingKey, "application-session-master")
        val transcript = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF("MPULTRA_MPC2_APPLICATION_SESSION")
                data.writeInt(VERSION)
                data.writeShort(challenge.size)
                data.write(challenge)
                data.writeUTF(requestId)
                data.writeShort(peerName.size)
                data.write(peerName)
            }
            bytes.toByteArray()
        }
        return try {
            UltraShareControlFrames.createSession(master, transcript, isClient)
        } finally {
            master.fill(0)
            transcript.fill(0)
        }
    }

    private fun isStablePeerId(value: String): Boolean =
        value.length == 32 && value.all { it.digitToIntOrNull(16) != null }

    private fun requestAuthentication(
        key: ByteArray,
        challenge: ByteArray,
        requestId: String,
        peerName: ByteArray
    ): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF("MPULTRA_CONTROL_REQUEST")
                data.writeInt(VERSION)
                data.write(challenge)
                data.writeUTF(requestId)
                data.writeShort(peerName.size)
                data.write(peerName)
            }
            bytes.toByteArray()
        }
        return hmac(deriveSubkey(key, "request-authentication"), payload)
    }

    private fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveSubkey(key, "decision-encryption"), "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD("MPULTRA_CONTROL_DECISION|1".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveSubkey(key, "decision-encryption"), "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD("MPULTRA_CONTROL_DECISION|1".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun deriveSubkey(master: ByteArray, label: String): ByteArray =
        hmac(master, ("MPULTRA_CONTROL|" + label + "|1").toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(payload)
        }


    /** Hex encoding keeps the small control frame independent of Android framework Base64. */
    private fun encodeHex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0x0f])
            }
        }

    private fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.length <= MAX_FRAME_BYTES * 2) {
            "اسم الجهاز المشفر غير صالح."
        }
        return ByteArray(value.length / 2) { index ->
            val high = value[index * 2].digitToIntOrNull(16)
                ?: error("اسم الجهاز المشفر غير صالح.")
            val low = value[index * 2 + 1].digitToIntOrNull(16)
                ?: error("اسم الجهاز المشفر غير صالح.")
            ((high shl 4) or low).toByte()
        }
    }

    private fun randomId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")
}
