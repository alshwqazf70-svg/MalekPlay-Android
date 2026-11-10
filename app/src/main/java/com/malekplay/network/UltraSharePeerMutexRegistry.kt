package com.malekplay.network

import kotlinx.coroutines.sync.Mutex

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

        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            return action()
        } finally {
            if (acquired) entry.mutex.unlock()
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
