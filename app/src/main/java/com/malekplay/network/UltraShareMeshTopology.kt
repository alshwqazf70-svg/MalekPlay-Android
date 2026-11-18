package com.malekplay.network

/**
 * Deterministic planner for a true peer-to-peer room.
 *
 * This class plans direct authenticated links; it does not open sockets, approve
 * peers, relay files, or claim that a physical mesh is already connected.
 * Runtime orchestration must establish each returned link through MPC2 and
 * verify the V2 data endpoint before marking that edge active.
 */
internal object UltraShareMeshTopology {
    const val MAX_PARTICIPANTS = 16

    data class Peer(
        val peerId: String,
        val displayName: String,
        val candidates: List<UltraShareNegotiation.Candidate>
    )

    /** Canonical undirected edge. The lower stable ID is always the initiator. */
    data class Edge private constructor(
        val firstPeerId: String,
        val secondPeerId: String
    ) {
        companion object {
            fun of(first: String, second: String): Edge {
                require(first.isNotBlank() && second.isNotBlank())
                require(!first.equals(second, ignoreCase = true)) { "A mesh edge cannot connect a peer to itself." }
                return if (first.lowercase() < second.lowercase()) Edge(first, second) else Edge(second, first)
            }
        }

        val initiatorPeerId: String get() = firstPeerId
        val responderPeerId: String get() = secondPeerId
    }

    data class DirectLink(
        val edge: Edge,
        val remotePeer: Peer,
        val endpoint: UltraShareNegotiation.Candidate
    )

    data class Plan(
        val participants: List<Peer>,
        val requiredEdges: Set<Edge>,
        val activeEdges: Set<Edge>,
        val missingEdges: Set<Edge>,
        /** Only these links should be dialed by this local device. */
        val outgoingLinks: List<DirectLink>
    ) {
        val isComplete: Boolean get() = missingEdges.isEmpty()
    }

    fun plan(
        localPeer: Peer,
        discoveredPeers: List<Peer>,
        activeEdges: Set<Edge> = emptySet(),
        maxParticipants: Int = MAX_PARTICIPANTS
    ): Plan {
        require(maxParticipants in 2..MAX_PARTICIPANTS)
        require(localPeer.peerId.isNotBlank())
        val localId = localPeer.peerId.lowercase()
        val peersById = LinkedHashMap<String, Peer>()
        peersById[localId] = localPeer

        discoveredPeers.forEach { peer ->
            require(peer.peerId.isNotBlank()) { "A mesh peer must have a stable identity." }
            val id = peer.peerId.lowercase()
            require(id != localId) { "The local device cannot be registered as a remote peer." }
            val previous = peersById[id]
            require(previous == null || previous == peer) { "Conflicting records share the same peer identity." }
            peersById[id] = peer
        }
        require(peersById.size <= maxParticipants) {
            "The room exceeds the configured mesh participant limit of $maxParticipants."
        }

        val participants = peersById.values.toList()
        val required = linkedSetOf<Edge>()
        for (i in participants.indices) {
            for (j in i + 1 until participants.size) {
                required += Edge.of(participants[i].peerId, participants[j].peerId)
            }
        }

        val normalizedActive = activeEdges.mapTo(linkedSetOf()) {
            Edge.of(it.firstPeerId, it.secondPeerId)
        }.intersect(required)

        val missing = required - normalizedActive
        val outgoing = missing.asSequence()
            .filter { it.initiatorPeerId.equals(localPeer.peerId, ignoreCase = true) }
            .map { edge ->
                val remote = peersById[edge.responderPeerId.lowercase()]
                    ?: error("Mesh responder is missing from the peer roster.")
                val endpoint = UltraShareNegotiation.orderedCandidates(remote.candidates)
                    .firstOrNull {
                        it.reachable &&
                            UltraShareCore.isValidPrivateLanHost(it.host) &&
                            it.port in 1..65535
                    } ?: throw IllegalStateException(
                    "No verified direct route is available for peer ${remote.peerId}."
                )
                DirectLink(edge, remote, endpoint)
            }
            .toList()

        return Plan(
            participants = participants,
            requiredEdges = required,
            activeEdges = normalizedActive,
            missingEdges = missing,
            outgoingLinks = outgoing
        )
    }
}
