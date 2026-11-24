package com.malekplay.network

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Pure normalization shared by provider payload adapters and JVM regression tests. */
internal object SubtitleProviderResultParser {
    private val supportedFormats = setOf("srt", "ass", "ssa", "vtt")

    fun language(raw: String): String = SubtitleQueryCleaner.normalizeLanguage(raw)

    fun format(raw: String): String? = raw.trim().lowercase(Locale.ROOT)
        .takeIf { it in supportedFormats }

    fun release(primary: String, fallback: String): String =
        primary.trim().takeIf { it.isNotBlank() } ?: fallback.trim().ifBlank { "Subtitle" }

    fun downloadCount(value: Int): Int = value.coerceAtLeast(0)

    /** Confirms that SubDL's subtitle array belongs to the requested work, not just the first fuzzy hit. */
    fun subDlHasExactTitleMatch(results: JSONArray, title: String, year: Int?, mediaType: String): Boolean {
        val wanted = normalizeIdentityTitle(title)
        if (wanted.isBlank()) return false
        return (0 until results.length()).any { index ->
            val item = results.optJSONObject(index) ?: return@any false
            val candidateType = item.optString("type").trim().lowercase(Locale.ROOT)
            val candidateYear = item.optInt("year").takeIf { it > 0 }
            val names = listOf(item.optString("name"), item.optString("original_name"))
            candidateType.isBlank().or(candidateType == mediaType.lowercase(Locale.ROOT)) &&
                (year == null || candidateYear == null || candidateYear == year) &&
                names.any { normalizeIdentityTitle(it) == wanted }
        }
    }

    /** Returns an IMDb identity only when SubDL confirms the exact normalized title/year/type. */
    fun subDlImdbId(results: JSONArray, title: String, year: Int?, mediaType: String): String? {
        val wanted = normalizeIdentityTitle(title)
        if (wanted.isBlank()) return null
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val candidateType = item.optString("type").trim().lowercase(Locale.ROOT)
            if (candidateType.isNotBlank() && candidateType != mediaType.lowercase(Locale.ROOT)) continue
            val candidateYear = item.optInt("year").takeIf { it > 0 }
            if (year != null && candidateYear != null && candidateYear != year) continue
            val names = listOf(item.optString("name"), item.optString("original_name"))
            if (names.none { normalizeIdentityTitle(it) == wanted }) continue
            val id = item.optString("imdb_id")
            if (id.matches(Regex("tt\\d{5,12}"))) return id
        }
        return null
    }

    private fun normalizeIdentityTitle(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    fun subDlSubtitle(
        url: String,
        releaseName: String,
        languageCode: String,
        formatName: String,
        downloads: Int,
        fallbackTitle: String,
        providerSeason: Int? = null,
        providerEpisode: Int? = null,
        providerEpisodeEnd: Int? = null,
        fullSeasonPack: Boolean = false
    ): OnlineSubtitle? {
        val source = url.trim()
        val language = language(languageCode)
        if (source.isBlank() || language !in setOf("ar", "en")) return null
        val release = release(releaseName, fallbackTitle)
        val extension = format(formatName)
            ?: format(release.substringAfterLast('.', "srt"))
            ?: "srt"
        return OnlineSubtitle(
            fileId = 0L,
            language = language,
            release = release,
            downloads = downloadCount(downloads),
            format = extension,
            sourceId = source,
            provider = "subdl",
            providerSeasonNumber = providerSeason,
            providerEpisodeNumber = providerEpisode,
            providerEpisodeEnd = providerEpisodeEnd,
            fullSeasonPack = fullSeasonPack
        )
    }
}
