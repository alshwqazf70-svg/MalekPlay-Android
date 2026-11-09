package com.malekplay.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated request/accept/reject control channel.
 *
 * The pairing PIN is never sent over the socket. A fresh server challenge is
 * used to derive a one-request key; request metadata is authenticated with
 * HMAC-SHA256 and the decision is returned as an AES-GCM protected frame.
 * The caller may start the existing encrypted file-transfer handshake only
 * after request() returns ACCEPTED.
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
    private const val SOCKET_TIMEOUT_MS = 35_000
    private const val DECISION_TIMEOUT_MS = 30_000L

    enum class Decision { ACCEPTED, REJECTED }

    data class Request(
        val requestId: String,
        val peerName: String,
        val peerAddress: String
    )

    suspend fun serveOnce(
        pairingSecret: String,
        port: Int = DEFAULT_PORT,
        onRequest: suspend (Request) -> Decision
    ): Request? = withContext(Dispatchers.IO) {
        require(UltraShareTransport.isValidPairingSecret(pairingSecret))
        require(port in 1..65535)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port), 4)
            server.soTimeout = 750
            while (true) {
                currentCoroutineContext().ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                socket.use { client ->
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    client.tcpNoDelay = true
                    handleIncoming(client, pairingSecret, onRequest)
                    return@withContext null
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    suspend fun request(
        host: String,
        pairingSecret: String,
        peerName: String,
        port: Int = DEFAULT_PORT
    ): Decision = withContext(Dispatchers.IO) {
        require(UltraShareTransport.isValidPrivateLanHost(host) || host == "127.0.0.1") { "عنوان الجهاز غير صالح." }
        require(UltraShareTransport.isValidPairingSecret(pairingSecret)) { "رمز الاقتران غير صالح." }
        require(port in 1..65535)
        val nameBytes = peerName.trim().toByteArray(Charsets.UTF_8)
        require(nameBytes.isNotEmpty() && nameBytes.size <= MAX_NAME_BYTES) { "اسم الجهاز طويل أو فارغ." }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
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

            val encryptedLength = input.readInt()
            require(encryptedLength in (NONCE_BYTES + 1)..MAX_FRAME_BYTES) { "إطار قرار الاتصال غير صالح." }
            val encrypted = ByteArray(encryptedLength)
            input.readFully(encrypted)
            val nonce = encrypted.copyOfRange(0, NONCE_BYTES)
            val ciphertext = encrypted.copyOfRange(NONCE_BYTES, encrypted.size)
            val response = decrypt(key, nonce, ciphertext)
            require(response == "ACCEPT|$requestId" || response == "REJECT|$requestId") {
                "استجابة قبول الاتصال غير صالحة."
            }
            if (response.startsWith("ACCEPT|")) Decision.ACCEPTED else Decision.REJECTED
        }
    }

    private suspend fun handleIncoming(
        socket: Socket,
        pairingSecret: String,
        onRequest: suspend (Request) -> Decision
    ) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val challenge = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.write(challenge)
        output.flush()

        val requestId = input.readUTF()
        require(requestId.length in 16..MAX_ID_BYTES && requestId.all { it.isLetterOrDigit() || it == '-' }) {
            "معرّف طلب الاتصال غير صالح."
        }
        val nameLength = input.readUnsignedShort()
        require(nameLength in 1..MAX_NAME_BYTES)
        val nameBytes = ByteArray(nameLength)
        input.readFully(nameBytes)
        val suppliedMac = ByteArray(MAC_BYTES)
        input.readFully(suppliedMac)
        val name = nameBytes.toString(Charsets.UTF_8)
        require(name.isNotBlank() && name.none { it.isISOControl() }) { "اسم الجهاز غير صالح." }

        val key = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
        val expectedMac = requestAuthentication(challenge, requestId, nameBytes)
        require(MessageDigest.isEqual(expectedMac, suppliedMac)) { "فشل التحقق من رمز الاقتران." }

        val decision = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
            onRequest(
            Request(
                requestId = requestId,
                peerName = name,
                peerAddress = socket.inetAddress.hostAddress.orEmpty()
            )
            )
        } ?: Decision.REJECTED
        val payload = (if (decision == Decision.ACCEPTED) "ACCEPT|" else "REJECT|") + requestId
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val ciphertext = encrypt(key, nonce, payload)
        val frame = nonce + ciphertext
        require(frame.size <= MAX_FRAME_BYTES)
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
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
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveSubkey(key, "decision-encryption"), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD("MPULTRA_CONTROL_DECISION|1".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveSubkey(key, "decision-encryption"), "AES"), GCMParameterSpec(TAG_BITS, nonce))
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

    private fun randomId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}
