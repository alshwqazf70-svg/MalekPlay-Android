package com.malekplay.network

import java.util.ArrayDeque

/**
 * In-memory PIN-attempt throttle. A peer gets five failed proofs before a cooldown;
 * repeated failures increase that peer's delay. A separate global budget limits
 * distributed guessing and KDF exhaustion across many source addresses.
 *
 * The peer table is bounded and idle entries expire so spoofed/changing source
 * addresses cannot grow memory for the lifetime of a foreground sharing session.
 * Successful authentication clears only that peer's history.
 */
internal class UltraSharePairingAttemptLimiter(
    private val peerFailureThreshold: Int = 5,
    private val peerWindowMillis: Long = 60_000L,
    private val baseCooldownMillis: Long = 30_000L,
    private val maximumCooldownMillis: Long = 10 * 60_000L,
    private val globalFailureThreshold: Int = 24,
    private val globalWindowMillis: Long = 60_000L,
    private val globalCooldownMillis: Long = 60_000L,
    private val maxTrackedPeers: Int = 512
) {
    private data class PeerFailures(
        val failures: Int,
        val lastFailureAt: Long,
        val blockedUntil: Long
    )

    private val lock = Any()
    // Access-order LRU: active/recent peers survive pressure from one-off addresses.
    private val peers = LinkedHashMap<String, PeerFailures>(16, 0.75f, true)
    private val globalFailures = ArrayDeque<Long>()
    private var globalBlockedUntil = 0L

    init {
        require(peerFailureThreshold > 0)
        require(peerWindowMillis > 0L && baseCooldownMillis > 0L)
        require(maximumCooldownMillis >= baseCooldownMillis)
        require(globalFailureThreshold > 0 && globalWindowMillis > 0L)
        require(globalCooldownMillis > 0L)
        require(maxTrackedPeers > 0)
    }

    fun isAllowed(peerAddress: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            pruneGlobal(nowMillis)
            prunePeers(nowMillis)
            if (nowMillis < globalBlockedUntil) return@synchronized false
            val key = normalize(peerAddress)
            val state = peers[key] ?: return@synchronized true
            if (hasPeerWindowExpired(state, nowMillis) && nowMillis >= state.blockedUntil) {
                peers.remove(key)
                return@synchronized true
            }
            nowMillis >= state.blockedUntil
        }

    fun recordFailure(peerAddress: String, nowMillis: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            pruneGlobal(nowMillis)
            prunePeers(nowMillis)
            val key = normalize(peerAddress)
            val previous = peers[key]
            val count = if (previous == null || hasPeerWindowExpired(previous, nowMillis)) {
                1
            } else {
                (previous.failures + 1).coerceAtLeast(1)
            }
            val cooldown = if (count < peerFailureThreshold) {
                0L
            } else {
                val exponent = (count - peerFailureThreshold).coerceAtMost(20)
                (baseCooldownMillis * (1L shl exponent)).coerceAtMost(maximumCooldownMillis)
            }
            peers[key] = PeerFailures(
                failures = count,
                lastFailureAt = nowMillis,
                blockedUntil = if (cooldown == 0L) 0L else safeAdd(nowMillis, cooldown)
            )
            evictExcessPeers()

            globalFailures.addLast(nowMillis)
            if (globalFailures.size >= globalFailureThreshold) {
                globalBlockedUntil = maxOf(globalBlockedUntil, safeAdd(nowMillis, globalCooldownMillis))
                globalFailures.clear()
            }
        }

    fun recordSuccess(peerAddress: String): Unit {
        synchronized(lock) {
            peers.remove(normalize(peerAddress))
        }
    }

    internal fun trackedPeerCount(): Int = synchronized(lock) { peers.size }

    private fun hasPeerWindowExpired(state: PeerFailures, nowMillis: Long): Boolean =
        nowMillis >= state.lastFailureAt &&
            nowMillis - state.lastFailureAt > peerWindowMillis

    private fun prunePeers(nowMillis: Long) {
        val iterator = peers.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            if (hasPeerWindowExpired(state, nowMillis) && nowMillis >= state.blockedUntil) {
                iterator.remove()
            }
        }
    }

    private fun evictExcessPeers() {
        val iterator = peers.entries.iterator()
        while (peers.size > maxTrackedPeers && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun pruneGlobal(nowMillis: Long) {
        while (globalFailures.isNotEmpty()) {
            val oldestFailure = globalFailures.peekFirst() ?: break
            if (nowMillis >= oldestFailure && nowMillis - oldestFailure > globalWindowMillis) {
                globalFailures.removeFirst()
            } else {
                break
            }
        }
        if (nowMillis >= globalBlockedUntil) globalBlockedUntil = 0L
    }

    private fun normalize(peerAddress: String): String =
        peerAddress.trim().takeIf { it.isNotEmpty() } ?: "unknown"

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
