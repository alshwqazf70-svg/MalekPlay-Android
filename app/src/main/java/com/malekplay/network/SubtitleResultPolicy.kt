package com.malekplay.network

/** Pure provider-result policy, kept independent from Android and HTTP for JVM tests. */
internal object SubtitleResultPolicy {
    fun rankAndFilter(results: List<OnlineSubtitle>, languageFilter: String): List<OnlineSubtitle> {
        val wanted = languageFilter.split(',').map(SubtitleQueryCleaner::normalizeLanguage)
            .filter { it == "ar" || it == "en" }.toSet().ifEmpty { setOf("ar", "en") }
        return results.filter { SubtitleQueryCleaner.normalizeLanguage(it.language) in wanted }
            .sortedWith(compareBy<OnlineSubtitle>(
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
    @Synchronized fun put(query: String, results: List<OnlineSubtitle>) { entries[query] = results.toList() }
    @Synchronized fun clear() = entries.clear()
}
