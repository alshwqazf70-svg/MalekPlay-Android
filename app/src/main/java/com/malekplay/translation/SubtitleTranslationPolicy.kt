package com.malekplay.translation

/**
 * Bounds subtitle context batches and verifies cue boundaries returned by ML Kit.
 */
internal object SubtitleTranslationPolicy {
    const val MAX_BATCH_CHARACTERS = 1_200
    const val MAX_BATCH_CUES = 8

    fun chunkCueTexts(texts: List<String>): List<List<Int>> {
        if (texts.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var characters = 0
        texts.forEachIndexed { index, text ->
            val size = text.length
            if (current.isNotEmpty() &&
                (current.size >= MAX_BATCH_CUES || characters + size > MAX_BATCH_CHARACTERS)
            ) {
                chunks += current
                current = mutableListOf()
                characters = 0
            }
            current += index
            characters += size
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    fun separator(batchStart: Int, position: Int): String =
        "§C${batchStart}_${position}§"

    /** Joins wrapped dialogue lines while keeping separate speakers on distinct lines. */
    fun mergeWrappedDialogueLines(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return text.trim()
        return buildString {
            lines.forEachIndexed { index, line ->
                if (index > 0) {
                    if (line.startsWith("-")) append('\n')
                    else append(' ')
                }
                append(line)
            }
        }
    }

    private fun flexibleSeparatorPattern(separator: String): Regex {
        val match = Regex("""§C(\d+)_(\d+)§""").matchEntire(separator) ?: return Regex.escape(separator).toRegex()
        return Regex(
            """§\s*C\s*${match.groupValues[1]}\s*_\s*${match.groupValues[2]}\s*§""",
            RegexOption.IGNORE_CASE
        )
    }

    fun splitBatchTranslation(translated: String, separators: List<String>): List<String>? {
        if (separators.isEmpty()) {
            val only = translated.trim()
            return if (only.isNotBlank()) listOf(only) else null
        }
        val parts = mutableListOf<String>()
        var remaining = translated
        separators.forEach { separator ->
            val pattern = flexibleSeparatorPattern(separator)
            val match = pattern.find(remaining) ?: return null
            if (pattern.find(remaining, match.range.last + 1) != null) return null
            parts += remaining.substring(0, match.range.first).trim()
            remaining = remaining.substring(match.range.last + 1)
        }
        parts += remaining.trim()
        return parts.takeIf { it.size == separators.size + 1 && it.all(String::isNotBlank) }
    }
}
