package com.malekplay.ui.videos

/** Conservative filename/folder classifier: ambiguous videos remain unclassified. */
internal enum class VideoKind { SERIES, MOVIE, UNCLASSIFIED }

internal object VideoKindClassifier {
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
        val source = "$title $folderName $folderPath"
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
