package com.malekplay.network

import java.util.Locale

/** Pure normalization shared by provider payload adapters and JVM regression tests. */
internal object SubtitleProviderResultParser {
    private val supportedFormats = setOf("srt", "ass", "ssa", "vtt")

    fun language(raw: String): String = SubtitleQueryCleaner.normalizeLanguage(raw)

    fun format(raw: String): String? = raw.trim().lowercase(Locale.ROOT)
        .takeIf { it in supportedFormats }

    fun release(primary: String, fallback: String): String =
        primary.trim().takeIf { it.isNotBlank() } ?: fallback.trim().ifBlank { "Subtitle" }

    fun downloadCount(value: Int): Int = value.coerceAtLeast(0)
}
