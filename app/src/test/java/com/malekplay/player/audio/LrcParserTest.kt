package com.malekplay.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesArabicLyricsAndCentisecondTimestamps() {
        val lines = LrcParser.parse(
            "[ar:Arabic]\n[00:01.25]يا ليل يا عين\n[00:03.5]أغنية عربية"
        )

        assertEquals(
            listOf(
                LrcLine(1_250L, "يا ليل يا عين"),
                LrcLine(3_500L, "أغنية عربية")
            ),
            lines
        )
    }

    @Test
    fun expandsMultipleTimestampsAndSortsOutOfOrderLines() {
        val lines = LrcParser.parse(
            "[00:05.00][00:07.00]Second\n[00:02.00]First"
        )

        assertEquals(
            listOf(
                LrcLine(2_000L, "First"),
                LrcLine(5_000L, "Second"),
                LrcLine(7_000L, "Second")
            ),
            lines
        )
    }

    @Test
    fun appliesPositiveAndNegativeOffsetsWithoutNegativePositions() {
        assertEquals(1_500L, LrcParser.parse("[offset:+500]\n[00:01.00]A").single().timestampMs)
        assertEquals(0L, LrcParser.parse("[offset:-1500]\n[00:01.00]A").single().timestampMs)
    }

    @Test
    fun ignoresMetadataMalformedAndUntimedLines() {
        val lines = LrcParser.parse(
            "[ti:Song]\n[ar:Arabic]\nplain text\n[00:99.00]bad\n[00:02.00]Valid"
        )

        assertEquals(listOf(LrcLine(2_000L, "Valid")), lines)
        assertTrue(LrcParser.parse("not an LRC file").isEmpty())
    }
}
