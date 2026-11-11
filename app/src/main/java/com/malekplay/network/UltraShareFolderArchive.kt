package com.malekplay.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
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
        val output = File(outputDirectory, "folder-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.zip")
        var fileCount = 0
        var directoryCount = 0
        var entryCount = 0
        var totalBytes = 0L
        val usedPaths = mutableSetOf<String>()

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
                val rootName = safeSegment(root.displayName.ifBlank { "folder" })
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
        while (candidate in used || (directory && "$candidate/" in used)) {
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
