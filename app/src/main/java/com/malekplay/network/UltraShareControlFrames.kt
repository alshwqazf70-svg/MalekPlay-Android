package com.malekplay.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bounded authenticated application frames for an already authenticated MPC2 session.
 *
 * This codec is intentionally not wired into UltraShareControlChannel yet. The caller
 * must supply a fresh per-handshake master secret and a transcript context that binds
 * the challenge and request identity. Never persist or log either value.
 */
object UltraShareControlFrames {
    private const val MAGIC = 0x4D504D46 // MPMF
    private const val VERSION = 1
    private const val CLIENT_TO_SERVER = 1
    private const val SERVER_TO_CLIENT = 2
    private const val KEY_BYTES = 32
    private const val PREFIX_BYTES = 4
    private const val TAG_BYTES = 16
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 4 + 1 + 1 + PREFIX_BYTES + 8 + 4
    const val MAX_PLAINTEXT_BYTES = 16 * 1024
    const val MAX_WIRE_BYTES = HEADER_BYTES + MAX_PLAINTEXT_BYTES + TAG_BYTES

    class Session private constructor(
        private val sendKey: ByteArray,
        private val receiveKey: ByteArray,
        private val sendPrefix: ByteArray,
        private val receivePrefix: ByteArray,
        private val sendDirection: Int,
        private val receiveDirection: Int
    ) : AutoCloseable {
        private var nextSendSequence = 0L
        private var nextReceiveSequence = 0L
        private var closed = false

        @Synchronized
        fun seal(plaintext: ByteArray): ByteArray {
            check(!closed) { "Control frame session is closed." }
            require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Control frame payload is too large." }
            check(nextSendSequence >= 0L && nextSendSequence != Long.MAX_VALUE) {
                "Control frame sequence exhausted; establish a new session."
            }
            val sequence = nextSendSequence
            val header = ByteBuffer.allocate(HEADER_BYTES)
                .putInt(MAGIC)
                .put(VERSION.toByte())
                .put(sendDirection.toByte())
                .put(sendPrefix)
                .putLong(sequence)
                .putInt(plaintext.size + TAG_BYTES)
                .array()
            val nonce = nonce(sendPrefix, sequence)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(plaintext)
            require(encrypted.size == plaintext.size + TAG_BYTES)
            nextSendSequence++
            return header + encrypted
        }

        @Synchronized
        fun open(frame: ByteArray): ByteArray {
            check(!closed) { "Control frame session is closed." }
            require(frame.size in (HEADER_BYTES + TAG_BYTES)..MAX_WIRE_BYTES) { "Invalid control frame size." }
            val buffer = ByteBuffer.wrap(frame)
            val magic = buffer.int
            val version = buffer.get().toInt() and 0xff
            val direction = buffer.get().toInt() and 0xff
            val prefix = ByteArray(PREFIX_BYTES).also(buffer::get)
            val sequence = buffer.long
            val encryptedLength = buffer.int
            require(magic == MAGIC && version == VERSION && direction == receiveDirection) {
                "Unsupported control frame header."
            }
            require(MessageDigest.isEqual(prefix, receivePrefix)) { "Control frame session prefix mismatch." }
            require(sequence >= 0L && sequence == nextReceiveSequence) {
                "Control frame replayed, skipped, or out of order."
            }
            require(encryptedLength in TAG_BYTES..(MAX_PLAINTEXT_BYTES + TAG_BYTES) &&
                encryptedLength == buffer.remaining()
            ) { "Invalid control frame payload length." }

            val header = frame.copyOfRange(0, HEADER_BYTES)
            val encrypted = ByteArray(encryptedLength).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(receiveKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(receivePrefix, sequence))
            )
            cipher.updateAAD(header)
            val plaintext = try {
                cipher.doFinal(encrypted)
            } catch (failure: AEADBadTagException) {
                throw SecurityException("Control frame authentication failed.", failure)
            }
            check(plaintext.size <= MAX_PLAINTEXT_BYTES)
            nextReceiveSequence++
            return plaintext
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            sendKey.fill(0)
            receiveKey.fill(0)
            sendPrefix.fill(0)
            receivePrefix.fill(0)
        }
    }

    /**
     * Creates matching client/server sessions from the same fresh handshake material.
     * masterSecret must be exactly 32 bytes. transcriptContext should bind the MPC2
     * challenge and request ID and must be identical at both peers.
     */
    fun createSession(
        masterSecret: ByteArray,
        transcriptContext: ByteArray,
        isClient: Boolean
    ): Session {
        require(masterSecret.size == KEY_BYTES) { "Master secret must be 32 bytes." }
        require(transcriptContext.size in 16..256) { "Invalid handshake transcript context." }
        val salt = MessageDigest.getInstance("SHA-256").digest(
            "MPULTRA_MPC2_FRAME_SALT|1".toByteArray(Charsets.US_ASCII) + transcriptContext
        )
        val prk = hmac(salt, masterSecret)
        val clientToServerKey = expand(prk, "client-to-server-key", transcriptContext, KEY_BYTES)
        val serverToClientKey = expand(prk, "server-to-client-key", transcriptContext, KEY_BYTES)
        val clientToServerPrefix = expand(prk, "client-to-server-nonce", transcriptContext, PREFIX_BYTES)
        val serverToClientPrefix = expand(prk, "server-to-client-nonce", transcriptContext, PREFIX_BYTES)
        try {
            return if (isClient) {
                Session(
                    clientToServerKey.copyOf(), serverToClientKey.copyOf(),
                    clientToServerPrefix.copyOf(), serverToClientPrefix.copyOf(),
                    CLIENT_TO_SERVER, SERVER_TO_CLIENT
                )
            } else {
                Session(
                    serverToClientKey.copyOf(), clientToServerKey.copyOf(),
                    serverToClientPrefix.copyOf(), clientToServerPrefix.copyOf(),
                    SERVER_TO_CLIENT, CLIENT_TO_SERVER
                )
            }
        } finally {
            salt.fill(0)
            prk.fill(0)
            clientToServerKey.fill(0)
            serverToClientKey.fill(0)
            clientToServerPrefix.fill(0)
            serverToClientPrefix.fill(0)
        }
    }

    private fun nonce(prefix: ByteArray, sequence: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putLong(sequence).array()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun expand(prk: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
        val info = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF("MPULTRA_MPC2_FRAME|1|$label")
                out.writeShort(context.size)
                out.write(context)
            }
            bytes.toByteArray()
        }
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val input = previous + info + byteArrayOf(counter.toByte())
            val block = hmac(prk, input)
            val count = minOf(block.size, length - offset)
            block.copyInto(result, offset, 0, count)
            offset += count
            previous.fill(0)
            previous = block
            input.fill(0)
            counter++
        }
        previous.fill(0)
        info.fill(0)
        return result
    }
}
