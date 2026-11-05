package com.malekplay.player

import android.net.Uri
import com.malekplay.network.NetworkEntry
import com.malekplay.network.NetworkProfile
import com.malekplay.network.NetworkProtocol

/**
 * Builds opaque Media3 URIs for remote SMB/SFTP files.
 * Profile identifiers, rather than credentials or server addresses, are embedded
 * in the URI; secrets remain in the encrypted credential vault.
 */
internal object NetworkPlaybackUriBuilder {
    fun build(profile: NetworkProfile, entry: NetworkEntry): Uri {
        require(!entry.isDirectory) { "لا يمكن تشغيل مجلد" }
        val scheme = when (profile.protocol) {
            NetworkProtocol.SMB -> "maleksmb"
            NetworkProtocol.SFTP -> "maleksftp"
            NetworkProtocol.WEBDAV -> throw IllegalArgumentException("WebDAV uses HTTPS media URLs")
        }
        val segments = entry.path.split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "مسار الملف البعيد فارغ" }
        require(segments.none { segment ->
            segment == "." || segment == ".." || segment.any { it.isISOControl() }
        }) { "مسار الملف البعيد يحتوي على مقطع غير آمن" }
        if (profile.protocol == NetworkProtocol.SMB) {
            require(segments.size >= 2) { "مسار SMB يجب أن يتضمن المشاركة واسم الملف" }
        }
        return Uri.Builder()
            .scheme(scheme)
            .authority(profile.id)
            .apply { segments.forEach { appendPath(it) } }
            .build()
    }
}
