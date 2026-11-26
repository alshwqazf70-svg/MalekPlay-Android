package com.malekplay.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class VaultArchiveEntry(
    val path: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedSizeBytes: Long,
    val compressionMethod: Int,
    val crc32: Long,
    val lastModifiedMs: Long,
    val mimeType: String,
    val localHeaderOffset: Long
) {
    fun toListingItem(childCount: Int = 0): VaultArchiveListingItem = VaultArchiveListingItem(
        path = path,
        name = name,
        parentPath = parentPath,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
        mimeType = mimeType,
        childCount = childCount
    )
}

data class VaultArchiveModificationResult(
    val updatedVaultEntry: PrivateVaultEntry,
    val deletedPathsCount: Int,
    val remainingItemCount: Int
)

/**
 * Random-access source over decrypted archive bytes.
 * For V2 chunked vault files this streams strictly in 64 KB RAM chunks without creating any disk file.
 */
internal interface SeekablePlainSource : Closeable {
    val size: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

internal class ChunkedV2PlainSource(
    private val reader: PrivateVaultCrypto.VaultSeekableReader
) : SeekablePlainSource {
    override val size: Long get() = reader.plaintextSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        reader.readAt(position, buffer, offset, length)

    override fun close() {
        reader.close()
    }
}

internal class PrivateWorkFilePlainSource(
    private val tempFile: File,
    private val repository: PrivateVaultRepository
) : SeekablePlainSource {
    private val raf = RandomAccessFile(tempFile, "r")
    override val size: Long = tempFile.length()

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0L || position >= size) return -1
        if (length == 0) return 0
        raf.seek(position)
        val toRead = minOf(length.toLong(), size - position).toInt()
        return raf.read(buffer, offset, toRead)
    }

    override fun close() {
        runCatching { raf.close() }
        repository.releasePrivateWorkFile(tempFile)
    }
}

internal object VaultZipArchiveReader {
    private const val SIG_LOCAL_HEADER = 0x04034B50
    private const val SIG_CENTRAL_DIR = 0x02014B50
    private const val SIG_EOCD = 0x06054B50
    private const val SIG_ZIP64_LOCATOR = 0x07064B50
    private const val SIG_ZIP64_EOCD = 0x06064B50
    private const val FLAG_UTF8 = 1 shl 11
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    fun parseEntries(source: SeekablePlainSource): List<VaultArchiveEntry> {
        val totalLen = source.size
        require(totalLen >= 22L) { "Archive is too small or corrupted." }

        val tailSize = minOf(totalLen, 65_557L).toInt()
        val tailStart = totalLen - tailSize
        val tail = readExact(source, tailStart, tailSize)

        var eocdTailOffset = -1
        for (i in (tailSize - 22) downTo 0) {
            if (getIntLE(tail, i) == SIG_EOCD) {
                val commentLen = getUShortLE(tail, i + 20)
                if (i + 22 + commentLen == tailSize) {
                    eocdTailOffset = i
                    break
                }
            }
        }
        require(eocdTailOffset >= 0) { "Invalid or corrupted ZIP archive (missing EOCD)." }

        var cdEntriesCount = getUShortLE(tail, eocdTailOffset + 10).toLong()
        var cdSizeBytes = getUIntLE(tail, eocdTailOffset + 12)
        var cdOffset = getUIntLE(tail, eocdTailOffset + 16)

        val eocdGlobalOffset = tailStart + eocdTailOffset
        if ((cdOffset == 0xFFFFFFFFL || cdSizeBytes == 0xFFFFFFFFL || cdEntriesCount == 0xFFFFL) &&
            eocdGlobalOffset >= 20L
        ) {
            val locatorBytes = readExact(source, eocdGlobalOffset - 20L, 20)
            if (getIntLE(locatorBytes, 0) == SIG_ZIP64_LOCATOR) {
                val zip64EocdOffset = getLongLE(locatorBytes, 8)
                require(zip64EocdOffset in 0L..(totalLen - 56L)) { "Corrupt ZIP64 EOCD offset." }
                val z64 = readExact(source, zip64EocdOffset, 56)
                require(getIntLE(z64, 0) == SIG_ZIP64_EOCD) { "Invalid ZIP64 EOCD signature." }
                cdEntriesCount = getLongLE(z64, 32)
                cdSizeBytes = getLongLE(z64, 40)
                cdOffset = getLongLE(z64, 48)
            }
        }

        require(cdOffset >= 0L && cdSizeBytes >= 0L && cdOffset + cdSizeBytes <= eocdGlobalOffset) {
            "Corrupted ZIP central directory bounds."
        }
        require(cdSizeBytes <= 64L * 1024L * 1024L) { "ZIP central directory exceeds safety limit." }

        val cdBytes = readExact(source, cdOffset, cdSizeBytes.toInt())
        val entries = ArrayList<VaultArchiveEntry>(cdEntriesCount.coerceIn(0L, 10_000L).toInt())
        val seenPaths = HashSet<String>()
        var cursor = 0

        while (cursor < cdBytes.size) {
            require(cursor + 46 <= cdBytes.size) { "Truncated ZIP central directory entry." }
            require(getIntLE(cdBytes, cursor) == SIG_CENTRAL_DIR) { "Invalid ZIP central directory signature." }

            val flags = getUShortLE(cdBytes, cursor + 8)
            val method = getUShortLE(cdBytes, cursor + 10)
            val dosTime = getUShortLE(cdBytes, cursor + 12)
            val dosDate = getUShortLE(cdBytes, cursor + 14)
            val crc32 = getUIntLE(cdBytes, cursor + 16)
            var compSize = getUIntLE(cdBytes, cursor + 20)
            var uncompSize = getUIntLE(cdBytes, cursor + 24)
            val nameLen = getUShortLE(cdBytes, cursor + 28)
            val extraLen = getUShortLE(cdBytes, cursor + 30)
            val commentLen = getUShortLE(cdBytes, cursor + 32)
            var localOffset = getUIntLE(cdBytes, cursor + 42)

            val nextCursor = cursor + 46 + nameLen + extraLen + commentLen
            require(nextCursor <= cdBytes.size) { "Truncated ZIP entry metadata." }

            val charset = if ((flags and FLAG_UTF8) != 0) Charsets.UTF_8 else Charsets.UTF_8
            val rawName = String(cdBytes, cursor + 46, nameLen, charset)
            val extraStart = cursor + 46 + nameLen
            val commentStart = extraStart + extraLen
            val comment = if (commentLen > 0) String(cdBytes, commentStart, commentLen, charset) else ""

            if (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var eCursor = extraStart
                val eEnd = extraStart + extraLen
                while (eCursor + 4 <= eEnd) {
                    val headerId = getUShortLE(cdBytes, eCursor)
                    val dataSize = getUShortLE(cdBytes, eCursor + 2)
                    val dataPos = eCursor + 4
                    if (headerId == 0x0001 && dataPos + dataSize <= eEnd) {
                        var fieldPos = dataPos
                        if (uncompSize == 0xFFFFFFFFL && fieldPos + 8 <= dataPos + dataSize) {
                            uncompSize = getLongLE(cdBytes, fieldPos)
                            fieldPos += 8
                        }
                        if (compSize == 0xFFFFFFFFL && fieldPos + 8 <= dataPos + dataSize) {
                            compSize = getLongLE(cdBytes, fieldPos)
                            fieldPos += 8
                        }
                        if (localOffset == 0xFFFFFFFFL && fieldPos + 8 <= dataPos + dataSize) {
                            localOffset = getLongLE(cdBytes, fieldPos)
                        }
                        break
                    }
                    eCursor = dataPos + dataSize
                }
            }

            cursor = nextCursor

            val isExplicitDir = rawName.endsWith("/") || rawName.endsWith("\\")
            val sanitized = sanitizeArchivePathOrNull(rawName)
                ?: throw SecurityException("Unsafe path detected in archive: $rawName")
            if (sanitized.isEmpty()) continue
            require(localOffset in 0L until cdOffset) { "Invalid local header offset for $sanitized" }
            require(uncompSize >= 0L && compSize >= 0L) { "Negative size for $sanitized" }
            require(method == METHOD_STORED || method == METHOD_DEFLATED) {
                "Unsupported compression method ($method) for $sanitized"
            }
            if (!seenPaths.add(sanitized.lowercase(Locale.ROOT) + if (isExplicitDir) "/" else "")) {
                continue
            }

            val name = sanitized.substringAfterLast('/')
            val parentPath = sanitized.substringBeforeLast('/', "")
            val mime = if (isExplicitDir) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                comment.takeIf { it.contains('/') } ?: guessMimeType(name)
            }

            entries += VaultArchiveEntry(
                path = sanitized,
                name = name,
                parentPath = parentPath,
                isDirectory = isExplicitDir,
                sizeBytes = if (isExplicitDir) 0L else uncompSize,
                compressedSizeBytes = if (isExplicitDir) 0L else compSize,
                compressionMethod = method,
                crc32 = crc32,
                lastModifiedMs = dosToEpochMillis(dosDate, dosTime),
                mimeType = mime,
                localHeaderOffset = localOffset
            )
        }
        return entries
    }

    fun resolveDataOffset(source: SeekablePlainSource, entry: VaultArchiveEntry): Long {
        val header = readExact(source, entry.localHeaderOffset, 30)
        require(getIntLE(header, 0) == SIG_LOCAL_HEADER) {
            "Corrupt local file header for ${entry.path}"
        }
        val nameLen = getUShortLE(header, 26)
        val extraLen = getUShortLE(header, 28)
        val dataOffset = entry.localHeaderOffset + 30L + nameLen.toLong() + extraLen.toLong()
        require(dataOffset + entry.compressedSizeBytes <= source.size) {
            "Entry data extends past end of archive: ${entry.path}"
        }
        return dataOffset
    }

    fun openEntryInputStream(
        source: SeekablePlainSource,
        entry: VaultArchiveEntry,
        verifyCrcOnEof: Boolean = true
    ): InputStream {
        val dataOffset = resolveDataOffset(source, entry)
        val rawSlice = BoundedSeekableInputStream(source, dataOffset, entry.compressedSizeBytes)
        val decompressed: InputStream = when (entry.compressionMethod) {
            METHOD_STORED -> rawSlice
            METHOD_DEFLATED -> object : InflaterInputStream(rawSlice, Inflater(true), 32 * 1024) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        inf.end()
                    }
                }
            }
            else -> throw IOException("Unsupported compression method: ${entry.compressionMethod}")
        }
        if (!verifyCrcOnEof) return decompressed
        return CrcVerifyingInputStream(decompressed, entry.sizeBytes, entry.crc32, entry.path)
    }

    fun sanitizeArchivePathOrNull(raw: String): String? {
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains(':')) return null
        val trimmed = normalized.trim('/')
        if (trimmed.isEmpty()) return ""
        val parts = trimmed.split('/')
        if (parts.any { part ->
                part.isEmpty() || part == "." || part == ".." || part.any { ch -> ch.code < 32 }
            }
        ) {
            return null
        }
        return parts.joinToString("/")
    }

    fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
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
            "bmp" -> "image/bmp"
            "txt", "log", "srt", "vtt", "ass", "ssa", "md", "csv", "json", "xml" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun dosToEpochMillis(dosDate: Int, dosTime: Int): Long {
        if (dosDate == 0 && dosTime == 0) return 0L
        val year = ((dosDate ushr 9) and 0x7F) + 1980
        val month = ((dosDate ushr 5) and 0x0F) - 1
        val day = dosDate and 0x1F
        val hour = (dosTime ushr 11) and 0x1F
        val minute = (dosTime ushr 5) and 0x3F
        val second = (dosTime and 0x1F) * 2
        val cal = Calendar.getInstance()
        cal.set(year, month.coerceIn(0, 11), day.coerceIn(1, 31), hour.coerceIn(0, 23), minute.coerceIn(0, 59), second.coerceIn(0, 59))
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun readExact(source: SeekablePlainSource, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var readTotal = 0
        while (readTotal < length) {
            val n = source.readAt(offset + readTotal, out, readTotal, length - readTotal)
            if (n < 0) throw EOFException("Unexpected end of archive at offset ${offset + readTotal}")
            if (n == 0) continue
            readTotal += n
        }
        return out
    }

    private fun getUShortLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun getIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun getUIntLE(buf: ByteArray, offset: Int): Long =
        getIntLE(buf, offset).toLong() and 0xFFFFFFFFL

    private fun getLongLE(buf: ByteArray, offset: Int): Long {
        val lo = getUIntLE(buf, offset)
        val hi = getUIntLE(buf, offset + 4)
        return lo or (hi shl 32)
    }
}

internal class BoundedSeekableInputStream(
    private val source: SeekablePlainSource,
    private val startOffset: Long,
    private val length: Long
) : InputStream() {
    private var position = 0L
    private val oneByte = ByteArray(1)

    override fun read(): Int {
        val n = read(oneByte, 0, 1)
        return if (n < 0) -1 else (oneByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val remaining = length - position
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = source.readAt(startOffset + position, b, off, toRead)
        if (n <= 0) return -1
        position += n.toLong()
        return n
    }
}

private class CrcVerifyingInputStream(
    private val delegate: InputStream,
    private val expectedSize: Long,
    private val expectedCrc32: Long,
    private val entryPath: String
) : InputStream() {
    private val crc = CRC32()
    private var totalRead = 0L
    private var verified = false
    private val oneByte = ByteArray(1)

    override fun read(): Int {
        val n = read(oneByte, 0, 1)
        return if (n < 0) -1 else (oneByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            crc.update(b, off, n)
            totalRead += n.toLong()
        } else if (n < 0 && !verified) {
            verify()
        }
        return n
    }

    private fun verify() {
        verified = true
        if (expectedSize >= 0L && totalRead != expectedSize) {
            throw IOException("Corrupt archive entry size for $entryPath ($totalRead != $expectedSize)")
        }
        if (crc.value != expectedCrc32) {
            throw IOException("CRC-32 mismatch for archive entry $entryPath")
        }
    }

    override fun close() {
        delegate.close()
    }
}

class PrivateVaultArchiveBrowser(
    context: Context,
    private val vault: PrivateVaultRepository,
    private val treeSupport: PrivateVaultTreeSupport
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    companion object {
        private val archiveMutationLock = Any()
    }

    internal fun openPlainSource(
        archiveEntry: PrivateVaultEntry,
        session: PrivateVaultSession
    ): SeekablePlainSource {
        val seekable = vault.openSeekableReader(archiveEntry.id, session)
        if (seekable != null) {
            return ChunkedV2PlainSource(seekable)
        }
        // Legacy V1 fallback only: decrypts into app-private 0600 work file and deletes on close().
        if (archiveEntry.sizeBytes > 0L) {
            vault.ensureAvailableInternalSpace(archiveEntry.sizeBytes)
        }
        val workFile = vault.createPrivateWorkFile(".archive-v1-", ".zip")
        try {
            vault.exportToFile(archiveEntry.id, workFile, session)
            return PrivateWorkFilePlainSource(workFile, vault)
        } catch (failure: Throwable) {
            vault.releasePrivateWorkFile(workFile)
            throw failure
        }
    }

    fun listAllEntries(
        archiveEntry: PrivateVaultEntry,
        session: PrivateVaultSession
    ): List<VaultArchiveEntry> = openPlainSource(archiveEntry, session).use { source ->
        VaultZipArchiveReader.parseEntries(source)
    }

    fun listDirectory(
        allEntries: List<VaultArchiveEntry>,
        currentPath: String
    ): List<VaultArchiveListingItem> {
        val normalizedCurrent = currentPath.trim('/')
        val prefix = if (normalizedCurrent.isEmpty()) "" else "$normalizedCurrent/"
        val dirMeta = linkedMapOf<String, VaultArchiveEntry?>()
        val directFiles = mutableListOf<VaultArchiveListingItem>()
        val directChildrenPerDir = linkedMapOf<String, MutableSet<String>>()

        for (entry in allEntries) {
            if (!entry.path.startsWith(prefix)) continue
            val remainder = entry.path.removePrefix(prefix)
            if (remainder.isEmpty()) continue
            val slashIdx = remainder.indexOf('/')
            if (slashIdx < 0) {
                if (entry.isDirectory) {
                    dirMeta[entry.path] = entry
                } else {
                    directFiles += entry.toListingItem()
                }
            } else {
                val childDirName = remainder.substring(0, slashIdx)
                val childDirPath = if (normalizedCurrent.isEmpty()) childDirName else "$normalizedCurrent/$childDirName"
                if (!dirMeta.containsKey(childDirPath)) {
                    dirMeta[childDirPath] = null
                }
                val grandRemainder = remainder.substring(slashIdx + 1)
                if (grandRemainder.isNotEmpty()) {
                    val grandChildSegment = grandRemainder.substringBefore('/')
                    directChildrenPerDir.getOrPut(childDirPath) { linkedSetOf() }.add(grandChildSegment)
                }
            }
        }

        val dirItems = dirMeta.entries.map { (dirPath, meta) ->
            val count = directChildrenPerDir[dirPath]?.size ?: 0
            VaultArchiveListingItem(
                path = dirPath,
                name = dirPath.substringAfterLast('/'),
                parentPath = normalizedCurrent,
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMs = meta?.lastModifiedMs ?: 0L,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                childCount = count
            )
        }
        return dirItems + directFiles
    }

    fun searchEntries(
        allEntries: List<VaultArchiveEntry>,
        currentPath: String,
        query: String,
        includeSubfolders: Boolean
    ): List<VaultArchiveListingItem> {
        if (!includeSubfolders) {
            return listDirectory(allEntries, currentPath)
        }
        val normalizedCurrent = currentPath.trim('/')
        val prefix = if (normalizedCurrent.isEmpty()) "" else "$normalizedCurrent/"
        val dirChildCounts = hashMapOf<String, MutableSet<String>>()

        for (entry in allEntries) {
            if (entry.parentPath.isNotEmpty()) {
                dirChildCounts.getOrPut(entry.parentPath) { linkedSetOf() }.add(entry.name)
            }
        }

        val allDirs = linkedMapOf<String, VaultArchiveEntry?>()
        val files = mutableListOf<VaultArchiveListingItem>()
        for (entry in allEntries) {
            if (!entry.path.startsWith(prefix)) continue
            if (entry.isDirectory) {
                allDirs[entry.path] = entry
            } else {
                files += entry.toListingItem()
            }
        }
        val dirItems = allDirs.map { (path, meta) ->
            VaultArchiveListingItem(
                path = path,
                name = path.substringAfterLast('/'),
                parentPath = path.substringBeforeLast('/', ""),
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMs = meta?.lastModifiedMs ?: 0L,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                childCount = dirChildCounts[path]?.size ?: 0
            )
        }
        val q = query.trim().lowercase(Locale.getDefault())
        return (dirItems + files).filter {
            q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q) ||
                it.path.lowercase(Locale.getDefault()).contains(q)
        }
    }

    fun readArchiveEntryBytes(
        archiveEntry: PrivateVaultEntry,
        itemPath: String,
        session: PrivateVaultSession,
        maxBytes: Int = 32 * 1024 * 1024
    ): ByteArray {
        val normalized = VaultZipArchiveReader.sanitizeArchivePathOrNull(itemPath)
            ?: throw SecurityException("Unsafe archive path.")
        openPlainSource(archiveEntry, session).use { source ->
            val target = VaultZipArchiveReader.parseEntries(source)
                .firstOrNull { !it.isDirectory && it.path == normalized }
                ?: error("Archive item not found: $normalized")
            require(target.sizeBytes in 0L..maxBytes.toLong()) {
                "File is too large for in-memory preview (${target.sizeBytes} bytes)."
            }
            val out = java.io.ByteArrayOutputStream(target.sizeBytes.toInt())
            VaultZipArchiveReader.openEntryInputStream(source, target, verifyCrcOnEof = true).use { input ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n.toLong()
                    check(total <= maxBytes.toLong()) { "Archive item exceeds preview size limit." }
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    /**
     * Deletes selected files or subfolders from an encrypted folder archive safely.
     * Rebuilds the remaining items into a verified V2 encrypted file before swapping the vault payload.
     */
    fun deleteFromArchive(
        archiveEntry: PrivateVaultEntry,
        pathsToDelete: Set<String>,
        session: PrivateVaultSession,
        isCancelled: () -> Boolean = { false },
        onProgress: ((current: Int, total: Int, label: String) -> Unit)? = null
    ): VaultArchiveModificationResult = synchronized(archiveMutationLock) {
        val normalizedDeletes = pathsToDelete.mapNotNull { VaultZipArchiveReader.sanitizeArchivePathOrNull(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        require(normalizedDeletes.isNotEmpty()) { "No valid items selected for deletion." }

        val workZip = vault.createPrivateWorkFile(".archive-rebuild-", ".zip")
        val tempEncrypted = vault.createPrivateWorkFile(".archive-enc-", ".edit.tmp")
        var verifiedNewEncryptedMoved = false

        try {
            val allEntries: List<VaultArchiveEntry>
            val keptEntries: List<VaultArchiveEntry>
            var deletedCount = 0

            openPlainSource(archiveEntry, session).use { source ->
                allEntries = VaultZipArchiveReader.parseEntries(source)
                fun isMarkedForDeletion(path: String): Boolean {
                    if (path in normalizedDeletes) return true
                    return normalizedDeletes.any { del -> path.startsWith("$del/") }
                }
                keptEntries = allEntries.filterNot { isMarkedForDeletion(it.path) }
                deletedCount = allEntries.size - keptEntries.size
                require(deletedCount > 0) { "Selected items were not found inside the archive." }

                val approxNeeded = keptEntries.sumOf { it.sizeBytes.coerceAtLeast(0L) } + 1024L * 1024L
                vault.ensureAvailableInternalSpace(approxNeeded)

                val total = keptEntries.size.coerceAtLeast(1)
                FileOutputStream(workZip).use { rawOut ->
                    ZipOutputStream(rawOut).use { zipOut ->
                        val copyBuf = ByteArray(64 * 1024)
                        keptEntries.forEachIndexed { idx, entry ->
                            if (isCancelled()) throw IOException("Archive update cancelled.")
                            onProgress?.invoke(idx + 1, total, entry.name)
                            if (entry.isDirectory) {
                                zipOut.putNextEntry(ZipEntry("${entry.path}/").apply {
                                    if (entry.lastModifiedMs > 0L) time = entry.lastModifiedMs
                                })
                                zipOut.closeEntry()
                            } else {
                                val outEntry = ZipEntry(entry.path).apply {
                                    comment = entry.mimeType
                                    if (entry.lastModifiedMs > 0L) time = entry.lastModifiedMs
                                    if (entry.compressionMethod == ZipEntry.STORED) {
                                        method = ZipEntry.STORED
                                        size = entry.sizeBytes
                                        compressedSize = entry.sizeBytes
                                        crc = entry.crc32
                                    }
                                }
                                zipOut.putNextEntry(outEntry)
                                VaultZipArchiveReader.openEntryInputStream(source, entry, verifyCrcOnEof = true)
                                    .use { entryIn ->
                                        while (true) {
                                            if (isCancelled()) throw IOException("Archive update cancelled.")
                                            val n = entryIn.read(copyBuf)
                                            if (n < 0) break
                                            if (n == 0) continue
                                            zipOut.write(copyBuf, 0, n)
                                        }
                                    }
                                zipOut.closeEntry()
                            }
                        }
                    }
                    rawOut.fd.sync()
                }
            }

            if (isCancelled()) throw IOException("Archive update cancelled.")
            val newPlainSize = workZip.length()
            vault.ensureAvailableInternalSpace(newPlainSize + 64L * 1024L)

            FileInputStream(workZip).use { zipIn ->
                PrivateVaultCrypto.encryptFileChunked(
                    input = zipIn,
                    destinationFile = tempEncrypted,
                    key = session.requireMasterKey()
                )
            }

            // Verify new encrypted archive integrity and entry count before swapping!
            PrivateVaultCrypto.VaultSeekableReader(tempEncrypted, session.requireMasterKey()).use { reader ->
                val verifiedSource = ChunkedV2PlainSource(reader)
                val verifiedEntries = VaultZipArchiveReader.parseEntries(verifiedSource)
                check(verifiedEntries.size == keptEntries.size) {
                    "Verified archive entry count mismatch (${verifiedEntries.size} != ${keptEntries.size})."
                }
            }

            val updatedEntry = vault.replaceEntryPayload(
                id = archiveEntry.id,
                verifiedNewEncryptedFile = tempEncrypted,
                newPlainSizeBytes = newPlainSize,
                session = session
            )
            verifiedNewEncryptedMoved = true

            return VaultArchiveModificationResult(
                updatedVaultEntry = updatedEntry,
                deletedPathsCount = deletedCount,
                remainingItemCount = keptEntries.size
            )
        } finally {
            vault.releasePrivateWorkFile(workZip)
            if (!verifiedNewEncryptedMoved) {
                vault.releasePrivateWorkFile(tempEncrypted)
            } else {
                // Already renamed to target; remove name from activeTempNames via release only if still present
                vault.releasePrivateWorkFile(tempEncrypted)
            }
        }
    }

    /**
     * Selectively extracts files or subfolders from inside an encrypted archive to [destinationTree]
     * (or to the original folder if [useOriginalFolder] is true and valid).
     * Verifies size and SHA-256 content digest after writing; only deletes extracted paths from the
     * encrypted archive when [removeFromArchiveAfterExtract] is true AND extraction succeeded.
     */
    fun extractArchiveSelection(
        archiveEntry: PrivateVaultEntry,
        selectedItems: List<VaultArchiveListingItem>,
        session: PrivateVaultSession,
        destinationTree: Uri?,
        useOriginalFolder: Boolean,
        removeFromArchiveAfterExtract: Boolean,
        isCancelled: () -> Boolean = { false },
        onItemProgress: ((current: Int, total: Int, label: String) -> Unit)? = null
    ): VaultBatchTransferSummary {
        if (selectedItems.isEmpty()) {
            return VaultBatchTransferSummary(0, 0, 0, 0)
        }

        val resolvedTarget = resolveDestinationTarget(archiveEntry, destinationTree, useOriginalFolder)
            ?: return VaultBatchTransferSummary(
                succeededCount = 0,
                failedCount = selectedItems.size,
                removedFromVaultCount = 0,
                keptInVaultCount = selectedItems.size,
                failures = listOf("No valid destination folder or expired original folder permission.")
            )
        val (targetTree, targetParentDocId) = resolvedTarget

        var succeededCount = 0
        var failedCount = 0
        val failures = mutableListOf<String>()
        val extractedUris = mutableListOf<String>()
        val succeededPathsForDeletion = linkedSetOf<String>()
        val dirCache = hashMapOf<String, Uri>()
        val knownChildNames = hashMapOf<String, MutableSet<String>>()

        openPlainSource(archiveEntry, session).use { source ->
            val allEntries = VaultZipArchiveReader.parseEntries(source)
            val entryByPath = allEntries.filterNot { it.isDirectory }.associateBy { it.path }

            data class ExtractTask(
                val archiveEntry: VaultArchiveEntry,
                val relativePathUnderTarget: String
            )

            val tasks = mutableListOf<ExtractTask>()
            val seenTaskPaths = HashSet<String>()
            for (sel in selectedItems) {
                val safeSelPath = VaultZipArchiveReader.sanitizeArchivePathOrNull(sel.path) ?: continue
                if (sel.isDirectory) {
                    val dirPrefix = "$safeSelPath/"
                    val baseParent = sel.parentPath.trim('/')
                    allEntries.filter { !it.isDirectory && it.path.startsWith(dirPrefix) }.forEach { child ->
                        if (seenTaskPaths.add(child.path)) {
                            val rel = if (baseParent.isEmpty()) child.path else child.path.removePrefix("$baseParent/")
                            tasks += ExtractTask(child, rel)
                        }
                    }
                    // Also handle explicitly empty directories selected by user
                    val hasAnyFile = allEntries.any { !it.isDirectory && it.path.startsWith(dirPrefix) }
                    if (!hasAnyFile) {
                        val relDir = if (baseParent.isEmpty()) safeSelPath else safeSelPath.removePrefix("$baseParent/")
                        runCatching {
                            ensureRelativeDirectory(
                                targetTree = targetTree,
                                targetRootDocId = targetParentDocId,
                                relDirPath = relDir,
                                dirCache = dirCache,
                                knownChildNames = knownChildNames
                            )
                            succeededPathsForDeletion += safeSelPath
                        }
                    }
                } else {
                    val found = entryByPath[safeSelPath]
                    if (found != null && seenTaskPaths.add(found.path)) {
                        tasks += ExtractTask(found, found.name)
                    }
                }
            }

            val total = tasks.size.coerceAtLeast(1)
            for ((idx, task) in tasks.withIndex()) {
                if (isCancelled()) {
                    failedCount += (tasks.size - idx)
                    failures += "Cancelled by user."
                    break
                }
                onItemProgress?.invoke(idx + 1, total, task.archiveEntry.name)
                var createdFile: Uri? = null
                val outcome = runCatching {
                    val relParts = task.relativePathUnderTarget.split('/').filter { it.isNotEmpty() }
                    require(relParts.isNotEmpty()) { "Invalid output path." }
                    val parentRel = if (relParts.size > 1) relParts.dropLast(1).joinToString("/") else ""
                    val parentUri = if (parentRel.isEmpty()) {
                        DocumentsContract.buildDocumentUriUsingTree(targetTree, targetParentDocId)
                    } else {
                        ensureRelativeDirectory(
                            targetTree = targetTree,
                            targetRootDocId = targetParentDocId,
                            relDirPath = parentRel,
                            dirCache = dirCache,
                            knownChildNames = knownChildNames
                        )
                    }
                    val parentDoc = DocumentsContract.getDocumentId(parentUri)
                    val usedNames = knownChildNames.getOrPut(parentRel) {
                        listChildNames(targetTree, parentDoc).toMutableSet()
                    }
                    val desiredName = relParts.last()
                    val uniqueFileName = resolveUniqueChildName(desiredName, usedNames, isDirectory = false)
                    usedNames += uniqueFileName

                    val docUri = DocumentsContract.createDocument(
                        resolver,
                        parentUri,
                        task.archiveEntry.mimeType,
                        uniqueFileName
                    ) ?: error("Could not create $uniqueFileName in destination.")
                    createdFile = docUri

                    val digest = MessageDigest.getInstance("SHA-256")
                    var writtenBytes = 0L
                    val out = resolver.openOutputStream(docUri, "wt")
                        ?: error("Could not open destination output stream for $uniqueFileName.")
                    out.use { rawOut ->
                        val buffer = ByteArray(64 * 1024)
                        VaultZipArchiveReader.openEntryInputStream(source, task.archiveEntry, verifyCrcOnEof = true)
                            .use { entryIn ->
                                while (true) {
                                    if (isCancelled()) throw IOException("Cancelled during write.")
                                    val n = entryIn.read(buffer)
                                    if (n < 0) break
                                    if (n == 0) continue
                                    rawOut.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                    writtenBytes += n.toLong()
                                }
                            }
                        rawOut.flush()
                        if (rawOut is FileOutputStream) {
                            runCatching { rawOut.fd.sync() }
                        }
                    }
                    check(writtenBytes == task.archiveEntry.sizeBytes) {
                        "Extracted byte count mismatch for $uniqueFileName ($writtenBytes != ${task.archiveEntry.sizeBytes})."
                    }
                    vault.verifyWrittenDestinationDigest(docUri, writtenBytes, digest.digest())
                    docUri
                }

                if (outcome.isSuccess) {
                    succeededCount++
                    outcome.getOrNull()?.let { extractedUris += it.toString() }
                    succeededPathsForDeletion += task.archiveEntry.path
                } else {
                    createdFile?.let { partial ->
                        runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                    }
                    failedCount++
                    val errMsg = outcome.exceptionOrNull()?.localizedMessage ?: task.archiveEntry.name
                    failures += "${task.archiveEntry.name}: $errMsg"
                }
            }
        }

        // Only delete from archive if ALL selected items succeeded or delete strictly succeeded items?
        // For safety on folders, delete individual succeeded files (and directories only when no failures occurred inside them).
        var removedCount = 0
        var deleteFailedCount = 0
        if (removeFromArchiveAfterExtract && succeededPathsForDeletion.isNotEmpty()) {
            val safeDeleteTargets = if (failedCount == 0) {
                selectedItems.map { it.path }.toSet()
            } else {
                succeededPathsForDeletion
            }
            val modOutcome = runCatching {
                deleteFromArchive(
                    archiveEntry = archiveEntry,
                    pathsToDelete = safeDeleteTargets,
                    session = session,
                    isCancelled = { false }
                )
            }
            if (modOutcome.isSuccess) {
                removedCount = succeededCount
            } else {
                deleteFailedCount = succeededCount
                failures += "Extracted successfully, but failed updating archive: ${modOutcome.exceptionOrNull()?.localizedMessage}"
            }
        }

        return VaultBatchTransferSummary(
            succeededCount = succeededCount,
            failedCount = failedCount,
            removedFromVaultCount = removedCount,
            keptInVaultCount = if (removeFromArchiveAfterExtract) (succeededCount - removedCount) + failedCount else succeededCount + failedCount,
            extractedButVaultDeleteFailedCount = deleteFailedCount,
            failures = failures,
            restoredFilePathsOrUris = extractedUris
        )
    }

    private fun resolveDestinationTarget(
        archiveEntry: PrivateVaultEntry,
        fallbackTree: Uri?,
        useOriginalFolder: Boolean
    ): Pair<Uri, String>? {
        if (useOriginalFolder && archiveEntry.sourceTreeUri != null) {
            val origTree = runCatching { Uri.parse(archiveEntry.sourceTreeUri) }.getOrNull()
            if (origTree != null && treeSupport.isTreeFolderAccessible(origTree, archiveEntry.sourceParentDocumentId)) {
                val parentId = archiveEntry.sourceParentDocumentId
                    ?: runCatching { DocumentsContract.getTreeDocumentId(origTree) }.getOrNull()
                if (parentId != null) return origTree to parentId
            }
        }
        val dest = fallbackTree ?: return null
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(dest) }.getOrNull() ?: return null
        return dest to rootDocId
    }

    private fun ensureRelativeDirectory(
        targetTree: Uri,
        targetRootDocId: String,
        relDirPath: String,
        dirCache: MutableMap<String, Uri>,
        knownChildNames: MutableMap<String, MutableSet<String>>
    ): Uri {
        dirCache[relDirPath]?.let { return it }
        val parts = relDirPath.split('/').filter { it.isNotEmpty() }
        var currentParentUri = DocumentsContract.buildDocumentUriUsingTree(targetTree, targetRootDocId)
        var currentRel = ""
        for (part in parts) {
            val nextRel = if (currentRel.isEmpty()) part else "$currentRel/$part"
            val cached = dirCache[nextRel]
            if (cached != null) {
                currentParentUri = cached
                currentRel = nextRel
                continue
            }
            val parentDoc = DocumentsContract.getDocumentId(currentParentUri)
            val existingDir = findChildDirectory(targetTree, parentDoc, part)
            val resolvedDir = if (existingDir != null) {
                existingDir
            } else {
                val usedNames = knownChildNames.getOrPut(currentRel) {
                    listChildNames(targetTree, parentDoc).toMutableSet()
                }
                val uniqueDirName = resolveUniqueChildName(part, usedNames, isDirectory = true)
                usedNames += uniqueDirName
                DocumentsContract.createDocument(
                    resolver,
                    currentParentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    uniqueDirName
                ) ?: error("Could not create folder $uniqueDirName")
            }
            dirCache[nextRel] = resolvedDir
            currentParentUri = resolvedDir
            currentRel = nextRel
        }
        return currentParentUri
    }

    private fun findChildDirectory(treeUri: Uri, parentDocId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        return runCatching {
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (idCol >= 0 && nameCol >= 0 && mimeCol >= 0 &&
                        cursor.getString(nameCol) == name &&
                        cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    ) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun listChildNames(treeUri: Uri, parentDocId: String): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val names = mutableSetOf<String>()
        runCatching {
            resolver.query(
                children,
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
}
