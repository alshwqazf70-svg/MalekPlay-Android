package com.malekplay.network

import kotlinx.coroutines.Job
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped task, resource, and callback guard for UltraShare's transfer room.
 *
 * Guarantees:
 * - Every room attempt gets a monotonic [transferSessionId].
 * - Send, multi-send recipient, receive, queue, idle-timer, and socket/stream resources
 *   are tracked under `(transferSessionId, peerHost, peerSessionId)`.
 * - Leaving a peer cancels only that peer's send/mesh tasks and closes its tracked sockets,
 *   without disturbing other still-paired peers.
 * - Ending a room session invalidates all stale callbacks immediately and cancels every
 *   in-flight task belonging solely to that ended session, without deleting completed files
 *   or durable history stores.
 */
internal class UltraShareRoomTaskRegistry {

    enum class TaskKind {
        SEND_SINGLE,
        SEND_MULTI_PARENT,
        SEND_MULTI_RECIPIENT,
        RECEIVE_LOOP,
        BATCH_RECEIVE_SERVER,
        CONTROL_SERVER,
        ADVERTISEMENT,
        IDLE_TIMER,
        MESH_SESSION,
        TEMP_QUEUE_OPERATION
    }

    data class TaskSnapshot(
        val taskId: String,
        val transferSessionId: Long,
        val kind: TaskKind,
        val peerHost: String?,
        val peerSessionId: String?
    )

    private class TrackedTask(
        val snapshot: TaskSnapshot,
        val job: Job?,
        val closeables: MutableList<Closeable> = mutableListOf()
    )

    private val lock = Any()
    private var activeSessionId: Long = 1L
    private val tasksById = ConcurrentHashMap<String, TrackedTask>()
    private val departedPeerHostsBySession = HashMap<Long, MutableSet<String>>()
    private val departedPeerIdsBySession = HashMap<Long, MutableSet<String>>()

    fun currentSessionId(): Long = synchronized(lock) { activeSessionId }

    /**
     * Rotates the active room session ID when a brand-new room begins, invalidating
     * any delayed callbacks from previous sessions without disturbing independent tasks.
     */
    fun beginNewSession(): Long = synchronized(lock) {
        activeSessionId = if (activeSessionId == Long.MAX_VALUE) 1L else activeSessionId + 1L
        val cutoff = activeSessionId - 16L
        departedPeerHostsBySession.keys.removeAll { it < cutoff }
        departedPeerIdsBySession.keys.removeAll { it < cutoff }
        activeSessionId
    }

    /** Marks a returning/re-paired peer as active inside the current session. */
    fun markPeerRejoined(transferSessionId: Long, peerHost: String?, peerSessionId: String?) {
        synchronized(lock) {
            if (transferSessionId != activeSessionId) return
            peerHost?.takeIf { it.isNotBlank() }?.let { host ->
                departedPeerHostsBySession[transferSessionId]?.remove(host)
            }
            peerSessionId?.takeIf { it.isNotBlank() }?.let { id ->
                departedPeerIdsBySession[transferSessionId]?.remove(id)
            }
        }
    }

    fun registerTask(
        transferSessionId: Long,
        kind: TaskKind,
        peerHost: String? = null,
        peerSessionId: String? = null,
        job: Job? = null,
        closeables: List<Closeable> = emptyList()
    ): String? {
        val toCancelJob: Job?
        val toClose: List<Closeable>
        synchronized(lock) {
            val hostDeparted = !peerHost.isNullOrBlank() &&
                departedPeerHostsBySession[transferSessionId]?.contains(peerHost) == true
            val idDeparted = !peerSessionId.isNullOrBlank() &&
                departedPeerIdsBySession[transferSessionId]?.contains(peerSessionId) == true
            if (transferSessionId != activeSessionId || hostDeparted || idDeparted) {
                toCancelJob = job
                toClose = closeables
            } else {
                val id = UUID.randomUUID().toString()
                tasksById[id] = TrackedTask(
                    snapshot = TaskSnapshot(
                        taskId = id,
                        transferSessionId = transferSessionId,
                        kind = kind,
                        peerHost = peerHost,
                        peerSessionId = peerSessionId
                    ),
                    job = job,
                    closeables = closeables.toMutableList()
                )
                return id
            }
        }
        toClose.forEach { runCatching { it.close() } }
        toCancelJob?.cancel()
        return null
    }

    fun attachCloseable(taskId: String?, closeable: Closeable) {
        if (taskId == null) {
            runCatching { closeable.close() }
            return
        }
        val shouldCloseImmediately = synchronized(lock) {
            val tracked = tasksById[taskId]
            if (tracked == null || !isCallbackAllowedLocked(
                    tracked.snapshot.transferSessionId,
                    tracked.snapshot.peerHost,
                    tracked.snapshot.peerSessionId
                )
            ) {
                true
            } else {
                tracked.closeables.add(closeable)
                false
            }
        }
        if (shouldCloseImmediately) {
            runCatching { closeable.close() }
        }
    }

    fun unregisterTask(taskId: String?) {
        if (taskId == null) return
        val removed = synchronized(lock) { tasksById.remove(taskId) } ?: return
        removed.closeables.forEach { runCatching { it.close() } }
    }

    fun isSessionValid(
        transferSessionId: Long,
        peerHost: String? = null,
        peerSessionId: String? = null
    ): Boolean = synchronized(lock) {
        isCallbackAllowedLocked(transferSessionId, peerHost, peerSessionId)
    }

    private fun isCallbackAllowedLocked(
        transferSessionId: Long,
        peerHost: String?,
        peerSessionId: String?
    ): Boolean {
        if (transferSessionId != activeSessionId) return false
        if (!peerHost.isNullOrBlank() &&
            departedPeerHostsBySession[transferSessionId]?.contains(peerHost) == true
        ) {
            return false
        }
        if (!peerSessionId.isNullOrBlank() &&
            departedPeerIdsBySession[transferSessionId]?.contains(peerSessionId) == true
        ) {
            return false
        }
        return true
    }

    /**
     * Cancels all tasks and closes tracked sockets belonging to the leaving peer inside
     * [transferSessionId] without touching tasks of any other paired peer or independent session.
     */
    fun cancelPeerTasks(
        peerHost: String?,
        peerSessionId: String?,
        transferSessionId: Long = currentSessionId()
    ): Int {
        val cancelled = mutableListOf<TrackedTask>()
        synchronized(lock) {
            if (!peerHost.isNullOrBlank()) {
                departedPeerHostsBySession.getOrPut(transferSessionId) { HashSet() }.add(peerHost)
            }
            if (!peerSessionId.isNullOrBlank()) {
                departedPeerIdsBySession.getOrPut(transferSessionId) { HashSet() }.add(peerSessionId)
            }
            val iterator = tasksById.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val snap = entry.value.snapshot
                if (snap.transferSessionId != transferSessionId) continue
                val matchesHost = !peerHost.isNullOrBlank() && snap.peerHost == peerHost
                val matchesId = !peerSessionId.isNullOrBlank() && snap.peerSessionId == peerSessionId
                if (matchesHost || matchesId) {
                    cancelled += entry.value
                    iterator.remove()
                }
            }
        }
        cancelled.forEach { task ->
            task.closeables.forEach { runCatching { it.close() } }
            task.job?.cancel()
        }
        return cancelled.size
    }

    /**
     * Ends [transferSessionId], rotates to a fresh session token so late callbacks from the
     * ended session are rejected, and cancels/closes all tasks owned by that session.
     * Any tasks tagged with a different (independent) session ID remain untouched.
     */
    fun endSession(
        transferSessionId: Long = currentSessionId(),
        rotateActiveSession: Boolean = true,
        includeKinds: Set<TaskKind>? = null
    ): Int {
        val toCancel = mutableListOf<TrackedTask>()
        synchronized(lock) {
            if (rotateActiveSession && transferSessionId == activeSessionId) {
                activeSessionId = if (activeSessionId == Long.MAX_VALUE) 1L else activeSessionId + 1L
            }
            val iterator = tasksById.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val snap = entry.value.snapshot
                if (snap.transferSessionId == transferSessionId &&
                    (includeKinds == null || snap.kind in includeKinds)
                ) {
                    toCancel += entry.value
                    iterator.remove()
                }
            }
        }
        toCancel.forEach { task ->
            task.closeables.forEach { runCatching { it.close() } }
            task.job?.cancel()
        }
        return toCancel.size
    }

    fun activeTasksForSession(transferSessionId: Long = currentSessionId()): List<TaskSnapshot> =
        synchronized(lock) {
            tasksById.values
                .asSequence()
                .map { it.snapshot }
                .filter { it.transferSessionId == transferSessionId }
                .toList()
        }
}
