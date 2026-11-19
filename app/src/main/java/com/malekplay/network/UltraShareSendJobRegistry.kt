package com.malekplay.network

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Atomically reserves peer identities for single-peer and multi-peer send jobs.
 * All send entry points must use this registry so a batch cannot overwrite a
 * concurrently-started single-peer job (or vice versa).
 */
internal object UltraShareSendJobRegistry {
    fun reserve(
        jobsByPeer: ConcurrentHashMap<String, Job>,
        peerSessionIds: Collection<String>,
        job: Job
    ): Boolean = synchronized(jobsByPeer) {
        val keys = peerSessionIds.distinct()
        if (keys.isEmpty() || keys.any { it.isBlank() || jobsByPeer.containsKey(it) }) {
            false
        } else {
            keys.forEach { jobsByPeer[it] = job }
            true
        }
    }
}
