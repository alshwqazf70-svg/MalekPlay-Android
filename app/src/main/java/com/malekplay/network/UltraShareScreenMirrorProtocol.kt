package com.malekplay.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Control & encrypted media-stream protocol for UltraShare Group Screen Mirroring.
 *
 * 1) Control messages (`MPSM`) travel strictly inside existing MPC2 (`MPMF` AES-GCM)
 *    authenticated application frames so room keys and stream secrets never touch
 *    QR codes, discovery, or plaintext TCP.
 * 2) Media stream handshakes (`MPSH`) and video/audio frames (`MPSV`) travel on an
 *    independent local TCP channel (default port 38746) authenticated with the
 *    session's 32-byte ephemeral `streamKey` and encrypted end-to-end with AES-256-GCM.
 */
object UltraShareScreenMirrorProtocol {
    const val CONTROL_MAGIC = 0x4D50534D // MPSM
    const val CONTROL_VERSION = 1

    const val HANDSHAKE_MAGIC = 0x4D505348 // MPSH
    const val STREAM_MAGIC = 0x4D505356 // MPSV
    const val STREAM_VERSION = 1

    const val ID_BYTES = 16
    const val STREAM_KEY_BYTES = 32
    const val CHALLENGE_BYTES = 32
    const val MAC_BYTES = 32
    const val NONCE_PREFIX_BYTES = 4
    const val TAG_BYTES = 16
    const val TAG_BITS = 128
    const val MAX_FRAME_PAYLOAD_BYTES = 2 * 1024 * 1024
    const val STREAM_HEADER_BYTES = 4 + 1 + 1 + 1 + 1 + ID_BYTES + 8 + 8 + 8 + 2 + 2 + 2 + 4

    enum class ControlType(val wire: Int) {
        MIRROR_START(1),
        MIRROR_UPDATE(2),
        MIRROR_STOP(3),
        MIRROR_VIEWER_STATE(4)
    }

    enum class ViewerAction(val wire: Int) {
        JOINED(1),
        LEFT(2),
        REQUEST_IDR(3)
    }

    enum class FrameType(val wire: Int) {
        VIDEO_CONFIG(1),
        VIDEO_IDR(2),
        VIDEO_DELTA(3),
        AUDIO_CONFIG(4),
        AUDIO_FRAME(5),
        HEARTBEAT(6),
        SCREEN_PROTECTED(7)
    }

    const val FLAG_KEYFRAME: Int = 0x01
    const val FLAG_EOS: Int = 0x02
    const val FLAG_PROTECTED: Int = 0x04

    data class StreamConfig(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Int,
        val audioEnabled: Boolean,
        val audioSampleRate: Int = 48_000,
        val audioChannelCount: Int = 2
    ) {
        init {
            require(width in 16..4096 && height in 16..4096) { "Invalid mirror video size." }
            require(fps in 1..120) { "Invalid mirror FPS." }
            require(bitrateBps in 100_000..50_000_000) { "Invalid mirror bitrate." }
            require(audioSampleRate in 8_000..96_000) { "Invalid audio sample rate." }
            require(audioChannelCount in 1..2) { "Invalid audio channels." }
        }
    }

    data class ControlMessage(
        val type: ControlType,
        val sessionId: String,
        val senderPeerId: String,
        val revision: Long,
        val streamPort: Int = UltraShareScreenMirrorPolicy.DEFAULT_STREAM_PORT,
        val streamKey: ByteArray? = null,
        val config: StreamConfig? = null,
        val viewerAction: ViewerAction? = null
    ) {
        override fun toString(): String =
            "ControlMessage(type=$type, sessionId=$sessionId, senderPeerId=$senderPeerId, " +
                "revision=$revision, streamPort=$streamPort, hasKey=${streamKey != null}, " +
                "config=$config, viewerAction=$viewerAction)"
    }

    data class MediaFrameHeader(
        val frameType: FrameType,
        val flags: Int,
        val sessionId: String,
        val wireSeq: Long,
        val mediaSeq: Long,
        val timestampUs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int
    )

    data class MediaFrame(
        val header: MediaFrameHeader,
        val payload: ByteArray
    )

    fun isMirrorControlPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0x4D.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x53.toByte() &&
            bytes[3] == 0x4D.toByte()
    }

    fun encodeControl(message: ControlMessage): ByteArray {
        validateControl(message)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(CONTROL_MAGIC)
                out.writeByte(CONTROL_VERSION)
                out.writeByte(message.type.wire)
                out.write(hexToBytes(message.sessionId))
                out.write(hexToBytes(message.senderPeerId))
                out.writeLong(message.revision)
                when (message.type) {
                    ControlType.MIRROR_START, ControlType.MIRROR_UPDATE -> {
                        val key = requireNotNull(message.streamKey)
                        val cfg = requireNotNull(message.config)
                        out.writeShort(message.streamPort)
                        out.write(key)
                        out.writeShort(cfg.width)
                        out.writeShort(cfg.height)
                        out.writeByte(cfg.fps)
                        out.writeInt(cfg.bitrateBps)
                        out.writeBoolean(cfg.audioEnabled)
                        out.writeInt(cfg.audioSampleRate)
                        out.writeByte(cfg.audioChannelCount)
                    }
                    ControlType.MIRROR_STOP -> Unit
                    ControlType.MIRROR_VIEWER_STATE -> {
                        out.writeByte(requireNotNull(message.viewerAction).wire)
                    }
                }
            }
            bytes.toByteArray()
        }.also {
            require(it.size <= UltraShareControlFrames.MAX_PLAINTEXT_BYTES)
        }
    }

    fun decodeControl(bytes: ByteArray): ControlMessage? = runCatching {
        require(bytes.size in (4 + 1 + 1 + ID_BYTES + ID_BYTES + 8)..UltraShareControlFrames.MAX_PLAINTEXT_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == CONTROL_MAGIC && input.readUnsignedByte() == CONTROL_VERSION)
            val typeWire = input.readUnsignedByte()
            val type = ControlType.entries.firstOrNull { it.wire == typeWire }
                ?: error("Unknown mirror control type.")
            val sessionId = bytesToHex(ByteArray(ID_BYTES).also(input::readFully))
            val senderPeerId = bytesToHex(ByteArray(ID_BYTES).also(input::readFully))
            val revision = input.readLong()
            val msg = when (type) {
                ControlType.MIRROR_START, ControlType.MIRROR_UPDATE -> {
                    val port = input.readUnsignedShort()
                    val key = ByteArray(STREAM_KEY_BYTES).also(input::readFully)
                    val width = input.readUnsignedShort()
                    val height = input.readUnsignedShort()
                    val fps = input.readUnsignedByte()
                    val bitrate = input.readInt()
                    val audioEnabled = input.readBoolean()
                    val sampleRate = input.readInt()
                    val channels = input.readUnsignedByte()
                    ControlMessage(
                        type = type,
                        sessionId = sessionId,
                        senderPeerId = senderPeerId,
                        revision = revision,
                        streamPort = port,
                        streamKey = key,
                        config = StreamConfig(width, height, fps, bitrate, audioEnabled, sampleRate, channels)
                    )
                }
                ControlType.MIRROR_STOP -> {
                    ControlMessage(
                        type = type,
                        sessionId = sessionId,
                        senderPeerId = senderPeerId,
                        revision = revision
                    )
                }
                ControlType.MIRROR_VIEWER_STATE -> {
                    val actionWire = input.readUnsignedByte()
                    val action = ViewerAction.entries.firstOrNull { it.wire == actionWire }
                        ?: error("Unknown viewer action.")
                    ControlMessage(
                        type = type,
                        sessionId = sessionId,
                        senderPeerId = senderPeerId,
                        revision = revision,
                        viewerAction = action
                    )
                }
            }
            require(input.available() == 0) { "Trailing bytes in mirror control message." }
            msg.also(::validateControl)
        }
    }.getOrNull()

    private fun validateControl(message: ControlMessage) {
        require(isHexId(message.sessionId) && isHexId(message.senderPeerId))
        require(message.revision > 0L)
        when (message.type) {
            ControlType.MIRROR_START, ControlType.MIRROR_UPDATE -> {
                require(message.streamPort in 1024..65535)
                require(message.streamKey != null && message.streamKey.size == STREAM_KEY_BYTES)
                requireNotNull(message.config)
            }
            ControlType.MIRROR_STOP -> Unit
            ControlType.MIRROR_VIEWER_STATE -> requireNotNull(message.viewerAction)
        }
    }

    /**
     * Per-connection AES-256-GCM stream cipher with strict monotonic sequence verification.
     */
    class StreamCipherSession internal constructor(
        private val sendKey: ByteArray,
        private val receiveKey: ByteArray,
        private val sendPrefix: ByteArray,
        private val receivePrefix: ByteArray,
        private val sessionId: String
    ) : AutoCloseable {
        private var nextSendWireSeq = 0L
        private var nextReceiveWireSeq = 0L
        private var closed = false
        private val sessionIdBytes = hexToBytes(sessionId)

        @Synchronized
        fun writeFrame(
            out: OutputStream,
            frameType: FrameType,
            flags: Int,
            mediaSeq: Long,
            timestampUs: Long,
            width: Int,
            height: Int,
            rotation: Int,
            payload: ByteArray
        ) {
            check(!closed) { "Mirror stream cipher session is closed." }
            require(payload.size <= MAX_FRAME_PAYLOAD_BYTES) { "Mirror frame payload too large." }
            check(nextSendWireSeq >= 0L && nextSendWireSeq != Long.MAX_VALUE) {
                "Mirror stream sequence exhausted."
            }
            val wireSeq = nextSendWireSeq
            val encryptedLength = payload.size + TAG_BYTES
            val header = ByteBuffer.allocate(STREAM_HEADER_BYTES)
                .putInt(STREAM_MAGIC)
                .put(STREAM_VERSION.toByte())
                .put(frameType.wire.toByte())
                .put(flags.toByte())
                .put(0.toByte())
                .put(sessionIdBytes)
                .putLong(wireSeq)
                .putLong(mediaSeq)
                .putLong(timestampUs)
                .putShort(width.coerceIn(0, 65535).toShort())
                .putShort(height.coerceIn(0, 65535).toShort())
                .putShort(rotation.coerceIn(0, 359).toShort())
                .putInt(encryptedLength)
                .array()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(sendKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(sendPrefix, wireSeq))
            )
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(payload)
            require(encrypted.size == encryptedLength)
            nextSendWireSeq++
            out.write(header)
            out.write(encrypted)
            out.flush()
        }

        @Synchronized
        fun readFrame(input: InputStream): MediaFrame {
            check(!closed) { "Mirror stream cipher session is closed." }
            val dataIn = DataInputStream(input)
            val header = ByteArray(STREAM_HEADER_BYTES)
            dataIn.readFully(header)

            val buf = ByteBuffer.wrap(header)
            val magic = buf.int
            val version = buf.get().toInt() and 0xff
            val typeWire = buf.get().toInt() and 0xff
            val flags = buf.get().toInt() and 0xff
            buf.get() // reserved
            val sidBytes = ByteArray(ID_BYTES).also(buf::get)
            val wireSeq = buf.long
            val mediaSeq = buf.long
            val timestampUs = buf.long
            val width = buf.short.toInt() and 0xffff
            val height = buf.short.toInt() and 0xffff
            val rotation = buf.short.toInt() and 0xffff
            val encryptedLength = buf.int

            require(magic == STREAM_MAGIC && version == STREAM_VERSION) {
                "Unsupported mirror media packet header."
            }
            val frameType = FrameType.entries.firstOrNull { it.wire == typeWire }
                ?: throw IOException("Unknown mirror frame type: $typeWire")
            if (!MessageDigest.isEqual(sidBytes, sessionIdBytes)) {
                throw SecurityException("Mirror frame session ID mismatch.")
            }
            if (wireSeq != nextReceiveWireSeq) {
                throw SecurityException("Mirror frame sequence anomaly: expected $nextReceiveWireSeq got $wireSeq.")
            }
            require(encryptedLength in TAG_BYTES..(MAX_FRAME_PAYLOAD_BYTES + TAG_BYTES)) {
                "Invalid mirror frame encrypted length: $encryptedLength"
            }

            val encrypted = ByteArray(encryptedLength)
            dataIn.readFully(encrypted)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(receiveKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(receivePrefix, wireSeq))
            )
            cipher.updateAAD(header)
            val plaintext = try {
                cipher.doFinal(encrypted)
            } catch (badTag: AEADBadTagException) {
                throw SecurityException("Mirror frame authentication tag verification failed.", badTag)
            }
            nextReceiveWireSeq++
            return MediaFrame(
                header = MediaFrameHeader(
                    frameType = frameType,
                    flags = flags,
                    sessionId = sessionId,
                    wireSeq = wireSeq,
                    mediaSeq = mediaSeq,
                    timestampUs = timestampUs,
                    width = width,
                    height = height,
                    rotation = rotation
                ),
                payload = plaintext
            )
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
     * Server-side mutual authentication on the independent media stream socket.
     * Rejects unapproved viewer identities or invalid proofs before deriving stream keys.
     */
    fun performHostStreamHandshake(
        input: InputStream,
        output: OutputStream,
        sessionId: String,
        hostPeerId: String,
        streamKey: ByteArray,
        isAuthorizedViewer: (viewerPeerId: String) -> Boolean,
        random: SecureRandom = SecureRandom()
    ): Pair<String, StreamCipherSession> {
        require(isHexId(sessionId) && isHexId(hostPeerId))
        require(streamKey.size == STREAM_KEY_BYTES)
        val sidBytes = hexToBytes(sessionId)
        val hostIdBytes = hexToBytes(hostPeerId)
        val hostChallenge = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)

        val out = DataOutputStream(output)
        out.writeInt(HANDSHAKE_MAGIC)
        out.writeByte(STREAM_VERSION)
        out.write(sidBytes)
        out.write(hostIdBytes)
        out.write(hostChallenge)
        out.flush()

        val dataIn = DataInputStream(input)
        val viewerIdBytes = ByteArray(ID_BYTES).also(dataIn::readFully)
        val viewerChallenge = ByteArray(CHALLENGE_BYTES).also(dataIn::readFully)
        val suppliedViewerProof = ByteArray(MAC_BYTES).also(dataIn::readFully)
        val viewerPeerId = bytesToHex(viewerIdBytes)

        if (!isAuthorizedViewer(viewerPeerId)) {
            out.writeByte(0)
            out.flush()
            throw SecurityException("Unpaired or unauthorized screen mirror viewer rejected: $viewerPeerId")
        }
        val expectedViewerProof = computeAuthMac(
            key = streamKey,
            label = "MPSM_AUTH_CLIENT|1|",
            sessionIdBytes = sidBytes,
            hostIdBytes = hostIdBytes,
            viewerIdBytes = viewerIdBytes,
            hostChallenge = hostChallenge,
            viewerChallenge = viewerChallenge
        )
        if (!MessageDigest.isEqual(expectedViewerProof, suppliedViewerProof)) {
            out.writeByte(0)
            out.flush()
            throw SecurityException("Mirror viewer proof authentication failed.")
        }

        val hostProof = computeAuthMac(
            key = streamKey,
            label = "MPSM_AUTH_SERVER|1|",
            sessionIdBytes = sidBytes,
            hostIdBytes = hostIdBytes,
            viewerIdBytes = viewerIdBytes,
            hostChallenge = hostChallenge,
            viewerChallenge = viewerChallenge
        )
        out.writeByte(1)
        out.write(hostProof)
        out.flush()

        val cipherSession = deriveStreamSession(
            streamKey = streamKey,
            sessionId = sessionId,
            sidBytes = sidBytes,
            hostIdBytes = hostIdBytes,
            viewerIdBytes = viewerIdBytes,
            hostChallenge = hostChallenge,
            viewerChallenge = viewerChallenge,
            isHost = true
        )
        return viewerPeerId to cipherSession
    }

    /**
     * Viewer-side mutual authentication on the independent media stream socket.
     */
    fun performViewerStreamHandshake(
        input: InputStream,
        output: OutputStream,
        expectedSessionId: String,
        expectedHostPeerId: String,
        localViewerPeerId: String,
        streamKey: ByteArray,
        random: SecureRandom = SecureRandom()
    ): StreamCipherSession {
        require(isHexId(expectedSessionId) && isHexId(expectedHostPeerId) && isHexId(localViewerPeerId))
        require(streamKey.size == STREAM_KEY_BYTES)
        val expectedSidBytes = hexToBytes(expectedSessionId)
        val expectedHostIdBytes = hexToBytes(expectedHostPeerId)
        val viewerIdBytes = hexToBytes(localViewerPeerId)

        val dataIn = DataInputStream(input)
        val magic = dataIn.readInt()
        val version = dataIn.readUnsignedByte()
        if (magic != HANDSHAKE_MAGIC || version != STREAM_VERSION) {
            throw IOException("Unsupported mirror stream handshake.")
        }
        val sidBytes = ByteArray(ID_BYTES).also(dataIn::readFully)
        val hostIdBytes = ByteArray(ID_BYTES).also(dataIn::readFully)
        val hostChallenge = ByteArray(CHALLENGE_BYTES).also(dataIn::readFully)
        if (!MessageDigest.isEqual(sidBytes, expectedSidBytes) ||
            !MessageDigest.isEqual(hostIdBytes, expectedHostIdBytes)
        ) {
            throw SecurityException("Mirror stream host or session mismatch.")
        }

        val viewerChallenge = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)
        val viewerProof = computeAuthMac(
            key = streamKey,
            label = "MPSM_AUTH_CLIENT|1|",
            sessionIdBytes = sidBytes,
            hostIdBytes = hostIdBytes,
            viewerIdBytes = viewerIdBytes,
            hostChallenge = hostChallenge,
            viewerChallenge = viewerChallenge
        )
        val out = DataOutputStream(output)
        out.write(viewerIdBytes)
        out.write(viewerChallenge)
        out.write(viewerProof)
        out.flush()

        val accepted = try {
            dataIn.readUnsignedByte()
        } catch (_: EOFException) {
            throw SecurityException("Host closed mirror stream during authentication.")
        }
        if (accepted != 1) {
            throw SecurityException("Host rejected mirror stream connection.")
        }
        val suppliedHostProof = ByteArray(MAC_BYTES).also(dataIn::readFully)
        val expectedHostProof = computeAuthMac(
            key = streamKey,
            label = "MPSM_AUTH_SERVER|1|",
            sessionIdBytes = sidBytes,
            hostIdBytes = hostIdBytes,
            viewerIdBytes = viewerIdBytes,
            hostChallenge = hostChallenge,
            viewerChallenge = viewerChallenge
        )
        if (!MessageDigest.isEqual(expectedHostProof, suppliedHostProof)) {
            throw SecurityException("Host proof verification failed on mirror stream.")
        }
        return deriveStreamSession(
            streamKey = streamKey,
            sessionId = expectedSessionId,
            sidBytes = sidBytes,
            hostIdBytes = hostIdBytes,
            viewerIdBytes = viewerIdBytes,
            hostChallenge = hostChallenge,
            viewerChallenge = viewerChallenge,
            isHost = false
        )
    }

    private fun deriveStreamSession(
        streamKey: ByteArray,
        sessionId: String,
        sidBytes: ByteArray,
        hostIdBytes: ByteArray,
        viewerIdBytes: ByteArray,
        hostChallenge: ByteArray,
        viewerChallenge: ByteArray,
        isHost: Boolean
    ): StreamCipherSession {
        val transcript = sidBytes + hostIdBytes + viewerIdBytes + hostChallenge + viewerChallenge
        val salt = MessageDigest.getInstance("SHA-256").digest(
            "MPSM_STREAM_SALT|1|".toByteArray(Charsets.US_ASCII) + transcript
        )
        val prk = hmac(salt, streamKey)
        val hostToViewerKey = expand(prk, "host-to-viewer-key", transcript, STREAM_KEY_BYTES)
        val viewerToHostKey = expand(prk, "viewer-to-host-key", transcript, STREAM_KEY_BYTES)
        val hostToViewerPrefix = expand(prk, "host-to-viewer-nonce", transcript, NONCE_PREFIX_BYTES)
        val viewerToHostPrefix = expand(prk, "viewer-to-host-nonce", transcript, NONCE_PREFIX_BYTES)
        try {
            return if (isHost) {
                StreamCipherSession(
                    sendKey = hostToViewerKey.copyOf(),
                    receiveKey = viewerToHostKey.copyOf(),
                    sendPrefix = hostToViewerPrefix.copyOf(),
                    receivePrefix = viewerToHostPrefix.copyOf(),
                    sessionId = sessionId
                )
            } else {
                StreamCipherSession(
                    sendKey = viewerToHostKey.copyOf(),
                    receiveKey = hostToViewerKey.copyOf(),
                    sendPrefix = viewerToHostPrefix.copyOf(),
                    receivePrefix = hostToViewerPrefix.copyOf(),
                    sessionId = sessionId
                )
            }
        } finally {
            salt.fill(0)
            prk.fill(0)
            hostToViewerKey.fill(0)
            viewerToHostKey.fill(0)
            hostToViewerPrefix.fill(0)
            viewerToHostPrefix.fill(0)
        }
    }

    private fun computeAuthMac(
        key: ByteArray,
        label: String,
        sessionIdBytes: ByteArray,
        hostIdBytes: ByteArray,
        viewerIdBytes: ByteArray,
        hostChallenge: ByteArray,
        viewerChallenge: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.US_ASCII))
        mac.update(sessionIdBytes)
        mac.update(hostIdBytes)
        mac.update(viewerIdBytes)
        mac.update(hostChallenge)
        mac.update(viewerChallenge)
        return mac.doFinal()
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
                out.writeUTF("MPSM_STREAM|1|$label")
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

    fun isHexId(raw: String): Boolean =
        raw.length == ID_BYTES * 2 && raw.all { it.digitToIntOrNull(16) != null }

    fun canonicalId(raw: String?): String? {
        val normalized = raw?.replace("-", "")?.trim()?.lowercase(java.util.Locale.ROOT) ?: return null
        return normalized.takeIf { isHexId(it) }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(isHexId(value)) { "Invalid hex ID: $value" }
        return ByteArray(ID_BYTES) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun bytesToHex(value: ByteArray): String = buildString(value.size * 2) {
        value.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append("0123456789abcdef"[unsigned ushr 4])
            append("0123456789abcdef"[unsigned and 0x0f])
        }
    }
}
