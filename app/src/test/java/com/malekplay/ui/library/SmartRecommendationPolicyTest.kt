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
