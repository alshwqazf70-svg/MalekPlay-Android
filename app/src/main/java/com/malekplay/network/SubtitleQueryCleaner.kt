package com.malekplay.network

import java.util.Locale

data class CleanSubtitleQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
)

/** Converts common scene/release filenames into a stable provider search query. */
object SubtitleQueryCleaner {
    private val episodePatterns = listOf(
        Regex("""(?i)(?:^|[ ._\-])S(\d{1,2})[ ._\-]*E(\d{1,3})(?:\b|$)"""),
        Regex("""(?i)(?:^|[ ._\-])(\d{1,2})x(\d{1,3})(?:\b|$)""")
    )
    private val yearPattern = Regex("""(?:^|[ ._()\-])(19\d{2}|20\d{2})(?:$|[ ._()\-])""")
    private val releaseBoundary = Regex(
        """(?i)(?:^|[ ._\-])(?:480p|576p|720p|1080p|1440p|2160p|4k|8k|x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|av1|web[ ._-]?dl|web[ ._-]?rip|blu[ ._-]?ray|brrip|bdrip|hdrip|hdtv|dvdrip|aac(?:\d(?:\.\d)?(?:ch)?)?|ddp?(?:\d(?:\.\d)?)?(?:ch)?|atmos|dts(?:-hd)?|truehd|proper|repack|limited|internal|extended|remux|\[.*?\])(?:$|[ ._\-])"""
    )

    fun clean(raw: String): CleanSubtitleQuery {
        val input = raw.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.', raw.substringAfterLast('/'))
            .replace(Regex("""(?i)\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|mpeg|mpg)$"""), "")
            .replace(Regex("""[._]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (input.isBlank()) return CleanSubtitleQuery("")
        var season: Int? = null
        var episode: Int? = null
        var title = input
        for (pattern in episodePatterns) {
            val match = pattern.find(title) ?: continue
            season = match.groupValues[1].toIntOrNull()
            episode = match.groupValues[2].toIntOrNull()
            title = title.removeRange(match.range.first, title.length).trim()
            break
        }
        val yearMatch = yearPattern.find(title)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (yearMatch != null) title = title.removeRange(yearMatch.range.first, yearMatch.range.last + 1).trim()
        val release = releaseBoundary.find(title)
        if (release != null) title = title.substring(0, release.range.first).trim()
        title = title.replace(Regex("""\s*[-–—]\s*$"""), "")
            .replace(Regex("""\s+"""), " ").trim(' ', '.', '_', '-', '[', ']', '(', ')')
        return CleanSubtitleQuery(title, year, season, episode)
    }

    fun normalizeLanguage(code: String): String = when (code.lowercase(Locale.ROOT)) {
        "ar", "ara", "arabic" -> "ar"
        "en", "eng", "english" -> "en"
        else -> code.lowercase(Locale.ROOT)
    }
}
