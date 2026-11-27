package com.malekplay.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable identity of an active Transfer Room session.
 *
 * Every async task, socket/stream registration, and UI/Service callback in the Transfer Room
 * is bound to BOTH [sessionId] (unique 32-hex session identifier) and [generation] (strictly
 * monotonic counter), never relying on `roomResetGeneration` alone.
 */
data class TransferSessionToken(
    val sessionId: String,
    val generation: Long
) {
    init {
        require(sessionId.isNotBlank()) { "TransferSessionToken.sessionId must not be blank." }
        require(generation >= 0L) { "TransferSessionToken.generation must be >= 0." }
    }
}

/**
 * Explicit lifecycle categories of Transfer Room operations managed per session and per peer.
 */
enum class TransferRoomTaskKind {
    /** Active outbound transfer to a single peer. */
    ACTIVE_SEND,

    /** Isolated per-recipient child job inside a multi-peer fan-out batch. */
    MULTI_SEND_RECIPIENT,

    /** Active inbound receive session from a peer. */
    ACTIVE_RECEIVE,

    /** Concurrent inbound receive stream when multiple peers send in parallel. */
    MULTI_RECEIVE_STREAM,

    /** Queued/pending transfer waiter or scheduled retry in the current room session. */
    QUEUED_PENDING,

    /** Ephemeral preparation, route-probe, or control/handshake task for a peer/session. */
    TEMP_OPERATION
}

/**
 * Handle to a task registered inside [UltraShareTransferSessionTracker].
 * Closing or completing the handle unregisters it and safely closes attached [Closeable] resources
 * when cancelled.
 */
class RegisteredTransferTask internal constructor(
    val taskId: Long,
    val token: TransferSessionToken,
    val kind: TransferRoomTaskKind,
    val peerId: String?,
    val peerHosts: Set<String>,
    val job: Job?,
    private val onUnregister: (Long) -> Unit
) : Closeable {
    private val closeables = CopyOnWriteArrayList<Closeable>()
    @Volatile private var closed = false

    fun attachCloseable(resource: Closeable) {
        if (closed) {
            runCatching { resource.close() }
            return
        }
        closeables.add(resource)
        if (closed) {
            runCatching { resource.close() }
        }
    }

    internal fun cancelAndCloseResources(reason: String) {
        closed = true
        for (resource in closeables) {
            runCatching { resource.close() }
        }
        closeables.clear()
        runCatching { job?.cancel(CancellationException(reason)) }
    }

    override fun close() {
        closed = true
        closeables.clear()
        onUnregister(taskId)
    }

    internal fun matchesPeer(targetPeerId: String?, targetHosts: Set<String>): Boolean {
        val normTargetId = targetPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val normSelfId = peerId?.trim()?.takeIf { it.isNotEmpty() }
        if (normTargetId != null && normSelfId != null && normTargetId == normSelfId) {
            return true
        }
        if (targetHosts.isNotEmpty() && peerHosts.any { it in targetHosts }) {
            return true
        }
        return false
    }
}

data class PeerTaskCancellationSummary(
    val cancelledTaskIds: List<Long>,
    val cancelledByKind: Map<TransferRoomTaskKind, Int>,
    val remainingActiveTasksInSession: Int
)

data class SessionEndCancellationSummary(
    val endedToken: TransferSessionToken,
    val newToken: TransferSessionToken,
    val cancelledTaskIds: List<Long>,
    val cancelledByKind: Map<TransferRoomTaskKind, Int>
)

/**
 * Session- and peer-scoped lifecycle registry for all Transfer Room tasks, sockets/streams,
 * queued waiters, and callback authorization checks.
 *
 * Key invariants:
 * 1. Departing peer isolation: [cancelPeerTasks] cancels ONLY tasks and closes ONLY sockets/streams
 *    associated with the departing peer, leaving other paired peers' active transfers untouched.
 * 2. Full session teardown: [endSessionAndRotate] atomically rotates [TransferSessionToken] and
 *    cancels/closes every active send, receive, multi-send, multi-receive, queued, and temporary
 *    task from the ended session.
 * 3. Stale callback rejection: [isCallbackAuthorized] rejects any callback carrying an ended
 *    [TransferSessionToken] or originating from a peer revoked in the current session.
 */
class UltraShareTransferSessionTracker(
    initialSessionId: String = newCanonicalSessionId(),
    initialGeneration: Long = 1L
) {
    private val lock = Any()
    private val nextTaskId = AtomicLong(1L)
    private var activeToken = TransferSessionToken(
        sessionId = initialSessionId,
        generation = initialGeneration
    )
    private val tasksById = LinkedHashMap<Long, RegisteredTransferTask>()
    private val revokedPeerIdsInCurrentSession = HashSet<String>()
    private val revokedPeerHostsInCurrentSession = HashSet<String>()

    fun currentToken(): TransferSessionToken = synchronized(lock) { activeToken }

    /**
     * Marks a newly paired/re-paired peer as authorized in the current session (clearing any prior
     * disconnect revocation from earlier in the same room session).
     */
    fun authorizePeerInCurrentSession(peerId: String?, peerHosts: Collection<String> = emptyList()) {
        synchronized(lock) {
            peerId?.trim()?.takeIf { it.isNotEmpty() }?.let { revokedPeerIdsInCurrentSession.remove(it) }
            for (host in peerHosts) {
                val clean = host.trim()
                if (clean.isNotEmpty()) {
                    revokedPeerHostsInCurrentSession.remove(clean)
                }
            }
        }
    }

    fun notePeerPaired(peerId: String?, peerHosts: Collection<String> = emptyList()) =
        authorizePeerInCurrentSession(peerId = peerId, peerHosts = peerHosts)

    /**
     * Registers a task under [token]. If [token] is stale or the peer has already left the
     * current session, the task's [job] and [initialCloseables] are cancelled/closed immediately
     * and `null` is returned so racing tasks never leak into a closed or departed state.
     */
    fun registerTask(
        token: TransferSessionToken,
        kind: TransferRoomTaskKind,
        peerId: String? = null,
        peerHosts: Collection<String> = emptyList(),
        job: Job? = null,
        initialCloseables: Collection<Closeable> = emptyList()
    ): RegisteredTransferTask? {
        val cleanPeerId = peerId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanHosts = peerHosts.mapNotNull { h -> h.trim().takeIf { it.isNotEmpty() } }.toSet()
        val rejectedReason: String?
        val createdTask: RegisteredTransferTask?

        synchronized(lock) {
            if (token != activeToken) {
                rejectedReason = "Transfer session ${token.sessionId}#${token.generation} already ended."
                createdTask = null
            } else if (isPeerRevokedLocked(cleanPeerId, cleanHosts)) {
                rejectedReason = "Peer ${cleanPeerId ?: cleanHosts.firstOrNull().orEmpty()} already left session."
                createdTask = null
            } else {
                rejectedReason = null
                val id = nextTaskId.getAndIncrement()
                val handle = RegisteredTransferTask(
                    taskId = id,
                    token = token,
                    kind = kind,
                    peerId = cleanPeerId,
                    peerHosts = cleanHosts,
                    job = job,
                    onUnregister = ::unregisterTask
                )
                for (c in initialCloseables) {
                    handle.attachCloseable(c)
                }
                tasksById[id] = handle
                createdTask = handle
            }
        }

        if (createdTask == null) {
            for (c in initialCloseables) {
                runCatching { c.close() }
            }
            runCatching { job?.cancel(CancellationException(rejectedReason ?: "Session inactive")) }
            return null
        }

        job?.invokeOnCompletion {
            unregisterTask(createdTask.taskId)
        }
        return createdTask
    }

    fun unregisterTask(taskId: Long) {
        synchronized(lock) {
            tasksById.remove(taskId)
        }
    }

    fun activeTaskCount(): Int = synchronized(lock) { tasksById.size }

    fun activeTasksSnapshot(): List<RegisteredTransferTask> = synchronized(lock) {
        tasksById.values.toList()
    }

    fun activeTasksForPeer(peerId: String?, peerHost: String? = null): List<RegisteredTransferTask> {
        val hosts = peerHost?.trim()?.takeIf { it.isNotEmpty() }?.let(::setOf) ?: emptySet()
        return synchronized(lock) {
            tasksById.values.filter { it.matchesPeer(peerId, hosts) }
        }
    }

    fun cancelPeerTasks(
        peerId: String? = null,
        peerHost: String?,
        reason: String = "Peer left transfer room"
    ): Int = cancelPeerTasks(
        peerId = peerId,
        peerHosts = peerHost?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList(),
        reason = reason
    ).cancelledTaskIds.size

    /**
     * Cancels all tasks and closes all sockets/streams belonging to the specified departing peer,
     * WITHOUT cancelling or disturbing any other peer still paired in the session.
     */
    fun cancelPeerTasks(
        peerId: String?,
        peerHosts: Collection<String> = emptyList(),
        reason: String = "Peer left transfer room",
        markPeerRevokedInSession: Boolean = true
    ): PeerTaskCancellationSummary {
        val cleanPeerId = peerId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanHosts = peerHosts.mapNotNull { h -> h.trim().takeIf { it.isNotEmpty() } }.toSet()
        val toCancel: List<RegisteredTransferTask>
        val remainingCount: Int

        synchronized(lock) {
            if (markPeerRevokedInSession) {
                if (cleanPeerId != null) revokedPeerIdsInCurrentSession.add(cleanPeerId)
                revokedPeerHostsInCurrentSession.addAll(cleanHosts)
            }
            toCancel = tasksById.values.filter { it.matchesPeer(cleanPeerId, cleanHosts) }
            for (task in toCancel) {
                tasksById.remove(task.taskId)
            }
            remainingCount = tasksById.size
        }

        val byKind = EnumMapOrMutableMap(toCancel)
        for (task in toCancel) {
            task.cancelAndCloseResources(reason)
        }
        return PeerTaskCancellationSummary(
            cancelledTaskIds = toCancel.map { it.taskId },
            cancelledByKind = byKind,
            remainingActiveTasksInSession = remainingCount
        )
    }

    /**
     * Ends the current transfer session, rotates to a brand-new [TransferSessionToken], and cancels
     * & closes every active send, receive, multi-send, multi-receive, queued, and temporary task.
     */
    fun endSessionAndRotate(
        newSessionId: String = newCanonicalSessionId(),
        reason: String = "Transfer session ended"
    ): SessionEndCancellationSummary {
        val oldToken: TransferSessionToken
        val nextToken: TransferSessionToken
        val toCancel: List<RegisteredTransferTask>

        synchronized(lock) {
            oldToken = activeToken
            nextToken = TransferSessionToken(
                sessionId = newSessionId,
                generation = oldToken.generation + 1L
            )
            activeToken = nextToken
            revokedPeerIdsInCurrentSession.clear()
            revokedPeerHostsInCurrentSession.clear()
            toCancel = tasksById.values.toList()
            tasksById.clear()
        }

        val byKind = EnumMapOrMutableMap(toCancel)
        for (task in toCancel) {
            task.cancelAndCloseResources(reason)
        }
        return SessionEndCancellationSummary(
            endedToken = oldToken,
            newToken = nextToken,
            cancelledTaskIds = toCancel.map { it.taskId },
            cancelledByKind = byKind
        )
    }

    /**
     * Verifies whether a callback captured under [token] (and optionally scoped to [peerId] /
     * [peerHost]) is still authorized to mutate Transfer Room state.
     */
    fun isCallbackAuthorized(
        token: TransferSessionToken,
        peerId: String? = null,
        peerHost: String? = null
    ): Boolean = synchronized(lock) {
        if (token != activeToken) return false
        val cleanId = peerId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanHosts = peerHost?.trim()?.takeIf { it.isNotEmpty() }?.let(::setOf) ?: emptySet()
        !isPeerRevokedLocked(cleanId, cleanHosts)
    }

    private fun isPeerRevokedLocked(peerId: String?, peerHosts: Set<String>): Boolean {
        if (peerId != null && peerId in revokedPeerIdsInCurrentSession) return true
        if (peerHosts.isNotEmpty() && peerHosts.any { it in revokedPeerHostsInCurrentSession }) return true
        return false
    }

    companion object {
        const val INITIAL_PLACEHOLDER_SESSION_ID = "00000000000000000000000000000001"

        fun newCanonicalSessionId(): String =
            UUID.randomUUID().toString().replace("-", "").lowercase()

        private fun EnumMapOrMutableMap(
            tasks: List<RegisteredTransferTask>
        ): Map<TransferRoomTaskKind, Int> {
            if (tasks.isEmpty()) return emptyMap()
            val counts = LinkedHashMap<TransferRoomTaskKind, Int>()
            for (task in tasks) {
                counts[task.kind] = (counts[task.kind] ?: 0) + 1
            }
            return counts
        }
    }
}

/**
 * Pure state-transition guard requiring an exact match on BOTH [TransferSessionToken.sessionId]
 * and [TransferSessionToken.generation] (never `roomResetGeneration` alone).
 */
internal inline fun applyIfTransferSessionMatches(
    state: UltraShareServiceState,
    capturedToken: TransferSessionToken,
    transform: (UltraShareServiceState) -> UltraShareServiceState
): UltraShareServiceState =
    if (state.transferSessionId == capturedToken.sessionId &&
        state.transferSessionGeneration == capturedToken.generation
    ) {
        transform(state)
    } else {
        state
    }
