package com.malekplay.network

/** Pure provider-result policy, kept independent from Android and HTTP for JVM tests. */
internal object SubtitleResultPolicy {
    fun combineCandidates(candidateResults: Iterable<List<OnlineSubtitle>>): List<OnlineSubtitle> =
        candidateResults.flatten().distinctBy { it.provider + ":" + it.sourceId }

    /**
     * A provider's episode filters can be overly strict or incomplete. Retry with
     * the title when scoped candidates are incomplete (for example, one requested
     * language is missing), preserving scoped hits and later rejecting explicit
     * neighbouring episodes while retaining unnumbered language fallbacks.
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

    /**
     * Seasons and episodes are exposed only when a provider result explicitly
     * identifies them. Requested search scope is deliberately not used here.
     */
    fun availableSeasons(results: List<OnlineSubtitle>): List<Int> = results.mapNotNull { result ->
        result.providerSeasonNumber?.takeIf { it > 0 } ?: parseReleaseEpisode(result.release)?.first
    }.distinct().sorted()

    /** Returns only individually identified episode files for the selected season. */
    fun availableEpisodes(results: List<OnlineSubtitle>, season: Int): List<Int> = results.mapNotNull { result ->
        if (result.fullSeasonPack) return@mapNotNull null
        val parsed = parseReleaseEpisode(result.release)
        val actualSeason = result.providerSeasonNumber?.takeIf { it > 0 } ?: parsed?.first
        if (actualSeason != season) return@mapNotNull null
        val actualEpisode = result.providerEpisodeNumber?.takeIf { it > 0 } ?: parsed?.second
            ?: return@mapNotNull null
        val end = result.providerEpisodeEnd?.takeIf { it > 0 }
        if (end != null && end != actualEpisode) return@mapNotNull null
        actualEpisode
    }.distinct().sorted()

    private fun parseReleaseEpisode(release: String): Pair<Int, Int?>? {
        val patterns = listOf(
            Regex("(?i)\\\\bS0*(\\\\d{1,2})[ ._-]*E0*(\\\\d{1,3})\\\\b"),
            Regex("(?i)\\\\b(\\\\d{1,2})x0*(\\\\d{1,3})\\\\b"),
            Regex("(?i)\\\\bSeason\\\\s*0*(\\\\d{1,2})\\\\s*(?:Episode|Ep)\\\\s*0*(\\\\d{1,3})\\\\b")
        )
        patterns.forEach { pattern ->
            val match = pattern.find(release) ?: return@forEach
            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (season > 0 && episode > 0) return season to episode
        }
        val seasonOnly = Regex("(?i)\\\\b(?:Season|S)\\\\s*0*(\\\\d{1,2})\\\\b").find(release)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        return seasonOnly?.let { it to null }
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
            Regex("""(?i)(?:\bS0?${season}E0?${episode}\b|\b${season}x0?${episode}\b)""")
        } else null
        val explicitEpisodePattern = Regex(
            """(?i)\bS(\d{1,2})[ ._-]*E(\d{1,3})\b|\b(\d{1,2})x(\d{1,3})\b"""
        )
        return results.filter { result ->
            if (SubtitleQueryCleaner.normalizeLanguage(result.language) !in wanted) return@filter false
            if (season == null || episode == null) return@filter true
            // Provider metadata is authoritative: never show a known neighbouring episode
            // or a whole-season archive as though it were a single-episode subtitle.
            if (result.fullSeasonPack) return@filter false
            if (result.providerSeasonNumber != null && result.providerSeasonNumber != season) return@filter false
            if (result.providerEpisodeNumber != null && result.providerEpisodeNumber != episode) return@filter false
            if (result.providerEpisodeEnd != null &&
                (result.providerEpisodeNumber == null || result.providerEpisodeEnd != result.providerEpisodeNumber)) return@filter false
            val explicit = explicitEpisodePattern.find(result.release) ?: return@filter true
            val groups = explicit.groupValues
            val actualSeason = (groups[1].ifBlank { groups[3] }).toIntOrNull()
            val actualEpisode = (groups[2].ifBlank { groups[4] }).toIntOrNull()
            // Keep unnumbered broad-search hits as fallbacks, but reject explicit neighbouring episodes.
            actualSeason == season && actualEpisode == episode
        }.sortedWith(compareBy<OnlineSubtitle>(
            { if (episodePattern == null ||
                (it.providerSeasonNumber == season && it.providerEpisodeNumber == episode) ||
                episodePattern.containsMatchIn(it.release)) 0 else 1 },
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
