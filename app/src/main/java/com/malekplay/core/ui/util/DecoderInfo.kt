package com.malekplay.core.ui.util

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.RequiresApi

object DecoderInfo {

    /**
     * True when the platform publishes a hardware-accelerated decoder for this
     * MIME type. This is the same signal used to drive the "HW" badge: it answers
     * "can this device decode it in silicon", not "is it decoding right now".
     */
    fun isHardwareAccelerated(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                    && isHardware(info)
            }
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isHardware(info: MediaCodecInfo): Boolean = info.isHardwareAccelerated

    /** Shortens "video/avc" to "AVC" for the info panel. */
    fun shortCodec(mimeType: String?): String {
        if (mimeType.isNullOrBlank()) return "—"
        return when {
            mimeType.contains("avc", true) || mimeType.contains("h264", true) -> "H.264"
            mimeType.contains("hevc", true) || mimeType.contains("h265", true) -> "HEVC"
            mimeType.contains("av01", true) -> "AV1"
            mimeType.contains("vp9", true) -> "VP9"
            mimeType.contains("vp8", true) -> "VP8"
            mimeType.contains("mp4a", true) -> "AAC"
            mimeType.contains("opus", true) -> "Opus"
            mimeType.contains("vorbis", true) -> "Vorbis"
            mimeType.contains("flac", true) -> "FLAC"
            mimeType.contains("mp3", true) -> "MP3"
            mimeType.contains("ac3", true) -> "AC-3"
            mimeType.contains("eac3", true) -> "E-AC-3"
            else -> mimeType.substringAfterLast('/').uppercase()
        }
    }
}
