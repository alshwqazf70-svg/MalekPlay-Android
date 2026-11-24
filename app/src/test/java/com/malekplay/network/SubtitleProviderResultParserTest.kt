package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONArray

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

    @Test fun validatesSubDlSubtitleParentIdentityBeforeUsingItsSubtitleArray() {
        val results = JSONArray(
            "[{\"name\":\"Dune: Part Two\",\"original_name\":\"Dune Part Two\",\"year\":2024,\"type\":\"movie\"}," +
            "{\"name\":\"Dune\",\"year\":2021,\"type\":\"movie\"}]"
        )
        assertEquals(true, SubtitleProviderResultParser.subDlHasExactTitleMatch(results, "Dune Part Two", 2024, "movie"))
        assertEquals(false, SubtitleProviderResultParser.subDlHasExactTitleMatch(results, "Dune", 2024, "movie"))
        assertEquals(false, SubtitleProviderResultParser.subDlHasExactTitleMatch(results, "Dune Part Two", 2024, "tv"))
        val subtitlesBelongToFirstOnly = JSONArray("[{\"name\":\"Dune\",\"year\":2021,\"type\":\"movie\"},{\"name\":\"Dune Part Two\",\"year\":2024,\"type\":\"movie\"}]")
        assertEquals(false, SubtitleProviderResultParser.subDlHasExactTitleMatch(subtitlesBelongToFirstOnly, "Dune Part Two", 2024, "movie"))
    }

    @Test fun selectsOnlyExactSubDlTitleYearAndMediaTypeForImdbIdentity() {
        val results = JSONArray(
            "[{\"name\":\"The Office\",\"original_name\":\"The Office\",\"year\":2005,\"type\":\"tv\",\"imdb_id\":\"tt0386676\"}," +
            "{\"name\":\"The Office\",\"year\":2001,\"type\":\"tv\",\"imdb_id\":\"tt9999999\"}," +
            "{\"name\":\"Office Space\",\"year\":1999,\"type\":\"movie\",\"imdb_id\":\"tt015099\"}]"
        )
        assertEquals("tt0386676", SubtitleProviderResultParser.subDlImdbId(results, "The Office", 2005, "tv"))
        assertNull(SubtitleProviderResultParser.subDlImdbId(results, "The Office", 2020, "tv"))
        assertNull(SubtitleProviderResultParser.subDlImdbId(results, "Office", null, "movie"))
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

    @Test fun preservesProviderEpisodeMetadataForExactEpisodeFiltering() {
        val subtitle = SubtitleProviderResultParser.subDlSubtitle(
            url = "/subtitle/episode-file",
            releaseName = "Series Arabic",
            languageCode = "AR",
            formatName = "srt",
            downloads = 4,
            fallbackTitle = "Series",
            providerSeason = 2,
            providerEpisode = 5,
            providerEpisodeEnd = 5
        )
        assertEquals(2, subtitle?.providerSeasonNumber)
        assertEquals(5, subtitle?.providerEpisodeNumber)
        assertEquals(5, subtitle?.providerEpisodeEnd)
        assertEquals(false, subtitle?.fullSeasonPack)
    }

    @Test fun usesSafeReleaseFallbackAndNonNegativeDownloadCounts() {
        assertEquals("WEB-DL release", SubtitleProviderResultParser.release("", "WEB-DL release"))
        assertEquals("Subtitle", SubtitleProviderResultParser.release(" ", ""))
        assertEquals(0, SubtitleProviderResultParser.downloadCount(-10))
        assertEquals(42, SubtitleProviderResultParser.downloadCount(42))
    }
}
