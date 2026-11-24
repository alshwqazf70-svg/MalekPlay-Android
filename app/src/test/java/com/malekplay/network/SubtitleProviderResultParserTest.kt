package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class SubtitleProviderResultParserTest {
    @Test fun parsesProviderLanguageAliases() {
        assertEquals("ar", SubtitleProviderResultParser.language("Arabic"))
        assertEquals("ar", SubtitleProviderResultParser.language("ARA"))
        assertEquals("en", SubtitleProviderResultParser.language("English"))
        assertEquals("de", SubtitleProviderResultParser.language("DE"))
    }

    @Test fun acceptsOnlySupportedSubtitleFormats() {
        assertEquals("srt", SubtitleProviderResultParser.format("SRT"))
        assertEquals("ass", SubtitleProviderResultParser.format(" ass "))
        assertEquals("vtt", SubtitleProviderResultParser.format("VTT"))
        assertNull(SubtitleProviderResultParser.format("zip"))
    }

    @Test fun parsesSubDlUnpackedFileMetadataAndRejectsUnknownLanguage() {
        val subtitle = SubtitleProviderResultParser.subDlSubtitle(
            url = "/subtitle/parent/file123",
            releaseName = "Breaking Bad S05E14",
            languageCode = "EN",
            formatName = "srt",
            downloads = -1,
            fallbackTitle = "Breaking Bad"
        )
        assertEquals("subdl", subtitle?.provider)
        assertEquals("en", subtitle?.language)
        assertEquals("srt", subtitle?.format)
        assertEquals(0, subtitle?.downloads)
        assertNull(SubtitleProviderResultParser.subDlSubtitle(
            url = "/subtitle/parent/file124",
            releaseName = "Unknown language",
            languageCode = "",
            formatName = "srt",
            downloads = 0,
            fallbackTitle = "Fallback"
        ))
    }

    @Test fun usesSafeReleaseFallbackAndNonNegativeDownloadCounts() {
        assertEquals("WEB-DL release", SubtitleProviderResultParser.release("", "WEB-DL release"))
        assertEquals("Subtitle", SubtitleProviderResultParser.release(" ", ""))
        assertEquals(0, SubtitleProviderResultParser.downloadCount(-10))
        assertEquals(42, SubtitleProviderResultParser.downloadCount(42))
    }
}
