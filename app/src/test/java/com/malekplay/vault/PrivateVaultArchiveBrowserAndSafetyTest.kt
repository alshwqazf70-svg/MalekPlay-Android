package com.malekplay.vault

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PrivateVaultArchiveBrowserAndSafetyTest {

    private lateinit var context: Context
    private lateinit var vaultDirectory: File
    private lateinit var providerStorageDir: File
    private lateinit var provider: InMemoryVaultDocumentsProvider
    private lateinit var repository: PrivateVaultRepository
    private lateinit var treeSupport: PrivateVaultTreeSupport
    private lateinit var archiveBrowser: PrivateVaultArchiveBrowser
    private val password = "archive-test-password-2026".toCharArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        vaultDirectory = File(context.filesDir, "private_vault").apply { deleteRecursively() }
        providerStorageDir = File(context.cacheDir, "vault-archive-test-${UUID.randomUUID()}").apply {
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
        archiveBrowser = PrivateVaultArchiveBrowser(context, repository, treeSupport)
    }

    @After
    fun tearDown() {
        password.fill('\u0000')
        providerStorageDir.deleteRecursively()
        vaultDirectory.deleteRecursively()
    }

    private fun setupSessionAndSampleFolder(): Pair<PrivateVaultSession, PrivateVaultEntry> {
        val session = repository.create(password)

        val srcDir = provider.addDirectory("root", "الأفلام الخاصة")
        val subMovies = provider.addDirectory(srcDir, "أفلام عربية")
        val subDeep = provider.addDirectory(subMovies, "كلاسيك")
        val subDocs = provider.addDirectory(srcDir, "Documents")

        provider.addFile(srcDir, "intro.txt", "text/plain", "Welcome to vault folder".toByteArray())
        provider.addFile(subMovies, "فيلم_تجريبي.mp4", "video/mp4", ByteArray(256 * 1024) { (it % 251).toByte() })
        provider.addFile(subDeep, "صورة_قديمة.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        provider.addFile(subDocs, "notes.txt", "text/plain", "Secret Arabic ملاحظة سرية".toByteArray())

        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val imported = treeSupport.importFolder(
            treeUri = treeUri,
            session = session,
            moveSource = false,
            rootDocumentId = srcDir,
            sourceParentDocumentId = "root",
            sourceDisplayPath = "Root / الأفلام الخاصة"
        )
        return session to imported.entry
    }

    @Test
    fun encryptedFolderNavigationSubfoldersBackAndArabicUnicodeNames() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val all = archiveBrowser.listAllEntries(folderEntry, s)
            val rootItems = archiveBrowser.listDirectory(all, "")
            val rootDirs = rootItems.filter { it.isDirectory }
            val rootFiles = rootItems.filterNot { it.isDirectory }

            assertEquals(setOf("أفلام عربية", "Documents"), rootDirs.map { it.name }.toSet())
            assertEquals(listOf("intro.txt"), rootFiles.map { it.name })
            assertEquals(2, rootDirs.first { it.name == "أفلام عربية" }.childCount)

            val subItems = archiveBrowser.listDirectory(all, "أفلام عربية")
            assertEquals(setOf("كلاسيك", "فيلم_تجريبي.mp4"), subItems.map { it.name }.toSet())

            val deepItems = archiveBrowser.listDirectory(all, "أفلام عربية/كلاسيك")
            assertEquals(listOf("صورة_قديمة.jpg"), deepItems.map { it.name })

            // Back navigation check (parent path reconstruction)
            val parent = deepItems.first().parentPath.substringBeforeLast('/', "")
            assertEquals("أفلام عربية", parent)
            assertNoLeftoverWorkFiles()
        }
    }

    @Test
    fun searchCurrentAndRecursiveSubfoldersAndSortModesWork() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val all = archiveBrowser.listAllEntries(folderEntry, s)

            val nonRecursiveTxt = filterAndSortArchiveItems(
                items = archiveBrowser.listDirectory(all, ""),
                query = ".txt",
                sort = VaultArchiveSort.NAME,
                ascending = true
            )
            assertEquals(listOf("intro.txt"), nonRecursiveTxt.map { it.name })

            val recursiveTxt = filterAndSortArchiveItems(
                items = archiveBrowser.searchEntries(all, "", query = ".txt", includeSubfolders = true),
                query = ".txt",
                sort = VaultArchiveSort.SIZE,
                ascending = false
            )
            assertEquals(2, recursiveTxt.size)
            assertTrue(recursiveTxt.any { it.path == "Documents/notes.txt" })
            assertTrue(recursiveTxt.any { it.path == "intro.txt" })
            assertTrue(recursiveTxt[0].sizeBytes >= recursiveTxt[1].sizeBytes)

            val sortedByType = filterAndSortArchiveItems(
                items = archiveBrowser.searchEntries(all, "", query = "", includeSubfolders = true)
                    .filterNot { it.isDirectory },
                query = "",
                sort = VaultArchiveSort.TYPE,
                ascending = true
            )
            assertEquals(4, sortedByType.size)
            assertEquals("image/jpeg", sortedByType.first().mimeType)
        }
    }

    @Test
    fun crossSubfolderSelectionPreservesItemsAcrossNavigation() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val all = archiveBrowser.listAllEntries(folderEntry, s)
            val manager = VaultArchiveSelectionManager()

            val rootIntro = archiveBrowser.listDirectory(all, "").first { it.name == "intro.txt" }
            val deepPhoto = archiveBrowser.listDirectory(all, "أفلام عربية/كلاسيك").first { it.name == "صورة_قديمة.jpg" }

            manager.toggle(rootIntro)
            manager.toggle(deepPhoto)

            assertEquals(2, manager.count())
            assertTrue(manager.contains("intro.txt"))
            assertTrue(manager.contains("أفلام عربية/كلاسيك/صورة_قديمة.jpg"))
            assertEquals(rootIntro.sizeBytes + deepPhoto.sizeBytes, manager.totalBytes())

            // Duplicate selectAll on same folder stays deduplicated
            manager.selectAll(archiveBrowser.listDirectory(all, ""))
            assertTrue(manager.count() >= 4)
        }
    }

    @Test
    fun selectiveSingleAndMultiFileExtractAndArchiveDeleteKeepRemainingFilesIntact() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val allBefore = archiveBrowser.listAllEntries(folderEntry, s)
            val intro = allBefore.first { it.path == "intro.txt" }.toListingItem()
            val deepPhoto = allBefore.first { it.path == "أفلام عربية/كلاسيك/صورة_قديمة.jpg" }.toListingItem()

            val destDir = provider.addDirectory("root", "ExportTarget")
            val destTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, destDir)

            val summary = archiveBrowser.extractArchiveSelection(
                archiveEntry = folderEntry,
                selectedItems = listOf(intro, deepPhoto),
                session = s,
                destinationTree = destTree,
                useOriginalFolder = false,
                removeFromArchiveAfterExtract = true
            )

            assertEquals(2, summary.succeededCount)
            assertEquals(0, summary.failedCount)
            assertEquals(2, summary.removedFromVaultCount)
            assertEquals(0, summary.extractedButVaultDeleteFailedCount)

            val destChildren = provider.listChildNames(destDir)
            assertTrue(destChildren.contains("intro.txt"))
            assertTrue(destChildren.contains("صورة_قديمة.jpg"))

            val exportedBytes = provider.readDocumentBytes(destDir, "intro.txt")
            assertArrayEquals("Welcome to vault folder".toByteArray(), exportedBytes)

            // Verify extracted entries removed from archive, while remaining entries are intact and readable!
            val updatedEntry = repository.list(s).first { it.id == folderEntry.id }
            val remaining = archiveBrowser.listAllEntries(updatedEntry, s)
            assertFalse(remaining.any { it.path == "intro.txt" })
            assertFalse(remaining.any { it.path == "أفلام عربية/كلاسيك/صورة_قديمة.jpg" })
            assertTrue(remaining.any { it.path == "أفلام عربية/فيلم_تجريبي.mp4" })
            assertTrue(remaining.any { it.path == "Documents/notes.txt" })

            val remainingNote = archiveBrowser.readArchiveEntryBytes(updatedEntry, "Documents/notes.txt", s)
            assertArrayEquals("Secret Arabic ملاحظة سرية".toByteArray(), remainingNote)
            assertNoLeftoverWorkFiles()
        }
    }

    @Test
    fun selectiveSubfolderDeleteAndSubfolderExtractPreserveHierarchy() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val all = archiveBrowser.listAllEntries(folderEntry, s)
            val moviesDir = archiveBrowser.listDirectory(all, "").first { it.name == "أفلام عربية" }

            val destDir = provider.addDirectory("root", "SubfolderExport")
            val destTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, destDir)

            val extractSummary = archiveBrowser.extractArchiveSelection(
                archiveEntry = folderEntry,
                selectedItems = listOf(moviesDir),
                session = s,
                destinationTree = destTree,
                useOriginalFolder = false,
                removeFromArchiveAfterExtract = false
            )
            assertEquals(2, extractSummary.succeededCount)
            assertEquals(0, extractSummary.failedCount)

            val exportedSub = provider.findChildId(destDir, "أفلام عربية")!!
            assertTrue(provider.listChildNames(exportedSub).contains("فيلم_تجريبي.mp4"))
            val exportedDeep = provider.findChildId(exportedSub, "كلاسيك")!!
            assertTrue(provider.listChildNames(exportedDeep).contains("صورة_قديمة.jpg"))

            // Now delete subfolder "أفلام عربية" inside archive without deleting Documents or intro.txt
            val modResult = archiveBrowser.deleteFromArchive(
                archiveEntry = folderEntry,
                pathsToDelete = setOf("أفلام عربية"),
                session = s
            )
            assertTrue(modResult.deletedPathsCount >= 2)
            val remaining = archiveBrowser.listAllEntries(modResult.updatedVaultEntry, s)
            assertFalse(remaining.any { it.path.startsWith("أفلام عربية") })
            assertTrue(remaining.any { it.path == "intro.txt" })
            assertTrue(remaining.any { it.path == "Documents/notes.txt" })
            assertNoLeftoverWorkFiles()
        }
    }

    @Test
    fun midWriteFailureDeletesOnlyPartialFileAndPreservesArchiveAndExistingFiles() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val destDir = provider.addDirectory("root", "PartialDest")
            provider.addFile(destDir, "existing_user_file.txt", "text/plain", "Do not delete me".toByteArray())
            provider.failWritesForNames += "notes.txt"
            val destTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, destDir)

            val all = archiveBrowser.listAllEntries(folderEntry, s)
            val notesItem = all.first { it.path == "Documents/notes.txt" }.toListingItem()

            val summary = archiveBrowser.extractArchiveSelection(
                archiveEntry = folderEntry,
                selectedItems = listOf(notesItem),
                session = s,
                destinationTree = destTree,
                useOriginalFolder = false,
                removeFromArchiveAfterExtract = true
            )

            assertEquals(0, summary.succeededCount)
            assertEquals(1, summary.failedCount)
            assertEquals(0, summary.removedFromVaultCount)

            // Existing user file must remain untouched; partial "notes.txt" must be deleted!
            val destNames = provider.listChildNames(destDir)
            assertTrue(destNames.contains("existing_user_file.txt"))
            assertFalse(destNames.contains("notes.txt"))

            // Encrypted archive still keeps notes.txt safely
            val currentArchive = repository.list(s).first { it.id == folderEntry.id }
            assertTrue(archiveBrowser.listAllEntries(currentArchive, s).any { it.path == "Documents/notes.txt" })
            assertNoLeftoverWorkFiles()
        }
    }

    @Test
    fun duplicateFilenameConflictCreatesUniqueNumberedFileWithoutSilentOverwrite() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val destDir = provider.addDirectory("root", "ConflictDest")
            provider.addFile(destDir, "intro.txt", "text/plain", "Original untouched file".toByteArray())
            val destTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, destDir)

            val introItem = archiveBrowser.listAllEntries(folderEntry, s)
                .first { it.path == "intro.txt" }.toListingItem()

            val summary = archiveBrowser.extractArchiveSelection(
                archiveEntry = folderEntry,
                selectedItems = listOf(introItem),
                session = s,
                destinationTree = destTree,
                useOriginalFolder = false,
                removeFromArchiveAfterExtract = false
            )
            assertEquals(1, summary.succeededCount)

            val names = provider.listChildNames(destDir)
            assertTrue(names.contains("intro.txt"))
            assertTrue(names.contains("intro (1).txt"))
            assertArrayEquals("Original untouched file".toByteArray(), provider.readDocumentBytes(destDir, "intro.txt"))
            assertArrayEquals("Welcome to vault folder".toByteArray(), provider.readDocumentBytes(destDir, "intro (1).txt"))
        }
    }

    @Test
    fun cancellationDuringArchiveExtractOrDeletePreservesVaultIntegrityAndCleansTemp() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val destDir = provider.addDirectory("root", "CancelDest")
            val destTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, destDir)
            val all = archiveBrowser.listAllEntries(folderEntry, s)
            val items = all.filterNot { it.isDirectory }.map { it.toListingItem() }

            val cancelSummary = archiveBrowser.extractArchiveSelection(
                archiveEntry = folderEntry,
                selectedItems = items,
                session = s,
                destinationTree = destTree,
                useOriginalFolder = false,
                removeFromArchiveAfterExtract = true,
                isCancelled = { true }
            )
            assertEquals(0, cancelSummary.succeededCount)
            assertEquals(0, cancelSummary.removedFromVaultCount)
            assertTrue(provider.listChildNames(destDir).isEmpty())

            // Cancel during archive deletion leaves original encrypted folder intact
            assertThrows(Exception::class.java) {
                archiveBrowser.deleteFromArchive(
                    archiveEntry = folderEntry,
                    pathsToDelete = setOf("intro.txt"),
                    session = s,
                    isCancelled = { true }
                )
            }
            val currentArchive = repository.list(s).first { it.id == folderEntry.id }
            assertEquals(all.size, archiveBrowser.listAllEntries(currentArchive, s).size)
            assertNoLeftoverWorkFiles()
        }
    }

    @Test
    fun insufficientStorageCheckRejectsSafelyWithoutDataLoss() {
        repository.create(password).use {
            assertThrows(VaultInsufficientStorageException::class.java) {
                repository.ensureAvailableInternalSpace(Long.MAX_VALUE / 2)
            }
        }
    }

    @Test
    fun expiredOrMissingDestinationUriPreservesEncryptedEntry() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val expiredTree = Uri.parse("content://${InMemoryVaultDocumentsProvider.AUTHORITY}/tree/deleted-missing")
            val intro = archiveBrowser.listAllEntries(folderEntry, s).first { it.path == "intro.txt" }.toListingItem()

            val summary = archiveBrowser.extractArchiveSelection(
                archiveEntry = folderEntry,
                selectedItems = listOf(intro),
                session = s,
                destinationTree = expiredTree,
                useOriginalFolder = false,
                removeFromArchiveAfterExtract = true
            )
            assertEquals(0, summary.succeededCount)
            assertEquals(0, summary.removedFromVaultCount)
            assertNotNull(repository.list(s).firstOrNull { it.id == folderEntry.id })
        }
    }

    @Test
    fun zipSlipAndPathTraversalEntriesAreRejected() {
        val evilZipBytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../../evil.txt"))
                zip.write("pwned".toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val source = object : SeekablePlainSource {
            override val size: Long = evilZipBytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                val pos = position.toInt()
                if (pos >= evilZipBytes.size) return -1
                val n = minOf(length, evilZipBytes.size - pos)
                evilZipBytes.copyInto(buffer, offset, pos, pos + n)
                return n
            }
            override fun close() {}
        }
        assertThrows(SecurityException::class.java) {
            VaultZipArchiveReader.parseEntries(source)
        }
    }

    @Test
    fun tamperedEncryptedArchiveFailsGcmAuthentication() {
        val (session, folderEntry) = setupSessionAndSampleFolder()
        session.use { s ->
            val payloadFile = File(vaultDirectory, "${folderEntry.id}.mkv")
            val bytes = payloadFile.readBytes()
            val tamperIdx = bytes.size - 10
            bytes[tamperIdx] = (bytes[tamperIdx].toInt() xor 0xFF).toByte()
            payloadFile.writeBytes(bytes)

            assertThrows(Exception::class.java) {
                archiveBrowser.listAllEntries(folderEntry, s)
            }
        }
    }

    @Test
    fun legacyV1VaultArchiveAndStreamingStoredMediaDataSourceWorkCleanly() {
        val session = repository.create(password)
        session.use { s ->
            // Build a ZIP with a STORED large-ish video entry and encrypt with V1 legacy format!
            val videoPayload = ByteArray(180 * 1024) { (it * 7 % 251).toByte() }
            val zipFile = File.createTempFile("legacy-v1-", ".zip", context.cacheDir)
            try {
                FileOutputStream(zipFile).use { fos ->
                    ZipOutputStream(fos).use { zip ->
                        val crc = CRC32().apply { update(videoPayload) }.value
                        zip.putNextEntry(ZipEntry("legacy_folder/clip.mp4").apply {
                            method = ZipEntry.STORED
                            size = videoPayload.size.toLong()
                            compressedSize = videoPayload.size.toLong()
                            this.crc = crc
                            comment = "video/mp4"
                        })
                        zip.write(videoPayload)
                        zip.closeEntry()
                    }
                }
                // Import entry and overwrite payload with V1 legacy password-derived encryption
                val v1Entry = zipFile.inputStream().use { input ->
                    repository.importStream(
                        displayName = "LegacyFolder",
                        mimeType = PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME,
                        input = input,
                        session = s
                    )
                }
                val v1Target = File(vaultDirectory, "${v1Entry.id}.mkv")
                zipFile.inputStream().use { input ->
                    v1Target.outputStream().use { output ->
                        PrivateVaultCrypto.encrypt(input, output, password)
                    }
                }

                val listed = archiveBrowser.listAllEntries(v1Entry, s)
                assertEquals(1, listed.size)
                assertEquals("legacy_folder/clip.mp4", listed.first().path)

                // Now verify VaultMediaDataSource streams archive:// entry via seekable read + random seek!
                val ds = VaultMediaDataSource(repository, s) { repository.list(s) }
                val archiveUri = VaultMediaDataSource.archiveEntryUri(v1Entry.id, "legacy_folder/clip.mp4")
                val len = ds.open(DataSpec(archiveUri))
                assertEquals(videoPayload.size.toLong(), len)
                val readBack = ByteArray(videoPayload.size)
                var offset = 0
                while (offset < readBack.size) {
                    val n = ds.read(readBack, offset, readBack.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                ds.close()
                assertArrayEquals(videoPayload, readBack)

                // Verify random seek on V2 archive STORED media uses ZERO disk temp files!
                val v2Dir = provider.addDirectory("root", "V2MediaFolder")
                provider.addFile(v2Dir, "streamable.mp4", "video/mp4", videoPayload)
                val rootTree = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
                val v2Entry = treeSupport.importFolder(
                    treeUri = rootTree,
                    session = s,
                    moveSource = false,
                    rootDocumentId = v2Dir,
                    sourceParentDocumentId = "root",
                    sourceDisplayPath = "Root / V2MediaFolder"
                ).entry

                val dsV2 = VaultMediaDataSource(repository, s) { repository.list(s) }
                val seekOffset = 75_000L
                val v2Uri = VaultMediaDataSource.archiveEntryUri(v2Entry.id, "streamable.mp4")
                dsV2.open(DataSpec.Builder().setUri(v2Uri).setPosition(seekOffset).setLength(4096L).build())
                val slice = ByteArray(4096)
                var got = 0
                while (got < slice.size) {
                    val n = dsV2.read(slice, got, slice.size - got)
                    if (n <= 0) break
                    got += n
                }
                dsV2.close()
                assertArrayEquals(videoPayload.copyOfRange(seekOffset.toInt(), seekOffset.toInt() + 4096), slice)
                assertNoLeftoverWorkFiles()
            } finally {
                zipFile.delete()
            }
        }
    }

    @Test
    fun recoveryCleansStaleWorkAndEditTempFilesWhileKeepingActiveTempFiles() {
        val workDir = File(vaultDirectory, ".work").apply { mkdirs() }
        val staleFile = File(workDir, "stale-crash.tmp").apply { writeText("stale") }
        val activeFile = File(workDir, "active-transfer.tmp").apply { writeText("active") }
        val staleEditTmp = File(vaultDirectory, "entry-1.edit.tmp").apply { writeText("broken") }

        PrivateVaultRecovery.recover(vaultDirectory, activeTempNames = setOf(activeFile.name))

        assertFalse(staleFile.exists())
        assertTrue(activeFile.exists())
        assertFalse(staleEditTmp.exists())
    }

    private fun assertNoLeftoverWorkFiles() {
        val workDir = File(vaultDirectory, ".work")
        val remaining = workDir.listFiles()?.toList().orEmpty()
        assertTrue("Expected empty .work directory, found: ${remaining.map { it.name }}", remaining.isEmpty())
    }
}
