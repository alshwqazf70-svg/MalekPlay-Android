package com.malekplay.vault

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PrivateVaultBrowserAndManagementTest {

    private lateinit var context: Context
    private lateinit var vaultDirectory: File
    private lateinit var providerStorageDir: File
    private lateinit var provider: InMemoryVaultDocumentsProvider
    private lateinit var repository: PrivateVaultRepository
    private lateinit var treeSupport: PrivateVaultTreeSupport
    private val password = "vault-strong-password-2026".toCharArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        vaultDirectory = File(context.filesDir, "private_vault").apply { deleteRecursively() }
        providerStorageDir = File(context.cacheDir, "vault-provider-test-${UUID.randomUUID()}").apply {
            mkdirs()
        }
        provider = InMemoryVaultDocumentsProvider(providerStorageDir)
        val info = ProviderInfo().apply {
            authority = InMemoryVaultDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(InMemoryVaultDocumentsProvider.AUTHORITY, provider)

        repository = PrivateVaultRepository(context)
        treeSupport = PrivateVaultTreeSupport(context, repository)
    }

    @After
    fun tearDown() {
        password.fill('\u0000')
        providerStorageDir.deleteRecursively()
        vaultDirectory.deleteRecursively()
    }

    @Test
    fun crossFolderSelectionPreservesAllItemsAcrossNavigationAndAvoidsDuplicates() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val manager = VaultBrowserSelectionManager()

        val folderAItems = listOf(
            VaultBrowserItem(
                documentId = "doc-a1",
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc-a1"),
                displayName = "alpha.mp4",
                mimeType = "video/mp4",
                isDirectory = false,
                sizeBytes = 1200L,
                lastModified = 100L
            ),
            VaultBrowserItem(
                documentId = "doc-a2",
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc-a2"),
                displayName = "beta.jpg",
                mimeType = "image/jpeg",
                isDirectory = false,
                sizeBytes = 800L,
                lastModified = 200L
            )
        )
        val folderBItems = listOf(
            VaultBrowserItem(
                documentId = "doc-b1",
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc-b1"),
                displayName = "gamma.mp3",
                mimeType = "audio/mpeg",
                isDirectory = false,
                sizeBytes = 500L,
                lastModified = 300L
            ),
            VaultBrowserItem(
                documentId = "doc-b2-dir",
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc-b2-dir"),
                displayName = "SubFolder",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                isDirectory = true,
                sizeBytes = -1L,
                lastModified = 400L
            )
        )

        // Select from Folder A
        manager.selectAll(folderAItems, treeUri, "folder-a", "Root / FolderA")
        assertEquals(2, manager.count())
        assertEquals(2000L, manager.totalBytes())

        // Navigate to Folder B and select gamma.mp3 (Folder A selections must stay intact!)
        manager.select(folderBItems[0], treeUri, "folder-b", "Root / FolderB")
        // Selecting duplicate URI must not duplicate count
        manager.select(folderAItems[0], treeUri, "folder-a", "Root / FolderA")
        // Select-all in Folder B excludes directories by default
        manager.selectAll(folderBItems, treeUri, "folder-b", "Root / FolderB")

        assertEquals(3, manager.count())
        assertEquals(2500L, manager.totalBytes())
        assertTrue(manager.contains(folderAItems[0].uri))
        assertTrue(manager.contains(folderAItems[1].uri))
        assertTrue(manager.contains(folderBItems[0].uri))
        assertFalse(manager.contains(folderBItems[1].uri))

        // Deselecting Folder B items leaves Folder A selections intact
        manager.deselectAll(folderBItems)
        assertEquals(2, manager.count())
        assertEquals("Root / FolderA", manager.selectedItems().first().displayFolderPath)
    }

    @Test
    fun browserFilteringAndSortingKeepDirectoriesFirstAndOrderFilesProperly() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        fun item(id: String, name: String, dir: Boolean, size: Long, date: Long) = VaultBrowserItem(
            documentId = id,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
            displayName = name,
            mimeType = if (dir) DocumentsContract.Document.MIME_TYPE_DIR else "video/mp4",
            isDirectory = dir,
            sizeBytes = size,
            lastModified = date
        )

        val items = listOf(
            item("1", "zebra.mp4", false, 50L, 100L),
            item("2", "Albums", true, -1L, 50L),
            item("3", "alpha.mp4", false, 500L, 300L),
            item("4", "Camera", true, -1L, 200L)
        )

        val bySizeDesc = filterAndSortBrowserItems(items, "", VaultBrowserSort.SIZE, ascending = false)
        assertTrue(bySizeDesc[0].isDirectory)
        assertTrue(bySizeDesc[1].isDirectory)
        assertEquals("alpha.mp4", bySizeDesc[2].displayName)
        assertEquals("zebra.mp4", bySizeDesc[3].displayName)

        val byDateAsc = filterAndSortBrowserItems(items, "", VaultBrowserSort.DATE, ascending = true)
        assertEquals("Albums", byDateAsc[0].displayName)
        assertEquals("Camera", byDateAsc[1].displayName)
        assertEquals("zebra.mp4", byDateAsc[2].displayName)
        assertEquals("alpha.mp4", byDateAsc[3].displayName)

        val searched = filterAndSortBrowserItems(items, "ALP", VaultBrowserSort.NAME, ascending = true)
        assertEquals(listOf("alpha.mp4"), searched.map { it.displayName })
    }

    @Test
    fun vaultEntryFilteringAndSortingHandleTypesSearchAndDirection() {
        val entries = listOf(
            PrivateVaultEntry("1", "clip.mp4", "video/mp4", 900L, 100L),
            PrivateVaultEntry("2", "song.mp3", "audio/mpeg", 300L, 300L),
            PrivateVaultEntry("3", "photo.jpg", "image/jpeg", 500L, 200L),
            PrivateVaultEntry("4", "notes.txt", "text/plain", 100L, 400L)
        )

        assertEquals(listOf("clip.mp4"), filterAndSortVaultEntries(entries, "", VaultEntryFilter.VIDEO, VaultEntrySort.NEWEST).map { it.displayName })
        assertEquals(listOf("song.mp3"), filterAndSortVaultEntries(entries, "", VaultEntryFilter.AUDIO, VaultEntrySort.NEWEST).map { it.displayName })
        assertEquals(listOf("photo.jpg"), filterAndSortVaultEntries(entries, "", VaultEntryFilter.IMAGE, VaultEntrySort.NEWEST).map { it.displayName })
        assertEquals(listOf("notes.txt"), filterAndSortVaultEntries(entries, "", VaultEntryFilter.OTHER, VaultEntrySort.NEWEST).map { it.displayName })

        val byNameAsc = filterAndSortVaultEntries(entries, "", VaultEntryFilter.ALL, VaultEntrySort.NAME, ascending = true)
        assertEquals(listOf("clip.mp4", "notes.txt", "photo.jpg", "song.mp3"), byNameAsc.map { it.displayName })

        val bySizeDesc = filterAndSortVaultEntries(entries, "", VaultEntryFilter.ALL, VaultEntrySort.SIZE, ascending = false)
        assertEquals(listOf("clip.mp4", "photo.jpg", "song.mp3", "notes.txt"), bySizeDesc.map { it.displayName })
    }

    @Test
    fun resolveUniqueChildNameAvoidsCaseInsensitiveOverwritesForFilesAndFolders() {
        val existingFiles = setOf("Photo.JPG", "photo (1).jpg", "readme")
        assertEquals("photo (2).jpg", resolveUniqueChildName("photo.jpg", existingFiles, isDirectory = false))
        assertEquals("README (1)", resolveUniqueChildName("README", existingFiles, isDirectory = false))

        val existingDirs = setOf("Vacation", "vacation (1)")
        assertEquals("Vacation (2)", resolveUniqueChildName("Vacation", existingDirs, isDirectory = true))
    }

    @Test
    fun indexV3PersistsAndRestoresSourceOriginMetadataAcrossSessions() {
        val session = repository.create(password)
        val origin = VaultSourceOrigin(
            treeUri = "content://com.malekplay.test.documents/tree/root",
            parentDocumentId = "folder-dcim",
            displayPath = "Root / DCIM / Camera"
        )
        val bytes = "hello encrypted source origin".toByteArray()
        val stored = repository.importStream(
            displayName = "camera_01.jpg",
            mimeType = "image/jpeg",
            input = ByteArrayInputStream(bytes),
            session = session,
            sourceOrigin = origin
        )
        assertEquals(origin.treeUri, stored.sourceTreeUri)
        assertEquals(origin.parentDocumentId, stored.sourceParentDocumentId)
        assertEquals(origin.displayPath, stored.sourceDisplayPath)
        session.close()

        val reopened = repository.openSession(password)
        try {
            val reloaded = repository.list(reopened).single()
            assertEquals("camera_01.jpg", reloaded.displayName)
            assertEquals(origin.treeUri, reloaded.sourceTreeUri)
            assertEquals(origin.parentDocumentId, reloaded.sourceParentDocumentId)
            assertEquals(origin.displayPath, reloaded.sourceDisplayPath)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun importBrowserSelectionVerifiesActualDeletionAndNeverFalselyClaimsMove() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val dirA = provider.addDirectory("root", "folder-a", "FolderA")
        val dirB = provider.addDirectory("root", "folder-b", "FolderB")
        val movableBytes = ByteArray(4096) { (it * 31).toByte() }
        val stubbornBytes = ByteArray(2048) { (it * 13).toByte() }
        val movableDocId = provider.addFile(dirA, "movable.bin", "application/octet-stream", movableBytes)
        val stubbornDocId = provider.addFile(dirB, "stubborn.bin", "application/octet-stream", stubbornBytes)

        // Provider pretends delete succeeded on stubbornDocId without deleting it!
        provider.unremovableDocumentIds.add(stubbornDocId)

        val session = repository.create(password)
        try {
            val selected = listOf(
                SelectedVaultBrowserItem(
                    documentId = movableDocId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, movableDocId),
                    displayName = "movable.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = movableBytes.size.toLong(),
                    lastModified = 100L,
                    isDirectory = false,
                    treeUri = treeUri,
                    parentDocumentId = dirA,
                    displayFolderPath = "Root / FolderA"
                ),
                SelectedVaultBrowserItem(
                    documentId = stubbornDocId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, stubbornDocId),
                    displayName = "stubborn.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = stubbornBytes.size.toLong(),
                    lastModified = 200L,
                    isDirectory = false,
                    treeUri = treeUri,
                    parentDocumentId = dirB,
                    displayFolderPath = "Root / FolderB"
                )
            )

            val result = treeSupport.importBrowserSelection(selected, session, moveSource = true)
            assertEquals(1, result.importedCount)
            assertEquals(1, result.failedCount)
            assertEquals(1, result.movedCount)
            assertEquals(0, result.copiedOnlyCount)
            assertFalse(provider.hasDocument(movableDocId))
            assertTrue(provider.hasDocument(stubbornDocId))

            val storedEntries = repository.list(session)
            assertEquals(1, storedEntries.size)
            assertEquals("movable.bin", storedEntries.single().displayName)
            assertEquals(treeUri.toString(), storedEntries.single().sourceTreeUri)
            assertEquals(
                listOf(VaultMoveItemStatus.MOVED, VaultMoveItemStatus.SOURCE_DELETE_FAILED),
                result.itemOutcomes.map { it.status }
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun restoreEntriesSupportsOriginalFolderConflictSafetyAndTakeOutVsCopy() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val dcimId = provider.addDirectory("root", "dcim", "DCIM")
        val exportDestId = provider.addDirectory("root", "export-dest", "Exports")
        val customDestTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, exportDestId)

        val payloadA = "secret-photo-bytes-12345".toByteArray()
        val payloadB = "secret-video-bytes-67890".toByteArray()
        val docAId = provider.addFile(dcimId, "vacation.jpg", "image/jpeg", payloadA)
        val docBId = provider.addFile(dcimId, "movie.mp4", "video/mp4", payloadB)

        val session = repository.create(password)
        try {
            val selected = listOf(
                SelectedVaultBrowserItem(
                    documentId = docAId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docAId),
                    displayName = "vacation.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = payloadA.size.toLong(),
                    lastModified = 10L,
                    isDirectory = false,
                    treeUri = treeUri,
                    parentDocumentId = dcimId,
                    displayFolderPath = "Root / DCIM"
                ),
                SelectedVaultBrowserItem(
                    documentId = docBId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docBId),
                    displayName = "movie.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = payloadB.size.toLong(),
                    lastModified = 20L,
                    isDirectory = false,
                    treeUri = treeUri,
                    parentDocumentId = dcimId,
                    displayFolderPath = "Root / DCIM"
                )
            )
            treeSupport.importBrowserSelection(selected, session, moveSource = true)
            assertFalse(provider.hasDocument(docAId))
            assertFalse(provider.hasDocument(docBId))

            val vaultEntries = repository.list(session)
            assertTrue(treeSupport.canRestoreAllToOriginalFolder(vaultEntries))

            // Put an existing file with the same name in DCIM to verify conflict resolution
            provider.addFile(dcimId, "vacation.jpg", "image/jpeg", "existing-user-photo".toByteArray())

            // Copy out ("نسخ نسخة") to original folder keeps encrypted vault entries
            val copySummary = treeSupport.restoreEntries(
                entries = listOf(vaultEntries.first { it.displayName == "vacation.jpg" }),
                destinationTree = null,
                session = session,
                useOriginalFolder = true,
                removeFromVaultAfterRestore = false
            )
            assertEquals(1, copySummary.succeededCount)
            assertEquals(0, copySummary.failedCount)
            assertEquals(0, copySummary.removedFromVaultCount)
            assertEquals(1, copySummary.keptInVaultCount)
            assertEquals(2, repository.list(session).size)
            assertArrayEquals("existing-user-photo".toByteArray(), provider.readFileBytes(dcimId, "vacation.jpg"))
            assertArrayEquals(payloadA, provider.readFileBytes(dcimId, "vacation (1).jpg"))

            // Take out ("إخراج من الخزنة") to custom destination deletes from vault only after verified write
            val moveOutSummary = treeSupport.restoreEntries(
                entries = vaultEntries,
                destinationTree = customDestTree,
                session = session,
                useOriginalFolder = false,
                removeFromVaultAfterRestore = true
            )
            assertEquals(2, moveOutSummary.succeededCount)
            assertEquals(0, moveOutSummary.failedCount)
            assertEquals(2, moveOutSummary.removedFromVaultCount)
            assertEquals(0, repository.list(session).size)
            assertArrayEquals(payloadA, provider.readFileBytes(exportDestId, "vacation.jpg"))
            assertArrayEquals(payloadB, provider.readFileBytes(exportDestId, "movie.mp4"))
        } finally {
            session.close()
        }
    }

    @Test
    fun partialFolderRestoreNeverDeletesEncryptedArchiveAndPreservesVaultCopy() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val sourceAlbumId = provider.addDirectory("root", "album", "Album")
        val subDirId = provider.addDirectory(sourceAlbumId, "album-sub", "Day1")
        provider.addFile(sourceAlbumId, "cover.jpg", "image/jpeg", "cover-bytes".toByteArray())
        provider.addFile(subDirId, "broken.bin", "application/octet-stream", "important-bytes".toByteArray())

        val restoreDestId = provider.addDirectory("root", "restore-target", "Restored")
        val restoreTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, restoreDestId)

        val session = repository.create(password)
        try {
            val folderResult = treeSupport.importFolder(
                treeUri = treeUri,
                session = session,
                moveSource = false,
                rootDocumentId = sourceAlbumId,
                sourceParentDocumentId = "root",
                sourceDisplayPath = "Root / Album"
            )
            assertEquals(2, folderResult.fileCount)

            // Simulate write failure on the second file inside the archive
            provider.failWritesForNames.add("broken.bin")

            val summary = treeSupport.restoreEntries(
                entries = listOf(folderResult.entry),
                destinationTree = restoreTree,
                session = session,
                useOriginalFolder = false,
                removeFromVaultAfterRestore = true
            )

            assertEquals(0, summary.succeededCount)
            assertEquals(1, summary.failedCount)
            assertEquals(0, summary.removedFromVaultCount)
            assertEquals(1, summary.keptInVaultCount)
            // Encrypted archive must still exist in the vault!
            assertEquals(1, repository.list(session).size)
            // The successful sibling file inside the folder should still be readable
            val restoredAlbumId = provider.findChildDirId(restoreDestId, "Album")
            assertNotNull(restoredAlbumId)
            assertArrayEquals("cover-bytes".toByteArray(), provider.readFileBytes(restoredAlbumId!!, "cover.jpg"))
        } finally {
            session.close()
        }
    }

    @Test
    fun exportToDocumentVerifiedFailsOnWriteErrorWithoutDeletingVaultCopy() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val dirId = provider.addDirectory("root", "dest", "Dest")
        val session = repository.create(password)
        try {
            val payload = ByteArray(8192) { (it % 127).toByte() }
            val entry = repository.importStream(
                displayName = "doc.dat",
                mimeType = "application/octet-stream",
                input = ByteArrayInputStream(payload),
                session = session
            )

            val brokenFileId = provider.addFile(dirId, "broken_out.dat", "application/octet-stream", ByteArray(0))
            val brokenUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, brokenFileId)
            provider.failWritesForNames.add("broken_out.dat")

            assertThrows(Exception::class.java) {
                repository.exportToDocumentVerified(entry.id, brokenUri, session)
            }
            // Vault copy must remain intact
            assertEquals(1, repository.list(session).size)
        } finally {
            session.close()
        }
    }
}

internal class InMemoryVaultDocumentsProvider(
    private val backingDir: File
) : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.malekplay.test.vault.documents"
    }

    private data class DocNode(
        val id: String,
        val parentId: String?,
        var name: String,
        val mimeType: String,
        val isDirectory: Boolean,
        val backingFile: File?,
        var lastModified: Long = System.currentTimeMillis()
    )

    private val nodes = linkedMapOf<String, DocNode>()
    val unremovableDocumentIds = mutableSetOf<String>()
    val failWritesForNames = mutableSetOf<String>()

    init {
        nodes["root"] = DocNode("root", null, "Root", DocumentsContract.Document.MIME_TYPE_DIR, true, null)
    }

    fun addDirectory(parentId: String, name: String): String =
        addDirectory(parentId, "dir-${UUID.randomUUID()}", name)

    fun addDirectory(parentId: String, id: String, name: String): String {
        nodes[id] = DocNode(id, parentId, name, DocumentsContract.Document.MIME_TYPE_DIR, true, null)
        return id
    }

    fun addFile(parentId: String, name: String, mimeType: String, bytes: ByteArray): String {
        val id = "doc-${UUID.randomUUID()}"
        val file = File(backingDir, "$id.bin").apply { writeBytes(bytes) }
        nodes[id] = DocNode(id, parentId, name, mimeType, false, file)
        return id
    }

    fun hasDocument(docId: String): Boolean = nodes.containsKey(docId)

    fun listChildNames(parentId: String): Set<String> =
        nodes.values.filter { it.parentId == parentId }.map { it.name }.toSet()

    fun findChildId(parentId: String, name: String): String? =
        nodes.values.firstOrNull { it.parentId == parentId && it.name == name }?.id

    fun findChildDirId(parentId: String, name: String): String? =
        nodes.values.firstOrNull { it.parentId == parentId && it.isDirectory && it.name == name }?.id

    fun readFileBytes(parentId: String, name: String): ByteArray? =
        nodes.values.firstOrNull { it.parentId == parentId && !it.isDirectory && it.name == name }?.backingFile?.readBytes()

    fun readDocumentBytes(parentId: String, name: String): ByteArray? = readFileBytes(parentId, name)

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        if (parentDocumentId == documentId) return true
        var cursor: DocNode? = nodes[documentId]
        val visited = HashSet<String>()
        while (cursor != null && visited.add(cursor.id)) {
            val pid = cursor.parentId ?: return false
            if (pid == parentDocumentId) return true
            cursor = nodes[pid]
        }
        return false
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS
        )
        return MatrixCursor(cols).apply {
            addRow(arrayOf("root", "root", "Root", DocumentsContract.Root.FLAG_SUPPORTS_CREATE))
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val node = nodes[documentId] ?: throw FileNotFoundException("Missing $documentId")
        val cols = projection ?: defaultDocProjection()
        return MatrixCursor(cols).apply { addNodeRow(this, cols, node) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (!nodes.containsKey(parentDocumentId)) throw FileNotFoundException("Missing parent $parentDocumentId")
        val cols = projection ?: defaultDocProjection()
        return MatrixCursor(cols).apply {
            nodes.values.filter { it.parentId == parentDocumentId }.forEach { addNodeRow(this, cols, it) }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (!nodes.containsKey(parentDocumentId)) throw FileNotFoundException("Missing parent $parentDocumentId")
        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val id = "doc-${UUID.randomUUID()}"
        val file = if (isDir) null else File(backingDir, "$id.bin").apply { createNewFile() }
        nodes[id] = DocNode(id, parentDocumentId, displayName, mimeType, isDir, file)
        return id
    }

    override fun deleteDocument(documentId: String) {
        if (documentId in unremovableDocumentIds) {
            // Simulate buggy OEM provider that returns without actually deleting the document.
            return
        }
        val removed = nodes.remove(documentId) ?: throw FileNotFoundException("Missing $documentId")
        removed.backingFile?.delete()
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException("Missing $documentId")
        val file = node.backingFile ?: throw FileNotFoundException("Directory cannot be opened")
        if ((mode.contains('w') || mode.contains('t')) && node.name in failWritesForNames) {
            throw FileNotFoundException("Simulated write failure for ${node.name}")
        }
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    private fun defaultDocProjection(): Array<String> = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    )

    private fun addNodeRow(cursor: MatrixCursor, cols: Array<out String>, node: DocNode) {
        val values = Array<Any?>(cols.size) { idx ->
            when (cols[idx]) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> node.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mimeType
                DocumentsContract.Document.COLUMN_SIZE, OpenableColumns.SIZE ->
                    if (node.isDirectory) null else node.backingFile?.length() ?: 0L
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> node.lastModified
                DocumentsContract.Document.COLUMN_FLAGS ->
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                else -> null
            }
        }
        cursor.addRow(values)
    }
}
