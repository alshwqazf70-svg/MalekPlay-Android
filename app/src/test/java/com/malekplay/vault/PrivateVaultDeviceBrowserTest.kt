package com.malekplay.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.MediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class PrivateVaultDeviceBrowserTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var providerBackingDir: File
    private lateinit var provider: InMemoryVaultDocumentsProvider
    private lateinit var vault: PrivateVaultRepository
    private lateinit var treeSupport: PrivateVaultTreeSupport
    private lateinit var session: PrivateVaultSession

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = Files.createTempDirectory("vault-device-browser-test").toFile()
        File(context.filesDir, "private_vault").deleteRecursively()

        providerBackingDir = File(tempDir, "provider").apply { mkdirs() }
        val info = android.content.pm.ProviderInfo().apply {
            authority = InMemoryVaultDocumentsProvider.AUTHORITY
            grantUriPermissions = true
            exported = true
        }
        provider = Robolectric.buildContentProvider(InMemoryVaultDocumentsProvider::class.java)
            .create(info)
            .get()
        provider.reset(providerBackingDir)

        vault = PrivateVaultRepository(context)
        treeSupport = PrivateVaultTreeSupport(context, vault)
        session = vault.create("BrowserVault#2026".toCharArray())
    }

    @After
    fun tearDown() {
        session.close()
        File(context.filesDir, "private_vault").deleteRecursively()
        tempDir.deleteRecursively()
    }

    @Test
    fun openFolderList_startsInFolderListAndNeverAutoClosesOrLaunchesPicker() {
        val holder = VaultBrowserStateHolder()
        val opened = holder.openFolderList()
        assertEquals(VaultBrowserStep.FolderList, opened.step)
        assertTrue(opened.isOpen)
        assertFalse(opened.isInsideFolder)
        assertEquals(0, opened.pendingCount)
    }

    @Test
    fun closeButton_stateAInsideFolderReturnsToFolderListKeepingSelections_stateBAtFolderListClosesSession() {
        val holder = VaultBrowserStateHolder()
        holder.openFolderList()

        val moviesFolder = VaultDeviceFolder(path = "Movies", name = "Movies", itemCount = 3)
        val clip1 = fakeDeviceItem("1", "Movies", "clip1.mp4", 1_200L)
        val clip2 = fakeDeviceItem("2", "Movies", "clip2.mkv", 2_500L)

        holder.enterFolder(moviesFolder)
        assertTrue(holder.snapshot.isInsideFolder)
        assertTrue(holder.toggleSelection(clip1))
        assertTrue(holder.toggleSelection(clip2))
        assertEquals(2, holder.snapshot.pendingCount)

        // State A: pressing X inside a folder returns to FolderList WITHOUT closing or losing selections.
        val afterStateA = holder.onCloseButtonPressed()
        assertEquals(VaultBrowserStep.FolderList, afterStateA.step)
        assertTrue(afterStateA.isOpen)
        assertEquals(2, afterStateA.pendingCount)
        assertEquals(3_700L, afterStateA.pendingTotalBytes)
        assertTrue(afterStateA.pendingSelectedByKey.containsKey(clip1.selectionKey))
        assertTrue(afterStateA.pendingSelectedByKey.containsKey(clip2.selectionKey))

        // State B: pressing X at FolderList ends the add session and returns to Vault.
        val afterStateB = holder.onCloseButtonPressed()
        assertEquals(VaultBrowserStep.Closed, afterStateB.step)
        assertFalse(afterStateB.isOpen)
        assertEquals(0, afterStateB.pendingCount)
    }

    @Test
    fun multiFolderSelection_deduplicatesSavesPartiallyAndGuardsConcurrentDoubleTapSave() {
        val holder = VaultBrowserStateHolder()
        holder.openFolderList()

        val movies = VaultDeviceFolder(path = "Movies", name = "Movies", itemCount = 2)
        val dcim = VaultDeviceFolder(path = "DCIM/Camera", name = "Camera", itemCount = 2)

        val movieA = fakeDeviceItem("m1", "Movies", "فيلم_عربي.mp4", 4_000L)
        val photoB = fakeDeviceItem("p1", "DCIM/Camera", "photo.jpg", 1_500L)
        val missingC = fakeDeviceItem("m2", "DCIM/Camera", "broken.pdf", 900L, mime = "application/pdf")

        // Select from Movies -> X back to folder list -> enter DCIM -> select more
        holder.enterFolder(movies)
        holder.setSelected(movieA, true)
        holder.onCloseButtonPressed()
        assertEquals(VaultBrowserStep.FolderList, holder.snapshot.step)

        holder.enterFolder(dcim)
        holder.setSelected(photoB, true)
        holder.setSelected(missingC, true)
        // Re-selecting same item stays deduplicated at 3
        holder.setSelected(movieA, true)
        assertEquals(3, holder.snapshot.pendingCount)

        // Begin save & verify double-tap concurrent call is rejected
        val batch = holder.tryBeginSave()
        assertNotNull(batch)
        assertEquals(3, batch!!.size)
        assertTrue(holder.snapshot.isSaving)
        assertNull(holder.tryBeginSave())
        // While saving, X/Back/toggles are ignored safely
        assertTrue(holder.onCloseButtonPressed().isSaving)
        assertFalse(holder.toggleSelection(fakeDeviceItem("x", "DCIM/Camera", "extra.mp4", 10L)))

        // Partial save outcome: movieA & photoB succeeded; missingC failed.
        val afterSave = holder.finishSave(
            succeededKeys = setOf(movieA.selectionKey, photoB.selectionKey),
            resultMessage = "Saved 2, failed 1"
        )
        assertFalse(afterSave.isSaving)
        assertTrue(afterSave.isOpen) // stays in browser so user can keep adding or tap X
        assertTrue(afterSave.savedKeys.contains(movieA.selectionKey))
        assertTrue(afterSave.savedKeys.contains(photoB.selectionKey))
        assertFalse(afterSave.savedKeys.contains(missingC.selectionKey))
        assertEquals(1, afterSave.pendingCount)
        assertTrue(afterSave.pendingSelectedByKey.containsKey(missingC.selectionKey))

        // Already-saved items cannot be re-queued as pending duplicates
        assertFalse(holder.toggleSelection(movieA))
        assertEquals(1, holder.snapshot.pendingCount)
    }

    @Test
    fun nestedSubfoldersAndSystemBack_navigatesStackAndPreservesPendingSelections() {
        val holder = VaultBrowserStateHolder()
        holder.openFolderList()
        holder.enterFolder(VaultDeviceFolder(path = "Movies", name = "Movies", itemCount = 5))
        val item1 = fakeDeviceItem("1", "Movies", "root_movie.mp4", 500L)
        holder.setSelected(item1, true)

        holder.enterSubfolder("Movies/Action", "Action")
        val item2 = fakeDeviceItem("2", "Movies/Action", "action_clip.mp4", 700L)
        holder.setSelected(item2, true)
        val step = holder.snapshot.step as VaultBrowserStep.FolderContents
        assertEquals("Movies/Action", step.currentPath)
        assertEquals("Action", step.currentName)

        // Back from subfolder returns to parent folder ("Movies"), keeping both selections
        val back1 = holder.onSystemBackPressed()
        assertEquals("Movies", (back1.step as VaultBrowserStep.FolderContents).currentPath)
        assertEquals(2, back1.pendingCount)

        // Back from root folder returns to FolderList, still keeping both selections
        val back2 = holder.onSystemBackPressed()
        assertEquals(VaultBrowserStep.FolderList, back2.step)
        assertEquals(2, back2.pendingCount)

        // Back from FolderList closes browser
        val back3 = holder.onSystemBackPressed()
        assertEquals(VaultBrowserStep.Closed, back3.step)
    }

    @Test
    fun largeFolderProgressivePaging_expandsWindowCleanlyWithoutDroppingItems() {
        val holder = VaultBrowserStateHolder(pageSize = 50)
        holder.openFolderList()
        holder.enterFolder(VaultDeviceFolder(path = "DCIM/Large", name = "Large", itemCount = 1_500))

        assertEquals(50, holder.snapshot.visibleItemLimit)
        holder.loadMoreItems()
        assertEquals(100, holder.snapshot.visibleItemLimit)
        holder.loadMoreItems()
        assertEquals(150, holder.snapshot.visibleItemLimit)

        val largeList = (1..1_500).map { idx ->
            fakeDeviceItem(idx.toString(), "DCIM/Large", "file_$idx.mp4", idx * 10L)
        }
        val filtered = filterAndSortDeviceItems(largeList, query = "file_999", sort = VaultBrowserSort.NAME)
        assertEquals(1, filtered.size)
        assertEquals("file_999.mp4", filtered.first().displayName)
    }

    @Test
    fun combineLibraryAndExtraDeviceFolders_mergesSubfoldersAndDeduplicatesFilesWithArabicAndLongNames() {
        val videoUri = Uri.parse("content://media/external/video/media/10")
        val imageUri = Uri.parse("content://media/external/images/media/20")
        val longName = "Very_Long_Arabic_مقطع_فيديو_${"x".repeat(120)}.mp4"

        val libFolders = listOf(
            MediaFolder("Movies", "Movies", itemCount = 1, videoCount = 1, audioCount = 0, totalBytes = 1000L, lastAddedMs = 5000L, newItemCount = 0, sampleUri = videoUri),
            MediaFolder("Movies/مجلد_عربي", "مجلد_عربي", itemCount = 1, videoCount = 1, audioCount = 0, totalBytes = 2000L, lastAddedMs = 6000L, newItemCount = 0, sampleUri = null)
        )
        val extraItems = listOf(
            VaultDeviceItem(
                id = imageUri.toString(),
                uri = imageUri,
                displayName = "صورة.jpg",
                extension = "JPG",
                mimeType = "image/jpeg",
                sizeBytes = 500L,
                lastModifiedMs = 7000L,
                folderPath = "Movies",
                folderDisplayPath = "Movies",
                sourceKind = VaultDeviceSourceKind.MEDIA_STORE
            ),
            VaultDeviceItem(
                id = "doc-1",
                uri = Uri.parse("content://media/external/downloads/1"),
                displayName = "document.pdf",
                extension = "PDF",
                mimeType = "application/pdf",
                sizeBytes = 800L,
                lastModifiedMs = 8000L,
                folderPath = "Download",
                folderDisplayPath = "Download",
                sourceKind = VaultDeviceSourceKind.MEDIA_STORE
            )
        )

        val combined = combineLibraryAndExtraDeviceFolders(libFolders, extraItems)
        val moviesCombined = combined.first { it.path == "Movies" }
        assertEquals(2, moviesCombined.itemCount)
        assertEquals(1, moviesCombined.subfolderCount)
        assertTrue(combined.any { it.path == "Download" })

        val libItem = LibraryItem(
            media = MediaItem(
                id = "10",
                uri = videoUri,
                title = longName,
                durationMs = 1000L,
                sizeBytes = 1000L,
                addedAtMs = 5000L,
                mimeType = "video/mp4",
                isVideo = true,
                folderPath = "Movies",
                folderName = "Movies",
                artist = null,
                album = null,
                albumId = null,
                artistId = null,
                genre = null,
                width = 1920,
                height = 1080,
                bitrate = 1000L
            )
        )

        val folderContents = buildFolderContentsForPath(
            folderPath = "Movies",
            libraryFolderItems = listOf(libItem),
            extraFolderItems = extraItems.filter { it.folderPath == "Movies" },
            allFolders = combined
        )

        // Expect 1 subfolder ("مجلد_عربي") + 2 files (longName + صورة.jpg)
        assertEquals(3, folderContents.size)
        assertTrue(folderContents.any { it.isDirectory && it.displayName == "مجلد_عربي" })
        assertTrue(folderContents.any { !it.isDirectory && it.displayName == longName })
        assertTrue(folderContents.any { !it.isDirectory && it.displayName == "صورة.jpg" })
    }

    @Test
    fun importDeviceSelection_encryptsAndVerifiesAndNeverMarksUnreadableFilesAsProtected() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val okPayload = "Encrypted-Media-Bytes-123456".toByteArray()
        val okId = provider.addFile("root", "مستند_سري.mp4", "video/mp4", okPayload)
        val okDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, okId)

        val missingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "nonexistent-doc-id")

        val items = listOf(
            VaultDeviceItem(
                id = okId,
                uri = okDocUri,
                displayName = "مستند_سري.mp4",
                extension = "MP4",
                mimeType = "video/mp4",
                sizeBytes = okPayload.size.toLong(),
                lastModifiedMs = 1000L,
                folderPath = "root",
                folderDisplayPath = "Movies",
                sourceKind = VaultDeviceSourceKind.SAF,
                safTreeUri = treeUri,
                safParentDocumentId = "root",
                safDocumentId = okId
            ),
            VaultDeviceItem(
                id = "missing",
                uri = missingUri,
                displayName = "deleted_during_selection.mp4",
                extension = "MP4",
                mimeType = "video/mp4",
                sizeBytes = 100L,
                lastModifiedMs = 1000L,
                folderPath = "root",
                folderDisplayPath = "Movies",
                sourceKind = VaultDeviceSourceKind.MEDIA_STORE
            )
        )

        val result = treeSupport.importDeviceSelection(
            items = items,
            session = session,
            moveSource = false
        )

        assertEquals(1, result.importedCount)
        assertEquals(1, result.failedCount)
        assertEquals(setOf(okDocUri.toString()), result.succeededKeys)

        val vaultEntries = vault.list(session)
        assertEquals(1, vaultEntries.size)
        val entry = vaultEntries.single()
        assertEquals("مستند_سري.mp4", entry.displayName)
        assertTrue(treeSupport.canRestoreToOriginalFolder(entry))

        // Delete original manually from provider, then restore directly to original folder without SAF picker!
        provider.deleteDocument(okId)
        assertNull(provider.readFileBytes("root", "مستند_سري.mp4"))

        val restoreSummary = treeSupport.restoreEntries(
            entries = listOf(entry),
            destinationTree = null,
            session = session,
            useOriginalFolder = true,
            removeFromVaultAfterRestore = true
        )
        assertEquals(1, restoreSummary.succeededCount)
        assertEquals(0, restoreSummary.failedCount)
        assertEquals(1, restoreSummary.removedFromVaultCount)
        assertEquals(0, vault.list(session).size)

        val restoredBytes = provider.readFileBytes("root", "مستند_سري.mp4")
        assertNotNull(restoredBytes)
        assertEquals(sha256Hex(okPayload), sha256Hex(restoredBytes!!))
    }

    @Test
    fun bucketPathFolderUsesFriendlyDisplayNameAndDirectRestoreSupportsScopedCollections() {
        val bucketItem = VaultDeviceItem(
            id = "b1",
            uri = Uri.parse("content://media/external/images/media/55"),
            displayName = "snapshot.jpg",
            extension = "JPG",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            lastModifiedMs = 2_000L,
            folderPath = "bucket:998877",
            folderDisplayPath = "bucket:998877",
            folderDisplayName = "Camera"
        )
        val combined = combineLibraryAndExtraDeviceFolders(emptyList(), listOf(bucketItem))
        assertEquals(1, combined.size)
        assertEquals("Camera", combined.single().name)
        assertEquals("bucket:998877", combined.single().path)

        val deviceSource = PrivateVaultDeviceSource(context)
        val moviesEntry = PrivateVaultEntry(
            id = "e1",
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Movies/Action"
        )
        val docsEntry = PrivateVaultEntry(
            id = "e2",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Documents/Private"
        )
        assertTrue(deviceSource.canRestoreToMediaStoreOrigin(moviesEntry))
        assertTrue(deviceSource.canRestoreToMediaStoreOrigin(docsEntry))
        assertTrue(treeSupport.canRestoreToOriginalFolder(moviesEntry))
        assertTrue(treeSupport.canRestoreToOriginalFolder(docsEntry))
    }

    private fun fakeDeviceItem(
        id: String,
        folderPath: String,
        name: String,
        size: Long,
        mime: String = "video/mp4"
    ): VaultDeviceItem = VaultDeviceItem(
        id = id,
        uri = Uri.parse("content://media/external/video/media/$id"),
        displayName = name,
        extension = extractExtension(name, mime),
        mimeType = mime,
        sizeBytes = size,
        lastModifiedMs = 1_000L,
        folderPath = folderPath,
        folderDisplayPath = folderPath
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
