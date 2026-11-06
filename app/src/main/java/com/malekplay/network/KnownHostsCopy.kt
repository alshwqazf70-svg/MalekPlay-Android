package com.malekplay.network

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Streams a known_hosts file while enforcing a strict size limit. */
internal fun copyKnownHostsBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = 1_000_000L
): Long {
    require(maxBytes > 0L) { "Maximum known_hosts size must be positive" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maxBytes - count.toLong()) {
            throw IOException("known_hosts exceeds the $maxBytes byte limit")
        }
        output.write(buffer, 0, count)
        total += count.toLong()
    }
    output.flush()
    return total
}
