package com.malekplay.ui.library

import com.malekplay.domain.model.LibraryItem
import java.util.Locale

internal data class LibraryAssistantResponse(val message: String, val items: List<LibraryItem>)

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

        if (containsAny(normalized, UNWATCHED_TERMS)) {
            val unwatched = items.filter { it.media.isVideo && !it.isCompleted && !it.isInProgress }
            val matchingGenre = unwatched.asSequence()
                .mapNotNull { it.media.genre?.trim()?.takeIf { value -> value.isNotEmpty() } }
                .distinctBy(::normalize).sortedByDescending { normalize(it).length }
                .firstOrNull { genre -> normalized.contains(normalize(genre)) }
            val matches = if (matchingGenre == null) unwatched else unwatched.filter { normalize(it.media.genre) == normalize(matchingGenre) }
            return LibraryAssistantResponse(
                when {
                    matches.isEmpty() && matchingGenre != null -> "لا توجد أفلام غير مشاهدة من نوع $matchingGenre."
                    matches.isEmpty() -> "لا توجد أفلام غير مشاهدة في مكتبتك حالياً."
                    matchingGenre != null -> "وجدت ${matches.size} من أفلام $matchingGenre التي لم تشاهدها."
                    else -> "وجدت ${matches.size} من الأفلام التي لم تشاهدها."
                },
                matches.sortedByDescending { it.media.addedAtMs }.take(30)
            )
        }

        val matches = SmartRecommendationPolicy.search(items, query)
        return LibraryAssistantResponse(
            if (matches.isEmpty()) "لم أفهم الطلب أو لم أجد نتائج. جرّب البحث باسم فيلم أو فنان أو نوع." else "هذه أقرب النتائج من مكتبتك.",
            matches.take(20)
        )
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
                    else -> append(character)
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    private val RESUME_TERMS = setOf("اكمل", "تابع المشاهده", "متابعه المشاهده", "resume", "continue")
    private val SIMILAR_TERMS = setOf("مشابه", "مشابهه", "مثل هذا", "اعمال مثل", "similar", "related")
    private val UNWATCHED_TERMS = setOf("لم اشاهد", "غير مشاهده", "unwatched", "not watched")
}