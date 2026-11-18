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
    private val destinationPublicationLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Serialize final-name selection and publication across concurrent V2 receiver workers. */
    internal fun <T> withDestinationPublicationLock(directory: File, action: () -> T): T {
        val key = directory.absoluteFile.path
        val lock = destinationPublicationLocks[key] ?: Any().let { candidate ->
            destinationPublicationLocks.putIfAbsent(key, candidate) ?: candidate
        }
        return synchronized(lock) { action() }
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
            // Avoid provider-specific SecretKeyFactory registration: some Android/OEM
            // providers omit PBKDF2WithHmacSHA256 even when HmacSHA256 is available.
            // This RFC 8018 implementation preserves the existing desktop wire vector.
            val password = value.toByteArray(Charsets.US_ASCII)
            val salt = challenge.copyOfRange(0, PIN_SALT_BYTES)
            val blockInput = ByteArray(salt.size + 4)
            salt.copyInto(blockInput)
            blockInput[blockInput.lastIndex] = 1 // INT_32_BE(1)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            var u = mac.doFinal(blockInput)
            val derived = u.copyOf()
            try {
                repeat(PIN_KDF_ITERATIONS - 1) {
                    val next = mac.doFinal(u)
                    for (index in derived.indices) {
                        derived[index] = (derived[index].toInt() xor next[index].toInt()).toByte()
                    }
                    u.fill(0)
                    u = next
                }
                return derived.copyOf(SECRET_BYTES)
            } finally {
                password.fill(0)
                salt.fill(0)
                blockInput.fill(0)
                u.fill(0)
                derived.fill(0)
            }
        }
        return ByteArray(SECRET_BYTES) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
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
        }.trim().trim('.')

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

    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "3g2", "mpg", "mpeg",
        "ts", "mts", "m2ts", "wmv", "flv", "ogv",
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr",
        "aiff", "aif", "mid", "midi",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff"
    )
    private val BLOCKED_EXTENSIONS = setOf(
        "apk", "aab", "dex", "jar", "exe", "msi", "bat", "cmd", "sh",
        "pdf", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "svg"
    )

    /** Accept media MIME families only; SVG is excluded because it can contain active content. */
    fun isSupportedShareMimeType(raw: String): Boolean {
        val candidate = raw.trim().lowercase(Locale.ROOT)
        val token = "[A-Za-z0-9!#$&^_.+-]+"
        if (candidate.length !in 3..160 || !Regex("^$token/$token$").matches(candidate)) return false
        if (candidate == "image/svg+xml") return false
        return candidate.substringBefore('/') in setOf("video", "audio", "image")
    }

    /**
     * Enforce media-only sharing at the protocol boundary, not just in the picker.
     * Some document providers label ordinary media as application/octet-stream;
     * allow that fallback only when the filename has a known media extension.
     */
    fun isAllowedShareFile(fileName: String, mimeType: String): Boolean {
        if (fileName.isBlank()) return false
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in BLOCKED_EXTENSIONS) return false
        if (isSupportedShareMimeType(mimeType)) return true
        val mime = mimeType.trim().lowercase(Locale.ROOT)
        return mime in setOf("application/octet-stream", "application/unknown") &&
            extension in MEDIA_EXTENSIONS
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
        return File(base, "MalekPlayer/Received")
    }

    private fun resolvePrivateLanAddress(host: String): InetAddress {
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
            "أدخل عنوان IPv4 المحلي الظاهر على جهاز الاستقبال."
        }
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
