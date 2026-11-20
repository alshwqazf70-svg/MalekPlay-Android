package com.malekplay.network

import java.util.Locale

data class CleanSubtitleQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
)

/** Pure filename parser shared by the player and every online subtitle provider. */
object SubtitleQueryCleaner {
    private val episodePatterns = listOf(
        Regex("""(?i)(?:^|[ ._\-])S(\d{1,2})[ ._\-]*E(\d{1,3})(?:\b|$)"""),
        Regex("""(?i)(?:^|[ ._\-])(\d{1,2})x(\d{1,3})(?:\b|$)""")
    )
    private val yearPattern = Regex("""(?:^|[ ._()\-])(19\d{2}|20\d{2})(?=$|[ ._()\-])""")
    private val releaseBoundary = Regex(
        """(?i)(?:^|[ ._\-])(?:480p|576p|720p|1080p|1440p|2160p|4k|8k|x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|av1|web[ ._-]?dl|web[ ._-]?rip|blu[ ._-]?ray|brrip|bdrip|hdrip|hdtv|dvdrip|aac(?:\s*\d(?:\s+\d)?(?:ch)?)?|ddp?(?:\s*\d(?:\s+\d)?)?(?:ch)?|eac3|ac3|atmos|dts(?:-hd)?|truehd|proper|repack|limited|internal|extended|remux|hdr10\+?|dolby[ ._-]?vision|\[.*?\])(?=$|[ ._\-])"""
    )
    private val knownExtension = Regex("""(?i)\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|mpeg|mpg|3gp|ogv)$""")
    private val trailingReleaseGroup = Regex("""-\s*[A-Z0-9]{2,14}$""")

    fun clean(raw: String): CleanSubtitleQuery {
        val segment = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        var input = knownExtension.replace(segment, "")
            .replace(Regex("""[._]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (input.isBlank()) return CleanSubtitleQuery("")
        var season: Int? = null
        var episode: Int? = null
        for (pattern in episodePatterns) {
            val match = pattern.find(input) ?: continue
            season = match.groupValues[1].toIntOrNull()
            episode = match.groupValues[2].toIntOrNull()
            input = input.substring(0, match.range.first).trim()
            break
        }
        val yearMatch = yearPattern.find(input)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (yearMatch != null) {
            input = (input.substring(0, yearMatch.range.first) +
                input.substring(yearMatch.range.last + 1)).trim()
        }
        val release = releaseBoundary.find(input)
        if (release != null) input = input.substring(0, release.range.first).trim()
        input = trailingReleaseGroup.replace(input, "")
            .replace(Regex("""\s*[-–—]\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '_', '-', '[', ']', '(', ')')
        return CleanSubtitleQuery(input, year, season, episode)
    }

    fun normalizeLanguage(code: String): String = when (code.trim().lowercase(Locale.ROOT)) {
        "ar", "ara", "arabic" -> "ar"
        "en", "eng", "english" -> "en"
        else -> code.trim().lowercase(Locale.ROOT)
    }
}
