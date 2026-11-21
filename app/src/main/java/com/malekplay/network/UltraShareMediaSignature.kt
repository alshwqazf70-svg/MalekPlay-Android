package com.malekplay.network

import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Checks container signatures, not user-supplied names or MIME declarations.
 * Unknown formats fail closed until a verified signature is registered here.
 */
internal object UltraShareMediaSignature {
    private val SUPPORTED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff",
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "aiff", "aif", "mid", "midi",
        "mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "3g2", "mpg", "mpeg", "ts", "mts", "m2ts",
        "wmv", "flv", "ogv"
    )

    private fun extensionForMime(rawMime: String): String? = when (
        rawMime.substringBefore(';').trim().lowercase(Locale.ROOT)
    ) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        "image/tiff" -> "tiff"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/x-ms-wma" -> "wma"
        "audio/amr" -> "amr"
        "audio/aiff", "audio/x-aiff" -> "aiff"
        "audio/midi", "audio/x-midi" -> "mid"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/3gpp2" -> "3g2"
        "video/mpeg" -> "mpg"
        "video/mp2t" -> "ts"
        "video/x-msvideo" -> "avi"
        "video/x-ms-wmv" -> "wmv"
        "video/x-flv" -> "flv"
        "video/ogg" -> "ogv"
        else -> null
    }

    fun isValid(file: File, fileName: String, mimeType: String): Boolean {
        if (!UltraShareCore.isAllowedShareFile(fileName, mimeType) || !file.isFile || file.length() <= 0L) return false
        val suppliedExtension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val declaredFamily = normalizedMime.substringBefore('/')
        // SVG is shared as an opaque document, not decoded or previewed as an image.
        // Documents, archives and unknown binary payloads are likewise opaque files.
        if (suppliedExtension == "svg" || normalizedMime == "image/svg+xml") return true
        val looksLikeMedia = suppliedExtension in SUPPORTED_EXTENSIONS || declaredFamily in setOf("video", "audio", "image")
        if (!looksLikeMedia) return true
        // Providers may omit media filename extensions; infer only known containers.
        val ext = suppliedExtension.takeIf { it in SUPPORTED_EXTENSIONS } ?: extensionForMime(mimeType) ?: return false
        val family = when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff" -> "image"
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr",
            "aiff", "aif", "mid", "midi" -> "audio"
            "mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "3g2", "mpg", "mpeg",
            "ts", "mts", "m2ts", "wmv", "flv", "ogv" -> "video"
            else -> return false
        }
        if (declaredFamily !in setOf(family, "application")) return false
        if (declaredFamily == "application" &&
            mimeType.lowercase(Locale.ROOT) !in setOf("application/octet-stream", "application/unknown")) return false
        // A zero-byte transfer is not a valid media container, even when its
        // extension and declared MIME type look plausible.
        if (!file.isFile || file.length() == 0L) return false
        val prefix = ByteArray(16)
        val count = runCatching {
            FileInputStream(file).use { input ->
                var total = 0
                while (total < prefix.size) {
                    val read = input.read(prefix, total, prefix.size - total)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                }
                total
            }
        }.getOrDefault(-1)
        if (count < 0) return false
        fun starts(vararg bytes: Int) = count >= bytes.size &&
            bytes.indices.all { (prefix[it].toInt() and 0xff) == bytes[it] }
        fun ascii(offset: Int, value: String) = count >= offset + value.length &&
            value.indices.all { prefix[offset + it].toInt().toChar() == value[it] }
        return when (ext) {
            "jpg", "jpeg" -> starts(0xff, 0xd8, 0xff)
            "png" -> starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            "gif" -> ascii(0, "GIF87a") || ascii(0, "GIF89a")
            "webp" -> ascii(0, "RIFF") && ascii(8, "WEBP")
            "bmp" -> starts(0x42, 0x4d)
            "tif", "tiff" -> starts(0x49, 0x49, 0x2a, 0x00) || starts(0x4d, 0x4d, 0x00, 0x2a)
            "mp4", "m4v", "mov", "3gp", "3g2", "m4a", "heic", "heif", "avif" ->
                count >= 12 && ascii(4, "ftyp")
            "mkv", "webm" -> starts(0x1a, 0x45, 0xdf, 0xa3)
            "avi" -> ascii(0, "RIFF") && ascii(8, "AVI ")
            "ts", "mts", "m2ts" -> starts(0x47) || (count >= 5 && (prefix[4].toInt() and 0xff) == 0x47)
            "mpg", "mpeg" -> starts(0x00, 0x00, 0x01, 0xba) || starts(0x00, 0x00, 0x01, 0xb3)
            "wmv", "wma" -> starts(0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11)
            "flv" -> ascii(0, "FLV")
            "ogv" -> ascii(0, "OggS")
            "aiff", "aif" -> ascii(0, "FORM") && (ascii(8, "AIFF") || ascii(8, "AIFC"))
            "mid", "midi" -> ascii(0, "MThd")
            "mp3" -> ascii(0, "ID3") || (count >= 2 &&
                (prefix[0].toInt() and 0xff) == 0xff && (prefix[1].toInt() and 0xe0) == 0xe0)
            "wav" -> ascii(0, "RIFF") && ascii(8, "WAVE")
            "flac" -> ascii(0, "fLaC")
            "ogg", "opus" -> ascii(0, "OggS")
            "aac" -> starts(0xff) && count >= 2 && (prefix[1].toInt() and 0xf6) == 0xf0
            "amr" -> ascii(0, "#!AMR")
            else -> false
        }
    }
}
