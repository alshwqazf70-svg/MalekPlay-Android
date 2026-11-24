package com.malekplay.vault

import android.content.ContentResolver
import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class VaultFolderResult(
    val entry: PrivateVaultEntry,
    val fileCount: Int,
    val deletedSourceUris: List<String>
) {
    val deletedSourceFiles: Int get() = deletedSourceUris.size
    val sourceFullyMoved: Boolean get() = deletedSourceFiles == fileCount
}

/** SAF folder archive import/export. Archive paths remain inside authenticated ciphertext. */
class PrivateVaultTreeSupport(context: Context, private val vault: PrivateVaultRepository) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    init { appContext.cacheDir.listFiles()?.filter { it.name.startsWith(".malek-vault-") }?.forEach { it.delete() } }
    private data class Node(val uri: Uri, val name: String, val relativePath: String, val directory: Boolean)

    fun importFolder(treeUri: Uri, password: CharArray, moveSource: Boolean): VaultFolderResult {
        val nodes = enumerate(treeUri)
        val files = nodes.filterNot { it.directory }
        require(files.isNotEmpty()) { "المجلد المحدد لا يحتوي على ملفات." }
        val rootName = queryDisplayName(rootDocumentUri(treeUri)).ifBlank { "مجلد خاص" }
        val tempArchive = File(appContext.cacheDir, ".malek-vault-${UUID.randomUUID()}.zip")
        val stored = try {
            FileOutputStream(tempArchive).use { raw ->
                ZipOutputStream(raw).use { zip ->
                    nodes.filter { it.directory }.forEach { node ->
                        val path = safeArchivePath(node.relativePath)
                        if (path.isNotEmpty()) {
                            zip.putNextEntry(ZipEntry("$path/"))
                            zip.closeEntry()
                        }
                    }
                    files.forEach { node ->
                        val path = safeArchivePath(node.relativePath)
                        require(path.isNotEmpty()) { "مسار ملف غير صالح." }
                        zip.putNextEntry(ZipEntry(path))
                        resolver.openInputStream(node.uri)?.use { it.copyTo(zip) }
                            ?: throw IOException("تعذرت قراءة ${node.name}")
                        zip.closeEntry()
                    }
                }
            }
            FileInputStream(tempArchive).use { vault.importStream(rootName, FOLDER_ARCHIVE_MIME, it, password) }
        } finally {
            tempArchive.delete()
        }
        val deleted = if (moveSource) deleteNodesAfterEncryption(nodes) else emptyList()
        return VaultFolderResult(stored, files.size, deleted)
    }

    fun restoreFolder(entry: PrivateVaultEntry, destinationTree: Uri, password: CharArray) {
        require(entry.mimeType == FOLDER_ARCHIVE_MIME) { "هذا العنصر ليس مجلداً." }
        val tempArchive = File(appContext.cacheDir, ".malek-restore-${UUID.randomUUID()}.zip")
        try {
            vault.exportToFile(entry.id, tempArchive, password)
            val rootFolder = DocumentsContract.createDocument(
                resolver, rootDocumentUri(destinationTree), DocumentsContract.Document.MIME_TYPE_DIR,
                safeSegment(entry.displayName).ifBlank { "Restored folder" }
            ) ?: error("تعذر إنشاء مجلد الاستعادة.")
            FileInputStream(tempArchive).use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: break
                        val path = safeArchivePath(item.name)
                        if (path.isNotEmpty()) {
                            val parts = path.split('/').filter { it.isNotEmpty() }
                            var parent = rootFolder
                            parts.forEachIndexed { index, part ->
                                val isDirectory = item.isDirectory || index < parts.lastIndex
                                parent = findChild(parent, part, destinationTree) ?: DocumentsContract.createDocument(
                                    resolver, parent,
                                    if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                                    part
                                ) ?: error("تعذر إنشاء $part")
                                if (!isDirectory && index == parts.lastIndex) {
                                    resolver.openOutputStream(parent, "wt")?.use { zip.copyTo(it) }
                                        ?: error("تعذرت كتابة $part")
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        } finally {
            tempArchive.delete()
        }
    }

    private fun enumerate(treeUri: Uri): List<Node> {
        val result = mutableListOf<Node>()
        fun walk(parent: Uri, prefix: String, depth: Int) {
            require(depth <= MAX_DEPTH) { "بنية المجلد أعمق من الحد المسموح." }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(parent)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    require(result.size < MAX_NODES) { "المجلد يحتوي على عناصر أكثر من الحد المسموح." }
                    val id = cursor.getString(idCol)
                    val name = safeSegment(cursor.getString(nameCol).orEmpty())
                    if (name.isBlank()) continue
                    val directory = cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val relative = if (prefix.isBlank()) name else "$prefix/$name"
                    result += Node(uri, name, relative, directory)
                    if (directory) walk(uri, relative, depth + 1)
                }
            } ?: throw IOException("تعذر قراءة محتويات المجلد.")
        }
        walk(rootDocumentUri(treeUri), "", 0)
        return result
    }

    private fun deleteNodesAfterEncryption(nodes: List<Node>): List<String> {
        val deleted = mutableListOf<String>()
        nodes.asReversed().forEach { node ->
            val success = runCatching { DocumentsContract.deleteDocument(resolver, node.uri) }
                .getOrElse { runCatching { resolver.delete(node.uri, null, null) > 0 }.getOrDefault(false) }
            if (success && !node.directory) deleted += libraryMediaId(node.uri)
        }
        return deleted
    }

    private fun rootDocumentUri(tree: Uri) = DocumentsContract.buildDocumentUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree)
    )

    private fun queryDisplayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return ""
    }

    private fun findChild(parent: Uri, name: String, treeUri: Uri): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                }
            }
        }
        return null
    }

    private fun safeArchivePath(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(!normalized.startsWith('/') && !normalized.contains(':')) { "مسار أرشيف غير آمن." }
        val parts = normalized.trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." }) { "مسار أرشيف غير آمن." }
        return parts.joinToString("/") { safeSegment(it) }
    }

    private fun safeSegment(raw: String): String = raw.replace('/', '_').replace('\\', '_')
        .filter { it.code >= 32 }.take(240).takeUnless { it == "." || it == ".." }.orEmpty()

    companion object {
        /** Converts Android's MediaDocumentsProvider IDs to the exact MediaStore URI used by the library. */
        fun libraryMediaId(uri: Uri): String {
            if (uri.authority != "com.android.providers.media.documents") return uri.toString()
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri.toString()
            val type = documentId.substringBefore(':')
            val id = documentId.substringAfter(':', "").toLongOrNull() ?: return uri.toString()
            val collection = when (type) {
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> return uri.toString()
            }
            return ContentUris.withAppendedId(collection, id).toString()
        }

        const val FOLDER_ARCHIVE_MIME = "application/vnd.malekplay.private-vault-folder"
        private const val MAX_DEPTH = 64
        private const val MAX_NODES = 20_000
    }
}
