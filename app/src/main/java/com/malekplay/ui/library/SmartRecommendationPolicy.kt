package com.malekplay.ui.library

import com.malekplay.domain.model.LibraryItem
import java.util.Locale

/**
 * Lightweight, on-device library recommendations.
 *
 * Uses only metadata already indexed by MediaStore and the current playback
 * state. No network calls, model downloads, analytics, or additional storage.
 */
internal object SmartRecommendationPolicy {

    fun recommend(
        items: List<LibraryItem>,
        lastPlayedMediaId: String?,
        limit: Int = DEFAULT_LIMIT
    ): List<LibraryItem> {
        if (limit <= 0 || items.isEmpty()) return emptyList()

        val anchor = lastPlayedMediaId?.let { id -> items.firstOrNull { it.media.id == id } }
        val anchorGenre = anchor?.media?.genre.normalized()
        val anchorArtist = anchor?.media?.artist.normalized()
        val anchorAlbum = anchor?.media?.album.normalized()

        return items.asSequence()
            .filterNot { it.media.id == lastPlayedMediaId }
            .filterNot { it.isCompleted || it.isInProgress }
            .map { candidate ->
                val genreMatch = anchorGenre != null &&
                    candidate.media.genre.normalized() == anchorGenre
                val artistMatch = anchorArtist != null &&
                    candidate.media.artist.normalized() == anchorArtist
                val albumMatch = anchorAlbum != null &&
                    candidate.media.album.normalized() == anchorAlbum

                // A small deterministic score keeps recommendations explainable.
                val score = (if (genreMatch) 6 else 0) +
                    (if (artistMatch) 5 else 0) +
                    (if (albumMatch) 3 else 0) +
                    (if (candidate.isFavourite) 2 else 0)

                ScoredItem(candidate, score)
            }
            .sortedWith(
                compareByDescending<ScoredItem> { it.score }
                    .thenByDescending { it.item.media.addedAtMs }
                    .thenBy { it.item.media.title.lowercase(Locale.ROOT) }
            )
            .take(limit)
            .map { it.item }
            .toList()
    }

    private fun String?.normalized(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    private data class ScoredItem(val item: LibraryItem, val score: Int)

    private const val DEFAULT_LIMIT = 12
}
