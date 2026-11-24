package com.malekplay.network

import java.util.Locale
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
