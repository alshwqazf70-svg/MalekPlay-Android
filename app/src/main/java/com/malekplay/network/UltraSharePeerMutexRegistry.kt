package com.malekplay.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes resume-state access per stable peer while retaining only a bounded
 * number of idle locks. Reference counts include both owners and queued waiters,
 * so an entry is never evicted while a coroutine can still acquire it.
 */
internal class UltraSharePeerMutexRegistry(
    private val maxRetainedEntries: Int = 128
) {
    private data class Entry(
        val mutex: Mutex = Mutex(),
        var references: Int = 0
    )

    private val guard = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    init {
        require(maxRetainedEntries > 0)
    }

    suspend fun <T> withLock(peerIdentity: String, action: suspend () -> T): T {
        require(peerIdentity.isNotBlank())
        val entry = synchronized(guard) {
            val selected = entries[peerIdentity] ?: Entry().also {
                entries[peerIdentity] = it
            }
            selected.references++
            evictIdleEntries()
            selected
        }

        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(guard) {
                entry.references--
                evictIdleEntries()
            }
        }
    }

    internal fun retainedEntryCount(): Int = synchronized(guard) { entries.size }

    private fun evictIdleEntries() {
        if (entries.size <= maxRetainedEntries) return
        val iterator = entries.entries.iterator()
        while (entries.size > maxRetainedEntries && iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.references == 0 && !entry.mutex.isLocked) iterator.remove()
        }
    }
}
