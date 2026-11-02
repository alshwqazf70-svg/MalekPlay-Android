package com.malekplay.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.malekplay.data.local.entity.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans audio and video using public MediaStore columns available from API 29.
 * RELATIVE_PATH works for both audio and video; legacy bucket columns do not.
 */
class MediaStoreScanner(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun scan(): List<MediaItemEntity> = withContext(Dispatchers.IO) {
        buildList {
            addAll(scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
            addAll(scanCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false))
        }
    }

    private fun scanCollection(collection: Uri, isVideo: Boolean): List<MediaItemEntity> {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val result = mutableListOf<MediaItemEntity>()
        val cursor = try {
            resolver.query(
                collection,
                columns,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )
        } catch (_: IllegalArgumentException) {
            // Keep the library usable with non-standard MediaStore providers.
            resolver.query(
                collection,
                columns.take(6).toTypedArray(),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )
        } ?: return result

        cursor.use { c ->
            val idIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationIndex = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val sizeIndex = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIndex = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val mimeIndex = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val pathIndex = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

            while (c.moveToNext()) {
                val itemUri = Uri.withAppendedPath(collection, c.getLong(idIndex).toString())
                val relativePath = if (pathIndex >= 0) {
                    c.getString(pathIndex)?.trim('/')?.takeIf { it.isNotBlank() }
                } else null
                val folderName = relativePath
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: "مجلدات أخرى"
                // Use the normalized path as the stable grouping key across audio/video.
                val folderId = relativePath?.lowercase()?.hashCode()?.toString() ?: "0"
                val fallbackMime = if (isVideo) "video/*" else "audio/*"

                result += MediaItemEntity(
                    mediaId = itemUri.toString(),
                    title = c.getString(nameIndex) ?: "بدون اسم",
                    duration = if (durationIndex >= 0) c.getLong(durationIndex) else 0L,
                    uri = itemUri.toString(),
                    mimeType = if (mimeIndex >= 0) c.getString(mimeIndex) ?: fallbackMime else fallbackMime,
                    sizeBytes = if (sizeIndex >= 0) c.getLong(sizeIndex) else 0L,
                    dateAdded = if (dateIndex >= 0) c.getLong(dateIndex) * 1000L else 0L,
                    isVideo = isVideo,
                    bucketId = folderId,
                    bucketName = folderName
                )
            }
        }
        return result
    }
}
