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
}
