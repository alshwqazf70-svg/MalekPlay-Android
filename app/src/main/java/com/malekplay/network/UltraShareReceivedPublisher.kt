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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Automatically publishes received files to Downloads/MalekPlayer/Received.
 * A durable app-owned copy already exists before this publisher is called, so a
 * MediaStore/legacy storage failure never loses the successfully received file.
 * Empty files and unknown MIME types are intentionally supported.
 */
object UltraShareReceivedPublisher {

    internal const val PUBLIC_APP_RELATIVE_PATH = "Download/MalekPlayer/Received"
    internal const val PUBLIC_DOWNLOADS_RELATIVE_PATH = "Download"

    internal fun publicRelativePaths(): List<String> =
        listOf(PUBLIC_APP_RELATIVE_PATH, PUBLIC_DOWNLOADS_RELATIVE_PATH)

    suspend fun publish(context: Context, received: UltraShareTransport.ReceivedFile): Uri? =
        withContext(Dispatchers.IO) {
            check(received.file.isFile && received.file.length() == received.sizeBytes) {
                "الملف المستلم غير مكتمل؛ لن نحفظ نسخة ناقصة."
            }
            val app = context.applicationContext
            val name = UltraShareTransport.sanitizeFileName(received.fileName)
            val mime = received.mimeType.ifBlank { "application/octet-stream" }

            // Primary destination is the public app folder. If Android's media
            // provider rejects it, retry the same file directly in Downloads root.
            val publicUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishMediaStore(app, received, name, mime, PUBLIC_APP_RELATIVE_PATH)
                    ?: publishMediaStore(app, received, name, mime, PUBLIC_DOWNLOADS_RELATIVE_PATH)
            } else {
                publishLegacyDownloads(app, received, name, mime, PUBLIC_APP_RELATIVE_PATH)
                    ?: publishLegacyDownloads(app, received, name, mime, PUBLIC_DOWNLOADS_RELATIVE_PATH)
            }
            if (publicUri != null) return@withContext publicUri

            // defaultReceiveDirectory() is already an app-owned persistent
            // MalekPlayer/Received folder. Keep the verified file there as fallback.
            val fallback = File(
                app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(app.filesDir, "downloads"),
                relativePath.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/")
            )
            check(fallback.isDirectory || fallback.mkdirs()) {
                "تعذر إنشاء مجلد MalekPlayer/Received لحفظ الملف المستلم."
            }
            val retained = File(fallback, name)
            if (retained.canonicalFile != received.file.canonicalFile) {
                copyVerified(received.file, retained, received.sizeBytes)
            }
            UltraShareDiagnostics.event(
                "RECEIVED_PUBLISH",
                "SAVED_TO_APP_RECEIVED_FOLDER",
                mapOf("file" to name, "sizeBytes" to received.sizeBytes, "path" to fallback.absolutePath)
            )
            null
        }

    private fun publishMediaStore(
        context: Context,
        received: UltraShareTransport.ReceivedFile,
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
                output.use { target -> input.copyTo(target, UltraShareTransport.CHUNK_BYTES) }
            }
            check(copied == received.sizeBytes && received.file.length() == received.sizeBytes) {
                "لم يكتمل نسخ الملف إلى التنزيلات."
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) {
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
        received: UltraShareTransport.ReceivedFile,
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
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MalekPlayer/Received"
            )
            check(directory.isDirectory || directory.mkdirs()) {
                "تعذر إنشاء مجلد التنزيلات."
            }
            val destination = uniqueFile(directory, name)
            copyVerified(received.file, destination, received.sizeBytes)
            suspendCancellableCoroutine { continuation ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destination.absolutePath),
                    arrayOf(mime)
                ) { _, uri ->
                    if (continuation.isActive) continuation.resume(uri)
                }
            }
        } catch (failure: Exception) {
            UltraShareDiagnostics.failure("RECEIVED_PUBLISH_LEGACY_DOWNLOADS", failure, "file=$name;path=$relativePath")
            null
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
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

    private fun copyVerified(source: File, destination: File, expectedBytes: Long) {
        check(source.isFile && source.length() == expectedBytes) {
            "الملف المصدر غير مكتمل."
        }
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true) {
            "تعذر إنشاء مجلد حفظ الملف."
        }
        val temporary = File(destination.parentFile, destination.name + ".malekpart")
        try {
            val copied = source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output, UltraShareTransport.CHUNK_BYTES).also { output.fd.sync() }
                }
            }
            check(copied == expectedBytes && temporary.length() == expectedBytes) {
                "لم يكتمل حفظ الملف المستلم."
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) {
                "تعذر تثبيت الملف في مجلد التنزيلات."
            }
        } catch (failure: Exception) {
            runCatching { temporary.delete() }
            throw failure
        }
    }
}
