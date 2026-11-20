package com.malekplay.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Preserve the fast single-session batch path, but recover a failed batch one file
 * at a time. This is a V2-compatible recovery strategy: it uses the existing
 * one-file manifest/receipt path and does not introduce new wire frame types.
 */
internal object UltraShareBatchRecovery {
    suspend fun <T, R> execute(
        items: List<T>,
        sendBatch: suspend (List<T>) -> List<R>,
        sendSingle: suspend (T) -> R,
        onItemFailure: (T, Exception) -> R,
        onBatchFailure: (Exception) -> Unit = {}
    ): List<R> {
        require(items.isNotEmpty()) { "لا يمكن استرداد دفعة فارغة." }

        try {
            val results = sendBatch(items)
            require(results.size == items.size) {
                "عدد نتائج الدفعة لا يطابق عدد الملفات المرسلة."
            }
            return results
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (batchFailure: Exception) {
            // Diagnostics must never prevent recovery of the remaining queue.
            runCatching { onBatchFailure(batchFailure) }
            // The caller's batch transport already applied its bounded retry policy.
            // Repeating the exact same one-file session adds latency but cannot isolate
            // any sibling, so surface its failure directly.
            if (items.size == 1) {
                return listOf(onItemFailure(items.single(), batchFailure))
            }
        }

        val recovered = ArrayList<R>(items.size)
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val result = try {
                sendSingle(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (itemFailure: Exception) {
                onItemFailure(item, itemFailure)
            }
            recovered += result
        }
        return recovered
    }
}
