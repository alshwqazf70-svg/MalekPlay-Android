package com.malekplay.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * In-memory Media3 [DataSource] for isolated playback of encrypted vault media.
 *
 * - Version 2 entries stream with O(1) memory and instant seeking via [PrivateVaultCrypto.VaultSeekableReader].
 * - Legacy v1 entries (up to 48 MB) decrypt strictly in RAM without writing plaintext files to disk.
 */
@UnstableApi
class VaultMediaDataSource(
    private val repository: PrivateVaultRepository,
    private val session: PrivateVaultSession,
    private val entriesProvider: () -> List<PrivateVaultEntry>
) : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var seekableReader: PrivateVaultCrypto.VaultSeekableReader? = null
    private var legacyMemoryBytes: ByteArray? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val entryId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Missing vault entry identifier.")

        val reader = repository.openSeekableReader(entryId, session)
        val totalLength: Long
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
        private const val MAX_LEGACY_MEMORY_BYTES = 48 * 1024 * 1024
        fun entryUri(entryId: String): Uri = Uri.parse("vault://entry/$entryId")
    }
}
