package com.malekplay.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes a fully verified received file into the public Downloads collection
 * so it is visible to file managers and MalekPlay's media scanner without a
 * second manual export step. Android 10+ uses scoped-storage MediaStore writes.
 */
object UltraShareReceivedPublisher {

    suspend fun publish(context: Context, received: UltraShareTransport.ReceivedFile): Uri? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
            val resolver = context.applicationContext.contentResolver
            val collection = when {
                received.mimeType.startsWith("video/") ->
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                received.mimeType.startsWith("audio/") ->
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, received.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, received.mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MalekPlayer/Received")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection, values) }
                .onFailure { failure ->
                    UltraShareDiagnostics.failure(
                        "RECEIVED_PUBLISH_INSERT",
                        failure,
                        "file=" + received.fileName + ";mime=" + received.mimeType
                    )
                }
                .getOrNull() ?: return@withContext null
            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: error("تعذر فتح ملف التنزيلات للحفظ.")
                val copiedBytes = received.file.inputStream().use { input ->
                    output.use { target -> input.copyTo(target, UltraShareTransport.CHUNK_BYTES) }
                }
                check(copiedBytes == received.sizeBytes && received.file.length() == received.sizeBytes) {
                    "تغير حجم الملف المستلم أثناء نشره في التنزيلات."
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) {
                    "تعذر إكمال نشر الملف في التنزيلات."
                }
                uri
            } catch (failure: Exception) {
                UltraShareDiagnostics.failure(
                    "RECEIVED_PUBLISH_COPY_OR_FINALIZE",
                    failure,
                    "file=" + received.fileName + ";expectedBytes=" + received.sizeBytes
                )
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
}
