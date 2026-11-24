package com.malekplay.network

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

data class TvEpisodeGuide(
    val showName: String,
    val showYear: Int?,
    val seasons: List<TvEpisodeGuideSeason>
)

data class TvEpisodeGuideSeason(
    val number: Int,
    val name: String,
    val episodes: List<Int>
)

internal data class TvMazeShowIdentity(
    val id: Long,
    val name: String,
    val year: Int?
)

/** Strict parsing for TVmaze's public show-search and episode-list responses. */
internal object TvMazeEpisodeGuideParser {
    fun selectExactShow(results: JSONArray, requestedTitle: String, requestedYear: Int?): TvMazeShowIdentity? {
        val wanted = normalizeTitle(requestedTitle)
        if (wanted.isBlank()) return null
        val matches = buildList {
            for (index in 0 until results.length()) {
                val show = results.optJSONObject(index)?.optJSONObject("show") ?: continue
                val id = show.optLong("id", -1L)
                val name = show.optString("name").trim()
                val year = show.optString("premiered").take(4).toIntOrNull()
                if (id <= 0L || name.isBlank() || normalizeTitle(name) != wanted) continue
                if (requestedYear != null && year != requestedYear) continue
                add(TvMazeShowIdentity(id, name, year))
            }
        }.distinctBy { it.id }
        // Never silently choose between remakes or same-name shows.
        return matches.singleOrNull()
    }

    fun parseEpisodes(show: TvMazeShowIdentity, episodes: JSONArray): TvEpisodeGuide? {
        val grouped = sortedMapOf<Int, MutableSet<Int>>()
        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue
            val season = episode.optInt("season", -1)
            val number = episode.optInt("number", -1)
            // Season zero is specials; missing/invalid episode numbers are not selectable.
            if (season <= 0 || number <= 0) continue
            grouped.getOrPut(season) { sortedSetOf() }.add(number)
        }
        val seasons = grouped.map { (number, numbers) ->
            TvEpisodeGuideSeason(number, "Season $number", numbers.toList())
        }
        return seasons.takeIf { it.isNotEmpty() }?.let {
            TvEpisodeGuide(show.name, show.year, it)
        }
    }

    private fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}

/**
 * Public TVmaze catalog adapter. It is intentionally separate from subtitle providers:
 * catalog failures never change SubDL/SubF2M search order or subtitle results.
 */
internal class TvMazeEpisodeGuideProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val cache = object : LinkedHashMap<String, TvEpisodeGuide>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TvEpisodeGuide>?): Boolean =
            size > 16
    }

    @Synchronized
    fun search(title: String, year: Int?): TvEpisodeGuide? {
        val cleanTitle = title.trim().take(150)
        if (cleanTitle.isBlank()) return null
        val cacheKey = cleanTitle.lowercase(Locale.ROOT) + "|" + (year ?: 0)
        cache[cacheKey]?.let { return it }

        val searchUrl = HttpUrl.Builder().scheme("https").host("api.tvmaze.com")
            .addPathSegments("search/shows").addQueryParameter("q", cleanTitle).build()
        val searchResults = requestArray(searchUrl) ?: return null
        val show = TvMazeEpisodeGuideParser.selectExactShow(searchResults, cleanTitle, year) ?: return null

        val episodesUrl = HttpUrl.Builder().scheme("https").host("api.tvmaze.com")
            .addPathSegment("shows").addPathSegment(show.id.toString())
            .addPathSegment("episodes").build()
        val episodes = requestArray(episodesUrl) ?: return null
        val guide = TvMazeEpisodeGuideParser.parseEpisodes(show, episodes) ?: return null
        cache[cacheKey] = guide
        return guide
    }

    private fun requestArray(url: HttpUrl): JSONArray? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "MalekPlayer/2.0 (TVmaze episode guide)")
            .header("Accept", "application/json")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (response.code == 429) throw IOException("TVmaze rate limit reached; retry later")
            if (!response.isSuccessful) throw IOException("TVmaze request failed (HTTP ${response.code})")
            val body = response.body ?: return null
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw IOException("TVmaze response exceeds the allowed size")
            }
            val text = body.string()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
                throw IOException("TVmaze response exceeds the allowed size")
            }
            return JSONArray(text)
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
