package com.malekplay.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Bounded, versioned batch manifest for the Ultra Share v2 data session.
 *
 * The manifest is deliberately independent from sockets so it can be validated
 * before any destination file is created. One manifest describes the complete
 * batch; each entry keeps its own identity, digest and eventual result.
 */
object UltraShareBatchProtocol {
    const val VERSION = 2
    const val MAX_FILES = 512
    const val MAX_MANIFEST_BYTES = 512 * 1024
    const val MAX_NAME_BYTES = 180
    const val MAX_MIME_BYTES = 192
    const val MAX_FILE_BYTES = UltraShareTransport.MAX_FILE_BYTES

    private const val MAGIC = 0x4D505532 // MPU2
    private const val ID_BYTES = 16
    private const val DIGEST_BYTES = 32

    data class FileEntry(
        val id: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: ByteArray
    ) {
        override fun equals(other: Any?): Boolean =
            other is FileEntry && id == other.id && name == other.name &&
                mimeType == other.mimeType && sizeBytes == other.sizeBytes &&
                MessageDigest.isEqual(sha256, other.sha256)

        override fun hashCode(): Int =
            31 * (31 * (31 * id.hashCode() + name.hashCode()) + sizeBytes.hashCode()) +
                sha256.contentHashCode()
    }

    data class Manifest(val batchId: String, val entries: List<FileEntry>) {
        val totalBytes: Long
            get() = entries.fold(0L) { total, entry ->
                require(Long.MAX_VALUE - total >= entry.sizeBytes) { "حجم الدفعة يتجاوز الحد الحسابي." }
                total + entry.sizeBytes
            }
    }

    enum class FileStatus { PENDING, TRANSFERRING, COMPLETED, FAILED, SKIPPED }

    data class FileResult(
        val fileId: String,
        val status: FileStatus,
        val transferredBytes: Long,
        val error: String? = null
    )

    fun newEntry(
        name: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: ByteArray,
        id: String = UUID.randomUUID().toString()
    ): FileEntry {
        val safeName = UltraShareTransport.sanitizeFileName(name)
        val safeMime = UltraShareTransport.sanitizeMimeType(mimeType)
        require(safeName.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        require(sizeBytes in 0..MAX_FILE_BYTES)
        require(sha256.size == DIGEST_BYTES)
        require(runCatching { UUID.fromString(id) }.isSuccess)
        return FileEntry(id, safeName, safeMime, sizeBytes, sha256.copyOf())
    }

    fun validate(manifest: Manifest): Manifest {
        require(runCatching { UUID.fromString(manifest.batchId) }.isSuccess) {
            "معرّف دفعة الإرسال غير صالح."
        }
        require(manifest.entries.isNotEmpty() && manifest.entries.size <= MAX_FILES) {
            "عدد ملفات الدفعة خارج الحد المسموح."
        }
        val ids = HashSet<String>(manifest.entries.size)
        manifest.entries.forEach { entry ->
            require(runCatching { UUID.fromString(entry.id) }.isSuccess)
            require(ids.add(entry.id)) { "يوجد معرّف ملف مكرر داخل الدفعة." }
            require(UltraShareTransport.sanitizeFileName(entry.name) == entry.name)
            require(entry.name.toByteArray(Charsets.UTF_8).size in 1..MAX_NAME_BYTES)
            require(UltraShareTransport.sanitizeMimeType(entry.mimeType) == entry.mimeType)
            require(UltraShareTransport.isAllowedTransferMimeType(entry.mimeType)) {
                "نوع الملف المرسل غير صالح."
            }
            require(entry.mimeType.toByteArray(Charsets.UTF_8).size <= MAX_MIME_BYTES)
            require(entry.sizeBytes in 0..MAX_FILE_BYTES)
            require(entry.sha256.size == DIGEST_BYTES)
        }
        manifest.totalBytes
        return manifest
    }

    fun encode(manifest: Manifest): ByteArray {
        val checked = validate(manifest)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeShort(VERSION)
            output.writeLong(UUID.fromString(checked.batchId).mostSignificantBits)
            output.writeLong(UUID.fromString(checked.batchId).leastSignificantBits)
            output.writeShort(checked.entries.size)
            checked.entries.forEach { entry ->
                val id = UUID.fromString(entry.id)
                output.writeLong(id.mostSignificantBits)
                output.writeLong(id.leastSignificantBits)
                writeText(output, entry.name, MAX_NAME_BYTES)
                writeText(output, entry.mimeType, MAX_MIME_BYTES)
                output.writeLong(entry.sizeBytes)
                output.write(entry.sha256)
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= MAX_MANIFEST_BYTES) { "بيان الدفعة أكبر من الحد المسموح." }
        }
    }

    fun decode(payload: ByteArray): Manifest {
        require(payload.size in 1..MAX_MANIFEST_BYTES) { "حجم بيان الدفعة غير صالح." }
        val manifest = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "توقيع بيان الدفعة غير صحيح." }
            require(input.readUnsignedShort() == VERSION) { "إصدار بيان الدفعة غير مدعوم." }
            val batchId = UUID(input.readLong(), input.readLong()).toString()
            val count = input.readUnsignedShort()
            require(count in 1..MAX_FILES) { "عدد ملفات الدفعة غير صالح." }
            val entries = ArrayList<FileEntry>(count)
            repeat(count) {
                val id = UUID(input.readLong(), input.readLong()).toString()
                val name = readText(input, MAX_NAME_BYTES)
                val mime = readText(input, MAX_MIME_BYTES)
                val size = input.readLong()
                val digest = ByteArray(DIGEST_BYTES).also(input::readFully)
                entries += FileEntry(id, name, mime, size, digest)
            }
            require(input.available() == 0) { "يحتوي بيان الدفعة على بيانات زائدة." }
            Manifest(batchId, entries)
        }
        return validate(manifest)
    }

    fun validateResults(manifest: Manifest, results: List<FileResult>): List<FileResult> {
        validate(manifest)
        require(results.size == manifest.entries.size) { "نتائج الملفات لا تطابق بيان الدفعة." }
        val expected = manifest.entries.map { it.id }.toSet()
        require(results.map { it.fileId }.toSet() == expected) { "معرّفات نتائج الملفات غير مطابقة." }
        results.forEach { result ->
            val entry = manifest.entries.first { it.id == result.fileId }
            require(result.transferredBytes in 0..entry.sizeBytes)
            if (result.status == FileStatus.COMPLETED) {
                require(result.transferredBytes == entry.sizeBytes)
                require(result.error == null)
            }
            require(result.error == null || result.error.toByteArray(Charsets.UTF_8).size <= 1024)
        }
        return results
    }

    private fun writeText(output: DataOutputStream, value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..maximumBytes)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream, maximumBytes: Int): String {
        val length = input.readUnsignedShort()
        require(length in 1..maximumBytes)
        val bytes = ByteArray(length).also(input::readFully)
        return bytes.toString(Charsets.UTF_8)
    }
}
