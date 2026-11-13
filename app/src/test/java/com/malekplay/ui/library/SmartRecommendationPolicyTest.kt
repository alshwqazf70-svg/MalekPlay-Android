package com.malekplay.ui.library

import android.net.Uri
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmartRecommendationPolicyTest {

    @Test
    fun recommendsRelatedMetadataBeforeUnrelatedItems() {
        val anchor = item("anchor", genre = "Sci-Fi", artist = "Studio A")
        val sameGenre = item("same-genre", genre = " sci-fi ")
        val sameArtist = item("same-artist", artist = "studio a")
        val unrelated = item("unrelated", genre = "Comedy")

        val result = SmartRecommendationPolicy.recommend(
            listOf(unrelated, sameGenre, anchor, sameArtist),
            lastPlayedMediaId = "anchor"
        )

        assertEquals(listOf("same-genre", "same-artist", "unrelated"), result.map { it.media.id })
    }

    @Test
    fun excludesCurrentCompletedAndInProgressItems() {
        val anchor = item("anchor", genre = "Drama")
        val completed = item("completed", genre = "Drama").copy(isCompleted = true)
        val inProgress = item("in-progress", genre = "Drama").copy(positionMs = 5_000L)
        val available = item("available", genre = "Drama")

        val result = SmartRecommendationPolicy.recommend(
            listOf(anchor, completed, inProgress, available),
            lastPlayedMediaId = "anchor"
        )

        assertEquals(listOf("available"), result.map { it.media.id })
    }

    @Test
    fun favouritesImproveRankingAndLimitIsRespected() {
        val anchor = item("anchor")
        val ordinary = item("ordinary", addedAtMs = 20L)
        val favourite = item("favourite", addedAtMs = 1L).copy(isFavourite = true)

        val result = SmartRecommendationPolicy.recommend(
            listOf(ordinary, favourite, anchor),
            lastPlayedMediaId = "anchor",
            limit = 1
        )

        assertEquals(listOf("favourite"), result.map { it.media.id })
        assertTrue(SmartRecommendationPolicy.recommend(listOf(anchor), "anchor").isEmpty())
    }


    @Test
    fun localIntelligenceLearnsGenreAndArtistPreferencesAndExplainsPicks() {
        val anchor = item("watched", genre = "Sci-Fi", artist = "Studio A").copy(isCompleted = true)
        val sameGenre = item("same-genre", genre = "sci-fi")
        val sameArtist = item("same-artist", artist = "studio a")
        val unrelated = item("unrelated", genre = "Comedy")

        val picks = SmartRecommendationPolicy.analyze(
            listOf(anchor, sameGenre, sameArtist, unrelated),
            lastPlayedMediaId = "watched"
        )

        assertEquals("same-genre", picks.first().item.media.id)
        assertTrue(picks.first().reason.isNotBlank())
        assertEquals("same-artist", picks[1].item.media.id)
    }

    @Test
    fun localIntelligenceFallsBackToFreshLibraryPicksWithoutHistory() {
        val older = item("older", addedAtMs = 1L)
        val newer = item("newer", addedAtMs = 20L)

        val picks = SmartRecommendationPolicy.analyze(listOf(older, newer), null)

        assertEquals(listOf("newer", "older"), picks.map { it.item.media.id })
        assertTrue(picks.all { it.reason.isNotBlank() })
    }


    @Test
    fun smartSearchRanksTitleBeforeArtistAndSupportsArabicMetadata() {
        val titleMatch = item("title-match").copy(
            media = item("title-match").media.copy(title = "The Expanse")
        )
        val artistMatch = item("artist-match", artist = "The Expanse Studio")
        val arabicMatch = item("arabic-match", genre = "خيال علمي")

        val titleResults = SmartRecommendationPolicy.search(listOf(artistMatch, titleMatch), "expanse")
        val arabicResults = SmartRecommendationPolicy.search(listOf(arabicMatch), "خيال")

        assertEquals(listOf("title-match", "artist-match"), titleResults.map { it.media.id })
        assertEquals(listOf("arabic-match"), arabicResults.map { it.media.id })
    }

    @Test
    fun smartSearchUnderstandsNaturalLanguagePrefixesAndGenericMediaWords() {
        val sciFi = item("sci-fi", genre = "خيال علمي")
        val drama = item("drama", genre = "دراما")
        val results = SmartRecommendationPolicy.search(
            listOf(drama, sciFi),
            "ابحث عن أفلام الخيال العلمي"
        )
        assertEquals(listOf("sci-fi"), results.map { it.media.id })
    }

    @Test
    fun smartSearchUnderstandsEnglishSearchCommands() {
        val target = item("target", artist = "Studio North")
        val other = item("other", artist = "Studio South")
        assertEquals(
            listOf("target"),
            SmartRecommendationPolicy.search(listOf(other, target), "search for videos Studio North")
                .map { it.media.id }
        )
    }

    @Test
    fun smartSearchRequiresMatchingTokensAndHonoursLimit() {
        val first = item("first", artist = "Studio North")
        val second = item("second", artist = "Studio South")
        val noMatch = item("none", artist = "Other")

        assertEquals(
            listOf("first"),
            SmartRecommendationPolicy.search(listOf(first, second, noMatch), "studio", limit = 1)
                .map { it.media.id }
        )
        assertTrue(SmartRecommendationPolicy.search(listOf(first), "missing").isEmpty())
        assertTrue(SmartRecommendationPolicy.search(listOf(first), "studio", limit = 0).isEmpty())
    }


    @Test
    fun similarMediaRanksSharedAlbumAndGenreAndExplainsMatches() {
        val source = item("source", genre = "Drama", artist = "Studio A")
            .copy(media = item("source", genre = "Drama", artist = "Studio A").media.copy(album = "Collection X", durationMs = 100_000L))
        val sameAlbum = item("album", genre = "Comedy", artist = "Studio B")
            .copy(media = item("album").media.copy(album = "collection x", durationMs = 300_000L))
        val sameGenre = item("genre", genre = "drama")
            .copy(media = item("genre").media.copy(durationMs = 90_000L))
        val durationOnly = item("duration")
            .copy(media = item("duration").media.copy(durationMs = 98_000L))
        val unrelated = item("unrelated", genre = "Comedy")
            .copy(media = item("unrelated").media.copy(durationMs = 400_000L))

        val result = SmartRecommendationPolicy.similar(
            listOf(source, durationOnly, unrelated, sameGenre, sameAlbum),
            "source"
        )

        assertEquals(listOf("genre", "album", "duration"), result.map { it.item.media.id })
        assertTrue(result.first().reason.isNotBlank())
    }

    @Test
    fun similarMediaExcludesSourceAndHonoursLimitsAndMissingIds() {
        val source = item("source", genre = "Drama")
        val related = item("related", genre = "Drama")
        assertEquals(
            listOf("related"),
            SmartRecommendationPolicy.similar(listOf(source, related), "source", limit = 1)
                .map { it.item.media.id }
        )
        assertTrue(SmartRecommendationPolicy.similar(listOf(source, related), "missing").isEmpty())
        assertTrue(SmartRecommendationPolicy.similar(listOf(source, related), "source", limit = 0).isEmpty())
    }

    @Test
    fun smartCollectionsGroupRepeatedGenresAndIgnoreSingletons() {
        val items = listOf(
            item("one", genre = "Sci-Fi"),
            item("two", genre = " sci-fi "),
            item("three", genre = "Drama"),
            item("four", genre = "DRAMA"),
            item("singleton", genre = "Comedy"),
            item("missing")
        )

        val groups = SmartRecommendationPolicy.collections(items)

        assertEquals(listOf("Drama", "Sci-Fi"), groups.map { it.title })
        assertEquals(listOf(2, 2), groups.map { it.itemCount })
        assertEquals(listOf("drama", "sci-fi"), groups.map { it.query })
        assertTrue(SmartRecommendationPolicy.collections(items, limit = 0).isEmpty())
    }


    @Test
    fun smartSearchNormalizesArabicAlefAndDiacritics() {
        val hamzaVariant = item("hamza", genre = "أفلام")
        val diacriticVariant = item("diacritics", genre = "إِنْمَاء")

        assertEquals(
            listOf("hamza"),
            SmartRecommendationPolicy.search(listOf(hamzaVariant), "افلام").map { it.media.id }
        )
        assertEquals(
            listOf("diacritics"),
            SmartRecommendationPolicy.search(listOf(diacriticVariant), "انماء").map { it.media.id }
        )
    }


    @Test
    fun smartCollectionsDiscoverArtistsAndAlbumsAsWellAsGenres() {
        val first = item("one", genre = "Drama", artist = "Studio A").copy(
            media = item("one", genre = "Drama", artist = "Studio A").media.copy(album = "Collection X")
        )
        val second = item("two", genre = "Comedy", artist = "studio a").copy(
            media = item("two", genre = "Comedy", artist = "studio a").media.copy(album = "collection x")
        )

        val groups = SmartRecommendationPolicy.collections(listOf(first, second))

        assertEquals(setOf("الفنان", "الألبوم"), groups.map { it.kind }.toSet())
        assertTrue(groups.all { it.itemCount == 2 })
        assertTrue(groups.any { it.title == "Studio A" })
        assertTrue(groups.any { it.title == "Collection X" })
    }

    @Test
    fun recommendationsDiversifyArtistsWhenEquivalentAlternativesExist() {
        val anchor = item("anchor", genre = "Drama", artist = "Studio A").copy(isCompleted = true)
        val first = item("first", genre = "Drama", artist = "Studio A")
        val second = item("second", genre = "Drama", artist = "Studio A")
        val third = item("third", genre = "Drama", artist = "Studio A")
        val alternative = item("alternative", genre = "Comedy", artist = "Studio B")

        val result = SmartRecommendationPolicy.analyze(
            listOf(anchor, first, second, third, alternative),
            lastPlayedMediaId = "anchor",
            limit = 4
        )

        assertEquals(4, result.size)
        assertEquals(2, result.count { it.item.media.artist == "Studio A" })
        assertTrue(result.any { it.item.media.id == "alternative" })
    }

    private fun item(
        id: String,
        genre: String? = null,
        artist: String? = null,
        addedAtMs: Long = 1L
    ) = LibraryItem(
        media = MediaItem(
            id = id,
            uri = Uri.parse("content://test/$id"),
            title = id,
            durationMs = 60_000L,
            sizeBytes = 1L,
            addedAtMs = addedAtMs,
            mimeType = "video/mp4",
            isVideo = true,
            folderPath = "/test",
            folderName = "test",
            artist = artist,
            album = null,
            albumId = null,
            artistId = null,
            genre = genre,
            width = 1,
            height = 1,
            bitrate = 1L
        )
    )
}
