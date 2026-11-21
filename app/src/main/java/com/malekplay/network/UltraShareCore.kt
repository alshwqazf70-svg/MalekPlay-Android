package com.malekplay.network

import android.net.Uri
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared validation, pairing-key, identity and publication primitives used by
 * UltraShareBatchTransport (V2). File data is never sent or received by this class.
 */
/**
 * Guarantees forward progress for unusual InputStreams that return zero from bulk reads.
 * Returns -1 only at EOF and otherwise copies at least one byte into the destination.
 */
internal fun readStreamWithProgress(
    input: InputStream,
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset
): Int {
    require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
    if (length == 0) return 0
    val count = input.read(buffer, offset, length)
    if (count != 0) return count
    val single = input.read()
    if (single < 0) return -1
    buffer[offset] = single.toByte()
    return 1
}

object UltraShareCore {
    const val MAX_FILE_BYTES = 100L * 1024 * 1024 * 1024
    private const val SECRET_BYTES = 32
    private const val PIN_LENGTH = 9
    private const val PIN_KDF_ITERATIONS = 210_000
    private const val PIN_SALT_BYTES = 16
    private const val CHALLENGE_BYTES = 32
    private val destinationPublicationLocks = Array(64) { Any() }

    /** Serialize final-name selection and publication across concurrent V2 receiver workers. */
    internal fun <T> withDestinationPublicationLock(directory: File, action: () -> T): T {
        val key = runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
        val stripe = (key.hashCode() and Int.MAX_VALUE) % destinationPublicationLocks.size
        return synchronized(destinationPublicationLocks[stripe]) { action() }
    }

    data class Progress(
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val receiving: Boolean
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f
            else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    data class ReceivedFile(
        val file: File,
        val fileName: String,
        val sizeBytes: Long,
        val mimeType: String,
        val publishedUri: Uri? = null
    )

    /** Generates a compact, human-friendly one-time numeric pairing PIN. */
    fun createPairingSecret(): String {
        // A nine-digit one-time PIN is easy to read aloud; challenge-response
        // and the per-session PBKDF2 key keep the PIN off the network.
        val value = SecureRandom().nextInt(1_000_000_000)
        return String.format(Locale.US, "%09d", value)
    }

    fun isValidPairingSecret(value: String): Boolean =
        (value.length == PIN_LENGTH && value.all { it in '0'..'9' }) ||
            (value.length == SECRET_BYTES * 2 && value.all { it in "0123456789abcdefABCDEF" })


    /** Accepts only literal private/link-local IPv4 endpoints; never resolves hostnames. */
    fun isValidPrivateLanHost(host: String): Boolean = runCatching {
        resolvePrivateLanAddress(host)
        true
    }.getOrDefault(false)

    /** Derives a per-handshake key; the fresh receiver challenge supplies the PBKDF2 salt. */
    internal fun derivePairingKey(value: String, challenge: ByteArray): ByteArray {
        require(isValidPairingSecret(value)) { "رمز الاقتران غير صالح." }
        require(challenge.size == CHALLENGE_BYTES) { "تحدي الاقتران غير صالح." }
        if (value.length == PIN_LENGTH) {
            val salt = challenge.copyOfRange(0, PIN_SALT_BYTES)
            val passwordChars = value.toCharArray()
            var spec: PBEKeySpec? = null
            val nativeKey = try {
                runCatching {
                    val keySpec = PBEKeySpec(passwordChars, salt, PIN_KDF_ITERATIONS, SECRET_BYTES * 8)
                    spec = keySpec
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(keySpec).encoded
                }.getOrNull()
            } finally {
                spec?.clearPassword()
                passwordChars.fill('\u0000')
            }
            if (nativeKey != null && nativeKey.size == SECRET_BYTES) {
                salt.fill(0)
                return nativeKey
            }
            salt.fill(0)
            return derivePairingKeyFallback(value, challenge)
        }
        return ByteArray(SECRET_BYTES) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** RFC 8018 fallback, exposed internally so its wire vector is tested on every JVM build. */
    internal fun derivePairingKeyFallback(value: String, challenge: ByteArray): ByteArray {
        require(value.length == PIN_LENGTH && value.all { it in '0'..'9' }) {
            "رمز الاقتران غير صالح."
        }
        require(challenge.size == CHALLENGE_BYTES) { "تحدي الاقتران غير صالح." }
        val salt = challenge.copyOfRange(0, PIN_SALT_BYTES)
        val password = value.toByteArray(Charsets.US_ASCII)
        val blockInput = ByteArray(salt.size + 4)
        salt.copyInto(blockInput)
        blockInput[blockInput.lastIndex] = 1
        val u = ByteArray(32)
        val next = ByteArray(32)
        val derived = ByteArray(32)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            mac.update(blockInput)
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, derived, 0, u.size)
            var iteration = 1
            while (iteration < PIN_KDF_ITERATIONS) {
                mac.update(u)
                mac.doFinal(next, 0)
                var index = 0
                while (index < derived.size) {
                    derived[index] = (derived[index].toInt() xor next[index].toInt()).toByte()
                    index++
                }
                System.arraycopy(next, 0, u, 0, u.size)
                next.fill(0)
                iteration++
            }
            return derived.copyOf(SECRET_BYTES)
        } finally {
            password.fill(0)
            salt.fill(0)
            blockInput.fill(0)
            u.fill(0)
            next.fill(0)
            derived.fill(0)
        }
    }

    fun sanitizeFileName(raw: String): String {
        val leaf = raw.replace('\\', '/').substringAfterLast('/')
        var cleaned = buildString(leaf.length) {
            var index = 0
            while (index < leaf.length) {
                val codePoint = leaf.codePointAt(index)
                index += Character.charCount(codePoint)
                val type = Character.getType(codePoint)
                val forbidden = codePoint == ':'.code || codePoint == '*'.code ||
                    codePoint == '?'.code || codePoint == '"'.code ||
                    codePoint == '<'.code || codePoint == '>'.code ||
                    codePoint == '|'.code || Character.isISOControl(codePoint) ||
                    type == Character.FORMAT.toInt()
                if (!forbidden) appendCodePoint(codePoint)
            }
        }.trim { it <= ' ' || it == '.' }

        // Bound the UTF-8 filename without splitting a Unicode code point.
        while (cleaned.isNotEmpty() && cleaned.toByteArray(Charsets.UTF_8).size > 180) {
            val lastCodePoint = cleaned.codePointBefore(cleaned.length)
            cleaned = cleaned.dropLast(Character.charCount(lastCodePoint))
        }
        require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            "اسم الملف غير صالح."
        }
        return cleaned
    }

    fun sanitizeMimeType(raw: String): String {
        val candidate = raw.trim()
        val token = "[A-Za-z0-9!#$&^_.+-]+"
        return if (candidate.length <= 160 && Regex("^$token/$token$").matches(candidate)) {
            candidate
        } else {
            "application/octet-stream"
        }
    }

    private val BLOCKED_EXTENSIONS = setOf("apk", "pdf", "svg")
    private val BLOCKED_MIME_TYPES = setOf("application/pdf", "application/vnd.android.package-archive", "application/x-android-package-archive", "image/svg+xml")
    private fun isValidShareMimeSyntax(raw: String): Boolean {
        val candidate = raw.trim()
        val token = "[A-Za-z0-9!#$&^_.+-]+"
        return candidate.length in 3..160 && Regex("^$token/$token$").matches(candidate)
    }
    fun isSupportedShareMimeType(raw: String): Boolean {
        val candidate = raw.trim().lowercase(Locale.ROOT)
        if (!isValidShareMimeSyntax(candidate) || candidate == "image/svg+xml") return false
        return candidate.substringBefore('/') in setOf("video", "audio", "image")
    }
    /** Ordinary files are opaque bytes; APK and PDF are blocked by product policy. */
    fun isAllowedShareFile(fileName: String, mimeType: String): Boolean {
        if (fileName.isBlank() || !isValidShareMimeSyntax(mimeType)) return false
        val normalizedName = runCatching { sanitizeFileName(fileName) }.getOrNull() ?: return false
        val extension = normalizedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeType.trim().lowercase(Locale.ROOT)
        return extension !in BLOCKED_EXTENSIONS && mime !in BLOCKED_MIME_TYPES
    }

    @Deprecated("Use isSupportedShareMimeType.")
    fun isSupportedMediaMimeType(raw: String): Boolean = isSupportedShareMimeType(raw)

    /**
     * A resume file belongs to the exact content, not just its visible name and size.
     * This prevents two different same-name/same-size files from sharing partial bytes.
     */
    internal fun buildTransferId(fileName: String, size: Long, contentDigest: ByteArray): String {
        require(size in 0..MAX_FILE_BYTES)
        require(contentDigest.size == 32) { "بصمة الملف غير صالحة." }
        val nameBytes = sanitizeFileName(fileName).toByteArray(Charsets.UTF_8)
        val identity = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(nameBytes.size)
                data.write(nameBytes)
                data.writeLong(size)
                data.write(contentDigest)
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity)
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    internal fun completedReceiptFileName(
        markerContent: String,
        expectedSize: Long,
        expectedDigestHex: String
    ): String? = runCatching {
        val fields = markerContent.split(':', limit = 3)
        if (fields.size != 3 || fields[0].toLongOrNull() != expectedSize ||
            fields[1] != expectedDigestHex
        ) return@runCatching null
        sanitizeFileName(fields[2])
    }.getOrNull()

    fun defaultReceiveDirectory(context: android.content.Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        return File(base, "MalekPlayer")
    }

    private fun resolvePrivateLanAddress(host: String): InetAddress {
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
            "أدخل عنوان IPv4 المحلي الظاهر على جهاز الاستقبال."
        }
        val octets = host.split('.')
        require(octets.size == 4 && octets.all { part ->
            part.isNotEmpty() && (part == "0" || part[0] != '0') &&
                part.all { it in '0'..'9' } && (part.toIntOrNull()?.let { it in 0..255 } == true)
        }) { "عنوان IPv4 غير صالح." }
        val address = InetAddress.getByName(host)
        require(address is Inet4Address && isPrivateLanAddress(address)) {
            "عنوان الاستقبال يجب أن يكون عنواناً خاصاً أو محلياً على الشبكة."
        }
        return address
    }

    private fun isPrivateLanAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isMulticastAddress &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress)

    internal fun decodeUtf8Strict(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("النص الوارد ليس UTF-8 صالحاً.", failure)
    }

}
