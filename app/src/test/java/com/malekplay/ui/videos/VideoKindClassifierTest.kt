package com.malekplay.ui.videos

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoKindClassifierTest {
    @Test fun detectsSeasonEpisodeNotation() {
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("The.Office.S02E04.1080p.mkv"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("Show 2x08"))
    }

    @Test fun detectsArabicEpisodeAndSeriesFolders() {
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("الحلقة 12", "الموسم الثاني"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("Episode 3", "Drama Series"))
    }

    @Test fun detectsExplicitMovieLabels() {
        assertEquals(VideoKind.MOVIE, VideoKindClassifier.classify("فيلم الرحلة"))
        assertEquals(VideoKind.MOVIE, VideoKindClassifier.classify("The Last Feature Film"))
    }

    @Test fun leavesAmbiguousNamesUnclassified() {
        assertEquals(VideoKind.UNCLASSIFIED, VideoKindClassifier.classify("Interstellar.2014.1080p"))
        assertEquals(VideoKind.UNCLASSIFIED, VideoKindClassifier.classify("Nature Beauty 4K"))
    }
    @Test fun extractsEpisodeOrderForLatinAndArabicNames() {
        val first = VideoKindClassifier.episodeSortKey("My.Show.S01E02.1080p.mkv")
        val nextSeason = VideoKindClassifier.episodeSortKey("My.Show.S02E01.mkv")
        val arabic = VideoKindClassifier.episodeSortKey("الحلقة ١٢", "مسلسل الرحلة")

        assertEquals("my show", first?.seriesKey)
        assertEquals(1, first?.season)
        assertEquals(2, first?.episode)
        assertEquals(2, nextSeason?.season)
        assertEquals(1, nextSeason?.episode)
        assertEquals("مسلسل الرحلة", arabic?.seriesKey)
        assertEquals(12, arabic?.episode)
    }

}
