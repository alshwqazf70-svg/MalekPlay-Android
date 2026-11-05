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
        val segments = path.split('/').filter { it.isNotBlank() }
        require(segments.none { segment ->
            segment == "." || segment == ".." ||
                segment.contains('\\') || segment.any { it.isISOControl() }
        }) { "مسار SMB غير صالح" }
        return segments
    }
}
