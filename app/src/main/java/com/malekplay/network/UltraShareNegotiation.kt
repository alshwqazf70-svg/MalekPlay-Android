package com.malekplay.network

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Versioned, secret-free connection offer for Ultra Share.
 *
 * Discovery carries only public peer metadata and reachable endpoint candidates.
 * Pairing secrets and authentication proofs must only travel in the authenticated
 * connection handshake, never in UDP broadcasts.
 */
object UltraShareNegotiation {
    const val VERSION = 2
    private const val PREFIX = "MPULTRA_OFFER|2"
    private const val MAX_WIRE_CHARS = 2048
    private const val MAX_CANDIDATES = 8

    enum class Transport { WLAN, WIFI_DIRECT, HOTSPOT }

    data class Candidate(
        val transport: Transport,
        val host: String,
        val port: Int,
        val connected: Boolean = true,
        val reachable: Boolean = true,
        val priority: Int = 0
    ) {
        fun isUsable(): Boolean =
            connected && reachable && host.isNotBlank() &&
                host.length <= 64 && port in 1..65535 &&
                UltraShareTransport.isValidPrivateLanHost(host)
    }

    data class Offer(
        val peerId: String,
        val displayName: String,
        val candidates: List<Candidate>,
        val protocolVersion: Int = VERSION
    )

    enum class InvitationStatus { PENDING, ACCEPTED, REJECTED, EXPIRED, CANCELLED }

    data class Invitation(
        val id: String,
        val peerId: String,
        val createdAtMillis: Long,
        val status: InvitationStatus = InvitationStatus.PENDING
    ) {
        fun decide(accept: Boolean): Invitation {
            require(status == InvitationStatus.PENDING) { "Invitation is no longer pending." }
            return copy(status = if (accept) InvitationStatus.ACCEPTED else InvitationStatus.REJECTED)
        }

        fun expire(nowMillis: Long, timeoutMillis: Long = 30_000L): Invitation {
            require(timeoutMillis > 0)
            return if (status == InvitationStatus.PENDING &&
                nowMillis - createdAtMillis >= timeoutMillis
            ) copy(status = InvitationStatus.EXPIRED) else this
        }

        fun cancel(): Invitation {
            require(status == InvitationStatus.PENDING) { "Invitation is no longer pending." }
            return copy(status = InvitationStatus.CANCELLED)
        }
    }

    /** Prefer a connected/reachable P2P path, then WLAN, then hotspot. */
    fun orderedCandidates(candidates: List<Candidate>): List<Candidate> =
        candidates.asSequence()
            .filter { it.isUsable() }
            .distinctBy { "${it.transport}:${it.host}:${it.port}" }
            .sortedWith(
                compareBy<Candidate> { transportRank(it.transport) }
                    .thenByDescending { it.priority }
            )
            .toList()

    fun selectCandidate(candidates: List<Candidate>): Candidate? =
        orderedCandidates(candidates).firstOrNull()

    /**
     * Rebuilds route candidates only from discovery sightings carrying the exact
     * authenticated/stable peer identity. A changed IP is never trusted by address
     * similarity or display name alone.
     */
    fun candidatesForPeer(
        peerId: String?,
        discoveredPeers: List<UltraShareDiscovery.Peer>
    ): List<Candidate> {
        if (peerId.isNullOrBlank()) return emptyList()
        return orderedCandidates(
            discoveredPeers.asSequence()
                .filter { it.peerId?.equals(peerId, ignoreCase = true) == true }
                .flatMap { it.candidates.asSequence() }
                .toList()
        )
    }

    fun dataPortForHost(host: String, candidates: List<Candidate>, fallbackPort: Int): Int {
        require(fallbackPort in 1..65535)
        return orderedCandidates(candidates).firstOrNull { it.host == host }?.port ?: fallbackPort
    }

    fun encode(offer: Offer): String {
        require(offer.protocolVersion == VERSION)
        require(offer.peerId.isNotBlank() && offer.peerId.length <= 96)
        require(offer.displayName.isNotBlank() && offer.displayName.length <= 64)
        require(offer.candidates.size <= MAX_CANDIDATES)
        val fields = mutableListOf(
            PREFIX,
            field(offer.peerId),
            field(offer.displayName),
            offer.candidates.size.toString()
        )
        offer.candidates.forEach { candidate ->
            require(candidate.host.length <= 64 && candidate.port in 1..65535)
            fields += listOf(
                candidate.transport.name,
                field(candidate.host),
                candidate.port.toString(),
                if (candidate.connected) "1" else "0",
                if (candidate.reachable) "1" else "0",
                candidate.priority.toString()
            ).joinToString(",")
        }
        return fields.joinToString("|").also {
            require(it.length <= MAX_WIRE_CHARS) { "Offer is too large." }
        }
    }

    fun decode(raw: String): Offer? = runCatching {
        require(raw.length <= MAX_WIRE_CHARS)
        val fields = raw.split('|')
        require(fields.size >= 4 && fields[0] == "MPULTRA_OFFER" && fields[1] == VERSION.toString())
        val peerId = unfield(fields[2])
        val name = unfield(fields[3])
        val count = fields.getOrNull(4)?.toIntOrNull() ?: 0
        require(peerId.isNotBlank() && peerId.length <= 96)
        require(name.isNotBlank() && name.length <= 64)
        require(count in 0..MAX_CANDIDATES && fields.size == 5 + count)
        val candidates = fields.drop(5).map { encoded ->
            val parts = encoded.split(',')
            require(parts.size == 6)
            Candidate(
                transport = Transport.valueOf(parts[0]),
                host = unfield(parts[1]),
                port = parts[2].toInt(),
                connected = parts[3] == "1",
                reachable = parts[4] == "1",
                priority = parts[5].toInt()
            ).also {
                require(parts[3] in setOf("0", "1") && parts[4] in setOf("0", "1"))
                require(it.host.isNotBlank() && it.host.length <= 64 && it.port in 1..65535)
            }
        }
        Offer(peerId, name, candidates)
    }.getOrNull()

    private fun transportRank(transport: Transport): Int = when (transport) {
        Transport.WIFI_DIRECT -> 0
        Transport.WLAN -> 1
        Transport.HOTSPOT -> 2
    }

    private fun field(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun unfield(value: String): String = URLDecoder.decode(value, "UTF-8")
}

/** A single batch manifest keeps per-file outcomes independent within one session. */
data class UltraShareBatchManifest(
    val batchId: String,
    val files: List<FileEntry>
) {
    data class FileEntry(
        val fileId: String,
        val displayName: String,
        val sizeBytes: Long,
        val sha256: String
    )

    init {
        require(batchId.isNotBlank() && batchId.length <= 96)
        require(files.isNotEmpty() && files.size <= MAX_FILES)
        require(files.map { it.fileId }.distinct().size == files.size)
        files.forEach {
            require(it.fileId.isNotBlank() && it.fileId.length <= 96)
            require(it.displayName.isNotBlank() && it.displayName.length <= 255)
            require(it.sizeBytes >= 0)
            require(it.sha256.matches(Regex("[0-9a-fA-F]{64}")))
        }
    }

    companion object { const val MAX_FILES = 256 }
}

enum class UltraShareFileResultStatus {
    WAITING, ACCEPTED, SENDING, VERIFYING, COMPLETED, FAILED, REJECTED, CANCELLED
}

data class UltraShareFileResult(
    val fileId: String,
    val status: UltraShareFileResultStatus,
    val transferredBytes: Long = 0L,
    val error: String? = null
) {
    init {
        require(fileId.isNotBlank())
        require(transferredBytes >= 0)
    }
}
