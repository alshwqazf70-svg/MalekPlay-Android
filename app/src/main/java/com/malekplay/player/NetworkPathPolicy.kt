package com.malekplay.player

import android.net.Uri
import java.io.IOException

/** Validates decoded URI segments before they reach SMBJ or SSHJ. */
internal object NetworkPathPolicy {
    const val MAX_SEGMENTS = 128
    const val MAX_SEGMENT_LENGTH = 255
    const val MAX_PATH_LENGTH = 4096

    fun validateSegments(segments: List<String>) {
        val totalLength = segments.sumOf { it.length } + (segments.size - 1).coerceAtLeast(0)
        if (segments.isEmpty() ||
            segments.size > MAX_SEGMENTS ||
            totalLength > MAX_PATH_LENGTH ||
            segments.any { segment ->
                segment.isEmpty() ||
                    segment.length > MAX_SEGMENT_LENGTH ||
                    segment == "." ||
                    segment == ".." ||
                    segment.contains('/') ||
                    segment.contains('\\') ||
                    segment.any { it.isISOControl() }
            }
        ) {
            throw IOException("مسار ملف الشبكة يحتوي على مكوّن غير آمن أو يتجاوز الحدود المسموحة")
        }
    }
}

/**
 * Accepts only the internal credential-free URI shape:
 * scheme://profile-id/path/to/file
 */
internal fun validateNetworkPlaybackUri(uri: Uri): String {
    val profileId = uri.host
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        ?: throw IOException("معرّف مصدر الشبكة غير صالح")

    if (uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) {
        throw IOException("رابط تشغيل الشبكة يحتوي على بيانات أو مكونات غير مسموحة")
    }

    NetworkPathPolicy.validateSegments(uri.pathSegments)
    return profileId
}
