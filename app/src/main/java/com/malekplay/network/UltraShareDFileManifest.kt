package com.malekplay.network

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated per-batch file manifest carried in NSTACKX DFile's userData field.
 *
 * DFile encrypts the transfer session, but its success callback alone is not an
 * end-to-end integrity receipt. This manifest binds each received basename and
 * exact byte count to the SHA-256 digest calculated before the sender queues it.
 */
internal object UltraShareDFileManifest {
    private val MAGIC = byteArrayOf(0x4d, 0x50, 0x44, 0x46, 0x01) // "MPDF", version 1
    private val HMAC_DOMAIN = "MalekPlayer/NSTACKX/DFile/manifest/v1".toByteArray(Charsets.UTF_8)
    private const val HMAC_BYTES = 32
    private const val DIGEST_BYTES = 32
    private const val MAX_FILE_NAME_BYTES = 255
    private const val MAX_ENTRIES = 500
    private const val MAX_USER_DATA_CHARS = 60 * 1024

    data class Entry(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: ByteArray
    )

    data class Manifest(val entries: List<Entry>) {
        val totalBytes: Long
            get() = entries.sumOf { it.sizeBytes }
    }

    /**
     * Returns a compact URL-safe Base64 string suitable for DFile's per-send
     * userData field. Throws before native payload submission if it cannot fit.
     */
    fun encode(entries: List<Entry>, sessionKey: ByteArray): String {
        require(sessionKey.size == 16) { "مفتاح جلسة DFile غير صالح." }
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) {
            "عدد ملفات بيان DFile غير مدعوم."
        }
        validateEntries(entries)

        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeShort(entries.size)
                entries.forEach { entry ->
                    val name = entry.fileName.toByteArray(StandardCharsets.UTF_8)
                    output.writeShort(name.size)
                    output.write(name)
                    output.writeLong(entry.sizeBytes)
                    output.write(entry.sha256)
                }
            }
            bytes.toByteArray()
        }
        val tag = hmac(sessionKey, payload)
        val authenticated = ByteArray(payload.size + tag.size)
        System.arraycopy(payload, 0, authenticated, 0, payload.size)
        System.arraycopy(tag, 0, authenticated, payload.size, tag.size)
        val encoded = authenticated.toByteString().base64Url()
        require(encoded.length <= MAX_USER_DATA_CHARS) {
            "بيان الملفات أكبر من الحد الذي تسمح به جلسة DFile؛ استخدم نقل TCP الآمن."
        }
        return encoded
    }

    /** Returns null for malformed, oversized, unauthenticated or inconsistent metadata. */
    fun decode(userData: String, sessionKey: ByteArray): Manifest? = runCatching {
        if (sessionKey.size != 16 || userData.isBlank() || userData.length > MAX_USER_DATA_CHARS) {
            return null
        }
        val decoded = userData.decodeBase64()?.toByteArray() ?: return null
        if (decoded.size <= MAGIC.size + 2 + HMAC_BYTES) return null

        val payloadSize = decoded.size - HMAC_BYTES
        val payload = decoded.copyOfRange(0, payloadSize)
        val suppliedTag = decoded.copyOfRange(payloadSize, decoded.size)
        val expectedTag = hmac(sessionKey, payload)
        if (!MessageDigest.isEqual(suppliedTag, expectedTag)) return null

        val entries = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            val count = input.readUnsignedShort()
            if (count !in 1..MAX_ENTRIES) return null
            val parsed = ArrayList<Entry>(count)
            repeat(count) {
                val nameLength = input.readUnsignedShort()
                if (nameLength !in 1..MAX_FILE_NAME_BYTES) return null
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val name = decoder.decode(ByteBuffer.wrap(nameBytes)).toString()
                val size = input.readLong()
                val digest = ByteArray(DIGEST_BYTES)
                input.readFully(digest)
                parsed += Entry(name, size, digest)
            }
            if (input.available() != 0) return null
            parsed
        }
        validateEntries(entries)
        Manifest(entries)
    }.getOrNull()

    fun verifyFile(file: File, entry: Entry): Boolean = runCatching {
        if (!file.isFile || file.length() != entry.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        MessageDigest.isEqual(digest.digest(), entry.sha256) && file.length() == entry.sizeBytes
    }.getOrDefault(false)

    private fun validateEntries(entries: List<Entry>) {
        val names = HashSet<String>(entries.size)
        entries.forEach { entry ->
            val encodedName = entry.fileName.toByteArray(StandardCharsets.UTF_8)
            require(encodedName.isNotEmpty() && encodedName.size <= MAX_FILE_NAME_BYTES)
            require(entry.fileName == UltraShareTransport.sanitizeFileName(entry.fileName))
            require('/' !in entry.fileName && '\\\\' !in entry.fileName)
            require(names.add(entry.fileName)) { "تكرّر اسم ملف في بيان DFile." }
            require(entry.sizeBytes in 0..UltraShareTransport.MAX_FILE_BYTES)
            require(entry.sha256.size == DIGEST_BYTES)
        }
    }

    private fun hmac(sessionKey: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update(HMAC_DOMAIN)
        mac.update(0)
        return mac.doFinal(payload)
    }
}
