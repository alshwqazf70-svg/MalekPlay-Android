package com.malekplay.player

/** Conservative limits for the real-time spatial GPU denoiser. */
internal object VideoDenoisePolicy {
    const val MIN_OPENGL_ES_VERSION = 0x20000
    const val MAX_LONG_SIDE = 1920
    const val MAX_SHORT_SIDE = 1080
    const val MAX_FRAME_RATE = 60f

    fun isEligible(width: Int, height: Int, frameRate: Float, glEsVersion: Int): Boolean {
        if (width <= 0 || height <= 0 || glEsVersion < MIN_OPENGL_ES_VERSION) return false
        if (maxOf(width, height) > MAX_LONG_SIDE || minOf(width, height) > MAX_SHORT_SIDE) return false
        if (!frameRate.isFinite() && frameRate != -1f) return false
        if (frameRate > MAX_FRAME_RATE) return false
        return true
    }
}
