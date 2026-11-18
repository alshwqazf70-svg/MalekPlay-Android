package com.malekplay.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated, bounded room-membership snapshot for UltraShare Mesh.
 * The fresh 32-byte room key must be delivered only inside an authenticated,
 * encrypted MPC2 handshake; never place it in discovery, QR data, or logs.
 * Callers must bind issuerPeerId to the authenticated MPC2 identity and reject
 * revision rollback. This codec alone does not establish live room membership.
 */
internal object UltraShareMeshRoster {
    const val VERSION = 1
    const val MAX_PARTICIPANTS = UltraShareMeshTopology.MAX_PARTICIPANTS
    const val MAX_CANDIDATES_PER_PEER = 8
    private const val MAGIC = 0x4D50524D
    private const val DOMAIN = "MPULTRA_MESH_ROSTER|1|"
    private const val MAX_NAME_BYTES = 96
    private const val MAX_HOST_BYTES = 64
    private const val MAX_PAYLOAD_BYTES = 12 * 1024
    private const val MAX_WIRE_CHARS = 2 + (MAX_PAYLOAD_BYTES + 32) * 2 + 64

    data class Member(
        val peerId: String,
        val displayName: String,
        val candidates: List<UltraShareNegotiation.Candidate>
    )

    data class Snapshot(
        val roomId: String,
        val issuerPeerId: String,
        val revision: Long,
        val createdAtMillis: Long,
        val members: List<Member>
    )

    fun encode(snapshot: Snapshot, roomKey: ByteArray): String {
        validate(snapshot, roomKey)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(VERSION)
                out.writeUTF(normalizeId(snapshot.roomId))
                out.writeUTF(normalizeId(snapshot.issuerPeerId))
                out.writeLong(snapshot.revision)
                out.writeLong(snapshot.createdAtMillis)
                val members = snapshot.members.sortedBy { normalizeId(it.peerId) }
                out.writeByte(members.size)
                members.forEach { member ->
                    out.writeUTF(normalizeId(member.peerId))
                    val name = member.displayName.toByteArray(Charsets.UTF_8)
                    out.writeShort(name.size)
                    out.write(name)
                    val candidates = UltraShareNegotiation.orderedCandidates(member.candidates)
                        .take(MAX_CANDIDATES_PER_PEER)
                    out.writeByte(candidates.size)
                    candidates.forEach { candidate ->
                        out.writeByte(candidate.transport.ordinal)
                        val host = candidate.host.toByteArray(Charsets.US_ASCII)
                        out.writeByte(host.size)
                        out.write(host)
                        out.writeShort(candidate.port)
                        out.writeBoolean(candidate.connected)
                        out.writeBoolean(candidate.reachable)
                        out.writeInt(candidate.priority)
                    }
                }
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_PAYLOAD_BYTES)
        val mac = authenticate(roomKey, payload)
        return "MPMESH|$VERSION|" + toHex(payload) + "|" + toHex(mac)
    }

    fun decode(wire: String, roomKey: ByteArray): Snapshot? = runCatching {
        require(roomKey.size == 32 && wire.length <= MAX_WIRE_CHARS)
        val fields = wire.split('|')
        require(fields.size == 4 && fields[0] == "MPMESH" && fields[1] == VERSION.toString())
        val payload = fromHex(fields[2], MAX_PAYLOAD_BYTES)
        val suppliedMac = fromHex(fields[3], 32)
        require(suppliedMac.size == 32)
        require(MessageDigest.isEqual(authenticate(roomKey, payload), suppliedMac))
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC && input.readUnsignedByte() == VERSION)
            val roomId = normalizeId(input.readUTF())
            val issuer = normalizeId(input.readUTF())
            val revision = input.readLong()
            val createdAt = input.readLong()
            val count = input.readUnsignedByte()
            require(count in 2..MAX_PARTICIPANTS)
            val members = List(count) {
                val peerId = normalizeId(input.readUTF())
                val nameLength = input.readUnsignedShort()
                require(nameLength in 1..MAX_NAME_BYTES)
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val name = nameBytes.toString(Charsets.UTF_8)
                require(name.isNotBlank() && name.none { it.isISOControl() })
                val candidateCount = input.readUnsignedByte()
                require(candidateCount <= MAX_CANDIDATES_PER_PEER)
                val candidates = List(candidateCount) {
                    val transportIndex = input.readUnsignedByte()
                    val transport = UltraShareNegotiation.Transport.values().getOrNull(transportIndex)
                        ?: error("Unknown mesh route transport.")
                    val hostLength = input.readUnsignedByte()
                    require(hostLength in 1..MAX_HOST_BYTES)
                    val hostBytes = ByteArray(hostLength)
                    input.readFully(hostBytes)
                    val host = hostBytes.toString(Charsets.US_ASCII)
                    val candidate = UltraShareNegotiation.Candidate(
                        transport = transport,
                        host = host,
                        port = input.readUnsignedShort(),
                        connected = input.readBoolean(),
                        reachable = input.readBoolean(),
                        priority = input.readInt()
                    )
                    require(candidate.isUsable())
                    candidate
                }
                Member(peerId, name, candidates)
            }
            require(input.available() == 0)
            Snapshot(roomId, issuer, revision, createdAt, members).also { validate(it, roomKey) }
        }
    }.getOrNull()

    private fun validate(snapshot: Snapshot, roomKey: ByteArray) {
        require(roomKey.size == 32)
        require(snapshot.revision > 0L && snapshot.createdAtMillis > 0L)
        normalizeId(snapshot.roomId)
        val issuer = normalizeId(snapshot.issuerPeerId)
        require(snapshot.members.size in 2..MAX_PARTICIPANTS)
        val ids = snapshot.members.map { normalizeId(it.peerId) }
        require(ids.distinct().size == ids.size && issuer in ids)
        snapshot.members.forEach { member ->
            normalizeId(member.peerId)
            val name = member.displayName.toByteArray(Charsets.UTF_8)
            require(name.isNotEmpty() && name.size <= MAX_NAME_BYTES)
            require(member.displayName.none { it.isISOControl() })
            require(member.candidates.size <= MAX_CANDIDATES_PER_PEER)
            member.candidates.forEach { require(it.isUsable()) }
        }
    }

    private fun normalizeId(raw: String): String {
        val value = raw.replace("-", "").lowercase(java.util.Locale.ROOT)
        require(value.length == 32 && value.all { it.digitToIntOrNull(16) != null })
        return value
    }

    private fun authenticate(key: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(DOMAIN.toByteArray(Charsets.UTF_8))
        return mac.doFinal(payload)
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun fromHex(value: String, maxBytes: Int): ByteArray {
        require(value.isNotEmpty() && value.length % 2 == 0 && value.length / 2 <= maxBytes)
        return ByteArray(value.length / 2) { index ->
            val high = value[index * 2].digitToIntOrNull(16) ?: error("Invalid hex.")
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: error("Invalid hex.")
            ((high shl 4) or low).toByte()
        }
    }

    private const val HEX = "0123456789abcdef"
}
