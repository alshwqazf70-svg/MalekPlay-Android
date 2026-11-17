package com.malekplay.network

/**
 * Gives DFile enough time for large or slow local-network transfers without
 * allowing a stale native session to wait indefinitely.
 *
 * The 256 KiB/s estimate is intentionally conservative. The 5.5-hour ceiling
 * leaves a margin below Android's six-hour dataSync foreground-service limit
 * on Android 15+, where that service type is subject to the platform budget.
 */
internal object UltraShareDFileTimeoutPolicy {
    private const val MIN_TIMEOUT_MILLIS = 60_000L
    private const val MAX_TIMEOUT_MILLIS = 5L * 60L * 60L * 1_000L + 30L * 60L * 1_000L
    private const val ESTIMATED_BYTES_PER_SECOND = 256L * 1024L

    fun completionTimeoutMillis(fileSizes: Iterable<Long>): Int {
        var totalBytes = 0L
        for (size in fileSizes) {
            require(size >= 0L) { "DFile file size cannot be negative." }
            if (size > Long.MAX_VALUE - totalBytes) {
                throw ArithmeticException("DFile batch size overflow.")
            }
            totalBytes += size
        }

        val estimatedSeconds = totalBytes / ESTIMATED_BYTES_PER_SECOND +
            if (totalBytes % ESTIMATED_BYTES_PER_SECOND == 0L) 0L else 1L
        val remainingBudget = (MAX_TIMEOUT_MILLIS - MIN_TIMEOUT_MILLIS) / 1_000L
        val estimatedMillis = if (estimatedSeconds > remainingBudget) {
            MAX_TIMEOUT_MILLIS
        } else {
            MIN_TIMEOUT_MILLIS + estimatedSeconds * 1_000L
        }
        return estimatedMillis.coerceAtMost(MAX_TIMEOUT_MILLIS).toInt()
    }
}
