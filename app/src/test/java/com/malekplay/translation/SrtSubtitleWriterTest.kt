package com.malekplay.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SrtSubtitleWriterTest {

    @Test
    fun writesOrderedSrtCuesWithMillisecondPrecision() {
        val result = SrtSubtitleWriter.fromWords(
            listOf(
                RecognizedWord("world", 1_300L, 1_700L),
                RecognizedWord("Hello", 1_000L, 1_250L)
            )
        )

        assertEquals(
            "1\n00:00:01,000 --> 00:00:01,700\nHello world",
            result
        )
    }

    @Test
    fun splitsCuesOnSilenceAndCharacterLimit() {
        val silenceSplit = SrtSubtitleWriter.fromWords(
            listOf(
                RecognizedWord("First", 0L, 300L),
                RecognizedWord("second", 1_200L, 1_500L)
            )
        )
        assertEquals(2, silenceSplit.split("\n\n").size)

        val lengthSplit = SrtSubtitleWriter.fromWords(
            listOf(
                RecognizedWord("123456", 0L, 200L),
                RecognizedWord("abcdef", 250L, 500L)
            ),
            maxCharacters = 8
        )
        assertEquals(2, lengthSplit.split("\n\n").size)
    }

    @Test
    fun preservesLongDurationsBeyondOneHour() {
        val result = SrtSubtitleWriter.fromWords(
            listOf(RecognizedWord("Again", 3_600_123L, 3_601_234L))
        )
        assertEquals(
            "1\n01:00:00,123 --> 01:00:01,234\nAgain",
            result
        )
    }

    @Test
    fun skipsEmptyTokensAndReturnsEmptyForNoSpeech() {
        assertEquals(
            "",
            SrtSubtitleWriter.fromWords(listOf(RecognizedWord("  ", 0L, 10L)))
        )
    }

    @Test
    fun rejectsInvalidRecognitionTimestampsAndConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            SrtSubtitleWriter.fromWords(listOf(RecognizedWord("bad", 10L, 10L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SrtSubtitleWriter.fromWords(emptyList(), maxCharacters = 0)
        }
    }
}
