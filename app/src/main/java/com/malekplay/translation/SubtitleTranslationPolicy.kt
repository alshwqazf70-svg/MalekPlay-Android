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
        "MALekPLAYCUE" + batchStart + "_" + position + "BOUNDARYTOKEN"

    fun splitBatchTranslation(translated: String, separators: List<String>): List<String>? {
        if (separators.isEmpty()) {
            val only = translated.trim()
            return if (only.isNotBlank()) listOf(only) else null
        }
        val parts = mutableListOf<String>()
        var remaining = translated
        separators.forEach { separator ->
            val index = remaining.indexOf(separator)
            if (index < 0 || remaining.indexOf(separator, index + separator.length) >= 0) return null
            parts += remaining.substring(0, index).trim()
            remaining = remaining.substring(index + separator.length)
        }
        parts += remaining.trim()
        return parts.takeIf { it.size == separators.size + 1 && it.all(String::isNotBlank) }
    }
}
