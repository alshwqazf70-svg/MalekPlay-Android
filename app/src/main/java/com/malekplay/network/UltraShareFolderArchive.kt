package com.malekplay.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Creates a ZIP representation of a SAF folder without loading file contents into memory.
 *
 * The relative directory structure is retained inside the archive. The caller must obtain
 * and persist a read grant for [treeUri] before invoking this function.
 */
object UltraShareFolderArchive {
    private const val MAX_ENTRIES = 25_000
    private const val MAX_DEPTH = 64
    private const val MAX_ARCHIVE_BYTES = 10L * 1024L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    data class Result(
        val file: File,
        val includedFiles: Int,
        val includedDirectories: Int,
        val totalBytes: Long
    )

    suspend fun create(
        context: Context,
        treeUri: Uri,
        onProgress: (completedEntries: Int, currentName: String) -> Unit = { _, _ -> }
    ): Result {
        require(DocumentsContract.isTreeUri(treeUri)) { "المعرّف المحدد ليس مجلداً صالحاً." }
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IOException("تعذر قراءة هوية المجلد المحدد.", it) }
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val root = queryDocument(resolver, rootUri)
        require(root.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            "العنصر المحدد ليس مجلداً."
        }

        val outputDirectory = File(context.filesDir, "ultrashare-outgoing").apply {
            if (!exists() && !mkdirs()) throw IOException("تعذر إنشاء مجلد ملفات المشاركة.")
        }
        val rootName = safeSegment(root.displayName.ifBlank { "folder" })
        val output = File(outputDirectory, "$rootName-${java.util.UUID.randomUUID()}.zip")
        var fileCount = 0
        var directoryCount = 0
        var entryCount = 0
        var totalBytes = 0L
        val usedPaths = mutableSetOf<String>()

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
                val rootPath = "$rootName/"
                zip.putNextEntry(ZipEntry(rootPath))
                zip.closeEntry()
                usedPaths += rootPath
                directoryCount++
                entryCount++

                suspend fun addDirectory(documentId: String, parentPath: String, depth: Int) {
                    currentCoroutineContext().ensureActive()
                    if (depth > MAX_DEPTH) throw IOException("تجاوز المجلد عمق التداخل المسموح.")
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                    val children = mutableListOf<Child>()
                    resolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                        ),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            children += Child(
                                id = cursor.getString(idColumn),
                                name = safeSegment(cursor.getString(nameColumn).orEmpty()),
                                mimeType = cursor.getString(mimeColumn).orEmpty()
                            )
                            if (entryCount + children.size > MAX_ENTRIES) {
                                throw IOException("يحتوي المجلد على عناصر أكثر من الحد الآمن المسموح.")
                            }
                        }
                    } ?: throw IOException("تعذر استعراض محتويات أحد المجلدات.")

                    children.sortedWith(compareBy<Child>({ it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }, { it.name.lowercase(Locale.ROOT) }))
                        .forEach { child ->
                            currentCoroutineContext().ensureActive()
                            val childPath = uniquePath(parentPath, child.name, child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR, usedPaths)
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                            entryCount++
                            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                zip.putNextEntry(ZipEntry("$childPath/"))
                                zip.closeEntry()
                                directoryCount++
                                onProgress(entryCount, child.name)
                                addDirectory(child.id, "$childPath/", depth + 1)
                            } else {
                                zip.putNextEntry(ZipEntry(childPath))
                                val input = resolver.openInputStream(childUri)
                                    ?: throw IOException("تعذر فتح الملف: ${child.name}")
                                input.use { source ->
                                    BufferedInputStream(source).use { buffered ->
                                        val buffer = ByteArray(BUFFER_SIZE)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val read = buffered.read(buffer)
                                            if (read < 0) break
                                            if (read == 0) continue
                                            val remainingSpace = output.usableSpace
                                            val reserveBytes = 16L * 1024L * 1024L
                                            if (remainingSpace > 0L && (remainingSpace <= reserveBytes || read.toLong() > remainingSpace - reserveBytes)) {
                                                throw IOException("مساحة التخزين غير كافية لإكمال أرشيف المجلد بأمان.")
                                            }
                                            zip.write(buffer, 0, read)
                                            totalBytes = if (Long.MAX_VALUE - totalBytes < read.toLong()) {
                                                Long.MAX_VALUE
                                            } else totalBytes + read.toLong()
                                        }
                                    }
                                }
                                zip.closeEntry()
                                fileCount++
                                onProgress(entryCount, child.name)
                            }
                        }
                }

                addDirectory(rootId, rootPath, 0)
            }
            if (fileCount == 0 && directoryCount <= 1) {
                // An empty folder is still a valid shareable archive.
                return Result(output, 0, 1, 0L)
            }
            return Result(output, fileCount, directoryCount, totalBytes)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    data class ExtractionResult(
        val extractedFiles: Int,
        val createdDirectories: Int,
        val totalBytes: Long
    )

    /**
     * Safely restores an archive created by [create] into a user-selected SAF folder.
     * Entry paths are validated before any document is created; partial output is removed
     * if extraction fails or is cancelled.
     */
    suspend fun extract(
        context: Context,
        archiveFile: File,
        destinationTreeUri: Uri,
        onProgress: (completedEntries: Int, currentName: String) -> Unit = { _, _ -> }
    ): ExtractionResult {
        require(archiveFile.isFile) { "ملف الأرشيف غير موجود." }
        require(DocumentsContract.isTreeUri(destinationTreeUri)) { "مجلد الوجهة غير صالح." }
        val resolver = context.contentResolver
        val destinationId = DocumentsContract.getTreeDocumentId(destinationTreeUri)
        val destinationRoot = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, destinationId)
        require(queryDocument(resolver, destinationRoot).mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            "وجهة فك الضغط ليست مجلداً."
        }

        val createdUris = mutableListOf<Uri>()
        val directoryUris = linkedMapOf<String, Uri>()
        val filePaths = mutableSetOf<String>()
        var entries = 0
        var fileCount = 0
        var directoryCount = 0
        var totalBytes = 0L

        suspend fun ensureDirectories(parts: List<String>): Uri {
            var parentUri = destinationRoot
            var currentPath = ""
            parts.forEach { segment ->
                currentCoroutineContext().ensureActive()
                currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                if (currentPath in filePaths) throw IOException("يتعارض مسار داخل الأرشيف مع ملف موجود.")
                val existing = directoryUris[currentPath]
                if (existing != null) {
                    parentUri = existing
                } else {
                    val created = DocumentsContract.createDocument(
                        resolver,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment
                    ) ?: throw IOException("تعذر إنشاء المجلد: $segment")
                    directoryUris[currentPath] = created
                    createdUris += created
                    directoryCount++
                    parentUri = created
                }
            }
            return parentUri
        }

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zip ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) throw IOException("الأرشيف يحتوي على عناصر أكثر من الحد الآمن.")
                    val directory = entry.isDirectory || entry.name.endsWith("/")
                    val segments = validatedArchiveSegments(entry.name, directory)
                    val path = segments.joinToString("/")
                    if (directory) {
                        if (path in filePaths) throw IOException("يوجد تعارض في أسماء عناصر الأرشيف.")
                        ensureDirectories(segments)
                        onProgress(entries, segments.last())
                    } else {
                        if (path in filePaths || path in directoryUris) {
                            throw IOException("يحتوي الأرشيف على مسار مكرر أو متعارض.")
                        }
                        val parent = ensureDirectories(segments.dropLast(1))
                        val fileName = segments.last()
                        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            ?: "application/octet-stream"
                        val destination = DocumentsContract.createDocument(
                            resolver,
                            parent,
                            mimeType,
                            fileName
                        ) ?: throw IOException("تعذر إنشاء الملف: $fileName")
                        createdUris += destination
                        filePaths += path
                        val output = resolver.openOutputStream(destination, "w")
                            ?: throw IOException("تعذر الكتابة إلى الملف: $fileName")
                        output.use { sink ->
                            BufferedOutputStream(sink).use { buffered ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    if (totalBytes > MAX_ARCHIVE_BYTES - read.toLong()) {
                                        throw IOException("حجم الأرشيف بعد فك الضغط يتجاوز الحد الآمن.")
                                    }
                                    totalBytes += read.toLong()
                                    buffered.write(buffer, 0, read)
                                }
                            }
                        }
                        fileCount++
                        onProgress(entries, fileName)
                    }
                    zip.closeEntry()
                }
            }
            return ExtractionResult(fileCount, directoryCount, totalBytes)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                createdUris.asReversed().forEach { uri ->
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
            }
            throw failure
        }
    }

    internal fun validatedArchiveSegments(rawName: String, directory: Boolean): List<String> {
        require(rawName.isNotBlank()) { "اسم عنصر الأرشيف فارغ." }
        val separator = 92.toChar()
        require(!rawName.startsWith("/") && !rawName.startsWith(separator.toString()) &&
            !rawName.contains(separator) && !rawName.contains(0.toChar())
        ) { "يحتوي الأرشيف على مسار غير آمن." }
        val normalized = if (directory) rawName.removeSuffix("/") else rawName
        val parts = normalized.split('/')
        require(parts.isNotEmpty() && parts.size <= MAX_DEPTH && parts.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 180
        }) { "يحتوي الأرشيف على مسار غير آمن أو متداخل بعمق مفرط." }
        return parts.map(::safeSegment)
    }

    internal fun safeSegment(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.')
            .take(180)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "item"
    }

    private fun uniquePath(
        parent: String,
        name: String,
        directory: Boolean,
        used: MutableSet<String>
    ): String {
        val extension = if (directory) "" else name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        val base = if (extension.isEmpty()) name else name.removeSuffix(extension)
        var candidate = parent + name
        var suffix = 2
        while (candidate in used || "$candidate/" in used) {
            candidate = parent + base + " ($suffix)" + extension
            suffix++
        }
        used += if (directory) "$candidate/" else candidate
        return candidate
    }

    private fun queryDocument(resolver: ContentResolver, uri: Uri): DocumentInfo {
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("تعذر قراءة بيانات المجلد.")
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).orEmpty()
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)).orEmpty()
            return DocumentInfo(name, mime)
        } ?: throw IOException("مدير الملفات لم يُرجع بيانات المجلد.")
    }

    private data class DocumentInfo(val displayName: String, val mimeType: String)
    private data class Child(val id: String, val name: String, val mimeType: String)
}
