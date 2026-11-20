package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleQueryCleanerTest {
    @Test fun cleansSceneFilenameAndExtractsEpisode() {
        val result = SubtitleQueryCleaner.clean("Arthdal.Chronicles.S01E01.1080p.WEB-DL.x264.DDP5.1-GROUP.mkv")
        assertEquals("Arthdal Chronicles", result.title)
        assertEquals(1, result.season)
        assertEquals(1, result.episode)
    }

    @Test fun extractsYearAndStripsResolutionCodecAndReleaseGroup() {
        val result = SubtitleQueryCleaner.clean("Dune.Part.Two.2024.2160p.BluRay.x265.10bit.TrueHD.Atmos-GROUP.mkv")
        assertEquals("Dune Part Two", result.title)
        assertEquals(2024, result.year)
    }

    @Test fun supportsAlternateEpisodeNotationAndUnderscores() {
        val result = SubtitleQueryCleaner.clean("The_Office_2x03_720p_H264_WEBRip.mkv")
        assertEquals("The Office", result.title)
        assertEquals(2, result.season)
        assertEquals(3, result.episode)
    }

    @Test fun keepsPlainMovieTitleAndDoesNotInventEpisode() {
        val result = SubtitleQueryCleaner.clean("Interstellar (2014).mp4")
        assertEquals("Interstellar", result.title)
        assertEquals(2014, result.year)
        assertNull(result.season)
        assertNull(result.episode)
    }

    @Test fun handlesEmptyAndUriLikeNames() {
        assertEquals("", SubtitleQueryCleaner.clean("").title)
        assertEquals("My Film", SubtitleQueryCleaner.clean("content://media/external/video/My_Film.mkv").title)
    }
}
