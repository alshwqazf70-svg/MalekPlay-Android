package com.malekplay.ui.library

import com.malekplay.domain.model.LibraryItem
import java.util.Locale

data class LibraryAssistantResponse(val message: String, val items: List<LibraryItem>)

private data class LibraryAssistantFilters(
    val videoOnly: Boolean = false,
    val audioOnly: Boolean = false,
    val unwatchedOnly: Boolean = false,
    val inProgressOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val favouritesOnly: Boolean = false,
    val genre: String? = null,
    val artist: String? = null,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null
) {
    val hasFilters: Boolean
        get() = videoOnly || audioOnly || unwatchedOnly || inProgressOnly ||
            completedOnly || favouritesOnly || genre != null || artist != null ||
            minDurationMs != null || maxDurationMs != null
}

/**
 * Deterministic command interpreter for the local library.
 * It reads indexed metadata only; it never starts playback or changes user data.
 */
internal object LibraryAssistantPolicy {
    fun answer(items: List<LibraryItem>, query: String, lastPlayedMediaId: String?): LibraryAssistantResponse {
        val normalized = normalize(query)
        if (normalized.isBlank()) return LibraryAssistantResponse("اكتب طلباً لأبحث في مكتبتك.", emptyList())

        if (containsAny(normalized, RESUME_TERMS)) {
            val lastPlayed = items.firstOrNull { it.media.id == lastPlayedMediaId }
                ?: items.filter { it.isInProgress }.maxByOrNull { it.positionMs }
            return if (lastPlayed == null) {
                LibraryAssistantResponse("لم أجد عنصراً سابقاً يمكن متابعة تشغيله.", emptyList())
            } else {
                LibraryAssistantResponse("وجدت آخر عنصر شغّلته. اضغط عليه للمتابعة.", listOf(lastPlayed))
            }
        }

        if (containsAny(normalized, SIMILAR_TERMS)) {
            val source = items.asSequence().filter { it.media.isVideo }.filter { candidate ->
                val title = normalize(candidate.media.displayTitle)
                title.length >= 2 && normalized.contains(title)
            }.maxByOrNull { normalize(it.media.displayTitle).length }
            if (source != null) {
                val related = SmartRecommendationPolicy.similar(items, source.media.id).map { it.item }
                return LibraryAssistantResponse(
                    if (related.isEmpty()) "لم أجد أعمالاً مشابهة اعتماداً على بيانات هذا الملف." else "هذه أعمال مشابهة لـ ${source.media.displayTitle}.",
                    related
                )
            }
        }

        val filters = parseFilters(items, normalized)
        if (filters.hasFilters) {
            val matches = items.asSequence()
                .filter { item ->
                    val media = item.media
                    (!filters.videoOnly || media.isVideo) &&
                        (!filters.audioOnly || !media.isVideo) &&
                        (!filters.unwatchedOnly || (!item.isCompleted && !item.isInProgress)) &&
                        (!filters.inProgressOnly || item.isInProgress) &&
                        (!filters.completedOnly || item.isCompleted) &&
                        (!filters.favouritesOnly || item.isFavourite) &&
                        (filters.genre == null || normalize(media.genre) == normalize(filters.genre)) &&
                        (filters.artist == null || normalize(media.artist) == normalize(filters.artist)) &&
                        (filters.minDurationMs == null || media.durationMs >= filters.minDurationMs) &&
                        (filters.maxDurationMs == null || media.durationMs <= filters.maxDurationMs)
                }
                .sortedByDescending { it.media.addedAtMs }
                .take(50)
                .toList()
            val criteria = buildList {
                if (filters.videoOnly) add("الفيديوهات")
                if (filters.audioOnly) add("الملفات الصوتية")
                if (filters.unwatchedOnly) add("غير المشاهدة")
                if (filters.inProgressOnly) add("قيد المشاهدة")
                if (filters.completedOnly) add("المكتملة")
                if (filters.favouritesOnly) add("المفضلة")
                filters.genre?.let { add("نوع $it") }
                filters.artist?.let { add("الفنان $it") }
                filters.minDurationMs?.let { add("أطول من ${formatDuration(it)}") }
                filters.maxDurationMs?.let { add("أقصر من ${formatDuration(it)}") }
            }
            return LibraryAssistantResponse(
                if (matches.isEmpty()) "لم أجد عناصر تطابق الشروط: ${criteria.joinToString(" • ")}."
                else "وجدت ${matches.size} نتيجة تطابق: ${criteria.joinToString(" • ")}.",
                matches
            )
        }

        val matches = SmartRecommendationPolicy.search(items, query)
        return LibraryAssistantResponse(
            if (matches.isEmpty()) "لم أفهم الطلب أو لم أجد نتائج. جرّب البحث باسم فيلم أو فنان أو نوع."
            else "هذه أقرب النتائج من مكتبتك.",
            matches.take(20)
        )
    }

    private fun parseFilters(items: List<LibraryItem>, query: String): LibraryAssistantFilters {
        val genre = items.asSequence().mapNotNull { it.media.genre?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy(::normalize).sortedByDescending { normalize(it).length }
            .firstOrNull { query.contains(normalize(it)) }
        val artist = items.asSequence().mapNotNull { it.media.artist?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy(::normalize).sortedByDescending { normalize(it).length }
            .firstOrNull { query.contains(normalize(it)) }
        val duration = parseDurationConstraint(query)
        return LibraryAssistantFilters(
            videoOnly = containsAny(query, VIDEO_TERMS),
            audioOnly = containsAny(query, AUDIO_TERMS),
            unwatchedOnly = containsAny(query, UNWATCHED_TERMS),
            inProgressOnly = containsAny(query, IN_PROGRESS_TERMS),
            completedOnly = containsAny(query, COMPLETED_TERMS),
            favouritesOnly = containsAny(query, FAVOURITE_TERMS),
            genre = genre,
            artist = artist,
            minDurationMs = duration.first,
            maxDurationMs = duration.second
        )
    }

    /** Returns inclusive lower/upper duration bounds in milliseconds. */
    private fun parseDurationConstraint(query: String): Pair<Long?, Long?> {
        val pattern = Regex("(اقل من|اكثر من|اطول من|اقصر من|under|over|longer than|shorter than)\\s+(\\d+(?:\\.\\d+)?)\\s*(دقيقه|دقائق|ساعه|ساعات|min|mins|minute|minutes|hour|hours)")
        val match = pattern.find(query)
        if (match != null) {
            val amount = match.groupValues[2].toDoubleOrNull() ?: return null to null
            val unit = match.groupValues[3]
            val multiplier = if (unit.startsWith("ساع") || unit.startsWith("hour")) 3_600_000.0 else 60_000.0
            val duration = (amount * multiplier).toLong().coerceAtLeast(0L)
            return when (match.groupValues[1]) {
                "اكثر من", "اطول من", "over", "longer than" -> (duration + 1L) to null
                else -> null to (duration - 1L).coerceAtLeast(0L)
            }
        }
        val wordBound = when {
            query.contains("ساعتين") || query.contains("ساعتان") -> 120L * 60_000L
            query.contains("نصف ساعه") -> 30L * 60_000L
            query.contains("ساعه") -> 60L * 60_000L
            else -> null
        } ?: return null to null
        return when {
            containsAny(query, setOf("اكثر من", "اطول من", "over", "longer than")) -> wordBound + 1L to null
            containsAny(query, setOf("اقل من", "اقصر من", "under", "shorter than")) -> null to wordBound - 1L
            else -> null to null
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val minutes = durationMs / 60_000L
        return if (minutes >= 60L) "${minutes / 60} ساعة" else "$minutes دقيقة"
    }

    private fun containsAny(value: String, terms: Set<String>): Boolean = terms.any { term -> value.contains(term) }

    private fun normalize(value: String?): String {
        val source = value?.lowercase(Locale.ROOT).orEmpty()
        return buildString(source.length) {
            source.forEach { character ->
                when {
                    character == '\u0640' -> Unit
                    character in '\u064B'..'\u065F' || character == '\u0670' -> Unit
                    character in "أإآٱ" -> append('ا')
                    character == 'ى' -> append('ي')
                    character in '٠'..'٩' -> append(('0'.code + character.code - '٠'.code).toChar())
                    character in '۰'..'۹' -> append(('0'.code + character.code - '۰'.code).toChar())
                    else -> append(character)
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    private val RESUME_TERMS = setOf("اكمل", "تابع المشاهده", "متابعه المشاهده", "resume", "continue")
    private val SIMILAR_TERMS = setOf("مشابه", "مشابهه", "مثل هذا", "اعمال مثل", "similar", "related")
    private val UNWATCHED_TERMS = setOf("لم اشاهد", "غير مشاهده", "unwatched", "not watched")
    private val IN_PROGRESS_TERMS = setOf("اكمل المشاهده", "قيد المشاهده", "تابع", "in progress", "continue watching")
    private val COMPLETED_TERMS = setOf("شاهدتها", "شاهدت بالكامل", "مكتمله", "completed", "watched")
    private val FAVOURITE_TERMS = setOf("المفضله", "مفضلتي", "favorite", "favourite", "favorites")
    private val VIDEO_TERMS = setOf("افلام", "فيلم", "فيديو", "مسلسلات", "مسلسل", "movies", "movie", "videos", "video", "series")
    private val AUDIO_TERMS = setOf("موسيقى", "اغاني", "صوت", "ملفات صوتيه", "music", "songs", "audio")
}
