package com.malekplay.network

/**
 * Converts browser paths (which include the SMB share name) into the
 * share-relative path expected by SMBJ.
 */
object SmbPathPolicy {
    fun resolve(rootPath: String, requestedPath: String): Pair<String, String> {
        val rootSegments = normalize(rootPath)
        require(rootSegments.isNotEmpty()) { "اسم مشاركة SMB غير محدد" }
        val shareName = rootSegments.first()
        val requestedSegments = normalize(requestedPath.ifBlank { rootSegments.joinToString("/") })
        val relativeSegments = if (requestedSegments.firstOrNull() == shareName) {
            requestedSegments.drop(1)
        } else {
            requestedSegments
        }
        return shareName to relativeSegments.joinToString("/")
    }

    private fun normalize(path: String): List<String> {
        require(path.length <= MAX_PATH_LENGTH) { "مسار SMB طويل جداً" }
        val segments = path.split('/').filter { it.isNotBlank() }
        val totalLength = segments.sumOf { it.length } + (segments.size - 1).coerceAtLeast(0)
        require(
            segments.size <= MAX_SEGMENTS &&
                totalLength <= MAX_PATH_LENGTH &&
                segments.all { segment ->
                    segment.length <= MAX_SEGMENT_LENGTH &&
                        segment != "." && segment != ".." &&
                        '\\' !in segment &&
                        segment.none { it.isISOControl() }
                }
        ) { "مسار SMB يحتوي على مكونات غير آمنة أو يتجاوز الحدود المسموحة" }
        return segments
    }

    private const val MAX_SEGMENTS = 128
    private const val MAX_SEGMENT_LENGTH = 255
    private const val MAX_PATH_LENGTH = 4096

}
