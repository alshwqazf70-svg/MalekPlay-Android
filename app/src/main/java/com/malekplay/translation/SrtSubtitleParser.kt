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
    fun splitBlocks(text: String): List<String> {
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n')
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

    fun timingLineIndex(lines: List<String>): Int =
        lines.indexOfFirst { timingPattern.matches(it) }

    fun maskMarkup(text: String): Pair<String, List<String>> {
        val tags = mutableListOf<String>()
        val masked = markupPattern.replace(text) { match ->
            val index = tags.size
            tags += match.value
            "MALekPLAYTAG" + index + "TOKEN"
        }
        return masked to tags
    }

    fun restoreMarkup(text: String, tags: List<String>): String {
        var restored = text
        tags.forEachIndexed { index, tag ->
            restored = restored.replace("MALekPLAYTAG" + index + "TOKEN", tag, ignoreCase = true)
        }
        return restored
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
