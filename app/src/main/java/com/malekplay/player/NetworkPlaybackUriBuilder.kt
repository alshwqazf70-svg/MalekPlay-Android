package com.malekplay.player

import android.net.Uri
import com.malekplay.network.NetworkEntry
import com.malekplay.network.NetworkProfile
import com.malekplay.network.NetworkProtocol

/**
 * Builds credential-free Media3 Uris for SMB/SFTP entries.
 *
 * The profile id is the URI authority; the server address and credentials are
 * resolved from private app storage by NetworkMediaDataSource.
 */
object NetworkPlaybackUriBuilder {
    fun build(profile: NetworkProfile, entry: NetworkEntry): Uri {
        require(!entry.isDirectory) { "لا يمكن تشغيل مجلد" }
        require(profile.protocol == NetworkProtocol.SMB || profile.protocol == NetworkProtocol.SFTP) {
            "هذا المنشئ مخصص لمصادر SMB وSFTP"
        }

        val rawSegments = entry.path.split('/').filter { it.isNotBlank() }
        require(rawSegments.isNotEmpty()) { "مسار الملف فارغ" }
        require(rawSegments.none { segment ->
            segment == "." || segment == ".." ||
                segment.contains('\\') ||
                segment.any { it.isISOControl() }
        }) { "مسار الملف غير صالح" }

        if (profile.protocol == NetworkProtocol.SMB) {
            val share = profile.rootPath.trim('/').substringBefore('/')
            require(share.isNotBlank()) { "اسم مشاركة SMB غير محدد" }
            require(rawSegments.size >= 2 && rawSegments.first() == share) {
                "مسار SMB يجب أن يبدأ بالمشاركة المحفوظة ويتضمن اسم الملف"
            }
        }

        return Uri.Builder()
            .scheme(if (profile.protocol == NetworkProtocol.SMB) "maleksmb" else "maleksftp")
            .authority(profile.id)
            .apply { rawSegments.forEach(::appendPath) }
            .build()
    }
}
