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

    @Test fun recognizesConsecutiveNumberOnlyEpisodesDirectlyInDownloads() {
        val files = listOf(8, 4, 7, 5, 6).map { number ->
            VideoFileDescriptor(
                "episode-$number",
                "$number.mp4",
                "Download",
                "Download/"
            )
        }
        val classified = VideoKindClassifier.classifyAll(files)

        files.forEach { file ->
            assertEquals(VideoKind.SERIES, classified.getValue(file.id).kind)
            assertEquals("حلقات مرقمة", classified.getValue(file.id).episode?.seriesKey)
        }
        assertEquals(4, classified.getValue("episode-4").episode?.episode)
        assertEquals(8, classified.getValue("episode-8").episode?.episode)
    }

    @Test fun doesNotTreatOneOrTwoNumberedDownloadsAsASeries() {
        val files = listOf(
            VideoFileDescriptor("clip-1", "1.mp4", "Download", "Download/"),
            VideoFileDescriptor("clip-2", "2.mp4", "Download", "Download/")
        )
        val classified = VideoKindClassifier.classifyAll(files)

        assertEquals(VideoKind.UNCLASSIFIED, classified.getValue("clip-1").kind)
        assertEquals(VideoKind.UNCLASSIFIED, classified.getValue("clip-2").kind)
    }

    @Test fun separatesNumberedEpisodeRunsFromUnrelatedNumbersInDownloads() {
        val numbers = listOf(4, 5, 6, 7, 8, 30, 60)
        val files = numbers.map { number ->
            VideoFileDescriptor("number-$number", "$number.mp4", "Download", "Download/")
        }
        val classified = VideoKindClassifier.classifyAll(files)

        (4..8).forEach { number ->
            assertEquals(VideoKind.SERIES, classified.getValue("number-$number").kind)
        }
        listOf(30, 60).forEach { number ->
            assertEquals(VideoKind.UNCLASSIFIED, classified.getValue("number-$number").kind)
        }
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

    @Test fun recognizesLeadingEpisodeNumbersInsideNamedSeriesFolders() {
        val files = listOf(
            VideoFileDescriptor("first", "01 - البداية.mp4", "The Series", "/storage/TV/The Series"),
            VideoFileDescriptor("second", "02 - الرحلة.mp4", "The Series", "/storage/TV/The Series")
        )
        val classified = VideoKindClassifier.classifyAll(files)
        assertEquals(VideoKind.SERIES, classified.getValue("first").kind)
        assertEquals(VideoKind.SERIES, classified.getValue("second").kind)
        assertEquals("the series", classified.getValue("first").episode?.seriesKey)
        assertEquals(1, classified.getValue("first").episode?.episode)
        assertEquals(2, classified.getValue("second").episode?.episode)
    }

    @Test fun recognizesNumberOnlyEpisodesWhenFolderNamesTheShow() {
        val files = listOf(
            VideoFileDescriptor("one", "01.mp4", "رحلة الغد", "/storage/TV/رحلة الغد/Season 1"),
            VideoFileDescriptor("two", "02.mp4", "رحلة الغد", "/storage/TV/رحلة الغد/Season 1")
        )
        val classified = VideoKindClassifier.classifyAll(files)
        assertEquals(VideoKind.SERIES, classified.getValue("one").kind)
        assertEquals(1, classified.getValue("one").episode?.season)
        assertEquals(1, classified.getValue("one").episode?.episode)
        assertEquals(2, classified.getValue("two").episode?.episode)
    }


    @Test fun recognizesOneThroughEightInDownloadAndLeavesUnrelatedNumberOut() {
        val files = (1..8).map { number ->
            VideoFileDescriptor("episode-$number", "$number.mp4", "Download", "Download/")
        } + VideoFileDescriptor("unrelated", "91.mp4", "Download", "Download/")
        val classified = VideoKindClassifier.classifyAll(files)

        (1..8).forEach { number ->
            assertEquals(VideoKind.SERIES, classified.getValue("episode-$number").kind)
            assertEquals("حلقات مرقمة", classified.getValue("episode-$number").episode?.seriesKey)
            assertEquals(number, classified.getValue("episode-$number").episode?.episode)
        }
        assertEquals(VideoKind.UNCLASSIFIED, classified.getValue("unrelated").kind)
    }

    @Test fun detectsCompactArabicSeasonAndEpisodeNotation() {
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("مسلسل الوعد ج2 ح05.mp4"))
        assertEquals(VideoKind.SERIES, VideoKindClassifier.classify("الرحلة ح 12.mkv"))
        val ep = VideoKindClassifier.episodeSortKey("الوعد ج٢ ح٠٥.mp4")
        assertEquals("الوعد", ep?.seriesKey)
        assertEquals(2, ep?.season)
        assertEquals(5, ep?.episode)
        assertEquals("Avatar The Last Airbender", VideoKindClassifier.formatSeriesDisplayName("avatar.the.last.airbender 1080p x265"))
    }


}
