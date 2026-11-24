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

    fun wyzieSubtitle(
        item: JSONObject,
        season: Int?,
        episode: Int?
    ): OnlineSubtitle? {
        val languageCode = language(item.optString("language"))
        if (languageCode !in setOf("ar", "en")) return null
        val formatName = format(item.optString("format")) ?: return null
        val downloadUrl = item.optString("url").takeIf { isSafeSubtitleDownloadUrl(it, "sub.wyzie.io") }
            ?: return null
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val match = item.optJSONObject("match")
        val reasons = match?.optJSONArray("reasons")
        for (index in 0 until (reasons?.length() ?: 0)) {
            val reason = reasons?.optString(index)
            if (reason == "wrong_episode" || reason == "wrong_season") return null
        }
        val rank = item.optJSONObject("rank")
        val score = match?.optInt("score", -1)?.takeIf { it >= 0 }
            ?: rank?.optInt("score", -1)?.takeIf { it >= 0 }
        return OnlineSubtitle(
            fileId = id.toLongOrNull() ?: 0L,
            language = languageCode,
            release = release(
                item.optString("release").ifBlank { item.optString("fileName") },
                item.optString("media", "Subtitle")
            ),
            downloads = downloadCount(item.optInt("downloadCount")),
            format = formatName,
            sourceId = id,
            provider = "wyzie",
            downloadUrl = downloadUrl,
            matchScore = score,
            seasonNumber = season,
            episodeNumber = episode
        )
    }

    fun subDlSubtitle(
        url: String,
        releaseName: String,
        languageCode: String,
        formatName: String,
        downloads: Int,
        fallbackTitle: String
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
            provider = "subdl"
        )
    }
}
