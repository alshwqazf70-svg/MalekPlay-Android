package com.malekplay.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.malekplay.ui.network.MediaFile
import com.malekplay.ui.network.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MediaPickerRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun getMediaFiles(type: MediaType): List<MediaFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<MediaFile>()
        val collection = when (type) {
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC" // الأحدث أولاً
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "ملف بدون اسم"
                val sizeBytes = cursor.getLong(sizeColumn)
                
                val contentUri = ContentUris.withAppendedId(collection, id)

                files.add(
                    MediaFile(
                        id = id.toString(),
                        name = name,
                        size = formatFileSize(sizeBytes),
                        uri = contentUri,
                        type = type,
                        thumbnailUrl = contentUri.toString() // Coil سيعرض الصورة المصغرة
                    )
                )
            }
        }
        files
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
