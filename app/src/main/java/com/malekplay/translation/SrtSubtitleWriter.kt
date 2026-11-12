package com.malekplay.translation

/**
 * A timed recognition token returned by an offline speech recognizer.
 * Times are absolute milliseconds from the beginning of the media stream.
 */
data class RecognizedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/** Builds deterministic, readable SRT cues from word-level recognition output. */
object SrtSubtitleWriter {
    fun fromWords(
        words: List<RecognizedWord>,
        maxCharacters: Int = 42,
        maxCueDurationMs: Long = 5_000L,
        maxSilenceGapMs: Long = 650L
    ): String {
        require(maxCharacters in 1..200) { "حد طول السطر يجب أن يكون بين 1 و200 حرف" }
        require(maxCueDurationMs in 500L..15_000L) { "مدة المقطع غير صالحة" }
        require(maxSilenceGapMs in 0L..5_000L) { "فاصل الصمت غير صالح" }

        val validWords = words.mapNotNull { word ->
            val text = word.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            require(word.startMs >= 0L && word.endMs > word.startMs) {
                "توقيت كلمة التعرف غير صالح"
            }
            word.copy(text = text)
        }.sortedWith(compareBy<RecognizedWord> { it.startMs }.thenBy { it.endMs })

        if (validWords.isEmpty()) return ""

        val cues = mutableListOf<Cue>()
        var cueStart = validWords.first().startMs
        var cueEnd = validWords.first().endMs
        val cueWords = mutableListOf<String>()

        fun flush() {
            if (cueWords.isEmpty()) return
            cues += Cue(cueStart, maxOf(cueStart + 1L, cueEnd), cueWords.joinToString(" "))
            cueWords.clear()
        }

        for (word in validWords) {
            val candidateLength = if (cueWords.isEmpty()) word.text.length
                else cueWords.sumOf { it.length } + cueWords.size + word.text.length
            val gap = word.startMs - cueEnd
            val duration = word.endMs - cueStart
            val shouldSplit = cueWords.isNotEmpty() && (
                candidateLength > maxCharacters ||
                    gap > maxSilenceGapMs ||
                    duration > maxCueDurationMs
                )
            if (shouldSplit) flush()
            if (cueWords.isEmpty()) cueStart = word.startMs
            cueEnd = maxOf(cueEnd, word.endMs)
            cueWords += word.text
        }
        flush()

        return cues.mapIndexed { index, cue ->
            "${index + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text}"
        }.joinToString("\n\n")
    }

    private fun timestamp(milliseconds: Long): String {
        val hours = milliseconds / 3_600_000L
        val minutes = (milliseconds / 60_000L) % 60L
        val seconds = (milliseconds / 1_000L) % 60L
        val millis = milliseconds % 1_000L
        return "%02d:%02d:%02d,%03d".format(
            java.util.Locale.ROOT, hours, minutes, seconds, millis
        )
    }

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)
}
