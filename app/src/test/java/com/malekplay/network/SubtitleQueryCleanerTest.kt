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

    @Test fun keepsNumericMovieTitlesThatAreNotPlausibleReleaseYears() {
        val result = SubtitleQueryCleaner.clean("Blade.Runner.2049.1080p.BluRay.x265.mkv")
        assertEquals("Blade Runner 2049", result.title)
        assertNull(result.year)
    }

    @Test fun supportsAlternateEpisodeNotationAndUnderscores() {
        val result = SubtitleQueryCleaner.clean("The_Office_2x03_720p_H264_WEBRip.mkv")
        assertEquals("The Office", result.title)
        assertEquals(2, result.season)
        assertEquals(3, result.episode)
    }

    @Test fun episodeSuffixIsAppendedOnlyWhenMissing() {
        assertEquals("Arthdal Chronicles S01E01",
            SubtitleQueryCleaner.appendEpisodeIfMissing("Arthdal Chronicles", 1, 1))
        assertEquals("Arthdal Chronicles S01E01",
            SubtitleQueryCleaner.appendEpisodeIfMissing("Arthdal Chronicles S01E01", 1, 1))
        assertEquals("The Office 2x03",
            SubtitleQueryCleaner.appendEpisodeIfMissing("The Office 2x03", 2, 3))
    }

    @Test fun keepsPlainMovieTitleAndDoesNotInventEpisode() {
        val result = SubtitleQueryCleaner.clean("Interstellar (2014).mp4")
        assertEquals("Interstellar", result.title)
        assertEquals(2014, result.year)
        assertNull(result.season)
        assertNull(result.episode)
    }

    @Test fun stripsReleaseGroupWithoutDamagingHyphenatedTitles() {
        assertEquals("Spider-Man", SubtitleQueryCleaner.clean("Spider-Man-GROUP.mkv").title)
        assertEquals("The Batman", SubtitleQueryCleaner.clean("The.Batman.2022-RARBG.mkv").title)
        assertEquals("Movie Name", SubtitleQueryCleaner.clean("Movie.Name.Pahe.mkv").title)
        assertEquals("Series Name", SubtitleQueryCleaner.clean("Series.Name.GalaxyRG.mkv").title)
    }

    @Test fun supportsMoreAudioAndDynamicRangeTags() {
        val result = SubtitleQueryCleaner.clean("Oppenheimer.2023.1080p.WEBRip.AAC5.1.Dolby.Vision-GROUP.mkv")
        assertEquals("Oppenheimer", result.title)
        assertEquals(2023, result.year)
    }

    @Test fun stripsMultichannelAudioWhenNoResolutionTagExists() {
        assertEquals("Movie Name", SubtitleQueryCleaner.clean("Movie.Name.DDP5.1-GROUP.mkv").title)
        assertEquals("Movie Name", SubtitleQueryCleaner.clean("Movie_Name_AAC5.1-RELEASE.mkv").title)
    }

    @Test fun handlesEmptyAndUriLikeNames() {
        assertEquals("", SubtitleQueryCleaner.clean("").title)
        assertEquals("My Film", SubtitleQueryCleaner.clean("content://media/external/video/My_Film.mkv").title)
        assertEquals("Release Name", SubtitleQueryCleaner.clean("Release.Name.srt").title)
    }
    @Test fun parsesArabicIndicAndPersianEpisodeDigits() {
        val arabicIndic = SubtitleQueryCleaner.clean("مسلسل.S٠٢E٠٥.1080p.mkv")
        assertEquals("مسلسل", arabicIndic.title)
        assertEquals(2, arabicIndic.season)
        assertEquals(5, arabicIndic.episode)
        assertEquals("2", SubtitleQueryCleaner.normalizeDigits("٢"))
        assertEquals("05", SubtitleQueryCleaner.normalizeDigits("۰۵"))
    }

}
