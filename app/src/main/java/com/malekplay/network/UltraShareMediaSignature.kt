package com.malekplay.network

import java.io.File
import java.io.FileInputStream

/**
 * Checks container signatures, not user-supplied names or MIME declarations.
 * Unknown formats fail closed until a verified signature is registered here.
 */
internal object UltraShareMediaSignature {
    fun isValid(file: File, fileName: String, mimeType: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val family = when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff" -> "image"
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr",
            "aiff", "aif", "mid", "midi" -> "audio"
            "mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "3g2", "mpg", "mpeg",
            "ts", "mts", "m2ts", "wmv", "flv", "ogv" -> "video"
            else -> return false
        }
        val declaredFamily = mimeType.substringBefore('/').lowercase()
        if (declaredFamily !in setOf(family, "application")) return false
        if (declaredFamily == "application" &&
            mimeType.lowercase() !in setOf("application/octet-stream", "application/unknown")) return false
        val prefix = ByteArray(16)
        val count = runCatching { FileInputStream(file).use { it.read(prefix) } }.getOrDefault(-1)
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
