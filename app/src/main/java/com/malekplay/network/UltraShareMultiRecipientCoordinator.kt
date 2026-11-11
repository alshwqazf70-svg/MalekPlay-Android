package com.malekplay.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded fan-out coordinator for already-approved UltraShare peers. */
object UltraShareMultiRecipientCoordinator {
    const val DEFAULT_MAX_PARALLEL_PEERS = 4

    data class Target(val sessionId: String, val host: String) {
        init {
            require(sessionId.isNotBlank()) { "Peer session ID must not be blank." }
            require(host.isNotBlank()) { "Peer host must not be blank." }
        }
    }

    /**
     * Retain a known peer session identity when the same endpoint reconnects.
     * The delivery ledger is keyed by this ID, so rotating it on reconnect would
     * make already-delivered files appear new and cause duplicate transfers.
     */
    fun resolveSessionId(
        host: String,
        retainedPeers: List<Pair<String, String>>,
        newSessionId: String
    ): String {
        require(host.isNotBlank() && newSessionId.isNotBlank())
        return retainedPeers.firstOrNull { (knownHost, sessionId) ->
            knownHost == host && sessionId.isNotBlank()
        }?.second ?: newSessionId
    }

    enum class Status { PENDING, SENDING, COMPLETED, FAILED }

    data class Delivery<T>(
        val target: Target,
        val status: Status,
        val value: T? = null,
        val error: String? = null
    )

    /**
     * One peer failure does not block other peers. Parent cancellation propagates.
     * Pairing credentials stay outside this model and observable UI state.
     */
    suspend fun <T> execute(
        targets: List<Target>,
        maxParallelPeers: Int = DEFAULT_MAX_PARALLEL_PEERS,
        onStatus: (Delivery<T>) -> Unit = {},
        sendToPeer: suspend (Target) -> T
    ): List<Delivery<T>> {
        require(maxParallelPeers in 1..DEFAULT_MAX_PARALLEL_PEERS) {
            "Parallel peer count must be between 1 and $DEFAULT_MAX_PARALLEL_PEERS."
        }
        require(targets.map { it.sessionId }.distinct().size == targets.size) {
            "Duplicate peer session IDs are not allowed."
        }
        require(targets.map { it.host }.distinct().size == targets.size) {
            "A peer host can only appear once in a delivery batch."
        }

        val semaphore = Semaphore(maxParallelPeers)
        return coroutineScope {
            targets.map { target ->
                async {
                    onStatus(Delivery(target, Status.PENDING))
                    semaphore.withPermit {
                        onStatus(Delivery(target, Status.SENDING))
                        try {
                            val result = sendToPeer(target)
                            Delivery(target, Status.COMPLETED, value = result).also(onStatus)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Delivery<T>(
                                target = target,
                                status = Status.FAILED,
                                error = failure.message?.take(240) ?: failure.javaClass.simpleName
                            ).also(onStatus)
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
