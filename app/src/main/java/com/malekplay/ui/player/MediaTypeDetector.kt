package com.malekplay.ui.player

/** Deterministic media classification shared by the player and unit tests. */
internal object MediaTypeDetector {
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "ts", "m2ts", "mpg", "mpeg", "ogv"
    )
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mid", "midi"
    )

    fun isVideo(mimeType: String?, path: String?, fallback: Boolean): Boolean {
        when {
            mimeType?.startsWith("video/", ignoreCase = true) == true -> return true
            mimeType?.startsWith("audio/", ignoreCase = true) == true -> return false
        }
        val extension = path
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
        return when {
            extension != null && extension in videoExtensions -> true
            extension != null && extension in audioExtensions -> false
            else -> fallback
        }
    }
}
