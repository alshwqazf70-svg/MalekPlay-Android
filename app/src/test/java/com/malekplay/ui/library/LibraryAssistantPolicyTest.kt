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
class LibraryAssistantPolicyTest {
    @Test fun findsUnwatchedVideosByArabicGenreAndExcludesCompletedAndInProgress() {
        val target = item("target", "خيال علمي")
        val completed = item("completed", "خيال علمي").copy(isCompleted = true)
        val watching = item("watching", "خيال علمي").copy(positionMs = 5_000L)
        val otherGenre = item("other", "دراما")
        val result = LibraryAssistantPolicy.answer(listOf(otherGenre, completed, watching, target), "اعرض أفلام الخيال العلمي التي لم أشاهدها", null)
        assertEquals(listOf("target"), result.items.map { it.media.id })
        assertTrue(result.message.contains("خيال علمي"))
    }

    @Test fun resumesLastPlayedItemButDoesNotStartPlaybackAutomatically() {
        val result = LibraryAssistantPolicy.answer(listOf(item("first", "دراما"), item("last", "كوميديا")), "أكمل آخر فيلم شاهدته", "last")
        assertEquals(listOf("last"), result.items.map { it.media.id })
        assertTrue(result.message.contains("اضغط عليه"))
    }

    @Test fun fallsBackToInProgressWhenLastPlayedIdIsUnavailable() {
        val progress = item("progress", "دراما").copy(positionMs = 12_000L)
        val result = LibraryAssistantPolicy.answer(listOf(progress), "تابع المشاهدة", null)
        assertEquals(listOf("progress"), result.items.map { it.media.id })
    }

    @Test fun findsSimilarTitlesUsingExistingSimilarityPolicy() {
        val source = item("source", "دراما").copy(media = item("source", "دراما").media.copy(title = "The Source"))
        val related = item("related", "دراما")
        val result = LibraryAssistantPolicy.answer(listOf(source, related), "اعرض أعمال مشابهة لـ The Source", null)
        assertEquals(listOf("related"), result.items.map { it.media.id })
    }

    @Test fun handlesEmptyAndUnknownRequestsWithoutMutatingLibrary() {
        val target = item("target", "دراما")
        assertTrue(LibraryAssistantPolicy.answer(listOf(target), "   ", null).items.isEmpty())
        assertTrue(LibraryAssistantPolicy.answer(listOf(target), "طلب غير معروف", null).items.isEmpty())
    }

    @Test fun combinesMediaTypeGenreArtistAndUnwatchedFilters() {
        val match = item("match", "خيال علمي").copy(
            media = item("match", "خيال علمي").media.copy(artist = "Nour")
        )
        val otherArtist = item("other", "خيال علمي").copy(
            media = item("other", "خيال علمي").media.copy(artist = "Other")
        )
        val watched = match.copy(isCompleted = true, media = match.media.copy(id = "watched"))
        val audio = match.copy(media = match.media.copy(id = "audio", isVideo = false))
        val result = LibraryAssistantPolicy.answer(
            listOf(match, otherArtist, watched, audio),
            "اعرض أفلام الخيال العلمي للفنان Nour التي لم أشاهدها",
            null
        )
        assertEquals(listOf("match"), result.items.map { it.media.id })
    }

    @Test fun filtersByDurationUsingArabicAndArabicIndicDigits() {
        val short = item("short", "دراما")
        val long = short.copy(media = short.media.copy(id = "long", durationMs = 7_200_000L))
        val result = LibraryAssistantPolicy.answer(
            listOf(short, long),
            "اعرض أفلام أطول من ٦٠ دقيقة",
            null
        )
        assertEquals(listOf("long"), result.items.map { it.media.id })
    }

    @Test fun filtersFavouritesAndInProgressItems() {
        val favourite = item("fav", "دراما").copy(isFavourite = true)
        val ordinary = item("ordinary", "دراما")
        val result = LibraryAssistantPolicy.answer(
            listOf(favourite, ordinary),
            "اعرض المفضلة",
            null
        )
        assertEquals(listOf("fav"), result.items.map { it.media.id })
    }

    private fun item(id: String, genre: String) = LibraryItem(
        media = MediaItem(id = id, uri = Uri.parse("content://test/$id"), title = id, durationMs = 60_000L,
            sizeBytes = 1L, addedAtMs = 1L, mimeType = "video/mp4", isVideo = true, folderPath = "/test",
            folderName = "test", artist = null, album = null, albumId = null, artistId = null, genre = genre,
            width = 1, height = 1, bitrate = 1L)
    )
}