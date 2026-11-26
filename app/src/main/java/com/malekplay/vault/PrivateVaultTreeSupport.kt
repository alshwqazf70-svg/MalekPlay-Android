package com.malekplay.vault

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class VaultFolderResult(
    val entry: PrivateVaultEntry,
    val fileCount: Int,
    val deletedSourceUris: List<String>,
    val sourceNodeCount: Int,
    val deletedSourceNodeCount: Int
) {
    val deletedSourceFiles: Int get() = deletedSourceUris.size
    val sourceFullyMoved: Boolean get() = deletedSourceNodeCount == sourceNodeCount
}

private data class SourceDeletionResult(
    val mediaUris: List<String>,
    val deletedNodeCount: Int
)

/** One row in the private vault's read-only SAF source browser. */
data class VaultBrowserItem(
    val documentId: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val childCount: Int? = null
)

/** SAF folder archive import/export and verified file/folder transfers. */
class PrivateVaultTreeSupport(context: Context, private val vault: PrivateVaultRepository) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    init {
        // These are app-private plaintext ZIP work files. A new support instance
        // is created when entering the vault; remove leftovers from killed sessions.
        appContext.cacheDir.listFiles()?.filter {
            it.name.startsWith(".malek-vault-") || it.name.startsWith(".malek-restore-")
        }?.forEach { it.delete() }
    }

    private data class Node(val uri: Uri, val name: String, val relativePath: String, val directory: Boolean)

    fun importFolder(treeUri: Uri, password: CharArray, moveSource: Boolean): VaultFolderResult {
        val session = vault.openSession(password)
        return try {
            importFolder(treeUri, session, moveSource)
        } finally {
            session.close()
        }
    }

    fun importFolder(
        treeUri: Uri,
        session: PrivateVaultSession,
        moveSource: Boolean,
        rootDocumentId: String? = null,
        sourceParentDocumentId: String? = null,
        sourceDisplayPath: String? = null,
        onProgress: ((current: Int, total: Int, label: String) -> Unit)? = null
    ): VaultFolderResult {
        val targetRootDocId = rootDocumentId ?: DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetRootDocId)
        val nodes = enumerate(treeUri, rootUri)
        val files = nodes.filterNot { it.directory }
        val rootName = queryDisplayName(rootUri).ifBlank { "Private folder" }
        val origin = VaultSourceOrigin(
            treeUri = treeUri.toString(),
            parentDocumentId = sourceParentDocumentId ?: DocumentsContract.getTreeDocumentId(treeUri),
            displayPath = sourceDisplayPath ?: rootName
        )
        val tempArchive = vault.createPrivateWorkFile(".malek-vault-", ".zip")
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
                    files.forEachIndexed { idx, node ->
                        onProgress?.invoke(idx + 1, files.size.coerceAtLeast(1), node.name)
                        val path = safeArchivePath(node.relativePath)
                        require(path.isNotEmpty()) { "Invalid file path in folder." }
                        val mime = resolver.getType(node.uri) ?: guessMimeFromName(node.name)
                        val preferStored = shouldStoreUncompressed(node.name, mime)
                        val storedMeta = if (preferStored) computeSizeAndCrc(node.uri) else null
                        val zipEntry = ZipEntry(path).apply {
                            comment = mime
                            if (storedMeta != null) {
                                method = ZipEntry.STORED
                                size = storedMeta.first
                                compressedSize = storedMeta.first
                                crc = storedMeta.second
                            }
                        }
                        zip.putNextEntry(zipEntry)
                        resolver.openInputStream(node.uri)?.use { it.copyTo(zip) }
                            ?: throw IOException("Could not read ${node.name}")
                        zip.closeEntry()
                    }
                }
            }
            vault.ensureAvailableInternalSpace(tempArchive.length())
            FileInputStream(tempArchive).use {
                vault.importStream(rootName, FOLDER_ARCHIVE_MIME, it, session, sourceOrigin = origin)
            }
        } finally {
            vault.releasePrivateWorkFile(tempArchive)
        }
        val deletion = if (moveSource) {
            val allToDelete = if (rootDocumentId != null) {
                nodes + Node(rootUri, rootName, "", true)
            } else {
                nodes
            }
            deleteNodesAfterEncryption(allToDelete)
        } else {
            SourceDeletionResult(emptyList(), 0)
        }
        return VaultFolderResult(
            entry = stored,
            fileCount = files.size,
            deletedSourceUris = deletion.mediaUris,
            sourceNodeCount = if (moveSource && rootDocumentId != null) nodes.size + 1 else nodes.size,
            deletedSourceNodeCount = deletion.deletedNodeCount
        )
    }

    /**
     * Imports a list of selected browser files/folders into the vault.
     * Supports copying or verified moving without dropping items selected across multiple folders.
     */
    fun importBrowserSelection(
        items: List<SelectedVaultBrowserItem>,
        session: PrivateVaultSession,
        moveSource: Boolean,
        isCancelled: () -> Boolean = { false },
        onItemProgress: ((current: Int, total: Int, displayName: String) -> Unit)? = null,
        onBytesProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null
    ): VaultImportBatchResult {
        var importedCount = 0
        var failedCount = 0
        var movedCount = 0
        var copiedOnlyCount = 0
        val deletedSourceMediaIds = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val total = items.size.coerceAtLeast(1)

        for ((index, item) in items.withIndex()) {
            if (isCancelled()) break
            onItemProgress?.invoke(index + 1, total, item.displayName)
            val outcome = runCatching {
                if (item.isDirectory) {
                    val folderRes = importFolder(
                        treeUri = item.treeUri,
                        session = session,
                        moveSource = moveSource,
                        rootDocumentId = item.documentId,
                        sourceParentDocumentId = item.parentDocumentId,
                        sourceDisplayPath = item.displayFolderPath,
                        onProgress = { _, _, childName ->
                            onItemProgress?.invoke(index + 1, total, "${item.displayName}/$childName")
                        }
                    )
                    importedCount++
                    if (moveSource) {
                        deletedSourceMediaIds += folderRes.deletedSourceUris
                        if (folderRes.sourceFullyMoved) movedCount++ else copiedOnlyCount++
                    }
                } else {
                    val origin = VaultSourceOrigin(
                        treeUri = item.treeUri.toString(),
                        parentDocumentId = item.parentDocumentId,
                        displayPath = item.displayFolderPath
                    )
                    vault.importUri(
                        uri = item.uri,
                        session = session,
                        displayNameOverride = item.displayName,
                        mimeTypeOverride = item.mimeType,
                        sourceOrigin = origin,
                        onProgress = { done ->
                            onBytesProgress?.invoke(done, item.sizeBytes)
                        }
                    )
                    importedCount++
                    if (moveSource) {
                        if (deleteSourceDocumentAndVerify(item.uri, item.treeUri, item.parentDocumentId, item.documentId)) {
                            movedCount++
                            deletedSourceMediaIds += libraryMediaId(item.uri)
                        } else {
                            copiedOnlyCount++
                        }
                    }
                }
            }
            if (outcome.isFailure) {
                failedCount++
                val msg = outcome.exceptionOrNull()?.localizedMessage ?: item.displayName
                failures += "${item.displayName}: $msg"
            }
        }
        return VaultImportBatchResult(
            importedCount = importedCount,
            failedCount = failedCount,
            movedCount = movedCount,
            copiedOnlyCount = copiedOnlyCount,
            deletedSourceMediaIds = deletedSourceMediaIds,
            failures = failures
        )
    }

    fun restoreFolder(
        entry: PrivateVaultEntry,
        destinationTree: Uri,
        password: CharArray
    ): VaultFolderRestoreResult {
        val session = vault.openSession(password)
        return try {
            restoreFolder(entry, destinationTree, session)
        } finally {
            session.close()
        }
    }

    fun restoreFolder(
        entry: PrivateVaultEntry,
        destinationTree: Uri,
        session: PrivateVaultSession,
        targetParentDocumentId: String? = null,
        isCancelled: () -> Boolean = { false },
        onFileRestored: ((fileName: String) -> Unit)? = null
    ): VaultFolderRestoreResult {
        require(entry.mimeType == FOLDER_ARCHIVE_MIME) { "Entry is not a folder archive." }
        val tempArchive = vault.createPrivateWorkFile(".malek-restore-", ".zip")
        val parentDocId = targetParentDocumentId ?: DocumentsContract.getTreeDocumentId(destinationTree)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(destinationTree, parentDocId)
        val existingRootNames = listChildNames(destinationTree, parentDocId)
        val rootFolderName = resolveUniqueChildName(
            desiredName = safeSegment(entry.displayName).ifBlank { "Restored folder" },
            existingNames = existingRootNames,
            isDirectory = true
        )
        var rootFolder: Uri? = null
        var restoredFiles = 0
        var failedFiles = 0
        val failures = mutableListOf<String>()
        val createdFileUris = mutableListOf<Uri>()
        val dirCache = hashMapOf<String, Uri>()
        val knownChildNames = hashMapOf<String, MutableSet<String>>()

        try {
            if (entry.sizeBytes > 0L) {
                vault.ensureAvailableInternalSpace(entry.sizeBytes)
            }
            vault.exportToFile(entry.id, tempArchive, session)
            rootFolder = DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                rootFolderName
            ) ?: error("Could not create destination folder.")
            dirCache[""] = rootFolder
            knownChildNames[""] = mutableSetOf()

            FileInputStream(tempArchive).use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        if (isCancelled()) {
                            failedFiles++
                            failures += "Cancelled before finishing all files."
                            break
                        }
                        val item = zip.nextEntry ?: break
                        val path = safeArchivePathOrNull(item.name)
                        if (path.isNullOrEmpty()) {
                            zip.closeEntry()
                            continue
                        }
                        val parts = path.split('/').filter { it.isNotEmpty() }
                        val itemOutcome = runCatching {
                            var currentParent = rootFolder!!
                            var currentRelPath = ""
                            parts.forEachIndexed { index, rawPart ->
                                val isDirectory = item.isDirectory || index < parts.lastIndex
                                val nextRelPath = if (currentRelPath.isEmpty()) rawPart else "$currentRelPath/$rawPart"
                                if (isDirectory) {
                                    val cached = dirCache[nextRelPath]
                                    if (cached != null) {
                                        currentParent = cached
                                        currentRelPath = nextRelPath
                                    } else {
                                        val parentDoc = DocumentsContract.getDocumentId(currentParent)
                                        val existing = findChild(currentParent, rawPart, destinationTree)
                                        val createdDir = if (existing != null && isDirectoryDocument(existing)) {
                                            existing
                                        } else {
                                            val usedNames = knownChildNames.getOrPut(currentRelPath) {
                                                listChildNames(destinationTree, parentDoc).toMutableSet()
                                            }
                                            val uniqueDirName = resolveUniqueChildName(rawPart, usedNames, isDirectory = true)
                                            usedNames += uniqueDirName
                                            DocumentsContract.createDocument(
                                                resolver,
                                                currentParent,
                                                DocumentsContract.Document.MIME_TYPE_DIR,
                                                uniqueDirName
                                            ) ?: error("Could not create folder $uniqueDirName")
                                        }
                                        dirCache[nextRelPath] = createdDir
                                        currentParent = createdDir
                                        currentRelPath = nextRelPath
                                    }
                                } else {
                                    val parentDoc = DocumentsContract.getDocumentId(currentParent)
                                    val usedNames = knownChildNames.getOrPut(currentRelPath) {
                                        listChildNames(destinationTree, parentDoc).toMutableSet()
                                    }
                                    val uniqueFileName = resolveUniqueChildName(rawPart, usedNames, isDirectory = false)
                                    usedNames += uniqueFileName
                                    val mime = item.comment?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                                    val createdFile = DocumentsContract.createDocument(
                                        resolver,
                                        currentParent,
                                        mime,
                                        uniqueFileName
                                    ) ?: error("Could not create file $uniqueFileName")
                                    var writeSuccess = false
                                    try {
                                        val out = resolver.openOutputStream(createdFile, "wt")
                                            ?: error("Could not write $uniqueFileName")
                                        val expectedSize = item.size
                                        var writtenBytes = 0L
                                        val expectedSha256: ByteArray
                                        out.use { rawOut ->
                                            val counter = CountingSink(rawOut)
                                            zip.copyTo(counter)
                                            counter.flush()
                                            if (rawOut is FileOutputStream) {
                                                runCatching { rawOut.fd.sync() }
                                            }
                                            writtenBytes = counter.written
                                            expectedSha256 = counter.digestBytes()
                                        }
                                        if (expectedSize >= 0L) {
                                            check(writtenBytes == expectedSize) {
                                                "Written size mismatch for $uniqueFileName ($writtenBytes != $expectedSize)"
                                            }
                                        }
                                        vault.verifyWrittenDestinationDigest(createdFile, writtenBytes, expectedSha256)
                                        writeSuccess = true
                                        createdFileUris += createdFile
                                        restoredFiles++
                                        onFileRestored?.invoke(uniqueFileName)
                                    } finally {
                                        if (!writeSuccess) {
                                            runCatching { DocumentsContract.deleteDocument(resolver, createdFile) }
                                        }
                                    }
                                }
                            }
                        }
                        if (itemOutcome.isFailure) {
                            failedFiles++
                            val err = itemOutcome.exceptionOrNull()?.localizedMessage ?: path
                            failures += "$path: $err"
                        }
                        zip.closeEntry()
                    }
                }
            }
            return VaultFolderRestoreResult(
                restoredFiles = restoredFiles,
                failedFiles = failedFiles,
                rootFolderUri = rootFolder,
                rootFolderName = rootFolderName,
                failures = failures,
                createdFileUris = createdFileUris
            )
        } finally {
            vault.releasePrivateWorkFile(tempArchive)
        }
    }

    /**
     * Restores (take out with verified delete) or exports (copy keeping vault item) one or many entries
     * either to each item's original source folder or to a user-selected [destinationTree].
     */
    fun restoreEntries(
        entries: List<PrivateVaultEntry>,
        session: PrivateVaultSession,
        destinationTree: Uri?,
        useOriginalFolder: Boolean,
        removeFromVaultAfterRestore: Boolean,
        isCancelled: () -> Boolean = { false },
        onItemProgress: ((current: Int, total: Int, displayName: String) -> Unit)? = null,
        onBytesProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null
    ): VaultBatchTransferSummary {
        var succeededCount = 0
        var failedCount = 0
        var removedFromVaultCount = 0
        var keptInVaultCount = 0
        var extractedButVaultDeleteFailedCount = 0
        val failures = mutableListOf<String>()
        val restoredPaths = mutableListOf<String>()
        val total = entries.size.coerceAtLeast(1)

        for ((index, entry) in entries.withIndex()) {
            if (isCancelled()) {
                keptInVaultCount += (entries.size - index)
                break
            }
            onItemProgress?.invoke(index + 1, total, entry.displayName)
            val resolvedTarget = resolveRestoreTarget(entry, destinationTree, useOriginalFolder)
            if (resolvedTarget == null) {
                failedCount++
                keptInVaultCount++
                failures += "${entry.displayName}: No valid destination folder."
                continue
            }
            val (targetTree, targetParentId) = resolvedTarget

            if (entry.mimeType == FOLDER_ARCHIVE_MIME) {
                val folderOutcome = runCatching {
                    restoreFolder(
                        entry = entry,
                        destinationTree = targetTree,
                        session = session,
                        targetParentDocumentId = targetParentId,
                        isCancelled = isCancelled,
                        onFileRestored = { name ->
                            onItemProgress?.invoke(index + 1, total, "${entry.displayName}/$name")
                        }
                    )
                }
                val result = folderOutcome.getOrNull()
                if (result == null) {
                    failedCount++
                    keptInVaultCount++
                    failures += "${entry.displayName}: ${folderOutcome.exceptionOrNull()?.localizedMessage ?: "Restore failed"}"
                } else if (!result.isCompleteSuccess) {
                    // Partial folder restore: preserve encrypted archive in vault!
                    failedCount++
                    keptInVaultCount++
                    failures += result.failures.ifEmpty { listOf("${entry.displayName}: Partial folder restore (${result.failedFiles} failed)") }
                    result.createdFileUris.forEach { restoredPaths += it.toString() }
                } else {
                    succeededCount++
                    result.createdFileUris.forEach { restoredPaths += it.toString() }
                    if (removeFromVaultAfterRestore) {
                        val deleted = runCatching { vault.delete(entry.id, session) }
                        if (deleted.isSuccess) {
                            removedFromVaultCount++
                        } else {
                            keptInVaultCount++
                            extractedButVaultDeleteFailedCount++
                            failures += "${entry.displayName}: Extracted safely, but encrypted copy remains in vault."
                        }
                    } else {
                        keptInVaultCount++
                    }
                }
            } else {
                var createdUri: Uri? = null
                val restoreOutcome = runCatching {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(targetTree, targetParentId)
                    val existingNames = listChildNames(targetTree, targetParentId)
                    val uniqueName = resolveUniqueChildName(
                        desiredName = safeSegment(entry.displayName).ifBlank { "Restored file" },
                        existingNames = existingNames,
                        isDirectory = false
                    )
                    val docUri = DocumentsContract.createDocument(
                        resolver,
                        parentUri,
                        entry.mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                        uniqueName
                    ) ?: error("Could not create $uniqueName in destination.")
                    createdUri = docUri
                    vault.exportToDocumentVerified(entry.id, docUri, session) { doneBytes ->
                        onBytesProgress?.invoke(doneBytes, entry.sizeBytes)
                    }
                    docUri
                }
                if (restoreOutcome.isFailure) {
                    createdUri?.let { partial ->
                        runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                    }
                    failedCount++
                    keptInVaultCount++
                    failures += "${entry.displayName}: ${restoreOutcome.exceptionOrNull()?.localizedMessage ?: "Restore failed"}"
                } else {
                    succeededCount++
                    restoreOutcome.getOrNull()?.let { restoredPaths += it.toString() }
                    if (removeFromVaultAfterRestore) {
                        val deleted = runCatching { vault.delete(entry.id, session) }
                        if (deleted.isSuccess) {
                            removedFromVaultCount++
                        } else {
                            keptInVaultCount++
                            extractedButVaultDeleteFailedCount++
                            failures += "${entry.displayName}: Extracted safely, but encrypted copy remains in vault."
                        }
                    } else {
                        keptInVaultCount++
                    }
                }
            }
        }

        return VaultBatchTransferSummary(
            succeededCount = succeededCount,
            failedCount = failedCount,
            removedFromVaultCount = removedFromVaultCount,
            keptInVaultCount = keptInVaultCount,
            extractedButVaultDeleteFailedCount = extractedButVaultDeleteFailedCount,
            failures = failures,
            restoredFilePathsOrUris = restoredPaths
        )
    }

    fun canRestoreAllToOriginalFolder(entries: List<PrivateVaultEntry>): Boolean =
        entries.isNotEmpty() && entries.all { canRestoreToOriginalFolder(it) }

    fun canRestoreToOriginalFolder(entry: PrivateVaultEntry): Boolean {
        val rawTree = entry.sourceTreeUri ?: return false
        val treeUri = runCatching { Uri.parse(rawTree) }.getOrNull() ?: return false
        return isTreeFolderAccessible(treeUri, entry.sourceParentDocumentId)
    }

    fun isTreeFolderAccessible(treeUri: Uri, parentDocumentId: String? = null): Boolean {
        val hasPersistedGrant = resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPersistedGrant && treeUri.scheme != "file") {
            // Still probe query in case Robolectric / provider grants runtime permission without persisted list.
            val docId = parentDocumentId ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return false
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            return runCatching {
                resolver.query(
                    docUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null
                )?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        }
        val docId = parentDocumentId ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return false
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return runCatching {
            resolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    fun isTreeReadable(treeUri: Uri): Boolean {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return false
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return runCatching {
            resolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /**
     * Lists one level of a persisted SAF tree for the vault's in-app browser.
     * The caller owns navigation; this method never recursively scans or mutates sources.
     */
    fun listBrowserChildren(treeUri: Uri, parentDocumentId: String? = null): List<VaultBrowserItem> {
        val parentId = parentDocumentId ?: DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<VaultBrowserItem>()
        // Some third-party DocumentsProviders reject optional metadata columns.
        // Retry with the required identity columns before treating the folder as unreadable.
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (_: Exception) {
            null
        } ?: try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )
        } catch (_: Exception) {
            null
        } ?: throw IOException("Could not read folder contents.")
        cursor.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                result += VaultBrowserItem(
                    documentId = id,
                    uri = uri,
                    displayName = name,
                    mimeType = mime,
                    isDirectory = directory,
                    sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol).takeIf { it >= 0L } ?: -1L else -1L,
                    lastModified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L
                )
                require(result.size <= MAX_NODES) { "Folder contains more items than allowed." }
            }
        }
        return result.sortedWith(compareBy<VaultBrowserItem> { !it.isDirectory }.thenBy { it.displayName.lowercase() })
    }

    /**
     * Counts immediate children in a folder without deep traversal. Returns null if provider
     * rejects the query so UI stays fast and responsive.
     */
    fun countDirectChildren(treeUri: Uri, folderDocumentId: String): Int? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocumentId)
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { cursor -> cursor.count }
    }.getOrNull()

    fun queryTreeRootLabel(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return ""
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return queryDisplayName(rootDoc)
    }

    /**
     * Attempts source deletion and verifies via follow-up queries that the document actually
     * disappeared. Never trusts DocumentsContract.deleteDocument's boolean return value alone.
     */
    fun deleteSourceDocumentAndVerify(
        uri: Uri,
        treeUri: Uri? = null,
        parentDocumentId: String? = null,
        documentId: String? = null
    ): Boolean {
        // Some SAF providers return false (without throwing) from the document
        // contract even though their ContentResolver.delete implementation works.
        // Try that fallback on both false and exceptions, then trust only the
        // follow-up document query—not either delete return value.
        val contractDeleted = runCatching {
            DocumentsContract.deleteDocument(resolver, uri)
        }.getOrDefault(false)
        if (!contractDeleted) {
            runCatching { resolver.delete(uri, null, null) }
        }
        return !documentStillExists(uri, treeUri, parentDocumentId, documentId)
    }

    private fun resolveRestoreTarget(
        entry: PrivateVaultEntry,
        fallbackTree: Uri?,
        useOriginalFolder: Boolean
    ): Pair<Uri, String>? {
        if (useOriginalFolder && entry.sourceTreeUri != null) {
            val treeUri = runCatching { Uri.parse(entry.sourceTreeUri) }.getOrNull()
            if (treeUri != null && isTreeFolderAccessible(treeUri, entry.sourceParentDocumentId)) {
                val parentId = entry.sourceParentDocumentId
                    ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                if (parentId != null) return treeUri to parentId
            }
        }
        val dest = fallbackTree ?: return null
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(dest) }.getOrNull() ?: return null
        return dest to rootDocId
    }

    private fun enumerate(treeUri: Uri, startDocumentUri: Uri = rootDocumentUri(treeUri)): List<Node> {
        val result = mutableListOf<Node>()
        val visitedDirectoryIds = mutableSetOf<String>()
        fun walk(parent: Uri, prefix: String, depth: Int) {
            require(depth <= MAX_DEPTH) { "Folder hierarchy exceeds allowed depth." }
            val parentId = DocumentsContract.getDocumentId(parent)
            require(visitedDirectoryIds.add(parentId)) { "Invalid folder cycle detected." }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
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
                    require(result.size < MAX_NODES) { "Folder contains more items than allowed." }
                    val id = cursor.getString(idCol)?.takeIf { it.isNotBlank() } ?: continue
                    val name = safeSegment(cursor.getString(nameCol).orEmpty())
                    if (name.isBlank()) continue
                    val directory = cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val relative = if (prefix.isBlank()) name else "$prefix/$name"
                    require(result.none { it.relativePath == relative }) {
                        "Duplicate sanitized filename in folder: $name"
                    }
                    result += Node(uri, name, relative, directory)
                    if (directory) walk(uri, relative, depth + 1)
                }
            } ?: throw IOException("Could not read folder contents.")
        }
        walk(startDocumentUri, "", 0)
        return result
    }

    private fun deleteNodesAfterEncryption(nodes: List<Node>): SourceDeletionResult {
        // Delete deepest descendants first. Provider return values alone are not
        // proof that a document disappeared; verify each source before reporting
        // it as moved or removing it from the media library.
        nodes.asReversed().forEach { node ->
            runCatching { DocumentsContract.deleteDocument(resolver, node.uri) }
                .getOrElse { runCatching { resolver.delete(node.uri, null, null) }.getOrDefault(0) }
        }
        val remaining = nodes.filter { documentStillExists(it.uri) }
        val deletedMedia = nodes.asSequence()
            .filterNot { it.directory }
            .filterNot { it in remaining }
            .map { libraryMediaId(it.uri) }
            .toList()
        return SourceDeletionResult(deletedMedia, nodes.size - remaining.size)
    }

    /**
     * A failed/unsupported query is treated conservatively as "still exists".
     * This prevents claiming a move or hiding a library item when provider state
     * cannot be verified after a delete request.
     */
    private fun documentStillExists(
        uri: Uri,
        treeUri: Uri? = null,
        parentDocumentId: String? = null,
        documentId: String? = null
    ): Boolean {
        var sawSecurityException = false
        fun hasRows(projection: Array<String>): Boolean? = try {
            resolver.query(uri, projection, null, null, null)?.use { it.moveToFirst() }
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            sawSecurityException = true
            null
        } catch (_: Exception) {
            null
        }
        val direct = hasRows(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            ?: hasRows(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        if (direct != null) return direct
        if (sawSecurityException && treeUri != null && !parentDocumentId.isNullOrBlank() && !documentId.isNullOrBlank()) {
            val childIds = listChildDocumentIds(treeUri, parentDocumentId)
            if (childIds != null) return documentId in childIds
        }
        return true
    }

    private fun listChildDocumentIds(treeUri: Uri, parentDocumentId: String): Set<String>? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val ids = mutableSetOf<String>()
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { cursor ->
            val col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            if (col >= 0) {
                while (cursor.moveToNext()) {
                    cursor.getString(col)?.takeIf { it.isNotBlank() }?.let(ids::add)
                }
            }
        }
        ids
    }.getOrNull()
    private fun listChildNames(treeUri: Uri, parentDocumentId: String): Set<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val names = mutableSetOf<String>()
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (col >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.takeIf { it.isNotBlank() }?.let(names::add)
                    }
                }
            }
        }
        return names
    }

    private fun isDirectoryDocument(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                idx >= 0 && cursor.getString(idx) == DocumentsContract.Document.MIME_TYPE_DIR
            } else false
        } ?: false
    }.getOrDefault(false)

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

    private fun safeArchivePath(raw: String): String =
        safeArchivePathOrNull(raw) ?: throw IllegalArgumentException("Unsafe archive path.")

    private fun safeArchivePathOrNull(raw: String): String? {
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.contains(':')) return null
        val parts = normalized.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/") { safeSegment(it) }
    }

    private fun safeSegment(raw: String): String = raw.replace('/', '_').replace('\\', '_')
        .filter { it.code >= 32 }.take(240).takeUnless { it == "." || it == ".." }.orEmpty()

    private fun computeSizeAndCrc(uri: Uri): Pair<Long, Long>? = runCatching {
        val crc = CRC32()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                crc.update(buffer, 0, n)
                total += n.toLong()
            }
            total to crc.value
        }
    }.getOrNull()

    private fun shouldStoreUncompressed(name: String, mime: String): Boolean {
        if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mp3", "m4a", "aac", "flac", "ogg", "opus", "jpg", "jpeg", "png", "webp", "gif", "zip", "rar", "7z", "gz")
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "txt", "log", "srt", "vtt", "ass", "md", "csv", "json", "xml" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private class CountingSink(private val delegate: OutputStream) : OutputStream() {
        var written: Long = 0L
            private set
        private val digest = MessageDigest.getInstance("SHA-256")

        override fun write(value: Int) {
            delegate.write(value)
            digest.update(value.toByte())
            written += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            written += length.toLong()
        }

        override fun flush() {
            delegate.flush()
        }

        fun digestBytes(): ByteArray = digest.digest()
    }

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
