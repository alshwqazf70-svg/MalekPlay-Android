package com.malekplay.data.scanner

import android.content.ContentResolver
import android.content.Context
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
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val result = mutableListOf<MediaItemEntity>()
        resolver.query(collection, columns, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val itemUri = Uri.withAppendedPath(collection, cursor.getLong(idIndex).toString())
                val fallbackMime = if (isVideo) "video/*" else "audio/*"
                result += MediaItemEntity(
                    mediaId = itemUri.toString(),
                    title = cursor.getString(nameIndex) ?: "بدون اسم",
                    duration = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L,
                    uri = itemUri.toString(),
                    mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) ?: fallbackMime else fallbackMime,
                    sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                    dateAdded = if (dateIndex >= 0) cursor.getLong(dateIndex) * 1000L else 0L,
                    isVideo = isVideo
                )
            }
        }
        return result
    }
}
