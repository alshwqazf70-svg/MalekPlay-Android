package com.malekplay.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.malekplay.data.local.entity.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ماسح وسائط الهاتف فائق السرعة والدقة (مع دعم استخراج المجلدات كـ MX Player)
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
        // الأعمدة الكاملة مع تضمين أعمدة المجلدات (Bucket Columns)
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        // استبعاد الملفات التالفة ذات الحجم صفر والملفات المعلقة قيد التنزيل
        val selection = StringBuilder("${MediaStore.MediaColumns.SIZE} > 0")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection.append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        }

        val result = mutableListOf<MediaItemEntity>()

        resolver.query(
            collection,
            columns,
            selection.toString(),
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val bucketIdIndex = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val itemUri = Uri.withAppendedPath(collection, id.toString())
                val fallbackMime = if (isVideo) "video/*" else "audio/*"

                // استخراج اسم ومعرف المجلد الحقيقي
                val rawBucketId = if (bucketIdIndex >= 0) cursor.getString(bucketIdIndex) else null
                val rawBucketName = if (bucketNameIndex >= 0) cursor.getString(bucketNameIndex) else null

                val bucketId = rawBucketId ?: "0"
                val bucketName = rawBucketName ?: if (isVideo) "فيديوهات أخرى" else "صوتيات أخرى"

                result += MediaItemEntity(
                    mediaId = itemUri.toString(),
                    title = cursor.getString(nameIndex) ?: "بدون اسم",
                    duration = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L,
                    uri = itemUri.toString(),
                    mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) ?: fallbackMime else fallbackMime,
                    sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                    dateAdded = if (dateIndex >= 0) cursor.getLong(dateIndex) * 1000L else 0L,
                    isVideo = isVideo,
                    bucketId = bucketId,
                    bucketName = bucketName
                )
            }
        }
        return result
    }
}
