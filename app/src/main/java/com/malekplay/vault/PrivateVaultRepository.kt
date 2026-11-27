package com.malekplay.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.*
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VaultInsufficientStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class VaultSourceOrigin(
    val treeUri: String? = null,
    val parentDocumentId: String? = null,
    val displayPath: String? = null
)

data class PrivateVaultEntry(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAtMs: Long = 0L,
    val sourceTreeUri: String? = null,
    val sourceParentDocumentId: String? = null,
    val sourceDisplayPath: String? = null
)

data class PendingVaultMove(
    val entryId: String,
    val sourceUri: String,
    val displayName: String
)

/**
 * Unlocked in-memory session holding the unwrapped 256-bit Master Key (DEK).
 * Call [close] when locking the vault or leaving the screen to zero out key material.
 */
class PrivateVaultSession internal constructor(
    internal val masterKey: ByteArray,
    private val legacyPassword: CharArray? = null
) : Closeable {
    @Volatile
    var isClosed: Boolean = false
        private set

    internal fun requireMasterKey(): ByteArray {
        check(!isClosed) { "Vault session is locked." }
        return masterKey
    }

    internal fun legacyPasswordCopy(): CharArray? =
        if (isClosed) null else legacyPassword?.copyOf()

    override fun close() {
        if (!isClosed) {
            isClosed = true
            masterKey.fill(0)
            legacyPassword?.fill('\u0000')
        }
    }
}

/** Encrypted app-private vault. Import copies; it never deletes a source implicitly. */
class PrivateVaultRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cacheDirectory = appContext.cacheDir
    private val directory = File(appContext.filesDir, "private_vault").apply {
        check(isDirectory || mkdirs()) { "Could not initialize private vault storage." }
        restrictDirectoryPermissions(this)
    }
    private val thumbsDirectory = File(directory, "thumbs").apply {
        check(isDirectory || mkdirs()) { "Could not initialize vault thumbnail directory." }
        restrictDirectoryPermissions(this)
    }
    private val workDirectory = File(directory, PrivateVaultRecovery.WORK_DIR_NAME).apply {
        check(isDirectory || mkdirs()) { "Could not initialize vault work directory." }
        restrictDirectoryPermissions(this)
    }
    private val indexFile = File(directory, "vault.index.enc")
    private val backupIndex = File(directory, "vault.index.bak")
    private val keyFile = File(directory, "vault.key.enc")
    private val backupKeyFile = File(directory, "vault.key.bak")
    private val pendingMovesFile = File(directory, "vault.pending_moves.enc")

    init {
        if (!indexFile.exists() && backupIndex.exists()) backupIndex.renameTo(indexFile)
        if (!keyFile.exists() && backupKeyFile.exists()) backupKeyFile.renameTo(keyFile)
    }

    private val legacyIndexMagic = 0x4D4B5649
    private val indexMagicV1 = 0x4D4B564A
    private val indexMagicV2 = 0x4D4B564B
    private val indexMagicV3 = 0x4D4B564C
    private val maxEntries = 20_000

    @Synchronized fun isConfigured() = indexFile.isFile && indexFile.length() > 0L

    @Synchronized fun create(password: CharArray): PrivateVaultSession {
        check(!isConfigured()) { "Vault is already configured." }
        val masterKey = PrivateVaultCrypto.generateMasterKey()
        return try {
            persistMasterKey(masterKey, password)
            persistIndexWithKey(emptyList(), masterKey)
            PrivateVaultSession(masterKey.copyOf(), password.copyOf())
        } finally {
            masterKey.fill(0)
        }
    }

    /**
     * Authenticates [password] once (single PBKDF2 derivation) and returns a fast
     * [PrivateVaultSession] holding the 256-bit Master Key for subsequent operations.
     */
    @Synchronized fun openSession(password: CharArray): PrivateVaultSession {
        check(isConfigured()) { "Vault has not been created." }
        val masterKey = unwrapOrMigrateMasterKey(password)
        return try {
            readIndexInternal(masterKey, password)
            PrivateVaultSession(masterKey.copyOf(), password.copyOf())
        } finally {
            masterKey.fill(0)
        }
    }

    @Synchronized fun changePassword(
        session: PrivateVaultSession,
        currentPassword: CharArray,
        newPassword: CharArray
    ): PrivateVaultSession {
        require(newPassword.size >= 8) { "Vault password must contain at least 8 characters." }
        val verifiedKey = unwrapOrMigrateMasterKey(currentPassword)
        try {
            check(verifiedKey.contentEquals(session.requireMasterKey())) {
                "Current vault key mismatch."
            }
            persistMasterKey(verifiedKey, newPassword)
            session.close()
            return PrivateVaultSession(verifiedKey.copyOf(), newPassword.copyOf())
        } finally {
            verifiedKey.fill(0)
        }
    }

    @Synchronized fun list(password: CharArray): List<PrivateVaultEntry> {
        check(isConfigured()) { "Vault has not been created." }
        val masterKey = unwrapOrMigrateMasterKey(password)
        return try {
            readIndexInternal(masterKey, password)
        } finally {
            masterKey.fill(0)
        }
    }

    @Synchronized fun list(session: PrivateVaultSession): List<PrivateVaultEntry> {
        check(isConfigured()) { "Vault has not been created." }
        val legacyPwd = session.legacyPasswordCopy()
        return try {
            readIndexInternal(session.requireMasterKey(), legacyPwd)
        } finally {
            legacyPwd?.fill('\u0000')
        }
    }

    /**
     * Loads and decrypts an entry's thumbnail strictly in memory (returns null if none).
     */
    fun loadThumbnailBytes(entryId: String, session: PrivateVaultSession): ByteArray? {
        requireValidId(entryId)
        val file = thumbFile(entryId)
        if (!file.isFile) return null
        return runCatching {
            PrivateVaultCrypto.decryptWithKey(file.readBytes(), session.requireMasterKey())
        }.getOrNull()
    }

    /**
     * Reads an entry's plaintext bytes strictly in memory (for image/text viewing)
     * without ever writing decrypted data to app cache or external storage.
     */
    fun readEntryBytes(
        entry: PrivateVaultEntry,
        session: PrivateVaultSession,
        maxBytes: Int = 32 * 1024 * 1024
    ): ByteArray {
        requireValidId(entry.id)
        if (entry.sizeBytes > maxBytes) {
            error("File is too large for direct in-memory preview (${entry.sizeBytes} bytes).")
        }
        val encrypted = payload(entry.id)
        check(encrypted.isFile) { "Encrypted file is missing." }
        val key = session.requireMasterKey()
        val legacyPwd = session.legacyPasswordCopy()
        return try {
            val out = object : ByteArrayOutputStream(entry.sizeBytes.coerceIn(0L, maxBytes.toLong()).toInt()) {
                override fun write(b: Int) {
                    check(count + 1 <= maxBytes) { "Entry exceeds in-memory preview limit." }
                    super.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    check(count + len <= maxBytes) { "Entry exceeds in-memory preview limit." }
                    super.write(b, off, len)
                }
            }
            PrivateVaultCrypto.decryptStreamAuto(encrypted, out, key, legacyPwd)
            out.toByteArray()
        } finally {
            legacyPwd?.fill('\u0000')
        }
    }

    /**
     * Opens a random-access seekable decrypting reader for isolated in-vault ExoPlayer playback.
     * Supports v2 chunked entries with O(1) seek and zero plaintext cache files.
     */
    internal fun openSeekableReader(
        entryId: String,
        session: PrivateVaultSession
    ): PrivateVaultCrypto.VaultSeekableReader? {
        requireValidId(entryId)
        val encrypted = payload(entryId)
        if (!encrypted.isFile || !PrivateVaultCrypto.isChunkedV2File(encrypted)) return null
        return PrivateVaultCrypto.VaultSeekableReader(encrypted, session.requireMasterKey())
    }

    /**
     * Legacy compatibility helper; prefers in-memory [readEntryBytes] in new UI code.
     */
    @Synchronized fun createImagePreviewFile(entry: PrivateVaultEntry, password: CharArray): File? {
        requireValidId(entry.id)
        if (!entry.mimeType.startsWith("image/") || entry.sizeBytes !in 1L..(24L * 1024L * 1024L)) return null
        val encrypted = payload(entry.id)
        if (!encrypted.isFile) return null
        val preview = File(cacheDirectory, ".malek-vault-preview-${entry.id}-${UUID.randomUUID()}.img")
        val masterKey = unwrapOrMigrateMasterKey(password)
        return try {
            FileOutputStream(preview).use { sink ->
                PrivateVaultCrypto.decryptStreamAuto(encrypted, sink, masterKey, password)
                sink.fd.sync()
            }
            preview
        } catch (failure: Throwable) {
            preview.delete()
            throw failure
        } finally {
            masterKey.fill(0)
        }
    }

    @Synchronized fun importUri(
        uri: Uri,
        password: CharArray,
        displayNameOverride: String? = null,
        mimeTypeOverride: String? = null,
        sourceOrigin: VaultSourceOrigin? = null
    ): PrivateVaultEntry {
        val session = openSession(password)
        return try {
            importUri(uri, session, displayNameOverride, mimeTypeOverride, sourceOrigin)
        } finally {
            session.close()
        }
    }

    @Synchronized fun importUri(
        uri: Uri,
        session: PrivateVaultSession,
        displayNameOverride: String? = null,
        mimeTypeOverride: String? = null,
        sourceOrigin: VaultSourceOrigin? = null,
        onProgress: ((Long) -> Unit)? = null
    ): PrivateVaultEntry {
        val name = displayNameOverride?.takeIf { it.isNotBlank() } ?: queryName(uri)
        val mime = mimeTypeOverride?.takeIf { it.isNotBlank() } ?: resolver.getType(uri) ?: "application/octet-stream"
        val thumbnailBytes = runCatching { generateThumbnailJpeg(uri, mime) }.getOrNull()
        val input = resolver.openInputStream(uri) ?: error("Cannot read selected file.")
        return importStream(name, mime, input, session, thumbnailBytes, sourceOrigin, onProgress)
    }

    /** Imports a private temporary stream without exposing it through a public document provider. */
    @Synchronized fun importStream(
        displayName: String,
        mimeType: String,
        input: InputStream,
        password: CharArray,
        sourceOrigin: VaultSourceOrigin? = null
    ): PrivateVaultEntry {
        val session = openSession(password)
        return try {
            importStream(displayName, mimeType, input, session, sourceOrigin = sourceOrigin)
        } finally {
            session.close()
        }
    }

    @Synchronized fun importStream(
        displayName: String,
        mimeType: String,
        input: InputStream,
        session: PrivateVaultSession,
        thumbnailBytes: ByteArray? = null,
        sourceOrigin: VaultSourceOrigin? = null,
        onProgress: ((Long) -> Unit)? = null
    ): PrivateVaultEntry {
        val name = displayName.takeIf { it.isNotBlank() } ?: "Private file"
        val mime = mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val key = session.requireMasterKey()
        val id = UUID.randomUUID().toString()
        val target = payload(id)
        val temp = File(directory, "$id.tmp")
        val pending = File(directory, "$id.pending")
        var committed = false
        var originalSize = 0L
        try {
            input.use { rawSource ->
                originalSize = PrivateVaultCrypto.encryptFileChunked(
                    input = rawSource,
                    destinationFile = temp,
                    key = key,
                    onBytesProcessed = onProgress
                )
            }
            // Verify the chunked header, exact layout size, and final chunk tag.
            PrivateVaultCrypto.VaultSeekableReader(temp, key).use { reader ->
                if (reader.plaintextSize == 0L) {
                    reader.verifyEmpty()
                } else {
                    val lastPos = (reader.plaintextSize - 1L).coerceAtLeast(0L)
                    val probe = ByteArray(1)
                    check(reader.readAt(lastPos, probe, 0, 1) == 1) {
                        "Final chunk verification failed."
                    }
                }
            }
            val legacyPwd = session.legacyPasswordCopy()
            val current = try {
                readIndexInternal(key, legacyPwd, runRecovery = false)
            } finally {
                legacyPwd?.fill('\u0000')
            }
            check(current.size < maxEntries) { "Vault item limit reached." }
            FileOutputStream(pending).use { it.fd.sync() }
            check(temp.renameTo(target)) { "Could not finalize encrypted file." }
            if (thumbnailBytes != null && thumbnailBytes.isNotEmpty()) {
                runCatching { saveEncryptedThumbnail(id, thumbnailBytes, key) }
            }
            val entry = PrivateVaultEntry(
                id = id,
                displayName = name,
                mimeType = mime,
                sizeBytes = originalSize,
                addedAtMs = System.currentTimeMillis(),
                sourceTreeUri = sourceOrigin?.treeUri?.takeIf { it.isNotBlank() },
                sourceParentDocumentId = sourceOrigin?.parentDocumentId?.takeIf { it.isNotBlank() },
                sourceDisplayPath = sourceOrigin?.displayPath?.takeIf { it.isNotBlank() }
            )
            persistIndexWithKey(current + entry, key)
            committed = true
            pending.delete()
            return entry
        } catch (failure: Throwable) {
            temp.delete()
            if (!committed) {
                target.delete()
                pending.delete()
                thumbFile(id).delete()
            }
            throw failure
        }
    }

    @Synchronized fun exportTo(id: String, destination: Uri, password: CharArray) {
        val session = openSession(password)
        try {
            exportTo(id, destination, session)
        } finally {
            session.close()
        }
    }

    @Synchronized fun exportTo(
        id: String,
        destination: Uri,
        session: PrivateVaultSession,
        onProgress: ((Long) -> Unit)? = null
    ) {
        exportToDocumentVerified(id, destination, session, onProgress)
    }

    /**
     * Decrypts, authenticates, writes to [destination], flushes, and verifies both the exact
     * written byte count and SHA-256 content digest before returning. Never deletes the vault copy.
     */
    @Synchronized fun exportToDocumentVerified(
        id: String,
        destination: Uri,
        session: PrivateVaultSession,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        requireValidId(id)
        val key = session.requireMasterKey()
        val legacyPwd = session.legacyPasswordCopy()
        try {
            val entry = readIndexInternal(key, legacyPwd).firstOrNull { it.id == id }
                ?: error("Vault item not found.")
            val file = payload(id)
            check(file.isFile) { "Encrypted file is missing." }
            var writtenBytes = 0L
            val expectedSha256: ByteArray
            if (PrivateVaultCrypto.isChunkedV2File(file)) {
                // Chunked v2 files authenticate each 64 KB chunk via AEAD directly into the destination stream
                // without duplicating gigabytes of plaintext in temporary storage.
                val output = resolver.openOutputStream(destination, "wt") ?: error("Cannot write destination.")
                output.use { rawSink ->
                    val countingSink = DigestCountingOutputStream(rawSink)
                    try {
                        PrivateVaultCrypto.decryptStreamAuto(file, countingSink, key, legacyPwd, onProgress)
                    } catch (io: IOException) {
                        throw mapStorageIoException(io)
                    }
                    countingSink.flush()
                    if (rawSink is FileOutputStream) {
                        runCatching { rawSink.fd.sync() }
                    }
                    writtenBytes = countingSink.bytesWritten
                    expectedSha256 = countingSink.digestBytes()
                }
            } else {
                val verified = createPrivateWorkFile("restore-", ".tmp")
                try {
                    try {
                        FileOutputStream(verified).use { sink ->
                            PrivateVaultCrypto.decryptStreamAuto(file, sink, key, legacyPwd, onProgress)
                            sink.fd.sync()
                        }
                    } catch (io: IOException) {
                        throw mapStorageIoException(io)
                    }
                    val expectedLegacySize = verified.length()
                    val output = resolver.openOutputStream(destination, "wt") ?: error("Cannot write destination.")
                    output.use { rawSink ->
                        val countingSink = DigestCountingOutputStream(rawSink)
                        try {
                            FileInputStream(verified).use { source -> source.copyTo(countingSink) }
                        } catch (io: IOException) {
                            throw mapStorageIoException(io)
                        }
                        countingSink.flush()
                        if (rawSink is FileOutputStream) {
                            runCatching { rawSink.fd.sync() }
                        }
                        writtenBytes = countingSink.bytesWritten
                        expectedSha256 = countingSink.digestBytes()
                    }
                    check(writtenBytes == expectedLegacySize) {
                        "Destination write size mismatch ($writtenBytes != $expectedLegacySize)."
                    }
                } finally {
                    releasePrivateWorkFile(verified)
                }
            }
            if (entry.sizeBytes >= 0L) {
                check(writtenBytes == entry.sizeBytes) {
                    "Restored byte count mismatch ($writtenBytes != ${entry.sizeBytes})."
                }
            }
            val reportedSize = queryDestinationSize(destination)
            if (reportedSize >= 0L && entry.sizeBytes >= 0L) {
                check(reportedSize == entry.sizeBytes) {
                    "Destination provider reported size mismatch ($reportedSize != ${entry.sizeBytes})."
                }
            }
            verifyWrittenDestinationDigest(destination, writtenBytes, expectedSha256)
            return writtenBytes
        } finally {
            legacyPwd?.fill('\u0000')
        }
    }

    internal fun verifyWrittenDestinationDigest(
        destination: Uri,
        expectedSize: Long,
        expectedSha256: ByteArray
    ) {
        val readBack = runCatching { resolver.openInputStream(destination) }.getOrNull() ?: return
        readBack.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                digest.update(buffer, 0, n)
                total += n.toLong()
            }
            check(total == expectedSize) {
                "Post-write verification size mismatch ($total != $expectedSize)."
            }
            check(digest.digest().contentEquals(expectedSha256)) {
                "Post-write SHA-256 verification failed for destination file."
            }
        }
    }

    internal fun ensureAvailableInternalSpace(requiredBytes: Long) {
        if (requiredBytes <= 0L) return
        val statAvailable = runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(-1L)
        val fileUsable = runCatching { directory.usableSpace }.getOrDefault(0L)
        val available = when {
            statAvailable > 0L -> statAvailable
            fileUsable > 0L -> fileUsable
            statAvailable == 0L -> 0L
            else -> Long.MAX_VALUE
        }
        if (available < requiredBytes) {
            throw VaultInsufficientStorageException(
                "Insufficient internal storage space (required=$requiredBytes, available=$available)."
            )
        }
    }

    internal fun createPrivateWorkFile(prefix: String, suffix: String): File {
        check(workDirectory.isDirectory || workDirectory.mkdirs()) {
            "Could not initialize vault work directory."
        }
        val file = File(workDirectory, "$prefix${UUID.randomUUID()}$suffix")
        file.createNewFile()
        restrictFilePermissions(file)
        activeTempNames.add(file.name)
        return file
    }

    internal fun releasePrivateWorkFile(file: File?) {
        if (file == null) return
        try {
            if (file.isFile) file.delete()
        } finally {
            activeTempNames.remove(file.name)
        }
    }

    /**
     * Verifies that an encrypted vault entry exists in the index, its encrypted payload
     * decrypts and authenticates every AEAD chunk without errors, and its plaintext size matches
     * the recorded metadata (confirming it can be restored safely before deleting any source).
     */
    @Synchronized fun verifyEncryptedEntryIntegrity(id: String, session: PrivateVaultSession): Boolean {
        requireValidId(id)
        val key = session.requireMasterKey()
        val legacyPwd = session.legacyPasswordCopy()
        return try {
            val entry = readIndexInternal(key, legacyPwd, runRecovery = false).firstOrNull { it.id == id }
                ?: return false
            val file = payload(id)
            if (!file.isFile) return false
            val counter = CountingOutputStream()
            PrivateVaultCrypto.decryptStreamAuto(file, counter, key, legacyPwd)
            entry.sizeBytes < 0L || counter.bytesWritten == entry.sizeBytes
        } catch (_: Exception) {
            false
        } finally {
            legacyPwd?.fill('\u0000')
        }
    }

    @Synchronized fun loadPendingMoves(session: PrivateVaultSession): List<PendingVaultMove> {
        val key = session.requireMasterKey()
        if (!pendingMovesFile.isFile || pendingMovesFile.length() == 0L) return emptyList()
        return runCatching {
            val plain = PrivateVaultCrypto.decryptWithKey(pendingMovesFile.readBytes(), key)
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val count = input.readInt().coerceIn(0, maxEntries)
                List(count) {
                    PendingVaultMove(
                        entryId = input.readUTF(),
                        sourceUri = input.readUTF(),
                        displayName = input.readUTF()
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun addPendingMoves(items: List<PendingVaultMove>, session: PrivateVaultSession) {
        if (items.isEmpty()) return
        val merged = LinkedHashMap<String, PendingVaultMove>()
        loadPendingMoves(session).forEach { merged[it.entryId] = it }
        items.forEach { merged[it.entryId] = it }
        persistPendingMoves(merged.values.toList(), session.requireMasterKey())
    }

    @Synchronized fun removePendingMoves(entryIds: Collection<String>, session: PrivateVaultSession) {
        if (entryIds.isEmpty() || !pendingMovesFile.isFile) return
        val idSet = entryIds.toSet()
        val remaining = loadPendingMoves(session).filterNot { it.entryId in idSet }
        persistPendingMoves(remaining, session.requireMasterKey())
    }

    private fun persistPendingMoves(items: List<PendingVaultMove>, key: ByteArray) {
        if (items.isEmpty()) {
            pendingMovesFile.delete()
            return
        }
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            out.writeInt(items.size)
            items.forEach {
                out.writeUTF(it.entryId)
                out.writeUTF(it.sourceUri.take(2048))
                out.writeUTF(it.displayName.take(1024))
            }
            out.flush()
        }
        val tmp = File(directory, "vault.pending_moves.tmp")
        try {
            val enc = PrivateVaultCrypto.encryptWithKey(plain.toByteArray(), key)
            FileOutputStream(tmp).use {
                it.write(enc)
                it.fd.sync()
            }
            if (pendingMovesFile.exists()) pendingMovesFile.delete()
            if (tmp.renameTo(pendingMovesFile)) restrictFilePermissions(pendingMovesFile)
        } finally {
            tmp.delete()
        }
    }

    internal fun payloadFileFor(id: String): File {
        requireValidId(id)
        return payload(id)
    }

    @Synchronized internal fun replaceEntryPayload(
        id: String,
        verifiedNewEncryptedFile: File,
        newPlainSizeBytes: Long,
        session: PrivateVaultSession
    ): PrivateVaultEntry {
        requireValidId(id)
        val key = session.requireMasterKey()
        val legacyPwd = session.legacyPasswordCopy()
        try {
            val current = readIndexInternal(key, legacyPwd)
            val existing = current.firstOrNull { it.id == id } ?: error("Vault item not found.")
            val target = payload(id)
            val backup = File(directory, "$id.mkv.bak")
            backup.delete()
            if (target.exists()) {
                check(target.renameTo(backup)) { "Could not stage existing encrypted archive backup." }
            }
            var committed = false
            try {
                check(verifiedNewEncryptedFile.renameTo(target)) {
                    "Could not promote verified encrypted archive."
                }
                val updated = existing.copy(sizeBytes = newPlainSizeBytes)
                persistIndexWithKey(current.map { if (it.id == id) updated else it }, key)
                committed = true
                backup.delete()
                return updated
            } catch (failure: Throwable) {
                if (!committed) {
                    if (target.exists() && backup.exists()) target.delete()
                    if (backup.exists() && !target.exists()) backup.renameTo(target)
                }
                throw failure
            }
        } finally {
            legacyPwd?.fill('\u0000')
        }
    }

    /** Decrypts and authenticates an entry into app-private storage (used for ZIP folder restore). */
    @Synchronized fun exportToFile(id: String, destination: File, password: CharArray) {
        val session = openSession(password)
        try {
            exportToFile(id, destination, session)
        } finally {
            session.close()
        }
    }

    @Synchronized fun exportToFile(
        id: String,
        destination: File,
        session: PrivateVaultSession,
        onProgress: ((Long) -> Unit)? = null
    ) {
        requireValidId(id)
        val key = session.requireMasterKey()
        val legacyPwd = session.legacyPasswordCopy()
        try {
            check(readIndexInternal(key, legacyPwd).any { it.id == id }) { "Vault item not found." }
            val encrypted = payload(id)
            check(encrypted.isFile) { "Encrypted file is missing." }
            val verified = File(directory, "restore-${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(verified).use { sink ->
                    PrivateVaultCrypto.decryptStreamAuto(encrypted, sink, key, legacyPwd, onProgress)
                    sink.fd.sync()
                }
                FileInputStream(verified).use { source ->
                    FileOutputStream(destination).use { sink ->
                        source.copyTo(sink)
                        sink.fd.sync()
                    }
                }
            } finally { verified.delete() }
        } finally {
            legacyPwd?.fill('\u0000')
        }
    }

    @Synchronized fun delete(id: String, password: CharArray) {
        val session = openSession(password)
        try {
            delete(id, session)
        } finally {
            session.close()
        }
    }

    @Synchronized fun delete(id: String, session: PrivateVaultSession) {
        requireValidId(id)
        val key = session.requireMasterKey()
        val legacyPwd = session.legacyPasswordCopy()
        try {
            val current = readIndexInternal(key, legacyPwd)
            check(current.any { it.id == id }) { "Vault item not found." }
            val source = payload(id)
            val staged = File(directory, "$id.delete")
            check(source.renameTo(staged)) { "Could not stage encrypted item deletion." }
            var indexCommitted = false
            try {
                persistIndexWithKey(current.filterNot { it.id == id }, key)
                indexCommitted = true
                staged.delete()
                thumbFile(id).delete()
            } catch (failure: Throwable) {
                if (!indexCommitted && staged.exists()) staged.renameTo(source)
                throw failure
            }
        } finally {
            legacyPwd?.fill('\u0000')
        }
    }

    private fun requireValidId(id: String) = require(
        id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    ) { "Invalid vault item." }

    private fun payload(id: String) = File(directory, "$id.mkv")
    private fun thumbFile(id: String) = File(thumbsDirectory, "$id.thumb.enc")

    private fun saveEncryptedThumbnail(id: String, jpegBytes: ByteArray, key: ByteArray) {
        val temp = File(thumbsDirectory, "$id.tmp")
        val target = thumbFile(id)
        try {
            val encrypted = PrivateVaultCrypto.encryptWithKey(jpegBytes, key)
            FileOutputStream(temp).use { out ->
                out.write(encrypted)
                out.fd.sync()
            }
            if (!temp.renameTo(target)) {
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }

    private fun generateThumbnailJpeg(uri: Uri, mimeType: String): ByteArray? {
        val maxDim = 320
        val bitmap: Bitmap? = when {
            mimeType.startsWith("image/") -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                }
            }
            mimeType.startsWith("video/") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            1_000_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            maxDim,
                            maxDim
                        ) ?: retriever.getScaledFrameAtTime(
                            0L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            maxDim,
                            maxDim
                        )
                    } else {
                        retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
            else -> null
        }
        if (bitmap == null) return null
        return try {
            val scaled = scaleDownBitmap(bitmap, maxDim)
            try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    out.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun scaleDownBitmap(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDim && h <= maxDim) return source
        val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun queryName(uri: Uri): String {
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Private file"
    }

    private fun unwrapOrMigrateMasterKey(password: CharArray): ByteArray {
        if (keyFile.isFile && keyFile.length() > 0L) {
            val out = ByteArrayOutputStream(32)
            FileInputStream(keyFile).buffered().use { input ->
                PrivateVaultCrypto.decrypt(input, out, password)
            }
            val key = out.toByteArray()
            require(key.size == 32) { "Corrupted vault master key." }
            return key
        }
        // Legacy v1 vault without vault.key.enc: authenticate password via vault.index.enc first,
        // then create vault.key.enc wrapping a new Master Key.
        val plain = File(directory, "index-${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(indexFile).use { input ->
                FileOutputStream(plain).use { output ->
                    PrivateVaultCrypto.decrypt(input, output, password)
                    output.fd.sync()
                }
            }
        } finally {
            plain.delete()
        }
        val newKey = PrivateVaultCrypto.generateMasterKey()
        persistMasterKey(newKey, password)
        return newKey
    }

    private fun persistMasterKey(masterKey: ByteArray, password: CharArray) {
        val newKeyFile = File(directory, "vault.key.new")
        try {
            FileOutputStream(newKeyFile).use { output ->
                PrivateVaultCrypto.encrypt(ByteArrayInputStream(masterKey), output, password)
                output.fd.sync()
            }
            backupKeyFile.delete()
            if (keyFile.exists()) check(keyFile.renameTo(backupKeyFile)) { "Could not stage previous vault key." }
            if (!newKeyFile.renameTo(keyFile)) {
                if (backupKeyFile.exists()) backupKeyFile.renameTo(keyFile)
                error("Could not save vault key.")
            }
            backupKeyFile.delete()
        } finally {
            newKeyFile.delete()
        }
    }

    private fun readIndexInternal(
        masterKey: ByteArray,
        password: CharArray?,
        runRecovery: Boolean = true
    ): List<PrivateVaultEntry> {
        check(indexFile.isFile) { "Vault has not been created." }
        val plain = File(directory, "index-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(plain).use { output ->
                PrivateVaultCrypto.decryptStreamAuto(indexFile, output, masterKey, password)
                output.fd.sync()
            }
            val (entries, needsSizeMigration) = DataInputStream(FileInputStream(plain)).use { input ->
                val magic = input.readInt()
                check(
                    magic == indexMagicV3 || magic == indexMagicV2 ||
                        magic == indexMagicV1 || magic == legacyIndexMagic
                ) { "Invalid vault index." }
                val count = input.readInt()
                require(count in 0..maxEntries) { "Invalid vault index size." }
                val hasTimestamp = magic == indexMagicV2 || magic == indexMagicV3
                val hasSourceOrigin = magic == indexMagicV3
                val entries = List(count) {
                    val id = input.readUTF()
                    requireValidId(id)
                    val displayName = input.readUTF()
                    val mimeType = input.readUTF()
                    val sizeBytes = input.readLong()
                    val addedAtMs = if (hasTimestamp) input.readLong() else 0L
                    val sourceTreeUri = if (hasSourceOrigin) input.readUTF().takeIf { it.isNotBlank() } else null
                    val sourceParentDocumentId = if (hasSourceOrigin) input.readUTF().takeIf { it.isNotBlank() } else null
                    val sourceDisplayPath = if (hasSourceOrigin) input.readUTF().takeIf { it.isNotBlank() } else null
                    require(displayName.isNotBlank() && mimeType.isNotBlank() && sizeBytes >= -1L) {
                        "Invalid vault item metadata."
                    }
                    PrivateVaultEntry(
                        id = id,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        addedAtMs = addedAtMs,
                        sourceTreeUri = sourceTreeUri,
                        sourceParentDocumentId = sourceParentDocumentId,
                        sourceDisplayPath = sourceDisplayPath
                    )
                }
                require(entries.map { it.id }.toSet().size == entries.size) { "Duplicate vault item identifier." }
                check(input.read() == -1) { "Unexpected vault index data." }
                entries to (magic == legacyIndexMagic)
            }
            if (runRecovery) {
                PrivateVaultRecovery.recover(
                    directory,
                    entries.mapTo(mutableSetOf()) { it.id },
                    pruneOrphanThumbs = true,
                    activeTempNames = activeTempNames.toSet()
                )
            }
            if (!needsSizeMigration) return entries

            // Older builds stored ciphertext length in sizeBytes. Recalculate the
            // original plaintext length once, then commit the corrected metadata.
            val migrated = entries.map { entry ->
                val encrypted = payload(entry.id)
                if (!encrypted.isFile) entry.copy(sizeBytes = -1L) else entry.copy(
                    sizeBytes = try {
                        val counter = CountingOutputStream()
                        PrivateVaultCrypto.decryptStreamAuto(encrypted, counter, masterKey, password)
                        counter.bytesWritten
                    } catch (_: Exception) {
                        -1L
                    }
                )
            }
            persistIndexWithKey(migrated, masterKey)
            return migrated
        } finally { plain.delete() }
    }

    private fun queryDestinationSize(destination: Uri): Long {
        if (destination.scheme == "file") {
            return destination.path?.let { File(it).takeIf(File::isFile)?.length() } ?: -1L
        }
        return runCatching {
            resolver.query(destination, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
                } else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private class CountingOutputStream : OutputStream() {
        var bytesWritten: Long = 0
            private set
        override fun write(value: Int) { bytesWritten = Math.addExact(bytesWritten, 1L) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytesWritten = Math.addExact(bytesWritten, length.toLong())
        }
    }

    private class DigestCountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0
            private set
        private val digest = MessageDigest.getInstance("SHA-256")

        override fun write(value: Int) {
            delegate.write(value)
            digest.update(value.toByte())
            bytesWritten += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            bytesWritten += length.toLong()
        }

        override fun flush() {
            delegate.flush()
        }

        fun digestBytes(): ByteArray = digest.digest()
    }

    private fun mapStorageIoException(io: IOException): IOException {
        val msg = io.message.orEmpty()
        return if (msg.contains("ENOSPC", ignoreCase = true) ||
            msg.contains("No space left", ignoreCase = true) ||
            msg.contains("disk full", ignoreCase = true)
        ) {
            VaultInsufficientStorageException("Insufficient storage space during vault operation.", io)
        } else {
            io
        }
    }

    private fun restrictDirectoryPermissions(dir: File) {
        runCatching {
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
    }

    private fun restrictFilePermissions(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    companion object {
        private val activeTempNames: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    }

    private fun persistIndexWithKey(entries: List<PrivateVaultEntry>, key: ByteArray) {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            out.writeInt(indexMagicV3)
            out.writeInt(entries.size)
            entries.forEach {
                out.writeUTF(it.id)
                out.writeUTF(it.displayName.take(4096))
                out.writeUTF(it.mimeType.take(512))
                out.writeLong(it.sizeBytes)
                out.writeLong(it.addedAtMs)
                out.writeUTF(it.sourceTreeUri.orEmpty().take(2048))
                out.writeUTF(it.sourceParentDocumentId.orEmpty().take(1024))
                out.writeUTF(it.sourceDisplayPath.orEmpty().take(1024))
            }
            out.flush()
        }
        val encrypted = File(directory, "vault.index.new")
        try {
            val payload = PrivateVaultCrypto.encryptWithKey(plain.toByteArray(), key)
            FileOutputStream(encrypted).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            backupIndex.delete()
            if (indexFile.exists()) check(indexFile.renameTo(backupIndex)) { "Could not stage previous vault index." }
            if (!encrypted.renameTo(indexFile)) {
                if (backupIndex.exists()) backupIndex.renameTo(indexFile)
                error("Could not save vault index.")
            }
            backupIndex.delete()
        } finally {
            encrypted.delete()
        }
    }
}
