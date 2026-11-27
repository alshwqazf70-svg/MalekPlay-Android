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
 * gating platform features explicitly by SDK level:
 *   - Android 6–9 (API 23–28): Screen video + optional independent Microphone (`RECORD_AUDIO`),
 *     plus video-only mode (`NONE`). `AudioPlaybackCapture` (`SYSTEM_AUDIO`) is NEVER offered or invoked.
 *   - Android 10–16 (API 29–36): Screen video + optional internal audio (`AudioPlaybackCapture`)
 *     + optional Microphone (`RECORD_AUDIO`) + video-only mode (`NONE`).
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

    enum class MirrorAudioMode(val wireId: String) {
        NONE("none"),
        SYSTEM_AUDIO("system"),
        MICROPHONE("mic");

        companion object {
            fun fromWireId(wireId: String?): MirrorAudioMode =
                entries.firstOrNull { it.wireId == wireId } ?: NONE
        }
    }

    data class CaptureProfile(
        val maxWidth: Int,
        val maxHeight: Int,
        val fps: Int,
        val bitrateBps: Int,
        val iFrameIntervalSec: Int = 1,
        val maxViewers: Int = MAX_MIRROR_VIEWERS
    )

    fun supportsMediaProjection(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.LOLLIPOP

    /**
     * Android 14+ (API 34+) requires registering a MediaProjection.Callback before
     * createVirtualDisplay() and using FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
     */
    fun requiresMediaProjectionCallback(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun requiresTypedMediaProjectionForegroundService(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.Q

    fun mediaProjectionForegroundServiceType(sdkInt: Int = Build.VERSION.SDK_INT): Int =
        mediaProjectionForegroundServiceType(sdkInt = sdkInt, includeMicrophone = false)

    /**
     * Computes the Foreground Service type mask for [UltraShareScreenMirrorService].
     * On API 30+ (Android 11–16), `FOREGROUND_SERVICE_TYPE_MICROPHONE` is OR-ed in ONLY when
     * microphone capture was explicitly requested and `RECORD_AUDIO` is already granted.
     */
    @android.annotation.SuppressLint("InlinedApi")
    fun mediaProjectionForegroundServiceType(
        sdkInt: Int = Build.VERSION.SDK_INT,
        includeMicrophone: Boolean
    ): Int {
        if (sdkInt < Build.VERSION_CODES.Q) return 0
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (includeMicrophone && sdkInt >= Build.VERSION_CODES.R) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return mask
    }

    /** AudioPlaybackCapture API was introduced in Android 10 (API 29). */
    fun supportsSystemAudioCapture(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.Q

    fun supportsAudioPlaybackCapture(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        supportsSystemAudioCapture(sdkInt)

    /** Optional independent Microphone capture is available across API 23–36 when RECORD_AUDIO is granted. */
    fun supportsMicrophoneAudioCapture(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.M

    /**
     * Audio modes safe to present in the UI for [sdkInt]:
     * - API 23–28: `[NONE, MICROPHONE]` (never claims internal system audio).
     * - API 29+:   `[NONE, SYSTEM_AUDIO, MICROPHONE]`.
     */
    fun availableAudioModes(sdkInt: Int = Build.VERSION.SDK_INT): List<MirrorAudioMode> =
        if (supportsSystemAudioCapture(sdkInt)) {
            listOf(MirrorAudioMode.NONE, MirrorAudioMode.SYSTEM_AUDIO, MirrorAudioMode.MICROPHONE)
        } else if (supportsMicrophoneAudioCapture(sdkInt)) {
            listOf(MirrorAudioMode.NONE, MirrorAudioMode.MICROPHONE)
        } else {
            listOf(MirrorAudioMode.NONE)
        }

    /**
     * Resolves the requested [MirrorAudioMode] into a safe effective mode for [sdkInt] and
     * current `RECORD_AUDIO` permission state. Unsupported or unpermitted audio degrades cleanly
     * to [MirrorAudioMode.NONE] so video mirroring always proceeds uninterrupted.
     */
    fun resolveEffectiveAudioMode(
        requestedMode: MirrorAudioMode,
        sdkInt: Int = Build.VERSION.SDK_INT,
        recordAudioGranted: Boolean
    ): MirrorAudioMode = when (requestedMode) {
        MirrorAudioMode.NONE -> MirrorAudioMode.NONE
        MirrorAudioMode.SYSTEM_AUDIO ->
            if (supportsSystemAudioCapture(sdkInt) && recordAudioGranted) {
                MirrorAudioMode.SYSTEM_AUDIO
            } else {
                MirrorAudioMode.NONE
            }
        MirrorAudioMode.MICROPHONE ->
            if (supportsMicrophoneAudioCapture(sdkInt) && recordAudioGranted) {
                MirrorAudioMode.MICROPHONE
            } else {
                MirrorAudioMode.NONE
            }
    }

    fun resolveEffectiveAudioMode(
        context: Context,
        requestedMode: MirrorAudioMode,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): MirrorAudioMode = resolveEffectiveAudioMode(
        requestedMode = requestedMode,
        sdkInt = sdkInt,
        recordAudioGranted = hasRecordAudioRuntimePermission(context)
    )

    /** True only when internal AudioPlaybackCapture is supported on [sdkInt] AND RECORD_AUDIO is granted. */
    fun hasAudioCapturePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!supportsSystemAudioCapture(sdkInt)) return false
        return hasRecordAudioRuntimePermission(context)
    }

    /** True when independent Microphone capture is supported on [sdkInt] AND RECORD_AUDIO is granted. */
    fun hasMicrophoneCapturePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!supportsMicrophoneAudioCapture(sdkInt)) return false
        return hasRecordAudioRuntimePermission(context)
    }

    fun hasRecordAudioRuntimePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

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
     * Scales source screen dimensions down to fit inside [maxLongEdge] x [maxShortEdge]
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
