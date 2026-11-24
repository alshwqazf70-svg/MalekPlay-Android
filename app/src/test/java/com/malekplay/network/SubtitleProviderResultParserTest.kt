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
    @Test fun parsesWyzieSubtitleAndPreservesItsSignedDownloadUrl() {
        val url = "https://sub.wyzie.io/c/abc/id/123?format=srt&tok=signed"
        val subtitle = SubtitleProviderResultParser.wyzieSubtitle(
            JSONObject().put("id", "123").put("url", url).put("language", "ar")
                .put("format", "srt").put("release", "Avatar.S01E03.1080p")
                .put("downloadCount", 25).put("match", JSONObject().put("score", 98)),
            season = 1,
            episode = 3
        )
        assertEquals("wyzie", subtitle?.provider)
        assertEquals("ar", subtitle?.language)
        assertEquals(url, subtitle?.downloadUrl)
        assertEquals(98, subtitle?.matchScore)
        assertEquals(1, subtitle?.seasonNumber)
        assertEquals(3, subtitle?.episodeNumber)
    }

    @Test fun rejectsUnsafeWyzieUrlsAndExplicitWrongEpisodeMatches() {
        val unsafe = JSONObject().put("id", "1")
            .put("url", "https://attacker.example/subtitle.srt")
            .put("language", "ar").put("format", "srt")
        assertNull(SubtitleProviderResultParser.wyzieSubtitle(unsafe, 1, 3))

        val wrongEpisode = JSONObject().put("id", "2")
            .put("url", "https://sub.wyzie.io/c/token/id/2")
            .put("language", "en").put("format", "srt")
            .put("match", JSONObject().put("score", 3)
                .put("reasons", org.json.JSONArray().put("wrong_episode")))
        assertNull(SubtitleProviderResultParser.wyzieSubtitle(wrongEpisode, 1, 3))
    }

}
