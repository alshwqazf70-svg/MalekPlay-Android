package com.malekplay.vault

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

internal sealed interface MediaStoreDeleteOutcome {
    data object DeletedVerified : MediaStoreDeleteOutcome
    data class NeedsRecoverableConsent(val intentSender: IntentSender) : MediaStoreDeleteOutcome
    data object NeedsBatchSystemConsent : MediaStoreDeleteOutcome
    data object FailedKeptSource : MediaStoreDeleteOutcome
}

/**
 * Scoped MediaStore helper for the Private Vault internal browser:
 * - Supplements [LibraryViewModel] (video/audio) with accessible images & non-AV files.
 * - Loads bounded thumbnails in background with cancellation support.
 * - Restores MediaStore-originated entries straight back to their original folder without SAF pickers.
 * - Strictly complies with Android 6–16 (API 23–36) storage permissions without broad storage grants.
 */
internal class PrivateVaultDeviceSource(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    fun hasMediaPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val video = hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
            val audio = hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
            val images = hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
            val visualSelected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return video || audio || images || visualSelected
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasImagesAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val full = hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
            val partial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return full || partial
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun permissionsForBrowsePrompt(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Queries accessible images and general files from MediaStore (excluding video/audio
     * already observed by [LibraryViewModel]) so folders also surface photos, PDFs, archives, etc.
     */
    fun querySupplementalItems(
        folderPathFilter: String? = null,
        maxItems: Int = 2_500
    ): List<VaultDeviceItem> {
        val items = ArrayList<VaultDeviceItem>()
        val seenUris = HashSet<String>()

        if (hasImagesAccess()) {
            queryCollection(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                collectionName = "images",
                defaultMime = "image/jpeg",
                folderPathFilter = folderPathFilter,
                maxRemaining = maxItems - items.size,
                seenUris = seenUris,
                out = items
            )
        }

        if (items.size < maxItems) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queryCollection(
                    collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    collectionName = "downloads",
                    defaultMime = "application/octet-stream",
                    folderPathFilter = folderPathFilter,
                    maxRemaining = maxItems - items.size,
                    seenUris = seenUris,
                    out = items
                )
            }
            if (items.size < maxItems && hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                queryFilesCollection(
                    folderPathFilter = folderPathFilter,
                    maxRemaining = maxItems - items.size,
                    seenUris = seenUris,
                    out = items
                )
            }
        }

        return items
    }

    private fun queryCollection(
        collection: Uri,
        collectionName: String,
        defaultMime: String,
        folderPathFilter: String?,
        maxRemaining: Int,
        seenUris: MutableSet<String>,
        out: MutableList<VaultDeviceItem>
    ) {
        if (maxRemaining <= 0) return
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add("bucket_display_name")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = buildList {
            add("${MediaStore.MediaColumns.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
            }
        }.joinToString(" AND ")

        val normalizedFilter = folderPathFilter?.let(::normalizeRelativeFolderPath)
        runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else -1
                val bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex("bucket_display_name")
                if (idCol < 0) return@use

                var added = 0
                while (cursor.moveToNext() && added < maxRemaining) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val uriStr = uri.toString()
                    if (uriStr in seenUris) continue

                    val rel = if (relCol >= 0) cursor.getString(relCol).orEmpty().trim() else ""
                    val bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol).orEmpty().trim() else ""
                    val bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol).orEmpty().trim() else ""
                    val folderPath = normalizeRelativeFolderPath(
                        when {
                            rel.isNotEmpty() -> rel
                            bucketId.isNotEmpty() -> "bucket:$bucketId"
                            bucketName.isNotEmpty() -> bucketName
                            else -> "/"
                        }
                    )
                    if (normalizedFilter != null && folderPath != normalizedFilter) continue

                    val displayName = (if (nameCol >= 0) {
                        cursor.getString(nameCol)?.takeIf { it.isNotBlank() }
                    } else null) ?: "file_$id"
                    val mime = (if (mimeCol >= 0) cursor.getString(mimeCol) else null)
                        ?.takeIf { it.isNotBlank() } ?: defaultMime
                    val sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                    val addedSec = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                    val ext = extractExtension(displayName, mime)
                    val friendlyFolderName = when {
                        rel.isNotEmpty() -> rel.trimEnd('/').substringAfterLast('/').ifEmpty { rel }
                        bucketName.isNotEmpty() -> bucketName
                        else -> folderPath.substringAfterLast('/')
                    }

                    seenUris += uriStr
                    out += VaultDeviceItem(
                        id = uriStr,
                        uri = uri,
                        displayName = displayName,
                        extension = ext,
                        mimeType = mime,
                        sizeBytes = sizeBytes,
                        lastModifiedMs = if (addedSec > 0L) addedSec * 1_000L else 0L,
                        folderPath = folderPath,
                        folderDisplayPath = folderPath,
                        folderDisplayName = friendlyFolderName,
                        isDirectory = false,
                        sourceKind = VaultDeviceSourceKind.MEDIA_STORE,
                        mediaStoreCollection = collectionName
                    )
                    added++
                }
            }
        }
    }

    private fun queryFilesCollection(
        folderPathFilter: String?,
        maxRemaining: Int,
        seenUris: MutableSet<String>,
        out: MutableList<VaultDeviceItem>
    ) {
        if (maxRemaining <= 0) return
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add("bucket_display_name")
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = buildList {
            add("${MediaStore.MediaColumns.SIZE} > 0")
            add("${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
            add("${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}")
            add("${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
            }
        }.joinToString(" AND ")

        val normalizedFilter = folderPathFilter?.let(::normalizeRelativeFolderPath)
        runCatching {
            resolver.query(
                filesUri,
                projection,
                selection,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else -1
                val bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex("bucket_display_name")
                if (idCol < 0) return@use

                var added = 0
                while (cursor.moveToNext() && added < maxRemaining) {
                    val id = cursor.getLong(idCol)
                    val mime = (if (mimeCol >= 0) cursor.getString(mimeCol) else null)
                        ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                    if (mime == "inode/directory" || mime == DocumentsContract.Document.MIME_TYPE_DIR) continue

                    val displayName = (if (nameCol >= 0) {
                        cursor.getString(nameCol)?.takeIf { it.isNotBlank() }
                    } else null) ?: continue

                    val rel = if (relCol >= 0) cursor.getString(relCol).orEmpty().trim() else ""
                    val bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol).orEmpty().trim() else ""
                    val bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol).orEmpty().trim() else ""
                    val folderPath = normalizeRelativeFolderPath(
                        when {
                            rel.isNotEmpty() -> rel
                            bucketId.isNotEmpty() -> "bucket:$bucketId"
                            bucketName.isNotEmpty() -> bucketName
                            else -> "/"
                        }
                    )
                    if (normalizedFilter != null && folderPath != normalizedFilter) continue

                    val uri = ContentUris.withAppendedId(filesUri, id)
                    val uriStr = uri.toString()
                    if (uriStr in seenUris) continue

                    val sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                    val addedSec = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                    val friendlyFolderName = when {
                        rel.isNotEmpty() -> rel.trimEnd('/').substringAfterLast('/').ifEmpty { rel }
                        bucketName.isNotEmpty() -> bucketName
                        else -> folderPath.substringAfterLast('/')
                    }
                    seenUris += uriStr
                    out += VaultDeviceItem(
                        id = uriStr,
                        uri = uri,
                        displayName = displayName,
                        extension = extractExtension(displayName, mime),
                        mimeType = mime,
                        sizeBytes = sizeBytes,
                        lastModifiedMs = if (addedSec > 0L) addedSec * 1_000L else 0L,
                        folderPath = folderPath,
                        folderDisplayPath = folderPath,
                        folderDisplayName = friendlyFolderName,
                        isDirectory = false,
                        sourceKind = VaultDeviceSourceKind.MEDIA_STORE,
                        mediaStoreCollection = "files"
                    )
                    added++
                }
            }
        }
    }

    /**
     * Checks whether the URI still points to a readable, non-deleted file before encrypting.
     */
    fun isUriReadable(uri: Uri): Boolean = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true }
            ?: resolver.openInputStream(uri)?.use { true }
            ?: false
    }.getOrDefault(false)

    /**
     * Loads a memory-bounded thumbnail for either MediaStore or SAF URIs on a background thread.
     */
    fun loadThumbnail(uri: Uri, mimeType: String, signal: CancellationSignal): Bitmap? {
        if (signal.isCanceled) return null
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) return null

        if (DocumentsContract.isDocumentUri(appContext, uri)) {
            val docThumb = runCatching {
                DocumentsContract.getDocumentThumbnail(resolver, uri, Point(144, 144), signal)
            }.getOrNull()
            if (docThumb != null) return docThumb
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val systemThumb = runCatching {
                resolver.loadThumbnail(uri, Size(144, 144), signal)
            }.getOrNull()
            if (systemThumb != null) return systemThumb
        }

        if (signal.isCanceled) return null

        if (mimeType.startsWith("image/")) {
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (signal.isCanceled || bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()
        }

        val retriever = MediaMetadataRetriever()
        return runCatching {
            if (signal.isCanceled) return@runCatching null
            retriever.setDataSource(appContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
            } else {
                val frame = retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let {
                    val scaled = Bitmap.createScaledBitmap(it, 144, 144, true)
                    if (scaled !== it) it.recycle()
                    scaled
                }
            }
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    /**
     * Attempts deleting a MediaStore source ONLY AFTER verified vault encryption, then confirms
     * with a follow-up query that the item actually disappeared.
     */
    fun deleteMediaStoreUriAndVerify(uri: Uri): MediaStoreDeleteOutcome {
        try {
            resolver.delete(uri, null, null)
        } catch (sec: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sec is RecoverableSecurityException) {
                return MediaStoreDeleteOutcome.NeedsRecoverableConsent(sec.userAction.actionIntent.intentSender)
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStoreDeleteOutcome.NeedsBatchSystemConsent
            } else {
                MediaStoreDeleteOutcome.FailedKeptSource
            }
        } catch (_: Exception) {
            return MediaStoreDeleteOutcome.FailedKeptSource
        }

        if (!mediaStoreUriExists(uri)) {
            return MediaStoreDeleteOutcome.DeletedVerified
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStoreDeleteOutcome.NeedsBatchSystemConsent
        } else {
            MediaStoreDeleteOutcome.FailedKeptSource
        }
    }

    fun mediaStoreUriExists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }.getOrDefault(true)

    fun verifyDeleted(uri: Uri): Boolean = !mediaStoreUriExists(uri)

    fun createBatchDeleteIntentSender(uris: Collection<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(resolver, uris).intentSender
        }.getOrNull()
    }

    /**
     * On Android 6–9 (API 23–28), resolves the real parent folder path relative to external
     * storage when [MediaStore.MediaColumns.DATA] is available, so direct restore can put
     * unlocked files straight back into their original directory instead of an opaque bucket id.
     */
    @Suppress("DEPRECATION")
    fun resolveLegacyOriginRelativeFolder(uri: Uri): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (col < 0) return@use null
                val rawPath = cursor.getString(col)?.trim()?.takeIf { it.isNotEmpty() } ?: return@use null
                val parent = File(rawPath).parentFile ?: return@use null
                val extRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                val parentAbs = parent.absolutePath.trimEnd('/')
                if (parentAbs.startsWith("$extRoot/")) {
                    parentAbs.removePrefix("$extRoot/").takeIf { it.isNotBlank() }
                } else {
                    parentAbs
                }
            }
        }.getOrNull()
    }

    fun canRestoreToMediaStoreOrigin(entry: PrivateVaultEntry): Boolean {
        if (entry.sourceTreeUri != PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI) return false
        if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) return false
        val relPath = entry.sourceParentDocumentId?.trim().orEmpty()
        if (relPath.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return isRelativePathAllowedForCollection(relPath, entry.mimeType)
        }
        return hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) && !relPath.startsWith("bucket:")
    }

    /**
     * Restores a MediaStore-originated entry straight back to its original folder without prompting
     * the user with Android's file picker, verifying bytes & SHA-256 before clearing IS_PENDING.
     */
    fun restoreEntryToMediaStoreOrigin(
        entry: PrivateVaultEntry,
        session: PrivateVaultSession,
        vault: PrivateVaultRepository,
        onBytesProgress: ((Long) -> Unit)? = null
    ): Uri {
        val rawFolderPath = entry.sourceParentDocumentId?.trim().orEmpty()
        require(rawFolderPath.isNotEmpty()) { "Missing original MediaStore folder path." }
        val normalizedFolder = normalizeRelativeFolderPath(rawFolderPath)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cleanRootSeg = normalizedFolder.trim('/').substringBefore('/').lowercase(Locale.ROOT)
            val collection = when {
                entry.mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                entry.mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                entry.mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                cleanRootSeg == "documents" -> MediaStore.Files.getContentUri("external")
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val relPathWithSlash = when {
                normalizedFolder == "/" -> defaultRelativeRootForMime(entry.mimeType)
                normalizedFolder.startsWith("bucket:") -> defaultRelativeRootForMime(entry.mimeType)
                else -> "${normalizedFolder.trimEnd('/')}/"
            }
            val existingNames = listExistingMediaStoreNames(collection, relPathWithSlash)
            val uniqueName = resolveUniqueChildName(
                desiredName = entry.displayName,
                existingNames = existingNames,
                isDirectory = false
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPathWithSlash)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destUri = resolver.insert(collection, values)
                ?: throw IOException("Could not create restored media entry in $relPathWithSlash")
            var verified = false
            try {
                vault.exportToDocumentVerified(entry.id, destUri, session, onBytesProgress)
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(destUri, publishValues, null, null)
                verified = true
                return destUri
            } finally {
                if (!verified) {
                    runCatching { resolver.delete(destUri, null, null) }
                }
            }
        }

        // Android 6–9 (API 23–28): write into external storage directory and trigger MediaScanner.
        @Suppress("DEPRECATION")
        val extRoot = Environment.getExternalStorageDirectory()
        val safeRel = normalizedFolder.trim('/').takeIf {
            it.isNotEmpty() && !it.startsWith("bucket:") && !it.contains("..")
        } ?: defaultRelativeRootForMime(entry.mimeType).trim('/')
        val targetDir = if (rawFolderPath.startsWith("/") && File(rawFolderPath).isDirectory) {
            File(rawFolderPath)
        } else {
            File(extRoot, safeRel)
        }
        check(targetDir.isDirectory || targetDir.mkdirs()) {
            "Could not access original directory: $safeRel"
        }
        val existingNames = targetDir.list().orEmpty().toSet()
        val uniqueName = resolveUniqueChildName(entry.displayName, existingNames, isDirectory = false)
        val targetFile = File(targetDir, uniqueName)
        val destUri = Uri.fromFile(targetFile)
        var verified = false
        try {
            vault.exportToFile(entry.id, targetFile, session, onBytesProgress)
            if (entry.sizeBytes >= 0L) {
                check(targetFile.length() == entry.sizeBytes) {
                    "Restored file size mismatch (${targetFile.length()} != ${entry.sizeBytes})"
                }
            }
            verified = true
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(targetFile.absolutePath),
                arrayOf(entry.mimeType),
                null
            )
            return destUri
        } finally {
            if (!verified) targetFile.delete()
        }
    }

    private fun listExistingMediaStoreNames(collection: Uri, relPathWithSlash: String): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val names = mutableSetOf<String>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relPathWithSlash),
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (col >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.takeIf { it.isNotBlank() }?.let(names::add)
                    }
                }
            }
        }
        return names
    }

    private fun isRelativePathAllowedForCollection(relPath: String, mimeType: String): Boolean {
        val clean = relPath.trim('/').lowercase(Locale.ROOT)
        if (clean.isEmpty() || clean.startsWith("bucket:")) return false
        val rootSeg = clean.substringBefore('/')
        return when {
            mimeType.startsWith("video/") -> rootSeg in setOf("movies", "dcim", "pictures", "download")
            mimeType.startsWith("audio/") -> rootSeg in setOf("music", "podcasts", "ringtones", "alarms", "notifications", "audiobooks", "recordings", "download")
            mimeType.startsWith("image/") -> rootSeg in setOf("pictures", "dcim", "download")
            else -> rootSeg in setOf("download", "documents")
        }
    }

    private fun defaultRelativeRootForMime(mimeType: String): String = when {
        mimeType.startsWith("video/") -> "Movies/"
        mimeType.startsWith("audio/") -> "Music/"
        mimeType.startsWith("image/") -> "Pictures/"
        else -> "Download/"
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
