package com.malekplay.ui.videos

import java.util.Locale

/** Conservative filename/folder classifier: ambiguous videos remain unclassified. */
internal enum class VideoKind { SERIES, MOVIE, UNCLASSIFIED }

internal data class EpisodeSortKey(
    val seriesKey: String,
    val season: Int,
    val episode: Int
)

internal object VideoKindClassifier {
    private val episodePatterns = listOf(
        Regex("""(?i)s(\d{1,2})\s*e(\d{1,3})"""),
        Regex("""(?i)(\d{1,2})\s*x\s*(\d{1,3})"""),
        Regex("""(?i)season\s*(\d{1,2})[ ._-]*(?:episode|ep)\s*(\d{1,3})"""),
        Regex("""(?i)(?:episode|ep)\s*[-_. ]?(\d{1,3})"""),
        Regex("""الحلقة\s*([0-9]{1,3})""")
    )

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '٠', '۰' -> '0'
                    '١', '۱' -> '1'
                    '٢', '۲' -> '2'
                    '٣', '۳' -> '3'
                    '٤', '۴' -> '4'
                    '٥', '۵' -> '5'
                    '٦', '۶' -> '6'
                    '٧', '۷' -> '7'
                    '٨', '۸' -> '8'
                    '٩', '۹' -> '9'
                    else -> char
                }
            )
        }
    }

    /** Extracts a stable show/season/episode key from common Latin and Arabic filenames. */
    fun episodeSortKey(title: String, folderName: String = "", folderPath: String = ""): EpisodeSortKey? {
        val normalized = normalizeDigits(title)
        val match = episodePatterns.firstNotNullOfOrNull { it.find(normalized) } ?: return null
        val numbers = match.groupValues.drop(1).filter { it.isNotBlank() }
        val season = if (numbers.size == 1) 1 else numbers[0].toIntOrNull() ?: 1
        val episode = numbers.lastOrNull()?.toIntOrNull() ?: return null
        val rawSeries = normalized.removeRange(match.range)
            .replace(Regex("""(?i)\b(1080p|720p|2160p|480p|4k|hevc|x264|x265|web[ ._-]?dl|bluray)\b"""), " ")
            .replace(Regex("""(?i)\.(mkv|mp4|avi|mov|webm|m4v|ts|3gp)$"""), " ")
            .replace(Regex("""[._\-\[\]()]+"""), " ")
            .trim()
            .ifBlank { folderName.ifBlank { folderPath.substringBeforeLast('/') } }
        val seriesKey = rawSeries.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
        return EpisodeSortKey(seriesKey, season.coerceAtLeast(1), episode.coerceAtLeast(0))
    }

    private val seriesPatterns = listOf(
        Regex("""(?i)\bs\d{1,2}\s*e\d{1,3}\b"""),
        Regex("""(?i)\b\d{1,2}\s*x\s*\d{1,3}\b"""),
        Regex("""(?i)\b(?:season|episode|ep)\s*[-_. ]?\d{1,3}\b"""),
        Regex("""(?i)\b(?:tv\s*series|web\s*series|series)\b"""),
        Regex("""الحلقة\s*\d*|الموسم\s*\d*|مسلسل""")
    )
    private val moviePatterns = listOf(
        Regex("""(?i)\b(?:movie|film|feature\s*film)\b"""),
        Regex("""فيلم|أفلام|سينمائي""")
    )

    fun classify(title: String, folderName: String = "", folderPath: String = ""): VideoKind {
        val source = normalizeDigits("$title $folderName $folderPath")
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('-', ' ')
        return when {
            seriesPatterns.any { it.containsMatchIn(source) } -> VideoKind.SERIES
            moviePatterns.any { it.containsMatchIn(source) } -> VideoKind.MOVIE
            else -> VideoKind.UNCLASSIFIED
        }
    }
}
