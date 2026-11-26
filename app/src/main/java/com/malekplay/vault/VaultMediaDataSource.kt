package com.malekplay.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * In-memory Media3 [DataSource] for isolated playback of encrypted vault media.
 *
 * - Regular V2 entries (`vault://entry/<id>`) stream with O(1) memory and instant seeking via [PrivateVaultCrypto.VaultSeekableReader].
 * - Legacy V1 entries (up to 48 MB) decrypt strictly in RAM without writing plaintext files to disk.
 * - Encrypted folder archive entries (`vault://archive/<id>?path=<encoded>`) stream directly via
 *   [VaultZipArchiveReader] over [PrivateVaultCrypto.VaultSeekableReader] when STORED (O(1) seek, zero disk cache),
 *   or selectively decompress a single entry for legacy DEFLATED entries (RAM <= 48 MB or 0600 app-private work file).
 */
@UnstableApi
class VaultMediaDataSource(
    private val repository: PrivateVaultRepository,
    private val session: PrivateVaultSession,
    private val entriesProvider: () -> List<PrivateVaultEntry>
) : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var seekableReader: PrivateVaultCrypto.VaultSeekableReader? = null
    private var archivePlainSource: SeekablePlainSource? = null
    private var archiveSliceOffset: Long = 0L
    private var archiveWorkFile: File? = null
    private var archiveWorkRaf: RandomAccessFile? = null
    private var legacyMemoryBytes: ByteArray? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val host = dataSpec.uri.host
        val entryId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Missing vault entry identifier.")

        val totalLength: Long
        if (host == HOST_ARCHIVE) {
            val rawPath = dataSpec.uri.getQueryParameter("path")
                ?: throw IOException("Missing archive item path.")
            val safePath = VaultZipArchiveReader.sanitizeArchivePathOrNull(rawPath)
                ?: throw SecurityException("Unsafe archive item path.")

            val reader = repository.openSeekableReader(entryId, session)
            val source: SeekablePlainSource = if (reader != null) {
                ChunkedV2PlainSource(reader)
            } else {
                val archiveEntry = entriesProvider().firstOrNull { it.id == entryId }
                    ?: throw IOException("Vault archive not found: $entryId")
                val work = repository.createPrivateWorkFile(".archive-play-", ".zip")
                try {
                    repository.exportToFile(archiveEntry.id, work, session)
                    PrivateWorkFilePlainSource(work, repository)
                } catch (err: Throwable) {
                    repository.releasePrivateWorkFile(work)
                    throw err
                }
            }
            archivePlainSource = source

            val target = VaultZipArchiveReader.parseEntries(source)
                .firstOrNull { !it.isDirectory && it.path == safePath }
                ?: run {
                    closeResources()
                    throw IOException("Archive item not found: $safePath")
                }

            if (target.compressionMethod == 0) {
                // STORED: stream directly from encrypted chunks with O(1) memory & instant seeking!
                archiveSliceOffset = VaultZipArchiveReader.resolveDataOffset(source, target)
                totalLength = target.sizeBytes
            } else if (target.sizeBytes in 0L..MAX_LEGACY_MEMORY_BYTES.toLong()) {
                // Small DEFLATED entry: keep strictly in RAM and zero out on close.
                val out = ByteArrayOutputStream(target.sizeBytes.toInt().coerceAtLeast(0))
                VaultZipArchiveReader.openEntryInputStream(source, target, verifyCrcOnEof = true).use { input ->
                    input.copyTo(out)
                }
                val bytes = out.toByteArray()
                legacyMemoryBytes = bytes
                totalLength = bytes.size.toLong()
                runCatching { source.close() }
                archivePlainSource = null
            } else {
                // Large DEFLATED legacy entry: extract only this single item to 0600 private work file and delete on close.
                repository.ensureAvailableInternalSpace(target.sizeBytes)
                val singleWork = repository.createPrivateWorkFile(".archive-media-", ".bin")
                archiveWorkFile = singleWork
                try {
                    FileOutputStream(singleWork).use { rawOut ->
                        VaultZipArchiveReader.openEntryInputStream(source, target, verifyCrcOnEof = true).use { input ->
                            input.copyTo(rawOut)
                        }
                        rawOut.fd.sync()
                    }
                    archiveWorkRaf = RandomAccessFile(singleWork, "r")
                    totalLength = singleWork.length()
                    runCatching { source.close() }
                    archivePlainSource = null
                } catch (err: Throwable) {
                    closeResources()
                    throw err
                }
            }
        } else {
            val reader = repository.openSeekableReader(entryId, session)
            if (reader != null) {
                seekableReader = reader
                totalLength = reader.plaintextSize
            } else {
                val entry = entriesProvider().firstOrNull { it.id == entryId }
                    ?: throw IOException("Vault entry not found: $entryId")
                val bytes = repository.readEntryBytes(entry, session, maxBytes = MAX_LEGACY_MEMORY_BYTES)
                legacyMemoryBytes = bytes
                totalLength = bytes.size.toLong()
            }
        }

        if (dataSpec.position > totalLength) {
            closeResources()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        readPosition = dataSpec.position
        val available = totalLength - readPosition
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, available)
        } else {
            available
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = seekableReader?.readAt(readPosition, buffer, offset, toRead)
            ?: archivePlainSource?.readAt(archiveSliceOffset + readPosition, buffer, offset, toRead)
            ?: archiveWorkRaf?.let { raf ->
                raf.seek(readPosition)
                raf.read(buffer, offset, toRead)
            }
            ?: legacyMemoryBytes?.let { bytes ->
                val pos = readPosition.toInt()
                val avail = bytes.size - pos
                if (avail <= 0) -1 else {
                    val count = minOf(toRead, avail)
                    bytes.copyInto(buffer, offset, pos, pos + count)
                    count
                }
            }
            ?: return C.RESULT_END_OF_INPUT

        if (read <= 0) return C.RESULT_END_OF_INPUT
        readPosition += read.toLong()
        bytesRemaining -= read.toLong()
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        closeResources()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeResources() {
        runCatching { seekableReader?.close() }
        seekableReader = null
        runCatching { archivePlainSource?.close() }
        archivePlainSource = null
        archiveSliceOffset = 0L
        runCatching { archiveWorkRaf?.close() }
        archiveWorkRaf = null
        repository.releasePrivateWorkFile(archiveWorkFile)
        archiveWorkFile = null
        legacyMemoryBytes?.fill(0)
        legacyMemoryBytes = null
    }

    class Factory(
        private val repository: PrivateVaultRepository,
        private val session: PrivateVaultSession,
        private val entriesProvider: () -> List<PrivateVaultEntry>
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            VaultMediaDataSource(repository, session, entriesProvider)
    }

    companion object {
        private const val HOST_ARCHIVE = "archive"
        private const val MAX_LEGACY_MEMORY_BYTES = 48 * 1024 * 1024
        fun entryUri(entryId: String): Uri = Uri.parse("vault://entry/$entryId")
        fun archiveEntryUri(entryId: String, archivePath: String): Uri =
            Uri.Builder()
                .scheme("vault")
                .authority(HOST_ARCHIVE)
                .appendPath(entryId)
                .appendQueryParameter("path", archivePath)
                .build()
    }
}
