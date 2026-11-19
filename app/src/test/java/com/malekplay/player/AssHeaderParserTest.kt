package com.malekplay.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import io.github.peerless2012.ass.media.parser.AssHeaderParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AssHeaderParserTest {

    private fun format(vararg initializationData: ByteArray): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setInitializationData(initializationData.toList())
            .build()

    @Test
    fun parseReturnsEmptyHeaderWhenInitializationDataIsMissing() {
        assertArrayEquals(ByteArray(0), AssHeaderParser.parse(format(), useOriginalHeaders = true))
    }

    @Test
    fun parseAcceptsSingleInitializationBlock() {
        val header = "[Script Info]".toByteArray()
        assertArrayEquals(header, AssHeaderParser.parse(format(header), useOriginalHeaders = true))
    }

    @Test
    fun parseAcceptsEmptySecondHeaderBlock() {
        assertArrayEquals(ByteArray(0), AssHeaderParser.parse(format("Format: Layer".toByteArray(), byteArrayOf()), useOriginalHeaders = true))
    }

    @Test
    fun parseDoesNotIndexPastEventsSectionAtEndOfHeader() {
        val formatLine = "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        val header = "[Script Info]\n[Events]".toByteArray()
        val parsed = AssHeaderParser.parse(format(formatLine.toByteArray(), header), useOriginalHeaders = false)
        assertEquals("[Script Info]\n[Events]", parsed.decodeToString())
    }
}
