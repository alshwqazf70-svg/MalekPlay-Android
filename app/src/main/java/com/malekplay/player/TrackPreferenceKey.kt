package com.malekplay.player

import androidx.media3.common.Format

/** Stable media-track identity; unlike a group index it survives track-group reordering. */
internal object TrackPreferenceKey {
    fun from(format: Format): String = fromParts(
        format.id,
        format.language,
        format.label,
        format.sampleMimeType
    )

    internal fun fromParts(id: String?, language: String?, label: String?, mimeType: String?): String =
        listOf(id, language, label, mimeType)
            .joinToString("\u001F") { it.orEmpty().replace("\u001F", "") }
}
