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

internal data class SmartMediaCollection(
    val title: String,
    val query: String,
    val itemCount: Int,
    val kind: String
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

    /** Discover local smart groups by genre, artist and album metadata. */
    fun collections(
        items: List<LibraryItem>,
        limit: Int = COLLECTION_LIMIT,
        minimumItems: Int = COLLECTION_MINIMUM_ITEMS
    ): List<SmartMediaCollection> {
        if (limit <= 0 || minimumItems < 2) return emptyList()
        val buckets = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        items.forEach { item ->
            val media = item.media
            val metadata = listOfNotNull(
                media.genre?.trim()?.takeIf { it.isNotEmpty() }?.let { "النوع" to it },
                media.artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "الفنان" to it },
                media.album?.trim()?.takeIf { it.isNotEmpty() }?.let { "الألبوم" to it }
            )
            metadata.forEach { (kind, value) ->
                val normalized = value.normalized()
                if (normalized != null) {
                    val key = "$kind:$normalized"
                    val bucket = buckets.getOrPut(key) { value to mutableListOf() }
                    bucket.second += media.id
                }
            }
        }
        return buckets.mapNotNull { (key, bucket) ->
            val (title, mediaIds) = bucket
            if (mediaIds.size < minimumItems) null
            else SmartMediaCollection(
                title = title,
                query = title,
                itemCount = mediaIds.size,
                kind = key.substringBefore(':')
            )
        }.sortedWith(
            compareByDescending<SmartMediaCollection> { it.itemCount }
                .thenBy { it.kind }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        ).take(limit)
    }

    /** Rank local metadata matches, including Arabic and Latin queries. */
    fun search(
        items: List<LibraryItem>,
        query: String,
        limit: Int = SEARCH_LIMIT
    ): List<LibraryItem> {
        if (limit <= 0) return emptyList()
        val normalizedQuery = query.normalized() ?: return emptyList()
        val tokens = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        return items.asSequence()
            .mapNotNull { item ->
                val fields = listOf(
                    item.media.displayTitle.normalized().orEmpty() to TITLE_WEIGHT,
                    item.media.artist.normalized().orEmpty() to ARTIST_SEARCH_WEIGHT,
                    item.media.album.normalized().orEmpty() to ALBUM_SEARCH_WEIGHT,
                    item.media.genre.normalized().orEmpty() to GENRE_SEARCH_WEIGHT,
                    item.media.folderName.normalized().orEmpty() to FOLDER_SEARCH_WEIGHT
                )
                val matchedTokens = tokens.count { token ->
                    fields.any { (value, _) -> value.contains(token) }
                }
                if (matchedTokens == 0) return@mapNotNull null
                val score = fields.sumOf { (value, weight) ->
                    when {
                        value == normalizedQuery -> weight * EXACT_MATCH_MULTIPLIER
                        value.startsWith(normalizedQuery) -> weight * PREFIX_MATCH_MULTIPLIER
                        value.contains(normalizedQuery) -> weight
                        else -> tokens.count { token -> value.contains(token) } * weight
                    }
                } + matchedTokens * TOKEN_COVERAGE_BONUS
                Triple(item, score, matchedTokens)
            }
            .sortedWith(
                compareByDescending<Triple<LibraryItem, Int, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first.media.title.lowercase(Locale.ROOT) }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun MutableMap<String, Double>.addWeight(key: String, weight: Double) {
        this[key] = (this[key] ?: 0.0) + weight
    }

    /**
     * Normalize common Arabic spelling variants and diacritics for local search.
     * Keep meaningful letters (including taa marbuta) distinct to avoid false matches.
     */
    private fun String?.normalized(): String? {
        val source = this?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val normalized = buildString(source.length) {
            source.forEach { character ->
                when {
                    character == '\u0640' -> Unit // tatweel
                    character in '\u064B'..'\u065F' || character == '\u0670' -> Unit
                    character in "أإآٱ" -> append('ا')
                    character == 'ى' -> append('ي')
                    else -> append(character)
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private const val DEFAULT_LIMIT = 12
    private const val LAST_PLAYED_WEIGHT = 5.0
    private const val COMPLETED_WEIGHT = 4.0
    private const val IN_PROGRESS_WEIGHT = 3.0
    private const val FAVOURITE_WEIGHT = 2.0
    private const val GENRE_FACTOR = 6.0
    private const val ARTIST_FACTOR = 4.0
    private const val ALBUM_FACTOR = 2.0
    private const val FAVOURITE_BONUS = 1.0
    private const val COLLECTION_LIMIT = 6
    private const val COLLECTION_MINIMUM_ITEMS = 2
    private const val SEARCH_LIMIT = 100
    private const val TITLE_WEIGHT = 12
    private const val ARTIST_SEARCH_WEIGHT = 8
    private const val ALBUM_SEARCH_WEIGHT = 7
    private const val GENRE_SEARCH_WEIGHT = 5
    private const val FOLDER_SEARCH_WEIGHT = 2
    private const val EXACT_MATCH_MULTIPLIER = 4
    private const val PREFIX_MATCH_MULTIPLIER = 2
    private const val TOKEN_COVERAGE_BONUS = 3
}
