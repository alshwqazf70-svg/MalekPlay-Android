package com.malekplay.player.audio

/**
 * One timed line from an LRC lyrics file. Timestamps are absolute media
 * positions in milliseconds after applying the file's optional [offset] tag.
 */
data class LrcLine(
    val timestampMs: Long,
    val text: String
)

/**
 * Small, dependency-free LRC parser suitable for local files and unit tests.
 *
 * Supported timestamp forms: [mm:ss], [mm:ss.t], [mm:ss.tt], [mm:ss.ttt].
 * Multiple timestamps on one source line are expanded into separate entries.
 * Metadata tags are ignored, while [offset:+/-milliseconds] is applied once.
 */
object LrcParser {
    private val timestampPattern =
        Regex("""\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?\]""")
    private val offsetPattern = Regex("""(?i)\[offset\s*:\s*([+-]?\d+)\]""")

    fun parse(source: String): List<LrcLine> {
        if (source.isBlank()) return emptyList()

        val offsetMs = offsetPattern.findAll(source)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L

        return buildList {
            source.lineSequence().forEach { rawLine ->
                val timestamps = timestampPattern.findAll(rawLine).toList()
                if (timestamps.isEmpty()) return@forEach

                val lyricText = timestampPattern.replace(rawLine, "").trim()
                if (lyricText.isEmpty()) return@forEach

                timestamps.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                    val fraction = match.groupValues.getOrNull(3).orEmpty()
                    val millis = when (fraction.length) {
                        1 -> (fraction.toLongOrNull() ?: 0L) * 100L
                        2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                        3 -> fraction.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    val rawTimestamp = minutes * 60_000L + seconds * 1_000L + millis
                    val adjusted = when {
                        offsetMs > 0L && rawTimestamp > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
                        else -> (rawTimestamp + offsetMs).coerceAtLeast(0L)
                    }
                    add(LrcLine(timestampMs = adjusted, text = lyricText))
                }
            }
        }.sortedBy(LrcLine::timestampMs)
    }
}
