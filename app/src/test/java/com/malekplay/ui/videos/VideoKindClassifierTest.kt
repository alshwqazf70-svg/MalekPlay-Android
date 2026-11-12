package com.malekplay.ui.videos

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoKindClassifierTest {
    @Test fun detectsSeasonEpisodeNotation() {
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("The.Office.S02E04.1080p.mkv"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("Show 2x08"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("Show.S02.E04.mkv"))
    }

    @Test fun detectsArabicEpisodeAndSeriesFolders() {
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("الحلقة 12", "الموسم الثاني"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("Episode 3", "Drama Series"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("الحلقة ١٢"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("مسلسل الرحلة الحلقة ١٢"))
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
        val arabicSeason = VideoKindClassifier.episodeSortKey("المسلسل S٠٢E٠٣.mkv")

        assertEquals("my show", first?.seriesKey)
        assertEquals(1, first?.season)
        assertEquals(2, first?.episode)
        assertEquals(2, nextSeason?.season)
        assertEquals(1, nextSeason?.episode)
        assertEquals("مسلسل الرحلة", arabic?.seriesKey)
        assertEquals(12, arabic?.episode)
        assertEquals(2, arabicSeason?.season)
        assertEquals(3, arabicSeason?.episode)

        val dotted = VideoKindClassifier.episodeSortKey("My.Show.S02.E04.mkv")
        assertEquals("my show", dotted?.seriesKey)
        assertEquals(2, dotted?.season)
        assertEquals(4, dotted?.episode)

        val folderSeason = VideoKindClassifier.episodeSortKey(
            "Episode 3.mkv", "Season 2", "/storage/TV/Drama Series/Season 2"
        )
        assertEquals("drama series", folderSeason?.seriesKey)
        assertEquals(2, folderSeason?.season)
        assertEquals(3, folderSeason?.episode)

        val numbered = VideoKindClassifier.episodeSortKey("Episode 3", "Drama Series")
        assertEquals("drama series", numbered?.seriesKey)
        assertEquals(3, numbered?.episode)
    }
    @Test fun episodeTitlesDoNotSplitOneSeriesIntoDifferentGroups() {
        val first = VideoKindClassifier.episodeSortKey(
            "Avatar.The.Last.Airbender.S01E01.The.Boy.In.The.Iceberg.1080p.mkv"
        )
        val second = VideoKindClassifier.episodeSortKey(
            "Avatar.The.Last.Airbender.S01E02.The.Avatar.Returns.1080p.mkv"
        )
        assertEquals("avatar the last airbender", first?.seriesKey)
        assertEquals(first?.seriesKey, second?.seriesKey)
        assertEquals(1, first?.episode)
        assertEquals(2, second?.episode)
    }

    @Test fun usesParentFolderWhenEpisodeMarkerStartsTheFilename() {
        val episode = VideoKindClassifier.episodeSortKey(
            "S02E04.The.Return.mkv", "Season 2", "/storage/TV/Avatar/Season 2"
        )
        assertEquals("avatar", episode?.seriesKey)
        assertEquals(2, episode?.season)
        assertEquals(4, episode?.episode)
    }

    @Test fun groupsNumberedSiblingFilesAsEpisodesAndSortsByNumber() {
        val files = listOf(
            VideoFileDescriptor("ep10", "My Show 10.mp4", "Downloads", "/storage/emulated/0/Download/MyShow"),
            VideoFileDescriptor("ep2", "My Show 2.mp4", "Downloads", "/storage/emulated/0/Download/MyShow"),
            VideoFileDescriptor("ep1", "My Show 1.mp4", "Downloads", "/storage/emulated/0/Download/MyShow")
        )
        val classified = VideoKindClassifier.classifyAll(files)

        assertEquals(VideoKind.SERIES, classified.getValue("ep1").kind)
        assertEquals(VideoKind.SERIES, classified.getValue("ep2").kind)
        assertEquals(VideoKind.SERIES, classified.getValue("ep10").kind)
        assertEquals("my show", classified.getValue("ep1").episode?.seriesKey)
        assertEquals(1, classified.getValue("ep1").episode?.episode)
        assertEquals(2, classified.getValue("ep2").episode?.episode)
        assertEquals(10, classified.getValue("ep10").episode?.episode)
    }

    @Test fun keepsAmbiguousVideoClassificationConservative() {
        val files = listOf(
            VideoFileDescriptor("unknown", "Nature Documentary 4K.mp4", "Downloads", "/storage/emulated/0/Download"),
            VideoFileDescriptor("movie", "فيلم الرحلة.mp4", "Movies", "/storage/emulated/0/Movies")
        )
        val classified = VideoKindClassifier.classifyAll(files)
        assertEquals(VideoKind.UNCLASSIFIED, classified.getValue("unknown").kind)
        assertEquals(VideoKind.MOVIE, classified.getValue("movie").kind)
    }

    @Test fun recognizesArabicEpisodeNumbersInSiblingFiles() {
        val files = listOf(
            VideoFileDescriptor("a", "رحلة ١.mp4", "التنزيلات", "/storage/emulated/0/Download/رحلة"),
            VideoFileDescriptor("b", "رحلة ٢.mp4", "التنزيلات", "/storage/emulated/0/Download/رحلة")
        )
        val classified = VideoKindClassifier.classifyAll(files)
        assertEquals(VideoKind.SERIES, classified.getValue("a").kind)
        assertEquals(VideoKind.SERIES, classified.getValue("b").kind)
        assertEquals(1, classified.getValue("a").episode?.episode)
        assertEquals(2, classified.getValue("b").episode?.episode)
    }

}
