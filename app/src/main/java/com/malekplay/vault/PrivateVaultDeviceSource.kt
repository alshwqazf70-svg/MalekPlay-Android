package com.malekplay.vault

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentResolver
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
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

internal sealed interface MediaStoreDeleteOutcome {
    data object DeletedVerified : MediaStoreDeleteOutcome
    data class NeedsRecoverableConsent(val intentSender: IntentSender) : MediaStoreDeleteOutcome
    data object NeedsBatchSystemConsent : MediaStoreDeleteOutcome
    data object FailedKeptSource : MediaStoreDeleteOutcome
}

/**
 * Scoped MediaStore helper for the MalekPlayer Private Vault (audio & video library only):
 * - Enforces Android 6–16 (API 23–36) permissions without broad storage or image/document grants.
 * - Loads bounded video & audio artwork thumbnails off the main thread with cancellation support.
 * - Performs verified source deletion after encryption, including Android 10 per-item
 *   RecoverableSecurityException authorization and Android 11+ batch createDeleteRequest.
 * - Restores unlocked video/audio files directly back to their original folder without prompts.
 */
internal class PrivateVaultDeviceSource(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    fun hasMediaPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val video = hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
            val audio = hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
            val visualSelected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return video || audio || visualSelected
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasLegacyWriteStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun permissionsForBrowsePrompt(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    /** Checks whether the URI still points to a readable, non-deleted file before encrypting. */
    fun isUriReadable(uri: Uri): Boolean = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true }
            ?: resolver.openInputStream(uri)?.use { true }
            ?: false
    }.getOrDefault(false)

    /**
     * Loads a memory-bounded thumbnail for video or embedded album art for audio on a background thread.
     */
    fun loadThumbnail(uri: Uri, mimeType: String, signal: CancellationSignal): Bitmap? {
        if (signal.isCanceled) return null
        if (!isVaultAllowedMediaMime(mimeType)) return null

        if (mimeType.startsWith("video/")) {
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

        // Embedded artwork for audio files (bounded decode)
        val retriever = MediaMetadataRetriever()
        return runCatching {
            if (signal.isCanceled) return@runCatching null
            retriever.setDataSource(appContext, uri)
            val art = retriever.embeddedPicture ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
            if (signal.isCanceled || bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(art, 0, art.size, opts)
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    /**
     * Attempts deleting a MediaStore source ONLY AFTER verified vault encryption, then confirms
     * with follow-up checks that the item and backing file actually disappeared.
     */
    fun deleteMediaStoreUriAndVerify(uri: Uri): MediaStoreDeleteOutcome {
        val legacyFile = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) resolveLegacyMediaFile(uri) else null
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return MediaStoreDeleteOutcome.NeedsBatchSystemConsent
            }
            return MediaStoreDeleteOutcome.FailedKeptSource
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && legacyFile != null && legacyFile.exists()) {
            runCatching { legacyFile.delete() }
            if (!legacyFile.exists()) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }

        if (verifyDeleted(uri, legacyFile)) {
            return MediaStoreDeleteOutcome.DeletedVerified
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStoreDeleteOutcome.NeedsBatchSystemConsent
        } else {
            MediaStoreDeleteOutcome.FailedKeptSource
        }
    }

    /**
     * Executes the actual MediaStore deletion on Android 10 (API 29) after the user grants
     * [RecoverableSecurityException] consent, then verifies the source disappeared.
     */
    fun executeAuthorizedAndroid10Delete(uri: Uri): Boolean {
        runCatching { resolver.delete(uri, null, null) }
        return verifyDeleted(uri)
    }

    fun mediaStoreUriExists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }.getOrDefault(true)

    fun verifyDeleted(uri: Uri, legacyFile: File? = null): Boolean {
        if (mediaStoreUriExists(uri)) return false
        if (legacyFile != null && legacyFile.exists()) return false
        return true
    }

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
        val file = resolveLegacyMediaFile(uri) ?: return null
        val parent = file.parentFile ?: return null
        val extRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val parentAbs = parent.absolutePath.trimEnd('/')
        return if (parentAbs.startsWith("$extRoot/")) {
            parentAbs.removePrefix("$extRoot/").takeIf { it.isNotBlank() }
        } else {
            parentAbs
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacyMediaFile(uri: Uri): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (col < 0) return@use null
                val rawPath = cursor.getString(col)?.trim()?.takeIf { it.isNotEmpty() } ?: return@use null
                File(rawPath)
            }
        }.getOrNull()
    }

    fun canRestoreToMediaStoreOrigin(entry: PrivateVaultEntry): Boolean {
        if (entry.sourceTreeUri != PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI) return false
        if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) return false
        if (!isVaultAllowedMediaMime(entry.mimeType)) return false
        val relPath = entry.sourceParentDocumentId?.trim().orEmpty()
        if (relPath.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return isRelativePathAllowedForCollection(relPath, entry.mimeType)
        }
        return hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) && !relPath.startsWith("bucket:")
    }

    /**
     * Restores a MediaStore-originated audio/video entry straight back to its original folder
     * without prompting the user with Android's file picker, verifying bytes & SHA-256 before
     * clearing IS_PENDING and refreshing MediaStore.
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
            require(isRelativePathAllowedForCollection(normalizedFolder, entry.mimeType)) {
                "Original folder ($normalizedFolder) is not writable by scoped MediaStore."
            }
            val collection = when {
                entry.mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                entry.mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> throw IOException("Unsupported media type for MediaStore restore: ${entry.mimeType}")
            }
            val relPathWithSlash = "${normalizedFolder.trimEnd('/')}/"
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

        // Android 6–9 (API 23–28): write into original external storage directory, verify size + SHA-256, and scan.
        require(hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            "Storage write permission is required to restore to original folder."
        }
        @Suppress("DEPRECATION")
        val extRoot = Environment.getExternalStorageDirectory()
        val safeRel = normalizedFolder.trim('/').takeIf {
            it.isNotEmpty() && !it.startsWith("bucket:") && !it.contains("..")
        } ?: throw IOException("Original folder path is unavailable for direct restore.")
        val targetDir = if (rawFolderPath.startsWith("/") && !rawFolderPath.contains("..") && File(rawFolderPath).isDirectory) {
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
        val tempPlain = vault.createPrivateWorkFile(".restore-verify-", ".bin")
        try {
            vault.exportToFile(entry.id, tempPlain, session, onBytesProgress)
            if (entry.sizeBytes >= 0L) {
                check(tempPlain.length() == entry.sizeBytes) {
                    "Restored file size mismatch (${tempPlain.length()} != ${entry.sizeBytes})"
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var totalCopied = 0L
            tempPlain.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        totalCopied += n.toLong()
                    }
                    output.fd.sync()
                }
            }
            check(totalCopied == tempPlain.length()) {
                "Restored destination write incomplete ($totalCopied != ${tempPlain.length()})"
            }
            val expectedSha = digest.digest()
            val actualSha = sha256OfFile(targetFile)
            check(expectedSha.contentEquals(actualSha)) {
                "Restored file checksum verification failed."
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
            vault.releasePrivateWorkFile(tempPlain)
            if (!verified) targetFile.delete()
        }
    }

    private fun sha256OfFile(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
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
            mimeType.startsWith("audio/") -> rootSeg in setOf(
                "music", "podcasts", "ringtones", "alarms", "notifications", "audiobooks", "recordings", "download"
            )
            else -> false
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
