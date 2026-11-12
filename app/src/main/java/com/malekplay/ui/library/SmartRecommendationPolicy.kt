package com.malekplay.ui.library

import com.malekplay.domain.model.LibraryItem
import java.util.Locale

/**
 * Small on-device media intelligence.
 *
 * Builds a short-lived preference profile from playback state and metadata already
 * present in the library. It performs deterministic ranking only: no network,
 * model download, analytics, generated files, or additional database tables.
 */
internal data class SmartMediaRecommendation(
    val item: LibraryItem,
    val reason: String,
    val score: Double
)

internal object SmartRecommendationPolicy {

    fun recommend(
        items: List<LibraryItem>,
        lastPlayedMediaId: String?,
        limit: Int = DEFAULT_LIMIT
    ): List<LibraryItem> = analyze(items, lastPlayedMediaId, limit).map { it.item }

    fun analyze(
        items: List<LibraryItem>,
        lastPlayedMediaId: String?,
        limit: Int = DEFAULT_LIMIT
    ): List<SmartMediaRecommendation> {
        if (limit <= 0 || items.isEmpty()) return emptyList()

        val genreAffinity = HashMap<String, Double>()
        val artistAffinity = HashMap<String, Double>()
        val albumAffinity = HashMap<String, Double>()

        items.forEach { history ->
            val weight = when {
                history.media.id == lastPlayedMediaId -> LAST_PLAYED_WEIGHT
                history.isCompleted -> COMPLETED_WEIGHT
                history.isInProgress -> IN_PROGRESS_WEIGHT
                history.isFavourite -> FAVOURITE_WEIGHT
                else -> 0.0
            }
            if (weight == 0.0) return@forEach
            history.media.genre.normalized()?.let { genreAffinity.addWeight(it, weight) }
            history.media.artist.normalized()?.let { artistAffinity.addWeight(it, weight) }
            history.media.album.normalized()?.let { albumAffinity.addWeight(it, weight) }
        }

        return items.asSequence()
            .filterNot { it.media.id == lastPlayedMediaId }
            .filterNot { it.isCompleted || it.isInProgress }
            .map { candidate ->
                val genre = candidate.media.genre.normalized()
                val artist = candidate.media.artist.normalized()
                val album = candidate.media.album.normalized()
                val genreScore = genre?.let { genreAffinity[it] } ?: 0.0
                val artistScore = artist?.let { artistAffinity[it] } ?: 0.0
                val albumScore = album?.let { albumAffinity[it] } ?: 0.0
                val score = genreScore * GENRE_FACTOR +
                    artistScore * ARTIST_FACTOR +
                    albumScore * ALBUM_FACTOR +
                    if (candidate.isFavourite) FAVOURITE_BONUS else 0.0

                val reason = when {
                    genreScore > 0.0 -> "لأنك تتابع هذا النوع"
                    artistScore > 0.0 -> "من أعمال الفنان الذي تفضله"
                    albumScore > 0.0 -> "من ألبوم تعرفه"
                    candidate.isFavourite -> "من قائمة مفضلاتك"
                    else -> "اختيار جديد من مكتبتك"
                }
                SmartMediaRecommendation(candidate, reason, score)
            }
            .sortedWith(
                compareByDescending<SmartMediaRecommendation> { it.score }
                    .thenByDescending { it.item.media.addedAtMs }
                    .thenBy { it.item.media.title.lowercase(Locale.ROOT) }
            )
            .take(limit)
            .toList()
    }

    private fun MutableMap<String, Double>.addWeight(key: String, weight: Double) {
        this[key] = (this[key] ?: 0.0) + weight
    }

    private fun String?.normalized(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    private const val DEFAULT_LIMIT = 12
    private const val LAST_PLAYED_WEIGHT = 5.0
    private const val COMPLETED_WEIGHT = 4.0
    private const val IN_PROGRESS_WEIGHT = 3.0
    private const val FAVOURITE_WEIGHT = 2.0
    private const val GENRE_FACTOR = 6.0
    private const val ARTIST_FACTOR = 4.0
    private const val ALBUM_FACTOR = 2.0
    private const val FAVOURITE_BONUS = 1.0
}
