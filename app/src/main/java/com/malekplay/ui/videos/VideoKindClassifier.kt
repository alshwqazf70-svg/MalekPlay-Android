package com.malekplay.ui.videos

import java.util.Locale

internal enum class VideoKind { SERIES, MOVIE, UNCLASSIFIED }
internal data class EpisodeSortKey(val seriesKey: String, val season: Int, val episode: Int)
internal data class VideoFileDescriptor(val id: String, val title: String, val folderName: String = "", val folderPath: String = "")
internal data class ClassifiedVideo(val kind: VideoKind, val episode: EpisodeSortKey? = null)

internal object VideoKindClassifier {
    private val episodePatterns = listOf(
        Regex("""(?i)s(\d{1,2})[ ._-]*e(\d{1,3})"""),
        Regex("""(?i)(\d{1,2})\s*x\s*(\d{1,3})"""),
        Regex("""(?i)season\s*(\d{1,2})[ ._-]*(?:episode|ep)\s*(\d{1,3})"""),
        Regex("""(?i)(?:episode|ep)\s*[-_. ]?(\d{1,3})"""),
        Regex("""الحلقة\s*([0-9]{1,3})""")
    )
    private val seriesPatterns = listOf(
        Regex("""(?i)\bs\d{1,2}[ ._-]*e\d{1,3}\b"""),
        Regex("""(?i)\b\d{1,2}\s*x\s*\d{1,3}\b"""),
        Regex("""(?i)\b(?:season|episode|ep)\s*[-_. ]?\d{1,3}\b"""),
        Regex("""(?i)\b(?:tv\s*series|web\s*series|series)\b"""),
        Regex("""الحلقة\s*\d*|الموسم\s*\d*|مسلسل|مسلسلات""")
    )
    private val moviePatterns = listOf(
        Regex("""(?i)\b(?:movie|film|feature\s*film)\b"""),
        Regex("""فيلم|أفلام|سينمائي""")
    )
    private val seasonPattern = Regex("""(?i)\bseason\s*(\d{1,2})\b|الموسم\s*(\d{1,2})""")
    private val trailingEpisodePattern = Regex("""(?i)^(.+?)[ ._-]+(?:episode|ep|e)?\s*([0-9]{1,2})$""")
    private val genericFolders = setOf("download", "downloads", "movies", "videos", "video", "dcim", "camera", "مسلسلات", "أفلام", "افلام", "فيديو", "الفيديوهات")

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { c ->
            append(when (c) {
                '٠', '۰' -> '0'; '١', '۱' -> '1'; '٢', '۲' -> '2'
                '٣', '۳' -> '3'; '٤', '۴' -> '4'; '٥', '۵' -> '5'
                '٦', '۶' -> '6'; '٧', '۷' -> '7'; '٨', '۸' -> '8'
                '٩', '۹' -> '9'; else -> c
            })
        }
    }

    private fun normalizedKey(value: String): String =
        normalizeDigits(value).lowercase(Locale.ROOT)
            .replace(Regex("""\.(mkv|mp4|avi|mov|webm|m4v|ts|3gp)$"""), "")
            .replace(Regex("""[._\-\[\]()]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    fun classify(title: String, folderName: String = "", folderPath: String = ""): VideoKind {
        val source = normalizeDigits("$title $folderName $folderPath")
            .replace('_', ' ').replace('.', ' ').replace('-', ' ')
        return when {
            seriesPatterns.any { it.containsMatchIn(source) } -> VideoKind.SERIES
            moviePatterns.any { it.containsMatchIn(source) } -> VideoKind.MOVIE
            else -> VideoKind.UNCLASSIFIED
        }
    }

    fun episodeSortKey(title: String, folderName: String = "", folderPath: String = ""): EpisodeSortKey? {
        val normalized = normalizeDigits(title)
        val match = episodePatterns.firstNotNullOfOrNull { it.find(normalized) } ?: return null
        val numbers = match.groupValues.drop(1).filter(String::isNotBlank)
        val folderSeason = seasonPattern.find(normalizeDigits("$folderName $folderPath"))
            ?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.toIntOrNull()
        val season = if (numbers.size == 1) folderSeason ?: 1 else numbers.firstOrNull()?.toIntOrNull() ?: folderSeason ?: 1
        val episode = numbers.lastOrNull()?.toIntOrNull() ?: return null
        val rawSeries = normalized.substring(0, match.range.first)
            .replace(Regex("""(?i)\b(1080p|720p|2160p|480p|4k|hevc|x264|x265|web[ ._-]?dl|bluray)\b"""), " ")
            .replace(Regex("""[._\-\[\]()]+"""), " ").trim()
            .ifBlank {
                val namedFolder = folderName.takeIf {
                    it.isNotBlank() && seasonPattern.find(normalizeDigits(it)) == null && normalizedKey(it) !in genericFolders
                }
                namedFolder ?: folderPath.split('/').filter(String::isNotBlank)
                    .lastOrNull { seasonPattern.find(normalizeDigits(it)) == null }.orEmpty()
            }
        val key = normalizedKey(rawSeries)
        if (key.isBlank()) return null
        return EpisodeSortKey(key, season.coerceAtLeast(1), episode.coerceAtLeast(0))
    }

    /** Classifies a complete snapshot so numbered sibling files can form an episode set. */
    fun classifyAll(files: List<VideoFileDescriptor>): Map<String, ClassifiedVideo> {
        val result = files.associate { file ->
            val kind = classify(file.title, file.folderName, file.folderPath)
            file.id to ClassifiedVideo(kind, episodeSortKey(file.title, file.folderName, file.folderPath))
        }.toMutableMap()

        val candidates = files.mapNotNull { file ->
            val kind = result[file.id]?.kind
            if (kind == VideoKind.MOVIE || (kind == VideoKind.SERIES && result[file.id]?.episode != null)) return@mapNotNull null
            val title = normalizedKey(file.title)
            val match = trailingEpisodePattern.matchEntire(title) ?: return@mapNotNull null
            val prefix = normalizedKey(match.groupValues[1])
            val number = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (prefix.isBlank() || number !in 1..99) return@mapNotNull null
            val folder = normalizedKey(file.folderPath.ifBlank { file.folderName })
            Triple(file, "$folder|$prefix", number)
        }
        candidates.groupBy { it.second }.values.forEach { siblings ->
            if (siblings.size < 2 || siblings.map { it.third }.distinct().size < 2) return@forEach
            siblings.forEach { (file, _, episodeNumber) ->
                val season = seasonPattern.find(normalizeDigits(file.folderName + " " + file.folderPath))
                    ?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.toIntOrNull() ?: 1
                val prefix = trailingEpisodePattern.matchEntire(normalizedKey(file.title))?.groupValues?.get(1).orEmpty()
                result[file.id] = ClassifiedVideo(VideoKind.SERIES, EpisodeSortKey(normalizedKey(prefix), season, episodeNumber))
            }
        }
        return result
    }
}
