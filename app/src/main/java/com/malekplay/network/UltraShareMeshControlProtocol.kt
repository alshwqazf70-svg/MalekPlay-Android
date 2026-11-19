package com.malekplay.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Typed, bounded messages carried only inside authenticated MPC2 application frames.
 * KEY_OFFER payloads therefore inherit MPC2 AES-GCM authentication and replay protection.
 * This codec never writes room keys to discovery, QR data, logs, or persistent storage.
 */
internal object UltraShareMeshControlProtocol {
    private const val MAGIC = 0x4D504D43 // MPMC
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = UltraShareControlFrames.MAX_PLAINTEXT_BYTES
    const val ROOM_KEY_BYTES = 32
    const val DIGEST_BYTES = 32
    private const val ID_BYTES = 16
    private const val MAX_ROSTER_BYTES = 12 * 1024

    enum class Type(val wire: Int) {
        KEY_OFFER(1),
        KEY_ACK(2),
        ROSTER_UPDATE(3),
        ROSTER_ACK(4)
    }

    data class Message(
        val type: Type,
        val roomId: String,
        val senderPeerId: String,
        val revision: Long,
        val payload: ByteArray
    )

    fun encode(message: Message): ByteArray {
        validate(message)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(VERSION)
                out.writeByte(message.type.wire)
                out.write(hexToBytes(message.roomId))
                out.write(hexToBytes(message.senderPeerId))
                out.writeLong(message.revision)
                out.writeShort(message.payload.size)
                out.write(message.payload)
            }
            bytes.toByteArray()
        }.also { require(it.size <= MAX_MESSAGE_BYTES) }
    }

    fun decode(bytes: ByteArray): Message? = runCatching {
        require(bytes.size in (4 + 1 + 1 + ID_BYTES + ID_BYTES + 8 + 2)..MAX_MESSAGE_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readUnsignedByte() == VERSION)
            val type = Type.entries.firstOrNull { it.wire == input.readUnsignedByte() }
                ?: error("Unknown Mesh control message type.")
            val roomId = bytesToHex(ByteArray(ID_BYTES).also(input::readFully))
            val senderId = bytesToHex(ByteArray(ID_BYTES).also(input::readFully))
            val revision = input.readLong()
            val payloadLength = input.readUnsignedShort()
            require(payloadLength == input.available())
            val payload = ByteArray(payloadLength).also(input::readFully)
            Message(type, roomId, senderId, revision, payload).also(::validate)
        }
    }.getOrNull()

    fun keyOffer(
        roomId: String,
        senderPeerId: String,
        revision: Long,
        roomKey: ByteArray
    ): Message = Message(Type.KEY_OFFER, roomId, senderPeerId, revision, roomKey.copyOf())
        .also(::validate)

    fun rosterUpdate(
        roomId: String,
        senderPeerId: String,
        revision: Long,
        authenticatedRosterWire: String
    ): Message = Message(
        Type.ROSTER_UPDATE, roomId, senderPeerId, revision,
        authenticatedRosterWire.toByteArray(Charsets.UTF_8)
    ).also(::validate)

    fun acknowledgement(
        type: Type,
        roomId: String,
        senderPeerId: String,
        revision: Long,
        acknowledgedDigest: ByteArray
    ): Message {
        require(type == Type.KEY_ACK || type == Type.ROSTER_ACK)
        return Message(type, roomId, senderPeerId, revision, acknowledgedDigest.copyOf()).also(::validate)
    }

    private fun validate(message: Message) {
        require(isId(message.roomId) && isId(message.senderPeerId))
        require(message.revision > 0L)
        when (message.type) {
            Type.KEY_OFFER -> require(message.payload.size == ROOM_KEY_BYTES)
            Type.KEY_ACK, Type.ROSTER_ACK -> require(message.payload.size == DIGEST_BYTES)
            Type.ROSTER_UPDATE -> {
                require(message.payload.isNotEmpty() && message.payload.size <= MAX_ROSTER_BYTES)
                val wire = message.payload.toString(Charsets.UTF_8)
                require(wire.toByteArray(Charsets.UTF_8).contentEquals(message.payload))
                require(wire.startsWith("MPMESH|1|"))
            }
        }
        require(4 + 1 + 1 + ID_BYTES + ID_BYTES + 8 + 2 + message.payload.size <= MAX_MESSAGE_BYTES)
    }

    // IDs are serialized as 16 raw bytes but represented in Kotlin as 32 hex characters.
    private fun isId(value: String): Boolean =
        value.length == ID_BYTES * 2 && value.all { it.digitToIntOrNull(16) != null }

    private fun hexToBytes(value: String): ByteArray {
        require(isId(value))
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
