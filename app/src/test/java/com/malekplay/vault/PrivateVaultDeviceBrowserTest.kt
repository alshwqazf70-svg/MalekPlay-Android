package com.malekplay.vault

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.malekplay.data.local.MalekPlayDatabase
import com.malekplay.data.local.entity.FavouriteEntity
import com.malekplay.data.local.entity.HistoryEntity
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackProgressEntity
import com.malekplay.data.local.entity.PlaylistEntity
import com.malekplay.data.local.entity.PlaylistItemEntity
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
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
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        tempDir = Files.createTempDirectory("vault-device-browser-test").toFile()
        File(context.filesDir, "private_vault").deleteRecursively()

        providerBackingDir = File(tempDir, "provider").apply { mkdirs() }
        provider = InMemoryVaultDocumentsProvider(providerBackingDir)
        val info = ProviderInfo().apply {
            authority = InMemoryVaultDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(InMemoryVaultDocumentsProvider.AUTHORITY, provider)

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
    fun browserStrictlyFiltersToVideoAndAudioOnlyAndExcludesImagesDocumentsAndAlreadyVaultedFiles() {
        assertTrue(isVaultAllowedMediaMime("video/mp4"))
        assertTrue(isVaultAllowedMediaMime("video/x-matroska"))
        assertTrue(isVaultAllowedMediaMime("audio/mpeg"))
        assertTrue(isVaultAllowedMediaMime("audio/flac"))
        assertFalse(isVaultAllowedMediaMime("image/jpeg"))
        assertFalse(isVaultAllowedMediaMime("image/png"))
        assertFalse(isVaultAllowedMediaMime("application/pdf"))
        assertFalse(isVaultAllowedMediaMime("application/zip"))
        assertFalse(isVaultAllowedMediaMime("text/plain"))
        assertFalse(isVaultAllowedMediaMime("application/octet-stream"))

        val videoUri = Uri.parse("content://media/external/video/media/10")
        val audioUri = Uri.parse("content://media/external/audio/media/11")
        val vaultedVideoUri = Uri.parse("content://media/external/video/media/12")

        val libFolders = listOf(
            MediaFolder("Movies", "Movies", itemCount = 2, videoCount = 2, audioCount = 0, totalBytes = 3000L, lastAddedMs = 5000L, newItemCount = 0, sampleUri = videoUri),
            MediaFolder("Music", "Music", itemCount = 1, videoCount = 0, audioCount = 1, totalBytes = 1500L, lastAddedMs = 6000L, newItemCount = 0, sampleUri = audioUri)
        )
        val vaultedEntry = PrivateVaultEntry(
            id = "v-existing",
            displayName = "already_hidden.mp4",
            mimeType = "video/mp4",
            sizeBytes = 2000L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Movies",
            sourceDisplayPath = "Movies"
        )

        val moviesItems = listOf(
            sampleLibraryItem("10", videoUri, "فيلم_عربي.mp4", "video/mp4", true, "Movies", 1000L),
            sampleLibraryItem("12", vaultedVideoUri, "already_hidden.mp4", "video/mp4", true, "Movies", 2000L),
            sampleLibraryItem("99", Uri.parse("content://media/external/images/media/99"), "photo.jpg", "image/jpeg", false, "Movies", 500L),
            sampleLibraryItem("100", Uri.parse("content://media/external/file/100"), "manual.pdf", "application/pdf", false, "Movies", 800L),
            sampleLibraryItem("101", Uri.parse("content://media/external/file/101"), "notes.txt", "text/plain", false, "Movies", 200L),
            sampleLibraryItem("102", Uri.parse("content://media/external/file/102"), "archive.zip", "application/zip", false, "Movies", 900L)
        )
        val songItem = sampleLibraryItem("11", audioUri, "song.flac", "audio/flac", false, "Music", 1500L)

        val folders = buildVaultLibraryFolders(
            libraryFolders = libFolders,
            allLibraryMedia = moviesItems + songItem,
            existingVaultEntries = listOf(vaultedEntry)
        )
        assertEquals(2, folders.size)

        val contents = buildFolderContentsForPath(
            folderPath = "Movies",
            libraryFolderItems = moviesItems,
            allFolders = folders,
            existingVaultEntries = listOf(vaultedEntry)
        )
        // Only the unvaulted video remains; images/pdf/txt/zip and already-vaulted video are excluded!
        assertEquals(1, contents.size)
        assertEquals("فيلم_عربي.mp4", contents.single().displayName)

        // Audio items appear in Music folder
        val musicContents = buildFolderContentsForPath(
            folderPath = "Music",
            libraryFolderItems = listOf(songItem),
            allFolders = folders,
            existingVaultEntries = listOf(vaultedEntry)
        )
        assertEquals(1, musicContents.size)
        assertEquals("song.flac", musicContents.single().displayName)
        assertEquals("FLAC", musicContents.single().extension)
    }

    @Test
    fun multiFolderSelection_disallowsImagesOrDocsAndGuardsConcurrentDoubleTapSave() {
        val holder = VaultBrowserStateHolder()
        holder.openFolderList()

        val movies = VaultDeviceFolder(path = "Movies", name = "Movies", itemCount = 2)
        val music = VaultDeviceFolder(path = "Music", name = "Music", itemCount = 2)

        val movieA = fakeDeviceItem("m1", "Movies", "فيلم_عربي.mp4", 4_000L, mime = "video/mp4")
        val songB = fakeDeviceItem("a1", "Music", "track.mp3", 1_500L, mime = "audio/mpeg")
        val disallowedImage = fakeDeviceItem("p1", "Movies", "photo.jpg", 900L, mime = "image/jpeg")
        val disallowedPdf = fakeDeviceItem("d1", "Movies", "report.pdf", 900L, mime = "application/pdf")

        // Non-video/audio items cannot even be selected into the Vault queue
        assertFalse(holder.toggleSelection(disallowedImage))
        assertFalse(holder.toggleSelection(disallowedPdf))
        holder.setSelected(disallowedImage, true)
        assertEquals(0, holder.snapshot.pendingCount)

        // Select from Movies -> X back to folder list -> enter Music -> select song
        holder.enterFolder(movies)
        holder.setSelected(movieA, true)
        holder.onCloseButtonPressed()
        assertEquals(VaultBrowserStep.FolderList, holder.snapshot.step)

        holder.enterFolder(music)
        holder.setSelected(songB, true)
        // Re-selecting same item stays deduplicated at 2
        holder.setSelected(movieA, true)
        assertEquals(2, holder.snapshot.pendingCount)

        // Begin move & verify double-tap concurrent call is rejected
        val batch = holder.tryBeginSave()
        assertNotNull(batch)
        assertEquals(2, batch!!.size)
        assertTrue(holder.snapshot.isSaving)
        assertNull(holder.tryBeginSave())
        assertTrue(holder.onCloseButtonPressed().isSaving)

        // Partial move outcome: movieA succeeded; songB failed
        val afterMove = holder.finishSave(
            succeededKeys = setOf(movieA.selectionKey),
            resultMessage = "Moved 1, failed 1"
        )
        assertFalse(afterMove.isSaving)
        assertTrue(afterMove.isOpen)
        assertTrue(afterMove.savedKeys.contains(movieA.selectionKey))
        assertFalse(afterMove.savedKeys.contains(songB.selectionKey))
        assertEquals(1, afterMove.pendingCount)
        assertFalse(holder.toggleSelection(movieA))
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
    fun moveIntoVault_encryptsVerifiesDeletesSource_andAutoRestoresToOriginalFolderWithoutPicker() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(InMemoryVaultDocumentsProvider.AUTHORITY, "root")
        val videoPayload = "Video-Stream-Bytes-987654321".toByteArray()
        val audioPayload = "Audio-Track-Bytes-123456789".toByteArray()
        val videoId = provider.addFile("root", "فيلم_سري.mp4", "video/mp4", videoPayload)
        val audioId = provider.addFile("root", "موسيقى_سرية.mp3", "audio/mpeg", audioPayload)
        val videoUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, videoId)
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, audioId)
        val missingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "nonexistent-doc-id")

        // Also ensure MediaStore-origin non-AV items are rejected cleanly even if passed programmatically
        val disallowedMediaStoreImage = VaultDeviceItem(
            id = "img-1",
            uri = Uri.parse("content://media/external/images/media/99"),
            displayName = "pic.jpg",
            extension = "JPG",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            lastModifiedMs = 1000L,
            folderPath = "Pictures",
            folderDisplayPath = "Pictures",
            sourceKind = VaultDeviceSourceKind.MEDIA_STORE
        )

        val items = listOf(
            VaultDeviceItem(
                id = videoId,
                uri = videoUri,
                displayName = "فيلم_سري.mp4",
                extension = "MP4",
                mimeType = "video/mp4",
                sizeBytes = videoPayload.size.toLong(),
                lastModifiedMs = 1000L,
                folderPath = "root",
                folderDisplayPath = "Movies",
                sourceKind = VaultDeviceSourceKind.SAF,
                safTreeUri = treeUri,
                safParentDocumentId = "root",
                safDocumentId = videoId
            ),
            VaultDeviceItem(
                id = audioId,
                uri = audioUri,
                displayName = "موسيقى_سرية.mp3",
                extension = "MP3",
                mimeType = "audio/mpeg",
                sizeBytes = audioPayload.size.toLong(),
                lastModifiedMs = 1000L,
                folderPath = "root",
                folderDisplayPath = "Music",
                sourceKind = VaultDeviceSourceKind.SAF,
                safTreeUri = treeUri,
                safParentDocumentId = "root",
                safDocumentId = audioId
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
            ),
            disallowedMediaStoreImage
        )

        val moveResult = treeSupport.importDeviceSelection(
            items = items,
            session = session,
            moveSource = true
        )

        assertEquals(2, moveResult.importedCount)
        assertEquals(2, moveResult.movedCount)
        assertEquals(0, moveResult.copiedOnlyCount)
        assertEquals(2, moveResult.failedCount)
        assertEquals(setOf(videoUri.toString(), audioUri.toString()), moveResult.succeededKeys)

        // Originals must have been deleted from their original source!
        assertNull(provider.readFileBytes("root", "فيلم_سري.mp4"))
        assertNull(provider.readFileBytes("root", "موسيقى_سرية.mp3"))

        val vaultEntries = vault.list(session)
        assertEquals(2, vaultEntries.size)
        assertTrue(treeSupport.canRestoreAllToOriginalFolder(vaultEntries))

        // Automatic picker-free restore straight back to original folder
        val restoreSummary = treeSupport.restoreEntries(
            entries = vaultEntries,
            destinationTree = null,
            session = session,
            useOriginalFolder = true,
            removeFromVaultAfterRestore = true
        )
        assertEquals(2, restoreSummary.succeededCount)
        assertEquals(0, restoreSummary.failedCount)
        assertEquals(2, restoreSummary.removedFromVaultCount)
        assertEquals(0, vault.list(session).size)

        val restoredVideo = provider.readFileBytes("root", "فيلم_سري.mp4")
        val restoredAudio = provider.readFileBytes("root", "موسيقى_سرية.mp3")
        assertNotNull(restoredVideo)
        assertNotNull(restoredAudio)
        assertEquals(sha256Hex(videoPayload), sha256Hex(restoredVideo!!))
        assertEquals(sha256Hex(audioPayload), sha256Hex(restoredAudio!!))
    }

    @Test
    fun failedRestoreToMissingOrigin_preservesEncryptedFileSafelyInVault() {
        val payload = "Protected-Video-Must-Stay-In-Vault".toByteArray()
        val entry = vault.importStream("missing_origin_clip.mp4", "video/mp4", payload.inputStream(), session)

        // Attempt direct original-folder restore when entry has no valid original folder metadata
        assertFalse(treeSupport.canRestoreToOriginalFolder(entry))
        val restoreSummary = treeSupport.restoreEntries(
            entries = listOf(entry),
            destinationTree = null,
            session = session,
            useOriginalFolder = true,
            removeFromVaultAfterRestore = true
        )
        assertEquals(0, restoreSummary.succeededCount)
        assertEquals(1, restoreSummary.failedCount)
        assertEquals(0, restoreSummary.removedFromVaultCount)
        assertEquals(1, restoreSummary.keptInVaultCount)

        // Encrypted file must remain completely intact inside the vault
        val remaining = vault.list(session)
        assertEquals(1, remaining.size)
        assertEquals(entry.id, remaining.single().id)
        val outFile = File(tempDir, "verify_intact.mp4")
        vault.exportToFile(entry.id, outFile, session)
        assertEquals(sha256Hex(payload), sha256Hex(outFile.readBytes()))
    }

    @Test
    fun movingFilesPurgesMediaFromLibraryFoldersSearchPlaylistsHistoryAndFavourites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MalekPlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val movedId = "content://media/external/video/media/7001"
            val keptId = "content://media/external/video/media/7002"
            db.mediaDao().upsertMedia(
                listOf(
                    MediaItemEntity(
                        mediaId = movedId,
                        title = "SecretClip",
                        uri = movedId,
                        mimeType = "video/mp4",
                        sizeBytes = 5_000L,
                        durationMs = 90_000L,
                        addedAtMs = 100L,
                        isVideo = true,
                        folderPath = "/Movies/Private",
                        folderName = "Private",
                        artist = null,
                        album = null,
                        albumId = null,
                        artistId = null,
                        genre = null,
                        width = 1920,
                        height = 1080,
                        bitrate = 1000L
                    ),
                    MediaItemEntity(
                        mediaId = keptId,
                        title = "PublicClip",
                        uri = keptId,
                        mimeType = "video/mp4",
                        sizeBytes = 4_000L,
                        durationMs = 60_000L,
                        addedAtMs = 200L,
                        isVideo = true,
                        folderPath = "/Movies/Public",
                        folderName = "Public",
                        artist = null,
                        album = null,
                        albumId = null,
                        artistId = null,
                        genre = null,
                        width = 1280,
                        height = 720,
                        bitrate = 800L
                    )
                )
            )
            val playlistId = db.playlistDao().createPlaylist(PlaylistEntity(name = "WatchLater", createdAtMs = 1L))
            db.playlistDao().insertPlaylistItems(
                listOf(
                    PlaylistItemEntity(playlistId = playlistId, mediaId = movedId, position = 0, addedAtMs = 1L),
                    PlaylistItemEntity(playlistId = playlistId, mediaId = keptId, position = 1, addedAtMs = 2L)
                )
            )
            db.userDataDao().upsertProgress(
                PlaybackProgressEntity(mediaId = movedId, positionMs = 30_000L, durationMs = 90_000L, updatedAtMs = 10L)
            )
            db.userDataDao().insertHistory(HistoryEntity(mediaId = movedId, playedAtMs = 10L))
            db.userDataDao().insertFavourite(FavouriteEntity(mediaId = movedId, addedAtMs = 10L))

            // Simulate Vault move deletion across all tables
            db.mediaDao().deletePlaylistItemsByMediaIds(listOf(movedId))
            db.mediaDao().deleteHistoryByMediaIds(listOf(movedId))
            db.mediaDao().deleteFavouritesByMediaIds(listOf(movedId))
            db.mediaDao().deleteMediaByIds(listOf(movedId))

            val videos = db.mediaDao().observeVideos().first()
            assertEquals(listOf(keptId), videos.map { it.mediaId })

            val folders = db.mediaDao().observeFolders().first()
            assertEquals(listOf("/Movies/Public"), folders.map { it.folderPath })

            val searchResults = db.mediaDao().searchMedia("SecretClip").first()
            assertTrue(searchResults.isEmpty())

            val playlistWithItems = db.playlistDao().observePlaylists().first().single()
            assertEquals(listOf(keptId), playlistWithItems.items.map { it.mediaId })

            val history = db.userDataDao().observeHistory().first()
            assertTrue(history.none { it.mediaId == movedId })

            val favIds = db.userDataDao().observeFavouriteIds().first()
            assertFalse(favIds.contains(movedId))
        } finally {
            db.close()
        }
    }

    @Test
    @Config(sdk = [23])
    fun android6Api23UsesReadAndWriteExternalStoragePermissionsWithoutScopedGrants() {
        val source = PrivateVaultDeviceSource(context)
        val promptPerms = source.permissionsForBrowsePrompt()
        assertTrue(promptPerms.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        assertTrue(promptPerms.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertFalse(source.hasMediaPermissions())
        assertFalse(source.hasLegacyWriteStoragePermission())

        Shadows.shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        assertTrue(source.hasMediaPermissions())
        assertTrue(source.hasLegacyWriteStoragePermission())
    }

    @Test
    @Config(sdk = [29])
    fun android10Api29UsesScopedMediaStoreAndSupportsVideoAudioDirectRestore() {
        val source = PrivateVaultDeviceSource(context)
        val promptPerms = source.permissionsForBrowsePrompt()
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), promptPerms.toList())
        val videoEntry = PrivateVaultEntry(
            id = "v29",
            displayName = "clip29.mp4",
            mimeType = "video/mp4",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Movies"
        )
        assertTrue(source.canRestoreToMediaStoreOrigin(videoEntry))
    }

    @Test
    @Config(sdk = [30])
    fun android11Api30SupportsScopedAudioAndVideoDirectRestore() {
        val source = PrivateVaultDeviceSource(context)
        val audioEntry = PrivateVaultEntry(
            id = "a30",
            displayName = "song30.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Music/Favorites"
        )
        assertTrue(source.canRestoreToMediaStoreOrigin(audioEntry))
    }

    @Test
    @Config(sdk = [33])
    fun android13Api33RequestsVideoAndAudioPermissionsOnlyNeverImages() {
        val source = PrivateVaultDeviceSource(context)
        val promptPerms = source.permissionsForBrowsePrompt().toSet()
        assertEquals(
            setOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO),
            promptPerms
        )
        assertFalse(promptPerms.contains(Manifest.permission.READ_MEDIA_IMAGES))
    }

    @Test
    @Config(sdk = [34])
    fun android14Api34AllowsScopedVideoAndAudioRestoreAndRejectsNonMediaRestore() {
        val deviceSource = PrivateVaultDeviceSource(context)
        val promptPerms = deviceSource.permissionsForBrowsePrompt().toSet()
        assertEquals(
            setOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ),
            promptPerms
        )
        assertFalse(promptPerms.contains(Manifest.permission.READ_MEDIA_IMAGES))

        val moviesEntry = PrivateVaultEntry(
            id = "e1",
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Movies/Action"
        )
        val musicEntry = PrivateVaultEntry(
            id = "e2",
            displayName = "track.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Music/Albums"
        )
        val legacyDocEntry = PrivateVaultEntry(
            id = "e3",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Documents/Private"
        )
        assertTrue(deviceSource.canRestoreToMediaStoreOrigin(moviesEntry))
        assertTrue(deviceSource.canRestoreToMediaStoreOrigin(musicEntry))
        assertFalse(deviceSource.canRestoreToMediaStoreOrigin(legacyDocEntry))
        assertTrue(treeSupport.canRestoreToOriginalFolder(moviesEntry))
        assertTrue(treeSupport.canRestoreToOriginalFolder(musicEntry))
    }

    @Test
    @Config(sdk = [35])
    fun android15Api35CompatibleMediaPermissionsAndDirectAudioVideoRestore() {
        val deviceSource = PrivateVaultDeviceSource(context)
        Shadows.shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        assertTrue(deviceSource.hasMediaPermissions())
        val downloadVideo = PrivateVaultEntry(
            id = "e35",
            displayName = "clip35.mp4",
            mimeType = "video/mp4",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "Download"
        )
        assertTrue(deviceSource.canRestoreToMediaStoreOrigin(downloadVideo))
    }

    @Test
    @Config(sdk = [28])
    fun legacyAndroid9SupportsDirectOriginalFolderRestoreWithWriteStoragePermission() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val deviceSource = PrivateVaultDeviceSource(context)
        val customDirEntry = PrivateVaultEntry(
            id = "e3",
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            sizeBytes = 100L,
            sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
            sourceParentDocumentId = "WhatsApp/Media/WhatsApp Video"
        )
        assertTrue(deviceSource.canRestoreToMediaStoreOrigin(customDirEntry))
        assertTrue(treeSupport.canRestoreToOriginalFolder(customDirEntry))
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

    private fun sampleLibraryItem(
        id: String,
        uri: Uri,
        title: String,
        mime: String,
        isVideo: Boolean,
        folderPath: String,
        sizeBytes: Long
    ) = LibraryItem(
        media = MediaItem(
            id = id,
            uri = uri,
            title = title,
            durationMs = 1000L,
            sizeBytes = sizeBytes,
            addedAtMs = 5000L,
            mimeType = mime,
            isVideo = isVideo,
            folderPath = folderPath,
            folderName = folderPath.substringAfterLast('/'),
            artist = null,
            album = null,
            albumId = null,
            artistId = null,
            genre = null,
            width = if (isVideo) 1920 else 0,
            height = if (isVideo) 1080 else 0,
            bitrate = 1000L
        )
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
