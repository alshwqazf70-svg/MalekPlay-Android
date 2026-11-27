package com.malekplay.network

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure JVM-testable descriptor of an H.264 encoder's capabilities.
 */
internal data class H264CodecDescriptor(
    val name: String,
    val isSoftwareOnly: Boolean,
    val supportsSurfaceColorFormat: Boolean,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val maxSupportedFps: Int,
    val maxSupportedBitrateBps: Int,
    val supportsSizeAndRate: (width: Int, height: Int, fps: Int) -> Boolean
)

internal data class H264EncoderCandidate(
    val codecName: String?,
    val isSoftwareFallback: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int
)

/**
 * Splits an Annex-B H.264 codec-specific-data buffer into `(csd0, csd1)` = `(SPS, PPS)` when
 * both NAL units are concatenated in a single `VIDEO_CONFIG` frame. Many Android 6–10 hardware
 * AVC decoders require `csd-0` and `csd-1` as separate ByteBuffers to output their first frame.
 */
internal fun splitAvcCodecConfig(csd: ByteArray): Pair<ByteArray, ByteArray?> {
    if (csd.size < 8) return csd to null
    val nalRanges = mutableListOf<IntRange>()
    var i = 0
    var currentStart = -1
    while (i < csd.size - 3) {
        val is4ByteStart = csd[i] == 0.toByte() && csd[i + 1] == 0.toByte() &&
            csd[i + 2] == 0.toByte() && csd[i + 3] == 1.toByte()
        val is3ByteStart = !is4ByteStart &&
            csd[i] == 0.toByte() && csd[i + 1] == 0.toByte() && csd[i + 2] == 1.toByte()
        if (is4ByteStart || is3ByteStart) {
            if (currentStart >= 0 && i > currentStart) {
                nalRanges += currentStart until i
            }
            currentStart = i
            i += if (is4ByteStart) 4 else 3
        } else {
            i++
        }
    }
    if (currentStart >= 0 && currentStart < csd.size) {
        nalRanges += currentStart until csd.size
    }
    if (nalRanges.size < 2) return csd to null

    var spsBytes: ByteArray? = null
    var ppsBytes: ByteArray? = null
    for (range in nalRanges) {
        val slice = csd.copyOfRange(range.first, range.last + 1)
        val headerIdx = when {
            slice.size > 4 && slice[0] == 0.toByte() && slice[1] == 0.toByte() &&
                slice[2] == 0.toByte() && slice[3] == 1.toByte() -> 4
            slice.size > 3 && slice[0] == 0.toByte() && slice[1] == 0.toByte() &&
                slice[2] == 1.toByte() -> 3
            else -> -1
        }
        if (headerIdx < 0 || headerIdx >= slice.size) continue
        when (slice[headerIdx].toInt() and 0x1F) {
            7 -> if (spsBytes == null) spsBytes = slice
            8 -> if (ppsBytes == null) ppsBytes = slice
        }
    }
    return if (spsBytes != null && ppsBytes != null) {
        spsBytes to ppsBytes
    } else {
        csd to null
    }
}

/**
 * Duplicates 16-bit little-endian mono PCM samples into interleaved stereo 16-bit PCM
 * so single-microphone Android 6–16 devices feed the standard stereo AAC-LC stream cleanly.
 * Returns the number of valid bytes written into [stereoOut] (always a multiple of 4).
 */
internal fun upmixMono16BitToStereo(monoPcm: ByteArray, validBytes: Int, stereoOut: ByteArray): Int {
    val alignedMonoBytes = (validBytes.coerceIn(0, monoPcm.size) / 2) * 2
    val maxMonoBytes = ((stereoOut.size / 4) * 2).coerceAtMost(alignedMonoBytes)
    var src = 0
    var dst = 0
    while (src < maxMonoBytes) {
        val lo = monoPcm[src]
        val hi = monoPcm[src + 1]
        stereoOut[dst] = lo
        stereoOut[dst + 1] = hi
        stereoOut[dst + 2] = lo
        stereoOut[dst + 3] = hi
        src += 2
        dst += 4
    }
    return dst
}

/** Convenience overload returning a newly allocated stereo 16-bit PCM array. */
internal fun upmixMono16BitToStereo(
    monoPcm: ByteArray,
    validBytes: Int = monoPcm.size
): ByteArray {
    val alignedMonoBytes = (validBytes.coerceIn(0, monoPcm.size) / 2) * 2
    val out = ByteArray(alignedMonoBytes * 2)
    upmixMono16BitToStereo(monoPcm, alignedMonoBytes, out)
    return out
}

/**
 * Extracts `(sampleRateHz, channelCount)` from a 2-byte AAC-LC AudioSpecificConfig (`csd-0`),
 * falling back to `(48_000, 2)` when non-standard or shorter than 2 bytes.
 */
internal fun parseAacAudioSpecificConfig(asc: ByteArray): Pair<Int, Int> {
    if (asc.size < 2) return 48_000 to 2
    val b0 = asc[0].toInt() and 0xFF
    val b1 = asc[1].toInt() and 0xFF
    val freqIdx = ((b0 and 0x07) shl 1) or ((b1 ushr 7) and 0x01)
    val channelCfg = (b1 ushr 3) and 0x0F
    val sampleRates = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
        24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350
    )
    val rate = if (freqIdx in sampleRates.indices) sampleRates[freqIdx] else 48_000
    val channels = if (channelCfg in 1..2) channelCfg else 2
    return rate to channels
}

/**
 * Orders viable H.264 encoder candidates across manufacturer hardware encoders and
 * software fallbacks without stopping on the first incompatible hardware encoder.
 */
internal fun selectH264EncoderCandidates(
    descriptors: List<H264CodecDescriptor>,
    sourceWidth: Int,
    sourceHeight: Int,
    baseProfile: UltraShareScreenMirrorPolicy.CaptureProfile
): List<H264EncoderCandidate> {
    val hardwarePrimary = mutableListOf<H264EncoderCandidate>()
    val hardwareReduced = mutableListOf<H264EncoderCandidate>()
    val softwarePrimary = mutableListOf<H264EncoderCandidate>()
    val softwareReduced = mutableListOf<H264EncoderCandidate>()

    for (desc in descriptors) {
        if (!desc.supportsSurfaceColorFormat) continue
        val alignW = desc.widthAlignment.coerceAtLeast(2)
        val alignH = desc.heightAlignment.coerceAtLeast(2)

        val primarySize = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxLongEdge = baseProfile.maxWidth,
            maxShortEdge = baseProfile.maxHeight,
            widthAlignment = alignW,
            heightAlignment = alignH
        )
        val primaryFps = minOf(baseProfile.fps, desc.maxSupportedFps.coerceAtLeast(15))
        val primaryBitrate = minOf(baseProfile.bitrateBps, desc.maxSupportedBitrateBps.coerceAtLeast(500_000))

        if (desc.supportsSizeAndRate(primarySize.first, primarySize.second, primaryFps)) {
            val candidate = H264EncoderCandidate(
                codecName = desc.name,
                isSoftwareFallback = desc.isSoftwareOnly,
                width = primarySize.first,
                height = primarySize.second,
                fps = primaryFps,
                bitrateBps = primaryBitrate
            )
            if (desc.isSoftwareOnly) softwarePrimary += candidate else hardwarePrimary += candidate
            continue
        }

        // Try reduced fallback profile if the encoder cannot sustain the primary resolution/fps.
        val reducedSize = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxLongEdge = UltraShareScreenMirrorPolicy.LOW_MEMORY_MAX_WIDTH,
            maxShortEdge = UltraShareScreenMirrorPolicy.LOW_MEMORY_MAX_HEIGHT,
            widthAlignment = alignW,
            heightAlignment = alignH
        )
        val reducedFps = minOf(UltraShareScreenMirrorPolicy.LOW_MEMORY_FPS, desc.maxSupportedFps.coerceAtLeast(15))
        val reducedBitrate = minOf(
            UltraShareScreenMirrorPolicy.LOW_MEMORY_BITRATE_BPS,
            desc.maxSupportedBitrateBps.coerceAtLeast(400_000)
        )
        if (desc.supportsSizeAndRate(reducedSize.first, reducedSize.second, reducedFps)) {
            val candidate = H264EncoderCandidate(
                codecName = desc.name,
                isSoftwareFallback = desc.isSoftwareOnly,
                width = reducedSize.first,
                height = reducedSize.second,
                fps = reducedFps,
                bitrateBps = reducedBitrate
            )
            if (desc.isSoftwareOnly) softwareReduced += candidate else hardwareReduced += candidate
        }
    }

    val ordered = hardwarePrimary + hardwareReduced + softwarePrimary + softwareReduced
    if (ordered.isNotEmpty()) return ordered

    val defaultSize = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        maxLongEdge = baseProfile.maxWidth,
        maxShortEdge = baseProfile.maxHeight,
        widthAlignment = 16,
        heightAlignment = 16
    )
    return listOf(
        H264EncoderCandidate(
            codecName = null,
            isSoftwareFallback = false,
            width = defaultSize.first,
            height = defaultSize.second,
            fps = baseProfile.fps,
            bitrateBps = baseProfile.bitrateBps
        )
    )
}

internal fun <T> configureFirstWorkingH264Encoder(
    candidates: List<H264EncoderCandidate>,
    attemptConfigure: (H264EncoderCandidate) -> T
): Pair<H264EncoderCandidate, T> {
    var lastError: Throwable? = null
    for (candidate in candidates) {
        try {
            return candidate to attemptConfigure(candidate)
        } catch (err: Throwable) {
            lastError = err
        }
    }
    throw IllegalStateException(
        lastError?.localizedMessage ?: "لم يتوفر أي مشفر H.264 صالح على هذا الجهاز.",
        lastError
    )
}

/**
 * Tracks video-only media sequence monotonicity so interleaved audio or config packets
 * never trigger false packet-loss resets on the video decoder.
 */
internal class VideoStreamSequenceTracker {
    var awaitingIdr: Boolean = true
    var lastVideoMediaSeq: Long = -1L
        private set

    data class Decision(
        val shouldFeedToDecoder: Boolean,
        val shouldRequestKeyframe: Boolean,
        val droppedGapDetected: Boolean
    )

    fun reset(awaitIdr: Boolean = true) {
        awaitingIdr = awaitIdr
        lastVideoMediaSeq = -1L
    }

    fun onVideoFrame(mediaSeq: Long, isIdr: Boolean): Decision {
        val gapDetected = lastVideoMediaSeq >= 0L && mediaSeq != lastVideoMediaSeq + 1L
        lastVideoMediaSeq = mediaSeq
        if (gapDetected && !isIdr) {
            awaitingIdr = true
            return Decision(
                shouldFeedToDecoder = false,
                shouldRequestKeyframe = true,
                droppedGapDetected = true
            )
        }
        if (awaitingIdr && !isIdr) {
            return Decision(
                shouldFeedToDecoder = false,
                shouldRequestKeyframe = false,
                droppedGapDetected = false
            )
        }
        if (isIdr) {
            awaitingIdr = false
        }
        return Decision(
            shouldFeedToDecoder = true,
            shouldRequestKeyframe = false,
            droppedGapDetected = gapDetected
        )
    }

    fun onInputFrameDropped(): Boolean {
        val wasAwaiting = awaitingIdr
        awaitingIdr = true
        return !wasAwaiting
    }
}

internal interface VideoCodecBufferAdapter {
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int)
    fun drainOutputs(renderToSurface: Boolean)
}

internal enum class VideoInputFeedResult {
    FED,
    DROPPED_NO_INPUT_BUFFER,
    REJECTED_OVERSIZED_OR_EMPTY_FRAME
}

/**
 * Feeds complete encoded video frames into [VideoCodecBufferAdapter] without truncation
 * or unbounded buffering; drops whole frames and triggers IDR recovery on pressure.
 */
internal object VideoDecoderBufferFeeder {
    fun feedFrame(
        adapter: VideoCodecBufferAdapter,
        bytes: ByteArray,
        ptsUs: Long,
        isIdr: Boolean,
        renderToSurface: Boolean,
        sequenceTracker: VideoStreamSequenceTracker,
        onRequestKeyframe: () -> Unit,
        onFrameDropped: () -> Unit = {}
    ): VideoInputFeedResult {
        // Drain pending decoder outputs first to free buffer slots and prevent lag buildup.
        adapter.drainOutputs(renderToSurface)

        if (bytes.isEmpty()) {
            sequenceTracker.awaitingIdr = true
            onFrameDropped()
            onRequestKeyframe()
            return VideoInputFeedResult.REJECTED_OVERSIZED_OR_EMPTY_FRAME
        }

        val inIdx = adapter.dequeueInputBuffer(15_000L)
        if (inIdx < 0) {
            sequenceTracker.awaitingIdr = true
            onFrameDropped()
            onRequestKeyframe()
            adapter.drainOutputs(renderToSurface)
            return VideoInputFeedResult.DROPPED_NO_INPUT_BUFFER
        }

        val buf = adapter.getInputBuffer(inIdx)
        if (buf == null) {
            // Return the dequeued slot empty if no ByteBuffer was mapped so the codec never leaks a buffer.
            runCatching { adapter.queueInputBuffer(inIdx, 0, 0, ptsUs, 0) }
            sequenceTracker.awaitingIdr = true
            onFrameDropped()
            onRequestKeyframe()
            return VideoInputFeedResult.DROPPED_NO_INPUT_BUFFER
        }

        buf.clear()
        if (bytes.size > buf.remaining()) {
            // Never truncate an encoded H.264 NALU; return the slot empty, reject the whole frame, and recover at IDR.
            adapter.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
            sequenceTracker.awaitingIdr = true
            onFrameDropped()
            onRequestKeyframe()
            return VideoInputFeedResult.REJECTED_OVERSIZED_OR_EMPTY_FRAME
        }

        buf.put(bytes, 0, bytes.size)
        val flags = if (isIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        adapter.queueInputBuffer(inIdx, 0, bytes.size, ptsUs, flags)
        adapter.drainOutputs(renderToSurface)
        return VideoInputFeedResult.FED
    }
}

/**
 * Real Android MediaProjection + VirtualDisplay + Hardware/Platform MediaCodec H.264
 * screen capturer with SDK-safe audio modes:
 * - [UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE]: Video only (API 23–36).
 * - [UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE]: Video + optional independent mic (API 23–36).
 * - [UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO]: Video + optional internal AudioPlaybackCapture (API 29–36 only).
 *
 * Audio failure at startup or mid-stream falls back cleanly to video-only without ever stopping video.
 */
internal class AndroidScreenMirrorCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val hub: ScreenMirrorBroadcasterHub,
    private val requestedAudioMode: UltraShareScreenMirrorPolicy.MirrorAudioMode,
    private val onConfigChanged: (UltraShareScreenMirrorProtocol.StreamConfig) -> Unit,
    private val onProjectionStopped: (String?) -> Unit,
    private val onFirstVideoFrameEncoded: () -> Unit = {},
    private val onAudioStatusNote: (activeMode: UltraShareScreenMirrorPolicy.MirrorAudioMode, note: String?) -> Unit = { _, _ -> }
) {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val firstVideoFrameEmitted = AtomicBoolean(false)
    private val mediaThread = HandlerThread("MalekMirrorCapture").apply { start() }
    private val mediaHandler = Handler(mediaThread.looper)

    @Volatile private var videoEncoder: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var videoDrainThread: Thread? = null

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioEncoder: MediaCodec? = null
    @Volatile private var audioPumpThread: Thread? = null

    @Volatile private var activeWidth = 0
    @Volatile private var activeHeight = 0
    @Volatile private var lastSourceWidth = 0
    @Volatile private var lastSourceHeight = 0
    @Volatile private var activeRotation = 0
    @Volatile private var activeFps = UltraShareScreenMirrorPolicy.DEFAULT_FPS
    @Volatile private var activeBitrate = UltraShareScreenMirrorPolicy.DEFAULT_BITRATE_BPS
    @Volatile private var activeAudioRunning = false
    @Volatile private var activeAudioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
    @Volatile private var currentConfig: UltraShareScreenMirrorProtocol.StreamConfig? = null

    private val videoSessionGeneration = AtomicLong(0L)
    private val encoderRestartLock = Any()

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopped.get()) {
                UltraShareDiagnostics.event("SCREEN_MIRROR", "MEDIA_PROJECTION_ON_STOP")
                stop()
                onProjectionStopped("تم إيقاف مشاركة الشاشة من النظام أو شريط الإشعارات.")
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY || stopped.get()) return
            checkScreenLockAndOrientationChange()
        }
    }

    fun start(): UltraShareScreenMirrorProtocol.StreamConfig {
        check(started.compareAndSet(false, true)) { "Capturer already started." }
        // Register MediaProjection.Callback BEFORE createVirtualDisplay() (mandatory on Android 14+).
        mediaProjection.registerCallback(projectionCallback, mediaHandler)
        displayManager.registerDisplayListener(displayListener, mediaHandler)

        val config = try {
            startVideoPipeline()
        } catch (err: Throwable) {
            stop()
            throw err
        }

        val recordGranted = UltraShareScreenMirrorPolicy.hasRecordAudioRuntimePermission(context)
        val effectiveMode = UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
            requestedMode = requestedAudioMode,
            sdkInt = Build.VERSION.SDK_INT,
            recordAudioGranted = recordGranted
        )
        val audioOk = when (effectiveMode) {
            UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE -> {
                if (requestedAudioMode != UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE) {
                    val note = when {
                        requestedAudioMode == UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO &&
                            !UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(Build.VERSION.SDK_INT) ->
                            "الصوت الداخلي غير مدعوم على هذا الإصدار من Android؛ يستمر البث بالفيديو فقط."
                        !recordGranted ->
                            "لم يُمنح إذن الصوت؛ يستمر بث الشاشة بالفيديو فقط دون انقطاع."
                        else -> null
                    }
                    onAudioStatusNote(UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE, note)
                } else {
                    onAudioStatusNote(UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE, null)
                }
                false
            }
            UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO -> {
                val ok = startSystemAudioPipelineIfSupported()
                if (ok) {
                    activeAudioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO
                    onAudioStatusNote(UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO, null)
                } else {
                    activeAudioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
                    onAudioStatusNote(
                        UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                        "تعذر تشغيل الصوت الداخلي (أو منعه التطبيق المصدر)؛ يستمر بث الفيديو فقط."
                    )
                }
                ok
            }
            UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE -> {
                val ok = startMicrophoneAudioPipelineIfSupported()
                if (ok) {
                    activeAudioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
                    onAudioStatusNote(UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE, null)
                } else {
                    activeAudioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
                    onAudioStatusNote(
                        UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                        "تعذر فتح الميكروفون؛ يستمر بث الشاشة بالفيديو فقط."
                    )
                }
                ok
            }
        }
        activeAudioRunning = audioOk

        val finalConfig = config.copy(audioEnabled = audioOk)
        currentConfig = finalConfig
        onConfigChanged(finalConfig)

        // Schedule proactive sync-frame pulses right after capture starts so static screens
        // emit an IDR promptly even if a viewer connects within the first second.
        scheduleInitialKeyframePulses()
        return finalConfig
    }

    private fun scheduleInitialKeyframePulses() {
        for (delayMs in longArrayOf(120L, 450L, 1_100L)) {
            mediaHandler.postDelayed({
                if (!stopped.get()) requestKeyframe()
            }, delayMs)
        }
    }

    fun requestKeyframe() {
        val codec = videoEncoder ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }
    }

    fun currentStreamConfig(): UltraShareScreenMirrorProtocol.StreamConfig? = currentConfig
    fun currentAudioMode(): UltraShareScreenMirrorPolicy.MirrorAudioMode = activeAudioMode

    private fun readDisplayMetrics(): Triple<Int, Int, Int> {
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val rotationDegrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        val w = metrics.widthPixels.coerceAtLeast(320)
        val h = metrics.heightPixels.coerceAtLeast(320)
        val dpi = metrics.densityDpi.coerceAtLeast(120)
        lastSourceWidth = w
        lastSourceHeight = h
        return Triple(w, h, dpi).also { activeRotation = rotationDegrees }
    }

    private fun collectH264EncoderCandidates(
        sourceW: Int,
        sourceH: Int
    ): List<H264EncoderCandidate> {
        val memMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.memoryClass ?: 256
        val baseProfile = UltraShareScreenMirrorPolicy.selectCaptureProfile(
            memoryClassMb = memMb,
            activeViewers = hub.activeViewerCount().coerceAtLeast(1)
        )
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val descriptors = mutableListOf<H264CodecDescriptor>()

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
                ?: continue
            val videoCaps = caps.videoCapabilities ?: continue
            val isSoftwareOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                info.name.startsWith("OMX.google.", ignoreCase = true) ||
                    info.name.startsWith("c2.android.", ignoreCase = true)
            }
            val colorFormats = caps.colorFormats
            val supportsSurface = colorFormats == null || colorFormats.isEmpty() ||
                colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            val fpsUpper = videoCaps.supportedFrameRates.upper.toInt().coerceAtLeast(15)
            val bitrateUpper = videoCaps.bitrateRange.upper.coerceAtLeast(500_000)
            descriptors += H264CodecDescriptor(
                name = info.name,
                isSoftwareOnly = isSoftwareOnly,
                supportsSurfaceColorFormat = supportsSurface,
                widthAlignment = videoCaps.widthAlignment.coerceAtLeast(2),
                heightAlignment = videoCaps.heightAlignment.coerceAtLeast(2),
                maxSupportedFps = fpsUpper,
                maxSupportedBitrateBps = bitrateUpper,
                supportsSizeAndRate = { w, h, fps ->
                    runCatching {
                        videoCaps.areSizeAndRateSupported(w, h, fps.toDouble())
                    }.getOrElse {
                        videoCaps.isSizeSupported(w, h) &&
                            videoCaps.supportedFrameRates.contains(fps)
                    }
                }
            )
        }

        return selectH264EncoderCandidates(
            descriptors = descriptors,
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            baseProfile = baseProfile
        )
    }

    private fun buildAvcFormat(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        includeBitrateMode: Boolean
    ): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // Repeat the last surface buffer every 100ms when the host screen is motionless so
        // VirtualDisplay -> InputSurface immediately produces initial and on-demand IDR frames.
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        if (includeBitrateMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
        }
    }

    private fun startVideoPipeline(): UltraShareScreenMirrorProtocol.StreamConfig {
        synchronized(encoderRestartLock) {
            val (rawW, rawH, dpi) = readDisplayMetrics()
            val candidates = collectH264EncoderCandidates(rawW, rawH)
            val (chosen, configured) = configureFirstWorkingH264Encoder(candidates) { candidate ->
                var codec: MediaCodec? = null
                var surface: Surface? = null
                try {
                    codec = if (candidate.codecName != null) {
                        MediaCodec.createByCodecName(candidate.codecName)
                    } else {
                        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    }
                    try {
                        codec.configure(
                            buildAvcFormat(
                                width = candidate.width,
                                height = candidate.height,
                                fps = candidate.fps,
                                bitrate = candidate.bitrateBps,
                                includeBitrateMode = true
                            ),
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE
                        )
                    } catch (_: Exception) {
                        // Some legacy OEM encoders fail when BITRATE_MODE_VBR is explicitly set.
                        codec.reset()
                        codec.configure(
                            buildAvcFormat(
                                width = candidate.width,
                                height = candidate.height,
                                fps = candidate.fps,
                                bitrate = candidate.bitrateBps,
                                includeBitrateMode = false
                            ),
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE
                        )
                    }
                    surface = codec.createInputSurface()
                    codec.start()
                    codec to surface
                } catch (err: Throwable) {
                    runCatching { surface?.release() }
                    runCatching { codec?.release() }
                    UltraShareDiagnostics.event(
                        "SCREEN_MIRROR",
                        "ENCODER_CANDIDATE_FAILED",
                        mapOf(
                            "codec" to (candidate.codecName ?: "default"),
                            "software" to candidate.isSoftwareFallback,
                            "error" to (err.localizedMessage ?: err.javaClass.simpleName)
                        )
                    )
                    throw err
                }
            }

            val codec = configured.first
            val surface = configured.second
            val width = chosen.width
            val height = chosen.height
            val fps = chosen.fps
            val bitrate = chosen.bitrateBps

            activeWidth = width
            activeHeight = height
            activeFps = fps
            activeBitrate = bitrate

            val vd = virtualDisplay
            if (vd == null) {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "MalekPlayGroupMirror",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    mediaHandler
                )
            } else {
                vd.resize(width, height, dpi)
                vd.surface = surface
            }

            videoEncoder = codec
            inputSurface = surface
            val generation = videoSessionGeneration.incrementAndGet()
            videoDrainThread = Thread({
                drainVideoEncoder(codec, generation, width, height)
            }, "MalekMirrorVideoDrain-$generation").apply {
                isDaemon = true
                start()
            }

            return UltraShareScreenMirrorProtocol.StreamConfig(
                width = width,
                height = height,
                fps = fps,
                bitrateBps = bitrate,
                audioEnabled = activeAudioRunning
            )
        }
    }

    private fun drainVideoEncoder(codec: MediaCodec, generation: Long, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        while (!stopped.get() && videoSessionGeneration.get() == generation) {
            val outIndex = try {
                codec.dequeueOutputBuffer(info, 25_000L)
            } catch (_: IllegalStateException) {
                break
            } catch (_: Exception) {
                break
            }
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) continue
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = runCatching { codec.outputFormat }.getOrNull()
                if (fmt != null) {
                    val csd0 = fmt.getByteBuffer("csd-0")
                    val csd1 = fmt.getByteBuffer("csd-1")
                    val combined = combineCsdBuffers(csd0, csd1)
                    if (combined.isNotEmpty()) {
                        hub.publishVideoConfig(combined, width, height, activeRotation)
                    }
                }
                continue
            }
            if (outIndex < 0) continue
            try {
                val buf = codec.getOutputBuffer(outIndex)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        hub.publishVideoConfig(bytes, width, height, activeRotation)
                    } else {
                        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        hub.publishVideoFrame(
                            encodedBytes = bytes,
                            isKeyframe = isKeyframe,
                            timestampUs = info.presentationTimeUs,
                            width = width,
                            height = height,
                            rotation = activeRotation
                        )
                        if (isKeyframe && firstVideoFrameEmitted.compareAndSet(false, true)) {
                            runCatching { onFirstVideoFrameEncoded() }
                        }
                    }
                }
            } finally {
                runCatching { codec.releaseOutputBuffer(outIndex, false) }
            }
        }
    }

    private fun checkScreenLockAndOrientationChange() {
        if (stopped.get()) return
        val lockedOrOff = keyguardManager?.isKeyguardLocked == true ||
            (powerManager != null && !powerManager.isInteractive)
        hub.publishProtectedScreenState(lockedOrOff)

        val prevSourceW = lastSourceWidth
        val prevSourceH = lastSourceHeight
        val (rawW, rawH, _) = readDisplayMetrics()
        if (rawW == prevSourceW && rawH == prevSourceH) return

        val memMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.memoryClass ?: 256
        val baseProfile = UltraShareScreenMirrorPolicy.selectCaptureProfile(memMb, hub.activeViewerCount().coerceAtLeast(1))
        val expected = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
            rawW, rawH, baseProfile.maxWidth, baseProfile.maxHeight
        )
        if (expected.first != activeWidth || expected.second != activeHeight) {
            UltraShareDiagnostics.event(
                "SCREEN_MIRROR",
                "DISPLAY_SIZE_CHANGED",
                mapOf("old" to "${activeWidth}x${activeHeight}", "new" to "${expected.first}x${expected.second}")
            )
            runCatching {
                val updated = synchronized(encoderRestartLock) {
                    if (stopped.get()) return
                    runCatching { virtualDisplay?.surface = null }
                    releaseVideoEncoderOnly()
                    startVideoPipeline().copy(audioEnabled = activeAudioRunning)
                }
                currentConfig = updated
                onConfigChanged(updated)
                scheduleInitialKeyframePulses()
            }.onFailure { err ->
                UltraShareDiagnostics.failure("SCREEN_MIRROR_RESIZE", err)
                stop()
                onProjectionStopped(err.localizedMessage ?: "تعذر إعادة ضبط دقة البث بعد تدوير الشاشة.")
            }
        }
    }

    private fun startSystemAudioPipelineIfSupported(): Boolean {
        if (!UltraShareScreenMirrorPolicy.hasAudioCapturePermission(context)) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        return runCatching {
            startSystemAudioPipelineApi29()
        }.onFailure { failure ->
            UltraShareDiagnostics.failure("SCREEN_MIRROR_AUDIO_INIT", failure)
            releaseAudioOnly()
        }.getOrDefault(false)
    }

    private fun startMicrophoneAudioPipelineIfSupported(): Boolean {
        if (!UltraShareScreenMirrorPolicy.hasMicrophoneCapturePermission(context)) {
            return false
        }
        return runCatching {
            startMicrophoneAudioPipeline()
        }.onFailure { failure ->
            UltraShareDiagnostics.failure("SCREEN_MIRROR_MIC_INIT", failure)
            releaseAudioOnly()
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSystemAudioPipelineApi29(): Boolean {
        val sampleRate = 48_000
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return false
        }

        return startAacEncoderAndPump(
            record = record,
            sampleRate = sampleRate,
            inputIsMono = false,
            threadName = "MalekMirrorSysAudioPump"
        )
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneAudioPipeline(): Boolean {
        val candidateRates = intArrayOf(48_000, 44_100)
        val candidateSources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        for (rate in candidateRates) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue
            for (source in candidateSources) {
                val record = try {
                    AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        (minBuf * 2).coerceAtLeast(8192)
                    )
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { record.release() }
                    continue
                }
                val startedOk = runCatching {
                    startAacEncoderAndPump(
                        record = record,
                        sampleRate = rate,
                        inputIsMono = true,
                        threadName = "MalekMirrorMicAudioPump"
                    )
                }.getOrDefault(false)
                if (startedOk) return true
                runCatching { record.release() }
            }
        }
        return false
    }

    private fun startAacEncoderAndPump(
        record: AudioRecord,
        sampleRate: Int,
        inputIsMono: Boolean,
        threadName: String
    ): Boolean {
        val channelCount = 2
        var aac: MediaCodec? = null
        try {
            val encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aac.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aac.start()
            record.startRecording()
        } catch (err: Throwable) {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { aac?.stop() }
            runCatching { aac?.release() }
            throw err
        }

        val activeAac = aac
        audioRecord = record
        audioEncoder = activeAac

        audioPumpThread = Thread({
            try {
                val rawReadBuf = ByteArray(if (inputIsMono) 2048 else 4096)
                val stereoBuf = if (inputIsMono) ByteArray(4096) else rawReadBuf
                val outInfo = MediaCodec.BufferInfo()
                while (!stopped.get() && audioEncoder === activeAac) {
                    val readBytes = record.read(rawReadBuf, 0, rawReadBuf.size)
                    if (readBytes < 0) {
                        UltraShareDiagnostics.event(
                            "SCREEN_MIRROR",
                            "AUDIO_READ_ERROR_FALLBACK_VIDEO_ONLY",
                            mapOf("errorCode" to readBytes)
                        )
                        handleMidStreamAudioFailure()
                        break
                    }
                    if (readBytes == 0) continue

                    val stereoValidBytes = if (inputIsMono) {
                        upmixMono16BitToStereo(rawReadBuf, readBytes, stereoBuf)
                    } else {
                        (readBytes / 4) * 4
                    }
                    var offset = 0
                    while (offset < stereoValidBytes && !stopped.get() && audioEncoder === activeAac) {
                        val inIdx = runCatching { activeAac.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
                        if (inIdx < 0) break
                        val inBuf = activeAac.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            runCatching { activeAac.queueInputBuffer(inIdx, 0, 0, 0L, 0) }
                            break
                        }
                        inBuf.clear()
                        val writable = ((minOf(stereoValidBytes - offset, inBuf.remaining())) / 4) * 4
                        if (writable <= 0) {
                            runCatching { activeAac.queueInputBuffer(inIdx, 0, 0, 0L, 0) }
                            break
                        }
                        inBuf.put(stereoBuf, offset, writable)
                        val ptsUs = System.nanoTime() / 1_000L
                        activeAac.queueInputBuffer(inIdx, 0, writable, ptsUs, 0)
                        offset += writable
                    }
                    while (!stopped.get() && audioEncoder === activeAac) {
                        val outIdx = runCatching { activeAac.dequeueOutputBuffer(outInfo, 0L) }.getOrDefault(-1)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val csd = runCatching { activeAac.outputFormat.getByteBuffer("csd-0") }.getOrNull()
                            if (csd != null) {
                                val asc = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                                if (asc.isNotEmpty()) hub.publishAudioConfig(asc)
                            }
                        } else if (outIdx >= 0) {
                            try {
                                val outBuf = activeAac.getOutputBuffer(outIdx)
                                if (outBuf != null && outInfo.size > 0) {
                                    outBuf.position(outInfo.offset)
                                    outBuf.limit(outInfo.offset + outInfo.size)
                                    val encoded = ByteArray(outInfo.size).also { outBuf.get(it) }
                                    if ((outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        hub.publishAudioConfig(encoded)
                                    } else {
                                        hub.publishAudioFrame(encoded, outInfo.presentationTimeUs)
                                    }
                                }
                            } finally {
                                runCatching { activeAac.releaseOutputBuffer(outIdx, false) }
                            }
                        } else break
                    }
                }
            } catch (err: Throwable) {
                if (!stopped.get()) {
                    UltraShareDiagnostics.failure("SCREEN_MIRROR_AUDIO_PUMP", err)
                    handleMidStreamAudioFailure()
                }
            }
        }, threadName).apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun handleMidStreamAudioFailure() {
        releaseAudioOnly()
        activeAudioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
        val updated = currentConfig?.copy(audioEnabled = false)
        if (updated != null) {
            currentConfig = updated
            runCatching { onConfigChanged(updated) }
        }
        runCatching {
            onAudioStatusNote(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                "توقف التقاط الصوت تلقائياً؛ يستمر بث الشاشة بالفيديو فقط دون انقطاع."
            )
        }
    }

    private fun releaseVideoEncoderOnly() {
        videoSessionGeneration.incrementAndGet()
        if (Thread.currentThread() !== videoDrainThread) {
            runCatching { videoDrainThread?.join(400L) }
        }
        videoDrainThread = null
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }

    private fun releaseAudioOnly() {
        activeAudioRunning = false
        val rec = audioRecord
        val enc = audioEncoder
        val pump = audioPumpThread
        audioRecord = null
        audioEncoder = null
        audioPumpThread = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        if (pump != null && Thread.currentThread() !== pump) {
            runCatching { pump.join(400L) }
        }
        runCatching { enc?.stop() }
        runCatching { enc?.release() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        mediaHandler.removeCallbacksAndMessages(null)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { mediaProjection.unregisterCallback(projectionCallback) }
        releaseAudioOnly()
        synchronized(encoderRestartLock) {
            releaseVideoEncoderOnly()
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
        }
        runCatching { mediaProjection.stop() }
        runCatching { mediaThread.quitSafely() }
    }

    private fun combineCsdBuffers(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray {
        val b0 = csd0?.duplicate()?.let { ByteArray(it.remaining()).also(it::get) } ?: ByteArray(0)
        val b1 = csd1?.duplicate()?.let { ByteArray(it.remaining()).also(it::get) } ?: ByteArray(0)
        return b0 + b1
    }
}

/**
 * Hardware/platform MediaCodec H.264 + AAC decoder rendering directly to a viewer Surface.
 *
 * Guarantees:
 * - Splits Annex-B SPS/PPS into `csd-0` + `csd-1` for Android 6–16 hardware decoder compatibility.
 * - Preserves an IDR frame that arrives before `SurfaceHolder.surfaceCreated` attaches the `Surface`,
 *   feeding it immediately once the decoder is configured.
 * - Schedules asynchronous output draining so a single static-screen IDR renders immediately without
 *   waiting for a second network frame.
 * - Emits [onFirstFrameRendered] strictly after `releaseOutputBuffer(outIdx, true)` paints the `Surface`.
 */
internal class AndroidScreenMirrorDecoder(
    private val onVideoDimensionsChanged: (width: Int, height: Int, rotation: Int) -> Unit,
    private val onRequestKeyframe: () -> Unit,
    private val onDecoderError: (String) -> Unit,
    private val onFirstFrameRendered: () -> Unit = {}
) {
    private val lock = Any()
    private val drainThread = HandlerThread("MalekMirrorDecodeDrain").apply { start() }
    private val drainHandler = Handler(drainThread.looper)

    private var outputSurface: Surface? = null
    private var videoDecoder: MediaCodec? = null
    private var lastVideoConfig: ByteArray? = null
    private var pendingPreSurfaceIdr: UltraShareScreenMirrorProtocol.MediaFrame? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredRotation = 0
    private var firstFrameRenderedOnCurrentSurface = false
    private val sequenceTracker = VideoStreamSequenceTracker()
    private val droppedInputFrames = AtomicLong(0L)

    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var lastAudioConfig: ByteArray? = null
    @Volatile private var audioMuted = false
    private var closed = false

    fun attachSurface(surface: Surface?) {
        synchronized(lock) {
            if (closed) return
            if (surface != null && surface === outputSurface && videoDecoder != null) {
                return
            }
            outputSurface = surface
            firstFrameRenderedOnCurrentSurface = false
            if (surface == null) {
                releaseVideoDecoderOnly()
                sequenceTracker.reset(awaitIdr = true)
            } else if (lastVideoConfig != null && configuredWidth > 0 && configuredHeight > 0) {
                val configuredOk = runCatching {
                    configureVideoDecoderLocked(lastVideoConfig!!, configuredWidth, configuredHeight)
                }.onFailure { err ->
                    onDecoderError(err.localizedMessage ?: "تعذر تهيئة مفكك ترميز الفيديو.")
                }.isSuccess

                val cachedIdr = pendingPreSurfaceIdr
                pendingPreSurfaceIdr = null
                if (configuredOk && cachedIdr != null && videoDecoder != null) {
                    val decision = sequenceTracker.onVideoFrame(cachedIdr.header.mediaSeq, isIdr = true)
                    if (decision.shouldFeedToDecoder) {
                        feedVideoBufferLocked(
                            codec = videoDecoder!!,
                            bytes = cachedIdr.payload,
                            ptsUs = cachedIdr.header.timestampUs,
                            isIdr = true
                        )
                    }
                }
                onRequestKeyframe()
            }
        }
    }

    fun setMuted(muted: Boolean) {
        audioMuted = muted
    }

    fun handleFrame(frame: UltraShareScreenMirrorProtocol.MediaFrame) {
        synchronized(lock) {
            if (closed) return
            when (frame.header.frameType) {
                UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG -> {
                    val w = frame.header.width.coerceAtLeast(16)
                    val h = frame.header.height.coerceAtLeast(16)
                    val configChanged = lastVideoConfig == null ||
                        !lastVideoConfig!!.contentEquals(frame.payload) ||
                        w != configuredWidth || h != configuredHeight
                    if (configChanged) {
                        pendingPreSurfaceIdr = null
                    }
                    lastVideoConfig = frame.payload.copyOf()
                    configuredWidth = w
                    configuredHeight = h
                    configuredRotation = frame.header.rotation
                    onVideoDimensionsChanged(w, h, frame.header.rotation)
                    if (outputSurface != null && (configChanged || videoDecoder == null)) {
                        runCatching {
                            configureVideoDecoderLocked(frame.payload, w, h)
                        }.onFailure { err ->
                            onDecoderError(err.localizedMessage ?: "تعذر ضبط دقة البث المستقبَل.")
                        }
                    }
                }
                UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR,
                UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA -> {
                    val isIdr = frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR
                    val codec = videoDecoder
                    if (codec == null) {
                        // Surface has not attached yet: retain the latest IDR so attachSurface()
                        // can decode and render immediately without waiting for another network IDR.
                        if (isIdr) {
                            pendingPreSurfaceIdr = frame
                        }
                        return
                    }

                    val decision = sequenceTracker.onVideoFrame(frame.header.mediaSeq, isIdr)
                    if (decision.shouldRequestKeyframe) {
                        onRequestKeyframe()
                    }
                    if (!decision.shouldFeedToDecoder) {
                        return
                    }

                    if (frame.header.width > 0 && frame.header.height > 0) {
                        if (frame.header.width != configuredWidth ||
                            frame.header.height != configuredHeight ||
                            frame.header.rotation != configuredRotation
                        ) {
                            configuredWidth = frame.header.width
                            configuredHeight = frame.header.height
                            configuredRotation = frame.header.rotation
                            onVideoDimensionsChanged(configuredWidth, configuredHeight, configuredRotation)
                        }
                    }
                    feedVideoBufferLocked(codec, frame.payload, frame.header.timestampUs, isIdr)
                }
                UltraShareScreenMirrorProtocol.FrameType.AUDIO_CONFIG -> {
                    if (lastAudioConfig == null || !lastAudioConfig!!.contentEquals(frame.payload)) {
                        lastAudioConfig = frame.payload.copyOf()
                        runCatching { configureAudioDecoderLocked(frame.payload) }
                    }
                }
                UltraShareScreenMirrorProtocol.FrameType.AUDIO_FRAME -> {
                    val aCodec = audioDecoder ?: return
                    feedAudioBufferLocked(aCodec, frame.payload, frame.header.timestampUs)
                }
                UltraShareScreenMirrorProtocol.FrameType.HEARTBEAT,
                UltraShareScreenMirrorProtocol.FrameType.SCREEN_PROTECTED -> Unit
            }
        }
    }

    private fun configureVideoDecoderLocked(csd: ByteArray, width: Int, height: Int) {
        releaseVideoDecoderOnly()
        val surface = outputSurface ?: return
        val (csd0, csd1) = splitAvcCodecConfig(csd)

        fun buildDecoderFormat(enableLowLatency: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                if (csd1 != null) {
                    setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
                }
                if (enableLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

        val attempts = buildList<Pair<String?, Boolean>> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(null to true)
            }
            add(null to false)
            add("c2.android.avc.decoder" to false)
            add("OMX.google.h264.decoder" to false)
        }

        var lastError: Throwable? = null
        for ((explicitName, lowLatency) in attempts) {
            var codec: MediaCodec? = null
            try {
                codec = if (explicitName != null) {
                    MediaCodec.createByCodecName(explicitName)
                } else {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }
                codec.configure(buildDecoderFormat(lowLatency), surface, null, 0)
                codec.start()
                videoDecoder = codec
                sequenceTracker.reset(awaitIdr = true)
                return
            } catch (err: Throwable) {
                lastError = err
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }
        }
        throw IllegalStateException(
            lastError?.localizedMessage ?: "تعذر تهيئة مفكك فيديو H.264 على هذا الجهاز.",
            lastError
        )
    }

    private fun drainDecoderOutputsLocked(codec: MediaCodec, renderToSurface: Boolean, waitForFirstFrameUs: Long) {
        val info = MediaCodec.BufferInfo()
        var waitBudgetUs = if (!firstFrameRenderedOnCurrentSurface && renderToSurface) waitForFirstFrameUs else 0L
        var safetyIterations = 0
        while (safetyIterations++ < 16) {
            val outIdx = codec.dequeueOutputBuffer(info, waitBudgetUs)
            waitBudgetUs = 0L
            when {
                outIdx >= 0 -> {
                    val shouldRender = renderToSurface && outputSurface != null
                    codec.releaseOutputBuffer(outIdx, shouldRender)
                    if (shouldRender && !firstFrameRenderedOnCurrentSurface) {
                        firstFrameRenderedOnCurrentSurface = true
                        runCatching { onFirstFrameRendered() }
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = runCatching { codec.outputFormat }.getOrNull()
                    if (fmt != null) {
                        val outW = runCatching { fmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(configuredWidth)
                        val outH = runCatching { fmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(configuredHeight)
                        if (outW > 0 && outH > 0 && (outW != configuredWidth || outH != configuredHeight)) {
                            configuredWidth = outW
                            configuredHeight = outH
                            onVideoDimensionsChanged(outW, outH, configuredRotation)
                        }
                    }
                    if (!firstFrameRenderedOnCurrentSurface && renderToSurface) {
                        waitBudgetUs = waitForFirstFrameUs
                    }
                }
                @Suppress("DEPRECATION")
                (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) -> {
                    if (!firstFrameRenderedOnCurrentSurface && renderToSurface) {
                        waitBudgetUs = waitForFirstFrameUs
                    }
                }
                else -> break
            }
        }
    }

    private fun scheduleAsyncFirstFrameDrain() {
        for (delayMs in longArrayOf(15L, 45L, 100L)) {
            drainHandler.postDelayed({
                synchronized(lock) {
                    if (closed || firstFrameRenderedOnCurrentSurface) return@synchronized
                    val codec = videoDecoder ?: return@synchronized
                    val render = outputSurface != null
                    runCatching { drainDecoderOutputsLocked(codec, render, 5_000L) }
                }
            }, delayMs)
        }
    }

    private fun feedVideoBufferLocked(
        codec: MediaCodec,
        bytes: ByteArray,
        ptsUs: Long,
        isIdr: Boolean
    ) {
        try {
            val adapter = object : VideoCodecBufferAdapter {
                override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
                override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
                override fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {
                    codec.queueInputBuffer(index, offset, size, ptsUs, flags)
                }
                override fun drainOutputs(renderToSurface: Boolean) {
                    drainDecoderOutputsLocked(codec, renderToSurface, waitForFirstFrameUs = 8_000L)
                }
            }
            val result = VideoDecoderBufferFeeder.feedFrame(
                adapter = adapter,
                bytes = bytes,
                ptsUs = ptsUs,
                isIdr = isIdr,
                renderToSurface = outputSurface != null,
                sequenceTracker = sequenceTracker,
                onRequestKeyframe = onRequestKeyframe,
                onFrameDropped = {
                    val total = droppedInputFrames.incrementAndGet()
                    UltraShareDiagnostics.event(
                        "SCREEN_MIRROR_DECODER",
                        "VIDEO_INPUT_DROPPED",
                        mapOf("bytes" to bytes.size, "isIdr" to isIdr, "totalDropped" to total)
                    )
                }
            )
            if (result == VideoInputFeedResult.FED && !firstFrameRenderedOnCurrentSurface) {
                scheduleAsyncFirstFrameDrain()
            } else if (result != VideoInputFeedResult.FED && isIdr) {
                sequenceTracker.awaitingIdr = true
            }
        } catch (_: Exception) {
            sequenceTracker.awaitingIdr = true
            releaseVideoDecoderOnly()
            lastVideoConfig?.let { cfg ->
                if (configuredWidth > 0 && configuredHeight > 0 && outputSurface != null) {
                    runCatching { configureVideoDecoderLocked(cfg, configuredWidth, configuredHeight) }
                }
            }
            onRequestKeyframe()
        }
    }

    private fun configureAudioDecoderLocked(asc: ByteArray) {
        releaseAudioOnly()
        val (sampleRate, channelCount) = parseAacAudioSpecificConfig(asc)
        val outChannelMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        }
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, 0)
            codec.start()

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                outChannelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(outChannelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            track.play()
            audioDecoder = codec
            audioTrack = track
        } catch (err: Throwable) {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            throw err
        }
    }

    private fun feedAudioBufferLocked(codec: MediaCodec, bytes: ByteArray, ptsUs: Long) {
        if (bytes.isEmpty()) return
        runCatching {
            val inIdx = codec.dequeueInputBuffer(5_000L)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx)
                if (inBuf == null) {
                    codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                } else {
                    inBuf.clear()
                    if (bytes.size > inBuf.remaining()) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    } else {
                        inBuf.put(bytes, 0, bytes.size)
                        codec.queueInputBuffer(inIdx, 0, bytes.size, ptsUs, 0)
                    }
                }
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 0L)
                if (outIdx >= 0) {
                    try {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && !audioMuted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val pcm = ByteArray(info.size).also { outBuf.get(it) }
                            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                        }
                    } finally {
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                } else break
            }
        }
    }

    private fun releaseVideoDecoderOnly() {
        drainHandler.removeCallbacksAndMessages(null)
        runCatching { videoDecoder?.stop() }
        runCatching { videoDecoder?.release() }
        videoDecoder = null
    }

    private fun releaseAudioOnly() {
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        runCatching { audioDecoder?.stop() }
        runCatching { audioDecoder?.release() }
        audioDecoder = null
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            outputSurface = null
            pendingPreSurfaceIdr = null
            releaseVideoDecoderOnly()
            releaseAudioOnly()
            runCatching { drainThread.quitSafely() }
        }
    }
}
