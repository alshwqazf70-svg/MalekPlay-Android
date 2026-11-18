package com.malekplay.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * In-memory room authority/joiner state machine.
 *
 * This class deliberately has no socket access: callers must invoke it only after
 * validating the authenticated MPC2 session identity and transport the returned
 * messages through that session's encrypted application-frame APIs.
 */
internal class UltraShareMeshRoomCoordinator(
    localPeerId: String,
    private val random: SecureRandom = SecureRandom()
) : AutoCloseable {
    data class RoomState(
        val roomId: String,
        val authorityPeerId: String,
        val revision: Long,
        val members: List<UltraShareMeshTopology.Peer>,
        val isAuthority: Boolean
    )

    private val localId = canonicalId(localPeerId)
    private var roomId: String? = null
    private var authorityId: String? = null
    private var roomKey: ByteArray? = null
    private var revision: Long = 0L
    private var createdAtMillis: Long = 0L
    private val members = LinkedHashMap<String, UltraShareMeshTopology.Peer>()
    private var closed = false

    @Synchronized
    fun createAuthorityRoom(localPeer: UltraShareMeshTopology.Peer, nowMillis: Long): RoomState {
        ensureOpen()
        require(canonicalId(localPeer.peerId) == localId)
        require(nowMillis > 0L)
        wipeRoomKey()
        members.clear()
        members[localId] = localPeer.copy(peerId = localId)
        roomId = canonicalId(UUID.randomUUID().toString())
        authorityId = localId
        revision = 1L
        createdAtMillis = nowMillis
        roomKey = ByteArray(UltraShareMeshControlProtocol.ROOM_KEY_BYTES).also(random::nextBytes)
        return state()
    }

    /** Copy the key into a typed message; caller must send it only through approved MPC2. */
    @Synchronized
    fun createKeyOffer(): UltraShareMeshControlProtocol.Message {
        ensureAuthority()
        val key = roomKey ?: error("Room key is unavailable.")
        return UltraShareMeshControlProtocol.keyOffer(
            roomId = roomId!!,
            senderPeerId = localId,
            revision = revision,
            roomKey = key
        )
    }

    /**
     * Accepts a key only when the envelope identity matches the authenticated MPC2 peer.
     * Replacing an existing room is intentionally forbidden; close/reset first.
     */
    @Synchronized
    fun acceptKeyOffer(
        authenticatedPeerId: String,
        message: UltraShareMeshControlProtocol.Message
    ): UltraShareMeshControlProtocol.Message {
        ensureOpen()
        val authenticatedId = canonicalId(authenticatedPeerId)
        require(message.type == UltraShareMeshControlProtocol.Type.KEY_OFFER)
        require(canonicalId(message.senderPeerId) == authenticatedId)
        require(message.revision > 0L)
        val incomingRoom = canonicalId(message.roomId)
        require(roomKey == null || (roomId == incomingRoom && authorityId == authenticatedId)) {
            "A different room is already active; close it before joining another."
        }
        if (roomKey == null) {
            roomId = incomingRoom
            authorityId = authenticatedId
            revision = message.revision
            roomKey = message.payload.copyOf()
            createdAtMillis = System.currentTimeMillis().coerceAtLeast(1L)
            members.clear()
        } else {
            require(MessageDigest.isEqual(roomKey, message.payload)) {
                "The authority attempted to replace the active room key."
            }
        }
        val digest = keyDigest(roomKey!!)
        return UltraShareMeshControlProtocol.acknowledgement(
            UltraShareMeshControlProtocol.Type.KEY_ACK,
            roomId!!,
            localId,
            revision,
            digest
        )
    }

    /** Authority adds/refreshes a member after MPC2 approval and authenticated identity binding. */
    @Synchronized
    fun upsertMember(
        authenticatedPeerId: String,
        peer: UltraShareMeshTopology.Peer
    ): String {
        ensureAuthority()
        val authenticatedId = canonicalId(authenticatedPeerId)
        require(canonicalId(peer.peerId) == authenticatedId)
        require(authenticatedId != localId)
        require(members.size < UltraShareMeshRoster.MAX_PARTICIPANTS || members.containsKey(authenticatedId))
        val normalized = peer.copy(peerId = authenticatedId)
        val previous = members[authenticatedId]
        if (previous != normalized) {
            check(revision < Long.MAX_VALUE) { "Room revision exhausted." }
            members[authenticatedId] = normalized
            revision++
        }
        return createRosterWire()
    }

    /** Builds the HMAC-authenticated room roster; at least two members are required by the wire codec. */
    @Synchronized
    fun createRosterUpdate(): UltraShareMeshControlProtocol.Message {
        ensureAuthority()
        val wire = createRosterWire()
        return UltraShareMeshControlProtocol.rosterUpdate(roomId!!, localId, revision, wire)
    }

    /** Joiner validates MPC2 identity, room, HMAC, issuer, self-membership and strict revision advance. */
    @Synchronized
    fun acceptRosterUpdate(
        authenticatedPeerId: String,
        message: UltraShareMeshControlProtocol.Message
    ): UltraShareMeshControlProtocol.Message {
        ensureOpen()
        val authenticatedId = canonicalId(authenticatedPeerId)
        require(message.type == UltraShareMeshControlProtocol.Type.ROSTER_UPDATE)
        require(canonicalId(message.senderPeerId) == authenticatedId)
        require(roomId == canonicalId(message.roomId))
        require(authorityId == authenticatedId)
        val key = roomKey ?: error("Room key has not been established.")
        val wire = message.payload.toString(Charsets.UTF_8)
        val snapshot = UltraShareMeshRoster.decode(wire, key)
            ?: throw SecurityException("Mesh roster authentication or structure validation failed.")
        require(snapshot.roomId == roomId)
        require(snapshot.issuerPeerId == authenticatedId)
        require(snapshot.revision == message.revision && snapshot.revision > revision) {
            "Stale or inconsistent room revision."
        }
        require(snapshot.members.any { canonicalId(it.peerId) == localId }) {
            "The authenticated roster does not contain this device."
        }
        members.clear()
        snapshot.members.forEach { member ->
            members[canonicalId(member.peerId)] = UltraShareMeshTopology.Peer(
                peerId = canonicalId(member.peerId),
                displayName = member.displayName,
                candidates = member.candidates
            )
        }
        revision = snapshot.revision
        createdAtMillis = snapshot.createdAtMillis
        val digest = sha256(wire.toByteArray(Charsets.UTF_8))
        return UltraShareMeshControlProtocol.acknowledgement(
            UltraShareMeshControlProtocol.Type.ROSTER_ACK,
            roomId!!,
            localId,
            revision,
            digest
        )
    }

    @Synchronized
    fun verifyAcknowledgement(
        authenticatedPeerId: String,
        acknowledgement: UltraShareMeshControlProtocol.Message,
        expectedType: UltraShareMeshControlProtocol.Type,
        expectedPayload: ByteArray
    ): Boolean {
        ensureOpen()
        val peerId = canonicalId(authenticatedPeerId)
        if (acknowledgement.type != expectedType ||
            (expectedType != UltraShareMeshControlProtocol.Type.KEY_ACK &&
                expectedType != UltraShareMeshControlProtocol.Type.ROSTER_ACK) ||
            canonicalId(acknowledgement.senderPeerId) != peerId ||
            acknowledgement.roomId != roomId ||
            acknowledgement.revision != revision
        ) return false
        val expected = when (expectedType) {
            UltraShareMeshControlProtocol.Type.KEY_ACK -> {
                if (expectedPayload.size != UltraShareMeshControlProtocol.ROOM_KEY_BYTES) return false
                keyDigest(expectedPayload)
            }
            UltraShareMeshControlProtocol.Type.ROSTER_ACK -> sha256(expectedPayload)
            else -> return false
        }
        return MessageDigest.isEqual(expected, acknowledgement.payload)
    }

    @Synchronized
    fun state(): RoomState {
        ensureOpen()
        return RoomState(
            roomId = roomId ?: error("No room is active."),
            authorityPeerId = authorityId ?: error("No room authority is active."),
            revision = revision,
            members = members.values.toList(),
            isAuthority = authorityId == localId
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        wipeRoomKey()
        members.clear()
        roomId = null
        authorityId = null
        revision = 0L
        createdAtMillis = 0L
    }

    private fun createRosterWire(): String {
        val key = roomKey ?: error("Room key is unavailable.")
        require(members.size in 2..UltraShareMeshRoster.MAX_PARTICIPANTS) {
            "A roster requires at least two approved peers."
        }
        return UltraShareMeshRoster.encode(
            UltraShareMeshRoster.Snapshot(
                roomId = roomId!!,
                issuerPeerId = localId,
                revision = revision,
                createdAtMillis = createdAtMillis,
                members = members.values.map { peer ->
                    UltraShareMeshRoster.Member(peer.peerId, peer.displayName, peer.candidates)
                }
            ),
            key
        )
    }

    private fun keyDigest(key: ByteArray): ByteArray =
        sha256("MPULTRA_MESH_KEY_ACK|1".toByteArray(Charsets.US_ASCII) + key)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ensureAuthority() {
        ensureOpen()
        check(authorityId == localId && roomKey != null && roomId != null) {
            "This device is not the active room authority."
        }
    }

    private fun ensureOpen() = check(!closed) { "Mesh room coordinator is closed." }

    private fun wipeRoomKey() {
        roomKey?.fill(0)
        roomKey = null
    }

    private fun canonicalId(value: String): String {
        val normalized = value.replace("-", "").lowercase(java.util.Locale.ROOT)
        require(normalized.length == 32 && normalized.all { it.digitToIntOrNull(16) != null }) {
            "Invalid stable peer or room identity."
        }
        return normalized
    }
}
