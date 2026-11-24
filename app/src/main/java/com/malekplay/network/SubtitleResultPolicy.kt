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

    /** Reject releases that describe a season pack or more than one episode. */
    private fun normalizeEpisodeRelease(release: String): String =
        release.replace(Regex("[._]+"), " ")

    internal fun isSeasonPackRelease(release: String): Boolean = Regex(
        """(?i)\b(?:full\s+season|season\s+pack|all\s+episodes|complete\s+season\s*0*\d+|season\s*0*\d+\s*(?:complete|pack|batch)|s0*\d{1,2}\s*(?:complete|pack))\b"""
    ).containsMatchIn(normalizeEpisodeRelease(release))

    internal fun isMultiEpisodeRange(release: String, season: Int, episode: Int): Boolean {
        val normalized = normalizeEpisodeRelease(release)
        val seasonEpisodeRange = Regex(
            """(?i)\bS0*${season}E0*${episode}\s*(?:-|–|—|\bto\b|\bthrough\b|\band\b|&|,|\+|E)\s*(?:S0*${season})?E?0*\d{1,3}\b"""
        )
        val xEpisodeRange = Regex(
            """(?i)\b0*${season}x0*${episode}\s*(?:-|–|—|\bto\b|\bthrough\b)\s*(?:0*${season}x)?0*\d{1,3}\b"""
        )
        val namedEpisodeRange = Regex(
            """(?i)\bSeason\s*0*${season}\s+Episodes?\s*0*${episode}\s*(?:-|–|—|\bto\b|\bthrough\b)\s*(?:Episode\s*)?0*\d{1,3}\b"""
        )
        return seasonEpisodeRange.containsMatchIn(normalized) ||
            xEpisodeRange.containsMatchIn(normalized) ||
            namedEpisodeRange.containsMatchIn(normalized)
    }

    /** Returns only individually identified episode files for the selected season. */
    fun availableEpisodes(results: List<OnlineSubtitle>, season: Int): List<Int> = results.mapNotNull { result ->
        if (result.fullSeasonPack || isSeasonPackRelease(result.release)) return@mapNotNull null
        val parsed = parseReleaseEpisode(result.release)
        if (parsed?.second != null && parsed.first == season &&
            isMultiEpisodeRange(result.release, season, parsed.second)
        ) return@mapNotNull null
        val providerSeason = result.providerSeasonNumber?.takeIf { it > 0 }
        val actualSeason = providerSeason ?: parsed?.first
        if (actualSeason != season) return@mapNotNull null
        // If the provider's season conflicts with the release tag, do not borrow
        // an episode number from that contradictory tag.
        if (providerSeason != null && parsed?.first != null &&
            parsed.first != providerSeason && result.providerEpisodeNumber == null
        ) return@mapNotNull null
        val actualEpisode = result.providerEpisodeNumber?.takeIf { it > 0 } ?: parsed?.second
            ?: return@mapNotNull null
        val end = result.providerEpisodeEnd?.takeIf { it > 0 }
        if (end != null && end != actualEpisode) return@mapNotNull null
        actualEpisode
    }.distinct().sorted()

    private fun parseReleaseEpisode(release: String): Pair<Int, Int?>? {
        val patterns = listOf(
            Regex("(?i)\\bS0*(\\d{1,2})[ ._-]*E0*(\\d{1,3})\\b"),
            Regex("(?i)\\b(\\d{1,2})x0*(\\d{1,3})\\b"),
            Regex("(?i)\\bSeason\\s*0*(\\d{1,2})\\s*(?:Episode|Ep)\\s*0*(\\d{1,3})\\b")
        )
        patterns.forEach { pattern ->
            val match = pattern.find(release) ?: return@forEach
            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (season > 0 && episode > 0) return season to episode
        }
        val seasonOnly = Regex("(?i)\\b(?:Season|S)\\s*0*(\\d{1,2})\\b").find(release)
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
        return results.filter { result ->
            if (SubtitleQueryCleaner.normalizeLanguage(result.language) !in wanted) return@filter false
            if (season == null || episode == null) return@filter true
            // Provider metadata is authoritative: never show a known neighbouring episode
            // or a whole-season archive as though it were a single-episode subtitle.
            if (result.fullSeasonPack || isSeasonPackRelease(result.release) ||
                isMultiEpisodeRange(result.release, season, episode)
            ) return@filter false
            val providerSeason = result.providerSeasonNumber?.takeIf { it > 0 }
            val providerEpisode = result.providerEpisodeNumber?.takeIf { it > 0 }
            if (providerSeason != null && providerSeason != season) return@filter false
            if (providerEpisode != null && providerEpisode != episode) return@filter false
            if (result.providerEpisodeEnd != null &&
                (providerEpisode == null || result.providerEpisodeEnd != providerEpisode)
            ) return@filter false

            val releaseEpisode = parseReleaseEpisode(result.release)
            if (releaseEpisode != null) {
                // A release that names a different season is not a generic fallback.
                if (providerSeason == null && releaseEpisode.first != season) return@filter false
                // Season-only names do not identify a single episode.
                if (releaseEpisode.second == null && providerEpisode == null) return@filter false
                // Respect explicit episode numbering unless the provider supplied a
                // separate, authoritative episode number.
                if (providerEpisode == null && releaseEpisode.second != null &&
                    releaseEpisode.second != episode
                ) return@filter false
            }
            // Truly unnumbered title-search hits remain a fallback when providers omit tags.
            true
        }.sortedWith(compareBy<OnlineSubtitle>(
            {
                val releaseEpisode = if (season != null && episode != null) {
                    parseReleaseEpisode(it.release)
                } else null
                if (season == null || episode == null ||
                    (it.providerSeasonNumber == season && it.providerEpisodeNumber == episode) ||
                    (releaseEpisode?.first == season && releaseEpisode.second == episode)
                ) 0 else 1
            },
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
