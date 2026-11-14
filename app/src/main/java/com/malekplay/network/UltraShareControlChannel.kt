package com.malekplay.network

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * control socket. V2 file batches use one separate encrypted data socket per batch;
 * legacy V1 fallback retains its compatible per-file sockets. This socket carries
 * heartbeat and close signals for the approved peer session.
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
        val dfileKey: ByteArray,
        val dfilePort: Int?,
        val lock: Any = Any(),
        var heartbeat: Job? = null
    )

    private val clientSessions = ConcurrentHashMap<String, ClientSession>()
    // Serialize replacement, explicit close, and stale-heartbeat cleanup so an old
    // session callback can never tear down a newly approved session for the same host.
    private val clientSessionGuard = Any()
    private val serverSessions = ConcurrentHashMap<String, Socket>()
    private val serverSessionPeerIds = ConcurrentHashMap<String, String>()
    private val serverSessionDFileKeys = ConcurrentHashMap<String, ByteArray>()
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
        onDFileServerReady: suspend (Request, ByteArray) -> Int? = { _, _ -> null },
        onListening: (Int) -> Unit = {},
        onRequest: suspend (Request) -> Decision
    ) = withContext(Dispatchers.IO) {
        require(UltraShareTransport.isValidPairingSecret(pairingSecret))
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
                    launch {
                        try {
                            socket.use { client ->
                                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                                client.tcpNoDelay = true
                                client.keepAlive = true
                                handleIncoming(
                                    client, pairingSecret, localPeerName, localPeerId,
                                    onRequest, onSessionClosed, onDFileServerReady
                                )
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            diagnostic("SERVER handshake cancelled")
                            throw cancelled
                        } catch (failure: Exception) {
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
        require(UltraShareTransport.isValidPrivateLanHost(host) || host == "127.0.0.1") {
            "عنوان الجهاز غير صالح."
        }
        require(UltraShareTransport.isValidPairingSecret(pairingSecret)) {
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
        try {
            // A peer can be reachable through more than one Android Network
            // (WLAN, SoftAP or Wi-Fi Direct). Never assume the first matching
            // route is usable: try each route with a fresh, unbound socket.
            val routeCandidates = UltraShareNetworkRouting.orderedDistinctRoutes(network, networks)
            var routeIndex = 0
            var lastRouteFailure: java.io.IOException? = null
            val connectedNetwork = UltraShareNetworkRouting.firstSuccessfulRoute(routeCandidates) { candidateNetwork ->
                val index = routeIndex++
                runCatching { socket.close() }
                socket = Socket()
                try {
                    diagnosticStage = "network-bind-$index"
                    candidateNetwork.bindSocket(socket)
                    diagnostic("CLIENT network bind succeeded: " + candidateNetwork)
                    diagnosticStage = "tcp-connect-$index"
                    socket.connect(endpoint, HANDSHAKE_TIMEOUT_MS)
                    diagnostic("CLIENT TCP connected via Android Network local=" +
                        socket.localAddress?.hostAddress + ":" + socket.localPort +
                        " remote=" + socket.inetAddress?.hostAddress + ":" + socket.port)
                    true
                } catch (routeFailure: java.io.IOException) {
                    lastRouteFailure = routeFailure
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
                } catch (fallbackFailure: java.io.IOException) {
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
            val key = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
            try {
            // Append the stable device token to the opaque request ID. This remains
            // wire-compatible with older peers (which already accept up to 64 hex chars)
            // without changing the displayed device name or control-frame layout.
            val stableToken = peerId?.replace("-", "")?.lowercase()
                ?.takeIf { it.length == 32 && it.all { char -> char.digitToIntOrNull(16) != null } }
                .orEmpty()
            val requestId = randomId() + stableToken
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
                val negotiatedDFilePort = responseParts.getOrNull(4)?.toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                socket.soTimeout = SESSION_TIMEOUT_MS
                val session = ClientSession(
                    socket, input, output, remoteName, remotePeerId,
                    deriveDFileKey(key), negotiatedDFilePort
                )
                synchronized(clientSessionGuard) { clientSessions[host] = session }
                session.heartbeat = CoroutineScope(Dispatchers.IO).launch {
                    while (isActive && !socket.isClosed) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        try {
                            synchronized(session.lock) {
                                session.output.writeUTF("PING")
                                session.output.flush()
                                check(session.input.readUTF() == "PONG") {
                                    "فقدت قناة التحكم استجابة نبض الحياة."
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
                                    session.dfileKey.fill(0)
                                    onSessionClosed(host)
                                }
                            }
                            break
                        }
                    }
                }
                retained = true
                Decision.ACCEPTED
            } else {
                Decision.REJECTED
            }
            } finally {
                key.fill(0)
            }
        } catch (cancelled: CancellationException) {
            diagnostic("CLIENT cancelled at " + diagnosticStage)
            throw cancelled
        } catch (failure: Exception) {
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
            runCatching { session.socket.close() }
            session.dfileKey.fill(0)
        }
    }

    fun closeServerSession(host: String) {
        serverSessions.remove(host)?.let { socket -> runCatching { socket.close() } }
        serverSessionPeerIds.remove(host)
        serverSessionDFileKeys.remove(host)?.fill(0)
    }

    fun sessionPeerName(host: String): String? = clientSessions[host]?.peerName

    /** Authenticated stable identity announced by the remote device during approval. */
    fun sessionPeerId(host: String): String? = clientSessions[host]?.peerId

    /** Returns a copy of the session-bound DFile key for an approved outgoing peer. */
    fun clientDFileKey(host: String): ByteArray? =
        clientSessions[host]?.dfileKey?.copyOf()

    /** Returns a copy of the session-bound DFile key for an approved incoming peer. */
    fun serverDFileKey(host: String): ByteArray? =
        serverSessionDFileKeys[host]?.copyOf()

    /** DFile endpoint advertised inside the authenticated ACCEPT frame; null means TCP fallback. */
    fun clientDFilePort(host: String): Int? = clientSessions[host]?.dfilePort

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
        onSessionClosed: suspend (Request) -> Unit,
        onDFileServerReady: suspend (Request, ByteArray) -> Int?
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

        val requestId = input.readUTF()
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

        val key = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
        var publishedRequest: Request? = null
        try {
        val expectedMac = requestAuthentication(key, challenge, requestId, nameBytes)
        if (!MessageDigest.isEqual(expectedMac, suppliedMac)) {
            pairingAttemptLimiter.recordFailure(peerAddress)
            error("فشل التحقق من رمز الاقتران.")
        }
        pairingAttemptLimiter.recordSuccess(peerAddress)

        val peerId = requestId.takeIf { value ->
            value.length == MAX_ID_BYTES && value.all { it.digitToIntOrNull(16) != null }
        }?.takeLast(32)
        val request = Request(
            requestId = requestId,
            peerName = name,
            peerAddress = peerAddress,
            peerId = peerId,
            localAddress = socket.localAddress?.hostAddress
        )
        val decision = withTimeoutOrNull(120_000L) { onRequest(request) } ?: Decision.REJECTED
        val dfileKey = if (decision == Decision.ACCEPTED) deriveDFileKey(key) else null
        var negotiatedDFilePort: Int? = null
        if (decision == Decision.ACCEPTED) {
            // Replace the old control session before publishing the new DFile key. This
            // prevents the old session's close callback from erasing the new peer key.
            socket.soTimeout = SESSION_TIMEOUT_MS
            serverSessions.put(peerAddress, socket)?.takeIf { it !== socket }?.let { previous ->
                runCatching { previous.close() }
                serverSessionDFileKeys.remove(peerAddress)?.fill(0)
            }
            // From this point onward, every failure must retire this exact session.
            // The outer finally owns cleanup so failures during DFile setup are covered too.
            publishedRequest = request
            serverSessionPeerIds.remove(peerAddress)
            request.peerId?.let { serverSessionPeerIds[peerAddress] = it }
        }
        if (dfileKey != null) {
            try {
                // Publish the key before preparing the native receiver. The receiver must be
                // listening before ACCEPT is sent, otherwise the first DFile SYN can be lost.
                serverSessionDFileKeys[peerAddress] = dfileKey.copyOf()
                negotiatedDFilePort = try {
                    val callbackKey = dfileKey.copyOf()
                    try {
                        onDFileServerReady(request, callbackKey)
                    } finally {
                        callbackKey.fill(0)
                    }
                } catch (cancelled: CancellationException) {
                    // Never turn structured cancellation into a successful TCP fallback.
                    // The outer finally will retire the published session and wipe keys.
                    throw cancelled
                } catch (_: Exception) {
                    // DFile is an optional fast path; ordinary startup errors retain
                    // the authenticated control session and its compatible TCP fallback.
                    null
                }?.takeIf { it in 1..65535 }
                if (negotiatedDFilePort == null) {
                    serverSessionDFileKeys.remove(peerAddress)?.fill(0)
                }
            } finally {
                // Wipe the derived local key even when the callback is cancelled.
                dfileKey.fill(0)
            }
        }
        val encodedLocalName = encodeHex(localPeerName.take(48).toByteArray(Charsets.UTF_8))
        val stableLocalPeerId = localPeerId?.replace("-", "")?.lowercase()
            ?.takeIf(::isStablePeerId)
        val payload = if (decision == Decision.ACCEPTED) {
            val identity = stableLocalPeerId?.let { "|$it" }
                ?: if (negotiatedDFilePort != null) "|" else ""
            val dfileEndpoint = negotiatedDFilePort?.let { "|$it" }.orEmpty()
            "ACCEPT|$requestId|$encodedLocalName$identity$dfileEndpoint"
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

        // The approved session was published before DFile setup so the receiver can
        // bind its data socket before ACCEPT is sent. Do not publish it a second time:
        // a single owner and a single cleanup path avoid stale-session races.
        if (negotiatedDFilePort == null) {
            serverSessionDFileKeys.remove(request.peerAddress)?.fill(0)
        }
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
        while (!socket.isClosed) {
            when (input.readUTF()) {
                "PING" -> {
                    output.writeUTF("PONG")
                    output.flush()
                }
                "CLOSE" -> return
                else -> error("أمر قناة التحكم غير معروف.")
            }
        }
        } finally {
            key.fill(0)
            // Also covers exceptions while preparing the DFile receiver or response.
            // remove(key, value) ensures an old coroutine cannot erase a replacement.
            val requestToClose = publishedRequest
            if (requestToClose != null &&
                serverSessions.remove(requestToClose.peerAddress, socket)
            ) {
                serverSessionPeerIds.remove(requestToClose.peerAddress)
                serverSessionDFileKeys.remove(requestToClose.peerAddress)?.fill(0)
                onSessionClosed(requestToClose)
            }
        }
    }


    /** Derive a dedicated 128-bit DFile key from the authenticated MPC2 control key. */
    private fun deriveDFileKey(controlKey: ByteArray): ByteArray {
        require(controlKey.size == 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(controlKey, "HmacSHA256"))
        return mac.doFinal("MalekPlayer/NSTACKX/DFile/v1".toByteArray(Charsets.UTF_8))
            .copyOf(16)
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
