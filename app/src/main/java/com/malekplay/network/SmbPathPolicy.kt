package com.malekplay.network

/**
 * Converts the browser's share-qualified SMB paths into paths relative to the
 * selected share. This avoids accidentally asking SMBJ for "share/share/folder".
 */
internal object SmbPathPolicy {
    fun resolve(rootPath: String, requestedPath: String): Pair<String, String> {
        val rootSegments = rootPath.trim('/').split('/').filter { it.isNotBlank() }
        val shareName = rootSegments.firstOrNull().orEmpty()
        require(shareName.isNotBlank()) {
            "أدخل اسم مشاركة SMB في المسار، مثل media/ أو media/videos"
        }

        val requestedSegments = requestedPath.trim('/').split('/').filter { it.isNotBlank() }
        require(requestedSegments.isEmpty() || requestedSegments.first() == shareName) {
            "المسار المطلوب لا يطابق مشاركة SMB المحفوظة"
        }
        val relativePath = requestedSegments.drop(1)
        require(relativePath.none { segment ->
            segment == "." || segment == ".." || segment.any { it.isISOControl() }
        }) { "مسار مجلد SMB غير صالح" }

        return shareName to relativePath.joinToString("/")
    }
}
