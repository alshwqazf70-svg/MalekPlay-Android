package com.malekplay.network

/**
 * Normalizes remote paths and rejects dot-segment traversal before a protocol client
 * sends a path to a server. SMB paths are relative to a share; SFTP may be absolute.
 */
internal object NetworkPathPolicy {
    fun normalize(path: String, separator: Char = '/', preserveLeadingSlash: Boolean = true): String {
        require(path.length <= 4096 && !path.contains('\u0000')) { "مسار الشبكة غير صالح" }
        val slashPath = path.replace('\\', '/')
        val absolute = preserveLeadingSlash && slashPath.startsWith('/')
        val segments = slashPath.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) {
            "المسار لا يمكن أن يحتوي على مقاطع رجوع أو انتقال نسبي"
        }
        val normalized = segments.joinToString(separator.toString())
        return if (absolute && normalized.isNotEmpty()) "$separator$normalized" else normalized
    }
}
