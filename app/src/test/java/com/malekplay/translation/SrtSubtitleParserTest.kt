package com.malekplay.translation

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtSubtitleParserTest {

    @Test
    fun decodesUtf8BomAndUtf16Files() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nHello"
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(StandardCharsets.UTF_8)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(StandardCharsets.UTF_16LE)

        assertEquals(text, SrtSubtitleParser.decode(utf8))
        assertEquals(text, SrtSubtitleParser.decode(utf16))
    }

    @Test
    fun preservesCueTimingAndFormattingMarkup() {
        val source = "1\n00:00:01,000 --> 00:00:02,000\n<i>Hello</i>\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld"
        val cues = SrtSubtitleParser.splitBlocks(source)
        assertEquals(2, cues.size)

        val lines = cues.first().split('\n')
        assertEquals(1, SrtSubtitleParser.timingLineIndex(lines))
        val (masked, tags) = SrtSubtitleParser.maskMarkup("<i>Hello</i>")
        assertTrue(masked.contains("MALekPLAYTAG0TOKEN"))
        assertEquals("<i>Bonjour</i>", SrtSubtitleParser.restoreMarkup(
            masked.replace("Hello", "Bonjour"), tags
        ))
    }

    @Test
    fun supportsCommaAndDotTimestampSeparators() {
        assertEquals(1, SrtSubtitleParser.timingLineIndex(listOf(
            "7",
            "00:00:01.250 --> 00:00:03.500",
            "Text"
        )))
    }
}
