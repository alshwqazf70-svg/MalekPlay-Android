package com.malekplay.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
