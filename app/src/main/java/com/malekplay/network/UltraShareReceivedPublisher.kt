package com.malekplay.network

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Automatically publishes received files to Downloads/MalekPlayer.
 * A durable app-owned copy already exists before this publisher is called, so a
 * MediaStore/legacy storage failure never loses the successfully received file.
 * Only non-empty, signature-verified media reaches this publisher; unknown MIME fallbacks remain supported.
 */
object UltraShareReceivedPublisher {

    internal const val PUBLIC_APP_RELATIVE_PATH = "Download/MalekPlayer"
    private const val PUBLIC_DOWNLOADS_RELATIVE_PATH = "Download"

    internal fun publicRelativePaths(): List<String> = listOf(PUBLIC_APP_RELATIVE_PATH)

    suspend fun publish(context: Context, received: UltraShareCore.ReceivedFile): Uri? =
        withContext(Dispatchers.IO) {
            check(received.sizeBytes > 0L && received.file.isFile && received.file.length() == received.sizeBytes) {
                "الملف المستلم غير مكتمل؛ لن نحفظ نسخة ناقصة."
            }
            val app = context.applicationContext
            val name = UltraShareCore.sanitizeFileName(received.fileName)
            val mime = received.mimeType.ifBlank { "application/octet-stream" }

            // Keep every public copy inside the requested MalekPlayer directory.
            // Never silently spill into the generic Downloads root.
            val publicUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishMediaStore(app, received, name, mime, PUBLIC_APP_RELATIVE_PATH)
            } else {
                publishLegacyDownloads(app, received, name, mime, PUBLIC_APP_RELATIVE_PATH)
            }
            if (publicUri != null) return@withContext publicUri

            // defaultReceiveDirectory() is already an app-owned persistent
            // MalekPlayer folder. Keep the verified file there as fallback.
            val fallback = UltraShareCore.defaultReceiveDirectory(app)
            check(fallback.isDirectory || fallback.mkdirs()) {
                "تعذر إنشاء مجلد MalekPlayer لحفظ الملف المستلم."
            }
            val retained = UltraShareCore.withDestinationPublicationLock(fallback) {
                // The transport has already placed the verified source in this folder.
                // Otherwise allocate a collision-free name so a repeated filename can
                // never overwrite an earlier received file.
                if (received.file.canonicalFile.parentFile == fallback.canonicalFile) {
                    received.file
                } else {
                    uniqueFile(fallback, name).also { destination ->
                        copyVerified(received.file, destination, received.sizeBytes)
                    }
                }
            }
            UltraShareDiagnostics.event(
                "RECEIVED_PUBLISH",
                "SAVED_TO_APP_MALEKPLAYER_FOLDER",
                mapOf("file" to name, "sizeBytes" to received.sizeBytes, "path" to fallback.absolutePath)
            )
            null
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishMediaStore(
        context: Context,
        received: UltraShareCore.ReceivedFile,
        name: String,
        mime: String,
        relativePath: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                relativePath
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.onFailure {
            UltraShareDiagnostics.failure("RECEIVED_PUBLISH_INSERT", it, "file=$name;mime=$mime")
        }.getOrNull() ?: return null

        return try {
            val output = resolver.openOutputStream(uri, "w")
                ?: error("تعذر فتح ملف التنزيلات للحفظ.")
            val copied = received.file.inputStream().use { input ->
                output.use { target -> input.copyTo(target, UltraShareBatchTransport.COPY_BUFFER_BYTES) }
            }
            check(copied == received.sizeBytes && received.file.length() == received.sizeBytes) {
                "لم يكتمل نسخ الملف إلى التنزيلات."
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            val updatedRows = resolver.update(uri, values, null, null)
            // Some OEM MediaStore providers report zero updated rows even when
            // the item was finalized. Verify the persisted IS_PENDING state before
            // treating that response as a failed publication.
            val isVisible = updatedRows > 0 || runCatching {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.IS_PENDING),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == 0
                } == true
            }.getOrDefault(false)
            check(isVisible) {
                "تعذر إظهار الملف في مجلد التنزيلات."
            }
            UltraShareDiagnostics.event(
                "RECEIVED_PUBLISH",
                "SAVED_TO_PUBLIC_DOWNLOADS",
                mapOf("file" to name, "sizeBytes" to received.sizeBytes)
            )
            uri
        } catch (failure: Exception) {
            UltraShareDiagnostics.failure(
                "RECEIVED_PUBLISH_COPY_OR_FINALIZE",
                failure,
                "file=$name;expectedBytes=${received.sizeBytes};path=$relativePath"
            )
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun publishLegacyDownloads(
        context: Context,
        received: UltraShareCore.ReceivedFile,
        name: String,
        mime: String,
        relativePath: String
    ): Uri? {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) return null

        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val subdirectory = legacyDownloadsSubdirectory(relativePath)
            val directory = if (subdirectory.isBlank()) downloads else File(downloads, subdirectory)
            check(directory.isDirectory || directory.mkdirs()) {
                "تعذر إنشاء مجلد التنزيلات."
            }
            val destination = UltraShareCore.withDestinationPublicationLock(directory) {
                uniqueFile(directory, name).also { target ->
                    copyVerified(received.file, target, received.sizeBytes)
                }
            }
            val scannedUri = withTimeoutOrNull(8_000L) {
                suspendCancellableCoroutine<Uri?> { continuation ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destination.absolutePath),
                        arrayOf(mime)
                    ) { _, uri ->
                        if (continuation.isActive) continuation.resume(uri)
                    }
                }
            }
            if (scannedUri == null) {
                UltraShareDiagnostics.event(
                    "RECEIVED_PUBLISH",
                    "LEGACY_MEDIA_SCAN_NO_RESULT",
                    mapOf("file" to name, "timeoutMs" to 8_000)
                )
            }
            scannedUri
        } catch (cancelled: CancellationException) {
            // Cancellation must reach the foreground-service owner; treating it as a
            // publication failure would continue copying after the user/system stopped the job.
            throw cancelled
        } catch (failure: Exception) {
            UltraShareDiagnostics.failure("RECEIVED_PUBLISH_LEGACY_DOWNLOADS", failure, "file=$name;path=$relativePath")
            null
        }
    }

    internal fun legacyDownloadsSubdirectory(relativePath: String): String = when {
        relativePath == PUBLIC_DOWNLOADS_RELATIVE_PATH -> ""
        relativePath.startsWith("$PUBLIC_DOWNLOADS_RELATIVE_PATH/") ->
            relativePath.removePrefix("$PUBLIC_DOWNLOADS_RELATIVE_PATH/")
        else -> relativePath
    }

    internal fun uniqueFile(directory: File, name: String): File {
        if (!File(directory, name).exists()) return File(directory, name)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    internal fun copyVerified(source: File, destination: File, expectedBytes: Long) {
        check(source.isFile && source.length() == expectedBytes) {
            "الملف المصدر غير مكتمل."
        }
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true) {
            "تعذر إنشاء مجلد حفظ الملف."
        }
        // Never delete a pre-existing destination: unique-name selection and this
        // publication step are separated by filesystem activity outside our process.
        check(!destination.exists()) { "يوجد ملف بهذا الاسم؛ لن نستبدل ملفاً سابقاً." }
        val temporary = File(destination.parentFile, destination.name + ".malekpart")
        try {
            val copied = source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output, UltraShareBatchTransport.COPY_BUFFER_BYTES).also { output.fd.sync() }
                }
            }
            check(copied == expectedBytes && temporary.length() == expectedBytes) {
                "لم يكتمل حفظ الملف المستلم."
            }
            check(!destination.exists()) { "ظهر ملف آخر بالاسم نفسه؛ لن نستبدله." }
            check(temporary.renameTo(destination)) {
                "تعذر تثبيت الملف في مجلد التنزيلات."
            }
        } catch (failure: Exception) {
            runCatching { temporary.delete() }
            throw failure
        }
    }
}
