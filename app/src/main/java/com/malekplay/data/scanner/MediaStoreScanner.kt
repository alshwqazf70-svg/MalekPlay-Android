package com.malekplay.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.malekplay.data.local.entity.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class MediaScanResult(
    val items: List<MediaItemEntity>,
    val videoScanCompleted: Boolean,
    val audioScanCompleted: Boolean,
    val scanTimestamp: Long
)

/**
 * Scans audio and video using MediaStore. A collection is marked complete only when
 * its query succeeds; callers must never prune records from a denied/failed collection.
 */
class MediaStoreScanner(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun scan(): MediaScanResult = withContext(Dispatchers.IO) {
        val timestamp = nextScanTimestamp()
        val videos = scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, timestamp)
        val audio = scanCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false, timestamp)
        MediaScanResult(
            items = videos.items + audio.items,
            videoScanCompleted = videos.completed,
            audioScanCompleted = audio.completed,
            scanTimestamp = timestamp
        )
    }

    private fun nextScanTimestamp(): Long = lastScanTimestamp.updateAndGet {
        maxOf(System.currentTimeMillis(), it + 1L)
    }

    private data class CollectionResult(
        val items: List<MediaItemEntity>,
        val completed: Boolean
    )

    private fun scanCollection(
        collection: Uri,
        isVideo: Boolean,
        scanTimestamp: Long
    ): CollectionResult {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val cursor = try {
            resolver.query(collection, columns, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            try {
                resolver.query(
                    collection,
                    columns.take(6).toTypedArray(),
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )
            } catch (_: Exception) {
                null
            }
        } catch (_: RuntimeException) {
            null
        } ?: return CollectionResult(emptyList(), false)

        return try {
            cursor.use { c ->
                val idIndex = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIndex = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (idIndex < 0 || nameIndex < 0) return@use CollectionResult(emptyList(), false)

                val durationIndex = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val sizeIndex = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIndex = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val mimeIndex = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val pathIndex = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val result = ArrayList<MediaItemEntity>()

                while (c.moveToNext()) {
                    val itemUri = Uri.withAppendedPath(collection, c.getLong(idIndex).toString())
                    val relativePath = if (pathIndex >= 0) {
                        c.getString(pathIndex)?.trim('/')?.takeIf { it.isNotBlank() }
                    } else null
                    val folderName = relativePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                        ?: "مجلدات أخرى"
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
                        bucketId = relativePath ?: "0",
                        bucketName = folderName,
                        lastSeenAt = scanTimestamp
                    )
                }
                CollectionResult(result, true)
            }
        } catch (_: SecurityException) {
            CollectionResult(emptyList(), false)
        } catch (_: RuntimeException) {
            CollectionResult(emptyList(), false)
        }
    }

    private companion object {
        val lastScanTimestamp = AtomicLong(0L)
    }
}
