package com.malekplay.translation

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Small, deterministic SRT parser helpers kept independent from ML Kit for unit testing. */
internal object SrtSubtitleParser {
    private val timingPattern = Regex("""^\s*(?:\d{1,}:)?\d{2}:\d{2}[,.]\d{3}\s*-->\s*(?:\d{1,}:)?\d{2}:\d{2}[,.]\d{3}(?:\s+.*)?\s*$""")
    private val vttTimestampPattern = Regex("""((?:\d{1,}:)?\d{2}:\d{2})\.(\d{3})""")
    private val markupPattern = Regex("""<[^>]+>""")
    private val assOverridePattern = Regex("""\{\\[^}]*}""")
    private val assTimestampPattern = Regex("""^(\d+):(\d{2}):(\d{2})[.](\d{1,2})$""")

    fun decode(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "ملف الترجمة فارغ" }
        return when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
                decodeStrict(bytes, 3, StandardCharsets.UTF_8)
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                decodeStrict(bytes, 2, StandardCharsets.UTF_16LE)
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                decodeStrict(bytes, 2, StandardCharsets.UTF_16BE)
            else -> decodeWithoutBom(bytes)
        }
    }

    private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String {
        val unitSize = if (charset == StandardCharsets.UTF_8) 1 else 2
        require((bytes.size - offset) % unitSize == 0) {
            "ملف الترجمة يحتوي على بيانات ترميز غير مكتملة"
        }
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    }

    private fun decodeWithoutBom(bytes: ByteArray): String {
        // Some subtitle exporters write UTF-16 without a BOM. Detect the common
        // ASCII-compatible zero-byte pattern before falling back to legacy Arabic
        // encodings; otherwise valid UTF-16 files become unreadable mojibake.
        val evenZeros = bytes.indices.count { it % 2 == 0 && bytes[it] == 0.toByte() }
        val oddZeros = bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() }
        val evenCount = (bytes.size + 1) / 2
        val oddCount = bytes.size / 2
        val utf16Charset = when {
            oddCount > 0 && evenZeros >= 2 && evenZeros * 3 >= evenCount -> StandardCharsets.UTF_16BE
            evenCount > 0 && oddZeros >= 2 && oddZeros * 3 >= oddCount -> StandardCharsets.UTF_16LE
            else -> null
        }
        if (utf16Charset != null) {
            runCatching { decodeStrict(bytes, 0, utf16Charset) }.getOrNull()?.let { return it }
        }

        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(bytes, Charset.forName("windows-1256")) }
            .getOrElse { String(bytes, Charset.forName("windows-1252")) }
    }

    /**
     * Normalizes both SRT and WebVTT cue blocks for SRT export.
     * WebVTT file headers and NOTE/STYLE/REGION metadata are not subtitle cues;
     * WebVTT timestamps use dots, while SRT requires comma milliseconds.
     */
    /** Detects a clear content signature before trusting a provider's filename or MIME hint. */
    fun detectFormat(text: String): String? {
        val normalized = text.removePrefix("\uFEFF").trimStart()
        if (normalized.startsWith("WEBVTT", ignoreCase = true)) return "vtt"
        if (normalized.contains("[Events]", ignoreCase = true) &&
            normalized.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }
        ) return "ass"
        val hasArrow = normalized.contains("-->")
        if (!hasArrow) return null
        val vttTiming = Regex("""(?m)^\s*(?:\d{1,2}:)?\d{1,2}:\d{2}\.\d{3}\s+-->""")
        return if (vttTiming.containsMatchIn(normalized)) "vtt" else "srt"
    }

    fun splitBlocks(text: String, formatHint: String? = null): List<String> {
        val normalized = text.removePrefix("\uFEFF")
        val hint = formatHint?.lowercase(java.util.Locale.ROOT)
        if (hint == "ass" || hint == "ssa" ||
            normalized.contains("[Events]", ignoreCase = true) &&
            normalized.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }
        ) return splitAssBlocks(normalized)
        val blocks = normalized.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n[\t ]*\n"))
            .filter { it.isNotBlank() }
        val isWebVtt = blocks.firstOrNull()?.lineSequence()?.firstOrNull()
            ?.trim()?.startsWith("WEBVTT", ignoreCase = true) == true

        return blocks.mapIndexedNotNull { index, rawBlock ->
            val firstLine = rawBlock.lineSequence().firstOrNull()?.trim().orEmpty()
            if (isWebVtt && index == 0) {
                return@mapIndexedNotNull null
            }
            if (isWebVtt && (
                firstLine.startsWith("NOTE", ignoreCase = true) ||
                    firstLine.equals("STYLE", ignoreCase = true) ||
                    firstLine.equals("REGION", ignoreCase = true)
                )
            ) {
                return@mapIndexedNotNull null
            }

            rawBlock.lineSequence().map { line ->
                if (line.contains("-->")) {
                    vttTimestampPattern.replace(line, "\$1,\$2")
                } else {
                    line
                }
            }.joinToString("\n")
        }
    }


    /**
     * Converts ASS/SSA Dialogue events to timestamp-preserving SRT cue blocks.
     * The Text field is allowed to contain commas, so it is split only after
     * all preceding columns described by the [Events] Format line.
     */
    private fun splitAssBlocks(text: String): List<String> {
        var eventFields = listOf("layer", "start", "end", "style", "name",
            "marginl", "marginr", "marginv", "effect", "text")
        val cues = mutableListOf<String>()
        var inEvents = false
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.trim().startsWith("[", ignoreCase = true) &&
                line.trim().endsWith("]", ignoreCase = true)
            ) {
                inEvents = line.trim().equals("[Events]", ignoreCase = true)
                return@forEach
            }
            if (!inEvents) return@forEach
            val trimmed = line.trim()
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                eventFields = trimmed.substringAfter(':')
                    .split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }
                return@forEach
            }
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) return@forEach
            val values = splitAssDialogue(trimmed.substringAfter(':').trim(), eventFields.size)
                ?: return@forEach
            val startIndex = eventFields.indexOf("start")
            val endIndex = eventFields.indexOf("end")
            val textIndex = eventFields.indexOf("text")
            if (startIndex < 0 || endIndex < 0 || textIndex < 0 ||
                maxOf(startIndex, endIndex, textIndex) >= values.size
            ) return@forEach
            val start = assTimeToSrt(values[startIndex]) ?: return@forEach
            val end = assTimeToSrt(values[endIndex]) ?: return@forEach
            val dialogue = values[textIndex]
                .replace(assOverridePattern, "")
                .replace(Regex("""\\[Nn]"""), "\n")
                .replace(Regex("""\\h"""), " ")
                .replace(Regex("""<[^>]*>"""), "")
                .trim()
            if (dialogue.isBlank()) return@forEach
            cues += (cues.size + 1).toString() + "\n" + start + " --> " + end + "\n" + dialogue
        }
        return cues
    }

    private fun splitAssDialogue(raw: String, fieldCount: Int): List<String>? {
        if (fieldCount < 3) return null
        val result = ArrayList<String>(fieldCount)
        var offset = 0
        repeat(fieldCount - 1) {
            val comma = raw.indexOf(',', offset)
            if (comma < 0) return null
            result += raw.substring(offset, comma).trim()
            offset = comma + 1
        }
        result += raw.substring(offset).trim()
        return result.takeIf { it.size == fieldCount }
    }

    private fun assTimeToSrt(raw: String): String? {
        val match = assTimestampPattern.matchEntire(raw.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val centiseconds = match.groupValues[4].padEnd(2, '0').take(2).toLongOrNull() ?: return null
        if (minutes > 59 || seconds > 59) return null
        val millis = hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + centiseconds * 10L
        val totalHours = millis / 3_600_000L
        val totalMinutes = (millis / 60_000L) % 60L
        val totalSeconds = (millis / 1_000L) % 60L
        val remainder = millis % 1_000L
        return "%02d:%02d:%02d,%03d".format(
            java.util.Locale.ROOT, totalHours, totalMinutes, totalSeconds, remainder
        )
    }

    fun timingLineIndex(lines: List<String>): Int =
        lines.indexOfFirst { timingPattern.matches(it) }

    fun maskMarkup(text: String): Pair<String, List<String>> {
        val tags = mutableListOf<String>()
        val masked = markupPattern.replace(text) { match ->
            val index = tags.size
            tags += match.value
            "§T$index§"
        }
        return masked to tags
    }

    fun restoreMarkup(text: String, tags: List<String>): String {
        var restored = text
        tags.forEachIndexed { index, tag ->
            restored = Regex("""§\s*T\s*$index\s*§""", RegexOption.IGNORE_CASE).replace(restored, tag)
        }
        return restored
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
