package com.malekplay.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.malekplay.data.local.entity.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreScanner(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun scan(): List<MediaItemEntity> = withContext(Dispatchers.IO) {
        buildList {
            addAll(scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
            addAll(scanCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false))
        }
    }

    private fun scanCollection(collection: Uri, isVideo: Boolean): List<MediaItemEntity> {
        val baseColumns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val folderColumns = baseColumns + arrayOf("bucket_id", "bucket_display_name")
        val result = mutableListOf<MediaItemEntity>()

        // Some providers expose bucket metadata only for video/images. Fall back to the
        // portable media columns instead of failing the entire library scan.
        val cursor = try {
            resolver.query(collection, folderColumns, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        } catch (_: IllegalArgumentException) {
            resolver.query(collection, baseColumns, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        } ?: return result

        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationIndex = it.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val sizeIndex = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIndex = it.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val mimeIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val bucketIdIndex = it.getColumnIndex("bucket_id")
            val bucketNameIndex = it.getColumnIndex("bucket_display_name")

            while (it.moveToNext()) {
                val itemUri = Uri.withAppendedPath(collection, it.getLong(idIndex).toString())
                val fallbackMime = if (isVideo) "video/*" else "audio/*"
                val bucketName = if (bucketNameIndex >= 0) {
                    it.getString(bucketNameIndex)?.takeIf(String::isNotBlank)
                } else null
                val bucketId = if (bucketIdIndex >= 0) {
                    it.getString(bucketIdIndex)?.takeIf(String::isNotBlank)
                } else null

                result += MediaItemEntity(
                    mediaId = itemUri.toString(),
                    title = it.getString(nameIndex) ?: "بدون اسم",
                    duration = if (durationIndex >= 0) it.getLong(durationIndex) else 0L,
                    uri = itemUri.toString(),
                    mimeType = if (mimeIndex >= 0) it.getString(mimeIndex) ?: fallbackMime else fallbackMime,
                    sizeBytes = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L,
                    dateAdded = if (dateIndex >= 0) it.getLong(dateIndex) * 1000L else 0L,
                    isVideo = isVideo,
                    bucketId = bucketId ?: "0",
                    bucketName = bucketName ?: "مجلدات أخرى"
                )
            }
        }
        return result
    }
}
