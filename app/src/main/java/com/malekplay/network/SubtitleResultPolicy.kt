package com.malekplay.network

/** Pure provider-result policy, kept independent from Android and HTTP for JVM tests. */
internal object SubtitleResultPolicy {
    fun combineCandidates(candidateResults: Iterable<List<OnlineSubtitle>>): List<OnlineSubtitle> =
        candidateResults.flatten().distinctBy { it.provider + ":" + it.sourceId }

    /**
     * A provider's episode filters can be overly strict or incomplete. Retry with
     * the title alone only when an episode-scoped search returned no candidates.
     */
    fun <T> withEpisodeFallback(
        season: Int?,
        episode: Int?,
        episodeSearch: () -> List<T>,
        titleSearch: () -> List<T>,
        shouldFallback: (List<T>) -> Boolean = { it.isEmpty() }
    ): List<T> {
        val scopedResults = episodeSearch()
        if (season == null && episode == null) return scopedResults
        if (!shouldFallback(scopedResults)) return scopedResults
        // Preserve exact episode hits and augment them with broader title hits.
        return scopedResults + titleSearch()
    }

    fun rankAndFilter(
        results: List<OnlineSubtitle>,
        languageFilter: String,
        season: Int? = null,
        episode: Int? = null
    ): List<OnlineSubtitle> {
        val wanted = languageFilter.split(',').map(SubtitleQueryCleaner::normalizeLanguage)
            .filter { it == "ar" || it == "en" }.toSet().ifEmpty { setOf("ar", "en") }
        val episodePattern = if (season != null && episode != null) {
            Regex(
                """(?i)(?:\bS0?${season}E0?${episode}\b|\b${season}x0?${episode}\b)"""
            )
        } else null
        return results.filter { SubtitleQueryCleaner.normalizeLanguage(it.language) in wanted }
            .sortedWith(compareBy<OnlineSubtitle>(
                { if (episodePattern == null || episodePattern.containsMatchIn(it.release)) 0 else 1 },
                { if (SubtitleQueryCleaner.normalizeLanguage(it.language) == "ar") 0 else 1 },
                { -it.downloads }
            ))
    }
}

/** Access-ordered bounded cache; callers filter language after retrieving combined results. */
internal class SubtitleSearchResultCache(private val capacity: Int = 12) {
    private val entries = object : LinkedHashMap<String, List<OnlineSubtitle>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OnlineSubtitle>>?) =
            size > capacity
    }
    @Synchronized fun get(query: String): List<OnlineSubtitle>? = entries[query]?.toList()
    @Synchronized fun put(query: String, results: List<OnlineSubtitle>) {
        if (results.isEmpty()) {
            entries.remove(query)
        } else {
            entries[query] = results.toList()
        }
    }
    @Synchronized fun clear() = entries.clear()
}
