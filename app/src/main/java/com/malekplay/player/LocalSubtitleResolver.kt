package com.malekplay.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import com.malekplay.domain.model.MediaItem as DomainMedia
import java.util.Locale

/**
 * Finds a subtitle beside a video in the same MediaStore folder.
 * Matching is deliberately conservative: exact video basename or a language
 * suffix (Movie.ar.srt / Movie.en.srt), never an unrelated file in Downloads.
 */
internal object LocalSubtitleResolver {
    data class Match(val uri: Uri, val mimeType: String)
    private val supportedExtensions = setOf("srt", "ass", "ssa", "vtt")

    fun findMatchingSubtitle(context: Context, video: DomainMedia): Match? {
        if (!video.isVideo || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val folder = video.folderPath.trim().trim('/')
        if (folder.isBlank() || folder.startsWith("bucket:")) return null

        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("$folder/")
        val videoBase = basename(video.title).lowercase(Locale.ROOT)
        if (videoBase.isBlank()) return null

        return runCatching {
            context.contentResolver.query(
                collection, projection, selection, selectionArgs,
                "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                var bestId: Long? = null
                var bestExtension: String? = null
                var bestScore = Int.MAX_VALUE
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn).orEmpty()
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (extension !in supportedExtensions) continue
                    val subtitleBase = basename(name).lowercase(Locale.ROOT)
                    val score = when {
                        subtitleBase == videoBase -> 0
                        subtitleBase.startsWith("$videoBase.") -> 1
                        subtitleBase.startsWith("$videoBase ") -> 2
                        else -> continue
                    } + extensionPriority(extension)
                    if (score < bestScore) {
                        bestScore = score
                        bestId = cursor.getLong(idColumn)
                        bestExtension = extension
                    }
                }
                bestId?.let { id ->
                    val uri = ContentUris.withAppendedId(collection, id)
                    val mime = when (bestExtension) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        "vtt" -> MimeTypes.TEXT_VTT
                        else -> return@let null
                    }
                    Match(uri, mime)
                }
            }
        }.getOrNull()
    }

    private fun basename(name: String): String =
        name.substringAfterLast('/').substringBeforeLast('.', name).trim()

    private fun extensionPriority(extension: String): Int = when (extension) {
        "srt" -> 0
        "ass" -> 1
        "ssa" -> 2
        "vtt" -> 3
        else -> 10
    }

    internal fun isSupportedSubtitleName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in supportedExtensions
}
