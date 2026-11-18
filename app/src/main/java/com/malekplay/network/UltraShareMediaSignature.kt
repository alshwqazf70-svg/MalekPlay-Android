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
            "mp4", "m4v", "mov", "3gp", "3g2", "heic", "heif", "avif" ->
                count >= 12 && ascii(4, "ftyp")
            "mkv", "webm" -> starts(0x1a, 0x45, 0xdf, 0xa3)
            "avi" -> ascii(0, "RIFF") && ascii(8, "AVI ")
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
