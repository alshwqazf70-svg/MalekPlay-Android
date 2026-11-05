package com.malekplay.player

import java.io.IOException

/** Validates decoded URI segments before they reach SMBJ or SSHJ. */
internal object NetworkPathPolicy {
    fun validateSegments(segments: List<String>) {
        if (segments.isEmpty() || segments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." ||
                    segment.contains('/') || segment.contains('\\') ||
                    segment.any { it.isISOControl() }
            }
        ) {
            throw IOException("مسار ملف الشبكة يحتوي على مكوّن غير آمن")
        }
    }
}
