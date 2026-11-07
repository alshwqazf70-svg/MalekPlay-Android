package com.malekplay.data.scanner

/**
 * Chooses the best available human-readable name for a media row.
 *
 * Some downloaders write ID3 title frames with an unsupported charset,
 * causing MediaStore to expose literal question marks. The original filename
 * may still be intact. Text already lost at the source cannot be reconstructed.
 */
internal fun chooseMediaDisplayName(metadataTitle: String, fileName: String): String {
    val metadata = metadataTitle.trim()
    val rawFileName = fileName.trim()
    val fallback = rawFileName.substringBeforeLast('.', rawFileName).trim()

    return when {
        metadata.isNotEmpty() && !isPlaceholderMediaTitle(metadata) &&
            !isLikelyCorruptedMediaText(metadata) -> metadata
        fallback.isNotEmpty() && !isLikelyCorruptedMediaText(fallback) -> fallback
        metadata.isNotEmpty() -> metadata
        fallback.isNotEmpty() -> fallback
        else -> "Untitled"
    }
}

internal fun isLikelyCorruptedMediaText(value: String): Boolean {
    if ('\uFFFD' in value) return true
    val questionMarks = value.count { it == '?' }
    return questionMarks >= 2 &&
        questionMarks.toDouble() / value.length.coerceAtLeast(1) >= 0.20
}

/** MediaStore occasionally supplies a generic tag even when the filename is descriptive. */
internal fun isPlaceholderMediaTitle(value: String): Boolean =
    value.trim().lowercase() in setOf("unknown", "untitled", "no title", "(unknown)")
