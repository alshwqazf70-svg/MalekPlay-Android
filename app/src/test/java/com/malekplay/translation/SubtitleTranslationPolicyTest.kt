package com.malekplay.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationPolicyTest {
    private val supported = setOf("en", "fr", "de", "es", "tr", "ar")

    @Test
    fun acceptsMultipleSupportedSourceLanguages() {
        assertEquals("en", validateTranslationSourceLanguage("en", supported))
        assertEquals("fr", validateTranslationSourceLanguage("fr", supported))
        assertEquals("tr", validateTranslationSourceLanguage("tr", supported))
    }

    @Test
    fun trimsAndNormalizesLanguageCodes() {
        assertEquals("de", validateTranslationSourceLanguage(" DE ", supported))
    }

    @Test
    fun rejectsBlankAndUnsupportedLanguageCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            validateTranslationSourceLanguage(" ", supported)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateTranslationSourceLanguage("xx", supported)
        }
    }
    
    @Test
    fun groupsNearbyCuesWithoutExceedingBatchLimits() {
        val texts = listOf("one", "two", "three", "four")
        val chunks = SubtitleTranslationPolicy.chunkCueTexts(texts)
        assertEquals(listOf(listOf(0, 1, 2, 3)), chunks)
        assertEquals(texts.indices.toList(), chunks.flatten())
    }

    @Test
    fun splitsLargeTranscriptsIntoBoundedBatches() {
        val texts = List(10) { "x".repeat(400) }
        val chunks = SubtitleTranslationPolicy.chunkCueTexts(texts)
        assertTrue(chunks.all { it.size <= SubtitleTranslationPolicy.MAX_BATCH_CUES })
        assertTrue(chunks.all { group ->
            group.sumOf { texts[it].length } <= SubtitleTranslationPolicy.MAX_BATCH_CHARACTERS
        })
        assertEquals(texts.indices.toList(), chunks.flatten())
    }

    @Test
    fun acceptsIntactBatchSeparatorsAndPreservesCueOrder() {
        val separators = listOf(
            SubtitleTranslationPolicy.separator(0, 0),
            SubtitleTranslationPolicy.separator(0, 1)
        )
        val translated = "مرحبا\n" + separators[0] + "\nكيف حالك؟\n" +
            separators[1] + "\nوداعاً"
        assertEquals(
            listOf("مرحبا", "كيف حالك؟", "وداعاً"),
            SubtitleTranslationPolicy.splitBatchTranslation(translated, separators)
        )
    }

    @Test
    fun rejectsAlteredOrMissingBatchSeparatorsForSafeFallback() {
        val separator = SubtitleTranslationPolicy.separator(0, 0)
        assertNull(SubtitleTranslationPolicy.splitBatchTranslation("مرحبا وداعاً", listOf(separator)))
        assertNull(
            SubtitleTranslationPolicy.splitBatchTranslation(
                "مرحبا\n" + separator + "\n\n",
                listOf(separator)
            )
        )
    }

    @Test
    fun toleratesWhitespaceAroundCompactBatchSeparators() {
        val separator = SubtitleTranslationPolicy.separator(3, 0)
        val translated = "مرحبا\\n§ C 3 _ 0 §\\nكيف حالك؟"
        assertEquals(
            listOf("مرحبا", "كيف حالك؟"),
            SubtitleTranslationPolicy.splitBatchTranslation(translated, listOf(separator))
        )
    }

    @Test
    fun mergesWrappedLinesButKeepsTwoSpeakerDialogueSeparated() {
        assertEquals(
            "This is one sentence",
            SubtitleTranslationPolicy.mergeWrappedDialogueLines("This is one\\n sentence")
        )
        assertEquals(
            "- Hello\\n- مرحباً",
            SubtitleTranslationPolicy.mergeWrappedDialogueLines("- Hello\\n- مرحباً")
        )
    }

}
