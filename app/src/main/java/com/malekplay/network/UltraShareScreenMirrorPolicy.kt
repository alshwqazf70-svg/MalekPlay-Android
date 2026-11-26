package com.malekplay.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android API 23..36 compatibility policy for Group Screen Mirroring.
 *
 * Keeps minSdk = 23 (Android 6.0) and targetSdk = 36 (Android 16) intact while
 * gating platform features explicitly by SDK level.
 */
object UltraShareScreenMirrorPolicy {
    const val DEFAULT_STREAM_PORT = 38_746
    const val MAX_MIRROR_VIEWERS = 8
    const val DEFAULT_MAX_WIDTH = 1280
    const val DEFAULT_MAX_HEIGHT = 720
    const val DEFAULT_FPS = 30
    const val DEFAULT_BITRATE_BPS = 2_500_000
    const val LOW_MEMORY_MAX_WIDTH = 960
    const val LOW_MEMORY_MAX_HEIGHT = 540
    const val LOW_MEMORY_FPS = 24
    const val LOW_MEMORY_BITRATE_BPS = 1_500_000

    data class CaptureProfile(
        val maxWidth: Int,
        val maxHeight: Int,
        val fps: Int,
        val bitrateBps: Int,
        val iFrameIntervalSec: Int = 1,
        val maxViewers: Int = MAX_MIRROR_VIEWERS
    )

    fun supportsMediaProjection(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.LOLLIPOP

    /**
     * Android 14+ (API 34+) requires registering a MediaProjection.Callback before
     * createVirtualDisplay() and using FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
     */
    fun requiresMediaProjectionCallback(sdkInt: Int = Build.VERSION_CODES.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun requiresTypedMediaProjectionForegroundService(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.Q

    fun mediaProjectionForegroundServiceType(sdkInt: Int = Build.VERSION_CODES.SDK_INT): Int =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

    /** AudioPlaybackCapture API was introduced in Android 10 (API 29). */
    fun supportsSystemAudioCapture(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.Q

    fun supportsAudioPlaybackCapture(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        supportsSystemAudioCapture(sdkInt)

    fun hasAudioCapturePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!supportsSystemAudioCapture(sdkInt)) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun selectCaptureProfile(memoryClassMb: Int, activeViewers: Int = 1): CaptureProfile {
        val safeMem = memoryClassMb.coerceAtLeast(64)
        val safeViewers = activeViewers.coerceAtLeast(1)
        return when {
            safeMem < 192 -> CaptureProfile(
                maxWidth = LOW_MEMORY_MAX_WIDTH,
                maxHeight = LOW_MEMORY_MAX_HEIGHT,
                fps = LOW_MEMORY_FPS,
                bitrateBps = LOW_MEMORY_BITRATE_BPS,
                maxViewers = 4
            )
            safeViewers >= 4 -> CaptureProfile(
                maxWidth = DEFAULT_MAX_WIDTH,
                maxHeight = DEFAULT_MAX_HEIGHT,
                fps = 24,
                bitrateBps = 2_000_000,
                maxViewers = MAX_MIRROR_VIEWERS
            )
            else -> CaptureProfile(
                maxWidth = DEFAULT_MAX_WIDTH,
                maxHeight = DEFAULT_MAX_HEIGHT,
                fps = DEFAULT_FPS,
                bitrateBps = DEFAULT_BITRATE_BPS,
                maxViewers = MAX_MIRROR_VIEWERS
            )
        }
    }

    /**
     * Scales source screen dimensions down to fit inside [maxWidth] x [maxHeight]
     * while preserving aspect ratio and rounding to encoder alignment (typically 16).
     */
    fun computeAlignedVideoSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxLongEdge: Int,
        maxShortEdge: Int,
        widthAlignment: Int = 16,
        heightAlignment: Int = 16
    ): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source display dimensions must be positive." }
        val alignW = widthAlignment.coerceAtLeast(2)
        val alignH = heightAlignment.coerceAtLeast(2)
        val isLandscape = sourceWidth >= sourceHeight
        val targetMaxW = if (isLandscape) maxLongEdge else maxShortEdge
        val targetMaxH = if (isLandscape) maxShortEdge else maxLongEdge
        val scale = minOf(
            1.0,
            targetMaxW.toDouble() / sourceWidth.toDouble(),
            targetMaxH.toDouble() / sourceHeight.toDouble()
        )
        val rawW = (sourceWidth * scale).toInt().coerceAtLeast(alignW * 8)
        val rawH = (sourceHeight * scale).toInt().coerceAtLeast(alignH * 8)
        val alignedW = ((rawW / alignW) * alignW).coerceAtLeast(alignW * 8)
        val alignedH = ((rawH / alignH) * alignH).coerceAtLeast(alignH * 8)
        return alignedW to alignedH
    }
}
