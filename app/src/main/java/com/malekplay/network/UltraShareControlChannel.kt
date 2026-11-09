package com.malekplay.network

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
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated request/accept/reject channel plus a persistent authenticated
 * control socket. File payloads still use the compatible v1 transport sockets;
 * this socket carries heartbeat and close signals for the approved peer session.
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
    private const val DECISION_TIMEOUT_MS = 35_000
    private const val SESSION_TIMEOUT_MS = 18_000
    private const val HEARTBEAT_INTERVAL_MS = 5_000L

    enum class Decision { ACCEPTED, REJECTED }

    data class Request(
        val requestId: String,
        val peerName: String,
        val peerAddress: String
    )

    private data class ClientSession(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val peerName: String,
        val lock: Any = Any(),
        var heartbeat: Job? = null
    )

    private val clientSessions = ConcurrentHashMap<String, ClientSession>()
    private val serverSessions = ConcurrentHashMap<String, Socket>()

    suspend fun serveOnce(
        pairingSecret: String,
        port: Int = DEFAULT_PORT,
        localPeerName: String = "MalekPlay Android",
        onSessionClosed: suspend (Request) -> Unit = {},
        onRequest: suspend (Request) -> Decision
    ) = withContext(Dispatchers.IO) {
        require(UltraShareTransport.isValidPairingSecret(pairingSecret))
        require(port in 1..65535)
        val permits = Semaphore(4)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port), 8)
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
                    launch {
                        try {
                            socket.use { client ->
                                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                                client.tcpNoDelay = true
                                client.keepAlive = true
                                handleIncoming(client, pairingSecret, localPeerName, onRequest, onSessionClosed)
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
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
        val socket = Socket()
        var retained = false
        try {
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port), HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            require(input.readInt() == MAGIC && input.readUnsignedByte() == VERSION) {
                "إصدار قناة التحكم غير متوافق."
            }
            val challenge = ByteArray(CHALLENGE_BYTES)
            input.readFully(challenge)
            val key = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
            val requestId = randomId()
            val authenticated = requestAuthentication(key, challenge, requestId, nameBytes)
            output.writeUTF(requestId)
            output.writeShort(nameBytes.size)
            output.write(nameBytes)
            output.write(authenticated)
            output.flush()
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
            val responseParts = response.split('|', limit = 3)
            require(responseParts.size >= 2 && responseParts[1] == requestId &&
                (responseParts[0] == "ACCEPT" || responseParts[0] == "REJECT")
            ) { "استجابة قبول الاتصال غير صالحة." }
            if (responseParts[0] == "ACCEPT") {
                val remoteName = responseParts.getOrNull(2)?.let {
                    runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
                }?.takeIf { it.isNotBlank() } ?: "جهاز MalekPlay"
                socket.soTimeout = SESSION_TIMEOUT_MS
                val session = ClientSession(socket, input, output, remoteName)
                clientSessions[host] = session
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
                            closeSession(host)
                            onSessionClosed(host)
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
            if (!retained) runCatching { socket.close() }
        }
    }

    fun closeSession(host: String) {
        val session = clientSessions.remove(host) ?: return
        session.heartbeat?.cancel()
        runCatching { session.socket.close() }
    }

    fun closeServerSession(host: String) {
        serverSessions.remove(host)?.let { socket -> runCatching { socket.close() } }
    }

    fun sessionPeerName(host: String): String? = clientSessions[host]?.peerName

    fun isSessionActive(host: String): Boolean =
        clientSessions[host]?.socket?.isConnected == true &&
            clientSessions[host]?.socket?.isClosed == false

    fun isServerSessionActive(host: String): Boolean =
        serverSessions[host]?.isConnected == true && serverSessions[host]?.isClosed == false

    private suspend fun handleIncoming(
        socket: Socket,
        pairingSecret: String,
        localPeerName: String,
        onRequest: suspend (Request) -> Decision,
        onSessionClosed: suspend (Request) -> Unit
    ) {
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
        val expectedMac = requestAuthentication(key, challenge, requestId, nameBytes)
        require(MessageDigest.isEqual(expectedMac, suppliedMac)) {
            "فشل التحقق من رمز الاقتران."
        }

        val request = Request(
            requestId = requestId,
            peerName = name,
            peerAddress = socket.inetAddress.hostAddress.orEmpty()
        )
        val decision = withTimeoutOrNull(30_000L) { onRequest(request) } ?: Decision.REJECTED
        val encodedLocalName = Base64.getUrlEncoder().withoutPadding().encodeToString(localPeerName.take(48).toByteArray(Charsets.UTF_8))
        val payload = if (decision == Decision.ACCEPTED) "ACCEPT|$requestId|$encodedLocalName" else "REJECT|$requestId"
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

        try {
            // Publish the approved server-side session before the ACCEPT frame reaches
            // the sender. Otherwise the first v2 data socket can race this map update.
            socket.soTimeout = SESSION_TIMEOUT_MS
            serverSessions[request.peerAddress] = socket
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
            serverSessions.remove(request.peerAddress, socket)
            onSessionClosed(request)
        }
    }

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

    private fun randomId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")
}
