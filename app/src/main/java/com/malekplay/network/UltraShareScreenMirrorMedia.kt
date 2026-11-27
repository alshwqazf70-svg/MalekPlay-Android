package com.malekplay.network

import android.annotation.SuppressLint
import android.app.ActivityManager
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
import android.app.KeyguardManager
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

private val AAC_SAMPLE_RATES = intArrayOf(
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
    24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350
)

internal fun parseAudioSpecificConfig(asc: ByteArray): Pair<Int, Int> {
    if (asc.size < 2) return 48_000 to 2
    val b0 = asc[0].toInt() and 0xff
    val b1 = asc[1].toInt() and 0xff
    val objectType = (b0 ushr 3) and 0x1F
    if (objectType == 31) return 48_000 to 2
    val freqIndex = ((b0 and 0x07) shl 1) or ((b1 ushr 7) and 0x01)
    if (freqIndex == 15) {
        if (asc.size < 5) return 48_000 to 2
        val b2 = asc[2].toInt() and 0xff
        val b3 = asc[3].toInt() and 0xff
        val b4 = asc[4].toInt() and 0xff
        val customRate = ((b1 and 0x7F) shl 17) or (b2 shl 9) or (b3 shl 1) or ((b4 ushr 7) and 0x01)
        val ch = ((b4 ushr 3) and 0x0F).let { if (it == 1) 1 else 2 }
        return customRate.coerceIn(8_000, 96_000) to ch
    }
    val sampleRate = AAC_SAMPLE_RATES.getOrElse(freqIndex) { 48_000 }
    val channelConfig = (b1 ushr 3) and 0x0F
    val channelCount = if (channelConfig == 1) 1 else 2
    return sampleRate to channelCount
}

internal data class MicrophoneAudioConfig(
    val sampleRate: Int,
    val channelMask: Int,
    val channelCount: Int,
    val bufferSizeBytes: Int
)

internal fun defaultMicrophoneAudioCandidates(): List<MicrophoneAudioConfig> = listOf(
    MicrophoneAudioConfig(48_000, AudioFormat.CHANNEL_IN_MONO, 1, 4096),
    MicrophoneAudioConfig(44_100, AudioFormat.CHANNEL_IN_MONO, 1, 4096),
    MicrophoneAudioConfig(48_000, AudioFormat.CHANNEL_IN_STEREO, 2, 8192),
    MicrophoneAudioConfig(44_100, AudioFormat.CHANNEL_IN_STEREO, 2, 8192),
    MicrophoneAudioConfig(16_000, AudioFormat.CHANNEL_IN_MONO, 1, 2048)
)

internal fun <T> selectMicrophoneAudioConfiguration(
    candidates: List<MicrophoneAudioConfig> = defaultMicrophoneAudioCandidates(),
    probeMinBuffer: (sampleRate: Int, channelMask: Int) -> Int,
    tryOpen: (MicrophoneAudioConfig, minBufferBytes: Int) -> T?
): Pair<MicrophoneAudioConfig, T>? {
    for (candidate in candidates) {
        val minBuf = runCatching { probeMinBuffer(candidate.sampleRate, candidate.channelMask) }.getOrDefault(-1)
        if (minBuf <= 0) continue
        val resolvedBuf = maxOf(minBuf * 2, candidate.bufferSizeBytes)
        val handle = runCatching { tryOpen(candidate, resolvedBuf) }.getOrNull()
        if (handle != null) return candidate.copy(bufferSizeBytes = resolvedBuf) to handle
    }
    return null
}

/**
 * Explicit 7-stage viewer playback verification state machine:
 * 1. PAIRED
 * 2. OFFER_ACCEPTED
 * 3. CONNECTED_TO_SOURCE
 * 4. VIDEO_CONFIG_RECEIVED
 * 5. FRAME_RECEIVED
 * 6. FRAME_DECODED
 * 7. FIRST_FRAME_RENDERED (STREAMING)
 */
enum class MirrorViewerStage {
    PAIRED,
    OFFER_ACCEPTED,
    CONNECTED_TO_SOURCE,
    VIDEO_CONFIG_RECEIVED,
    FRAME_RECEIVED,
    FRAME_DECODED,
    FIRST_FRAME_RENDERED
}

internal class MirrorViewerPlaybackTracker(
    initialStage: MirrorViewerStage = MirrorViewerStage.PAIRED
) {
    var stage: MirrorViewerStage = initialStage
        private set
    var firstFrameRendered: Boolean = initialStage == MirrorViewerStage.FIRST_FRAME_RENDERED
        private set
    var hasVideoConfig: Boolean = false
        private set
    var waitingSinceMs: Long = 0L
        private set
    var lastKeyframeRequestMs: Long = 0L
        private set
    var keyframeRetryCount: Int = 0
        private set
    var decoderError: String? = null
        private set

    fun onOfferAccepted() {
        if (stage < MirrorViewerStage.OFFER_ACCEPTED) {
            stage = MirrorViewerStage.OFFER_ACCEPTED
        }
    }

    fun onConnectedToSource(nowMs: Long) {
        decoderError = null
        firstFrameRendered = false
        stage = MirrorViewerStage.CONNECTED_TO_SOURCE
        waitingSinceMs = nowMs
        lastKeyframeRequestMs = nowMs
        keyframeRetryCount = 0
    }

    fun onVideoConfigReceived() {
        hasVideoConfig = true
        if (!firstFrameRendered && stage < MirrorViewerStage.VIDEO_CONFIG_RECEIVED) {
            stage = MirrorViewerStage.VIDEO_CONFIG_RECEIVED
        }
    }

    fun onVideoFrameReceived() {
        if (!firstFrameRendered && stage < MirrorViewerStage.FRAME_RECEIVED) {
            stage = MirrorViewerStage.FRAME_RECEIVED
        }
    }

    fun onFrameDecoded(renderedToSurface: Boolean) {
        if (renderedToSurface && !firstFrameRendered && stage < MirrorViewerStage.FRAME_DECODED) {
            stage = MirrorViewerStage.FRAME_DECODED
        }
    }

    fun onFirstFrameRendered(): Boolean {
        val wasRendered = firstFrameRendered
        firstFrameRendered = true
        stage = MirrorViewerStage.FIRST_FRAME_RENDERED
        decoderError = null
        return !wasRendered
    }

    fun onSurfaceOrDecoderReset(nowMs: Long): Boolean {
        firstFrameRendered = false
        stage = if (hasVideoConfig) {
            MirrorViewerStage.VIDEO_CONFIG_RECEIVED
        } else if (stage >= MirrorViewerStage.CONNECTED_TO_SOURCE) {
            MirrorViewerStage.CONNECTED_TO_SOURCE
        } else {
            stage
        }
        waitingSinceMs = nowMs
        lastKeyframeRequestMs = nowMs
        keyframeRetryCount = 0
        return true
    }

    fun onDecoderFailure(message: String) {
        decoderError = message
        firstFrameRendered = false
    }

    enum class WatchdogAction {
        NONE,
        REQUEST_KEYFRAME,
        TIMED_OUT
    }

    fun evaluateFirstFrameWatchdog(
        nowMs: Long,
        retryIntervalMs: Long = FIRST_FRAME_RETRY_INTERVAL_MS,
        totalTimeoutMs: Long = FIRST_FRAME_TOTAL_TIMEOUT_MS,
        maxRetries: Int = FIRST_FRAME_MAX_RETRIES
    ): WatchdogAction {
        if (firstFrameRendered || decoderError != null) return WatchdogAction.NONE
        if (stage < MirrorViewerStage.CONNECTED_TO_SOURCE || waitingSinceMs <= 0L) {
            return WatchdogAction.NONE
        }
        val elapsedTotal = (nowMs - waitingSinceMs).coerceAtLeast(0L)
        if (elapsedTotal >= totalTimeoutMs) {
            return WatchdogAction.TIMED_OUT
        }
        val sinceLastRequest = (nowMs - lastKeyframeRequestMs).coerceAtLeast(0L)
        if (keyframeRetryCount < maxRetries && sinceLastRequest >= retryIntervalMs) {
            keyframeRetryCount++
            lastKeyframeRequestMs = nowMs
            return WatchdogAction.REQUEST_KEYFRAME
        }
        return WatchdogAction.NONE
    }

    companion object {
        const val FIRST_FRAME_RETRY_INTERVAL_MS = 2_500L
        const val FIRST_FRAME_TOTAL_TIMEOUT_MS = 10_000L
        const val FIRST_FRAME_MAX_RETRIES = 3
    }
}

/**
 * Real Android MediaProjection + VirtualDisplay + Hardware/Platform MediaCodec H.264
 * screen capturer with mutually-exclusive audio capture:
 * - Android 10+ (API 29+): optional AudioPlaybackCapture (internal audio) OR Microphone
 * - Android 6–9 (API 23–28): optional Microphone via AudioRecord
 */
internal class AndroidScreenMirrorCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val hub: ScreenMirrorBroadcasterHub,
    private val requestedAudioMode: UltraShareScreenMirrorPolicy.MirrorAudioMode,
    private val onConfigChanged: (UltraShareScreenMirrorProtocol.StreamConfig) -> Unit,
    private val onProjectionStopped: (String?) -> Unit,
    private val onAudioStateChanged: (UltraShareScreenMirrorPolicy.MirrorAudioMode, String?) -> Unit = { _, _ -> }
) {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
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
    @Volatile private var activeAudioSampleRate = 48_000
    @Volatile private var activeAudioChannelCount = 2
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
        currentConfig = config
        val startedAudioMode = startAudioPipelineForMode(requestedAudioMode)
        activeAudioRunning = startedAudioMode != UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
        activeAudioMode = startedAudioMode

        val finalConfig = config.copy(
            audioEnabled = activeAudioRunning,
            audioSampleRate = activeAudioSampleRate,
            audioChannelCount = activeAudioChannelCount,
            audioMode = activeAudioMode
        )
        currentConfig = finalConfig
        onConfigChanged(finalConfig)
        return finalConfig
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
                    startVideoPipeline().copy(
                        audioEnabled = activeAudioRunning,
                        audioSampleRate = activeAudioSampleRate,
                        audioChannelCount = activeAudioChannelCount,
                        audioMode = activeAudioMode
                    )
                }
                currentConfig = updated
                onConfigChanged(updated)
            }.onFailure { err ->
                UltraShareDiagnostics.failure("SCREEN_MIRROR_RESIZE", err)
                stop()
                onProjectionStopped(err.localizedMessage ?: "تعذر إعادة ضبط دقة البث بعد تدوير الشاشة.")
            }
        }
    }

    private fun startAudioPipelineForMode(
        requestedMode: UltraShareScreenMirrorPolicy.MirrorAudioMode
    ): UltraShareScreenMirrorPolicy.MirrorAudioMode {
        val hasPerm = UltraShareScreenMirrorPolicy.hasRecordAudioPermission(context)
        val effective = UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
            requested = requestedMode,
            hasRecordAudioPermission = hasPerm,
            sdkInt = Build.VERSION.SDK_INT
        )
        if (effective == UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE) {
            if (requestedMode != UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE) {
                val reason = when {
                    !hasPerm -> "لم يُمنح إذن الصوت (RECORD_AUDIO)؛ يستمر بث الصورة فقط."
                    requestedMode == UltraShareScreenMirrorPolicy.MirrorAudioMode.INTERNAL_AUDIO ->
                        "الصوت الداخلي يتطلب Android 10 فأعلى؛ يستمر بث الصورة فقط."
                    else -> "تعذر تشغيل الصوت؛ يستمر بث الصورة فقط."
                }
                onAudioStateChanged(UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE, reason)
            }
            return UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
        }
        // Strictly mutually exclusive: release any prior audio handle before opening one.
        releaseAudioOnly()
        val started = when (effective) {
            UltraShareScreenMirrorPolicy.MirrorAudioMode.INTERNAL_AUDIO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { startInternalAudioPipelineApi29() }
                        .onFailure { failure ->
                            UltraShareDiagnostics.failure("SCREEN_MIRROR_INTERNAL_AUDIO_INIT", failure)
                            releaseAudioOnly()
                            onAudioStateChanged(
                                UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                                "تعذر التقاط الصوت الداخلي؛ يستمر بث صورة الشاشة."
                            )
                        }
                        .getOrDefault(false)
                } else {
                    false
                }
            }
            UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE -> {
                runCatching { startMicrophoneAudioPipeline() }
                    .onFailure { failure ->
                        UltraShareDiagnostics.failure("SCREEN_MIRROR_MIC_AUDIO_INIT", failure)
                        releaseAudioOnly()
                        onAudioStateChanged(
                            UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                            "تعذر تشغيل صوت الميكروفون؛ يستمر بث صورة الشاشة."
                        )
                    }
                    .getOrDefault(false)
            }
            UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE -> false
        }
        return if (started) effective else UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startInternalAudioPipelineApi29(): Boolean {
        if (!UltraShareScreenMirrorPolicy.hasRecordAudioPermission(context)) return false
        val sampleRate = 48_000
        val channelCount = 2
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

        val activeAac = startAacAudioEncoderAndRecord(record, sampleRate, channelCount)
            ?: return false
        activeAudioSampleRate = sampleRate
        activeAudioChannelCount = channelCount
        startAudioPumpLoop(
            record = record,
            activeAac = activeAac,
            bytesPerSampleFrame = channelCount * 2,
            activeMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.INTERNAL_AUDIO
        )
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneAudioPipeline(): Boolean {
        if (!UltraShareScreenMirrorPolicy.hasRecordAudioPermission(context)) return false
        val selected = selectMicrophoneAudioConfiguration(
            probeMinBuffer = { rate, mask ->
                AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            },
            tryOpen = { candidate, bufferSize ->
                val rec = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        candidate.sampleRate,
                        candidate.channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                } catch (_: SecurityException) {
                    return@selectMicrophoneAudioConfiguration null
                } catch (_: IllegalArgumentException) {
                    return@selectMicrophoneAudioConfiguration null
                }
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { rec.release() }
                    null
                } else {
                    val aac = runCatching {
                        startAacAudioEncoderAndRecord(rec, candidate.sampleRate, candidate.channelCount)
                    }.getOrNull()
                    if (aac == null) {
                        runCatching { rec.stop() }
                        runCatching { rec.release() }
                        null
                    } else {
                        rec to aac
                    }
                }
            }
        ) ?: return false

        val (chosenCfg, handles) = selected
        val (record, activeAac) = handles
        activeAudioSampleRate = chosenCfg.sampleRate
        activeAudioChannelCount = chosenCfg.channelCount
        startAudioPumpLoop(
            record = record,
            activeAac = activeAac,
            bytesPerSampleFrame = chosenCfg.channelCount * 2,
            activeMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
        )
        return true
    }

    private fun startAacAudioEncoderAndRecord(
        record: AudioRecord,
        sampleRate: Int,
        channelCount: Int
    ): MediaCodec? {
        var aac: MediaCodec? = null
        return try {
            val encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channelCount == 1) 64_000 else 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aac.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aac.start()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("AudioRecord failed to enter RECORDSTATE_RECORDING")
            }
            audioRecord = record
            audioEncoder = aac
            aac
        } catch (err: Throwable) {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { aac?.stop() }
            runCatching { aac?.release() }
            throw err
        }
    }

    private fun startAudioPumpLoop(
        record: AudioRecord,
        activeAac: MediaCodec,
        bytesPerSampleFrame: Int,
        activeMode: UltraShareScreenMirrorPolicy.MirrorAudioMode
    ) {
        val alignUnit = bytesPerSampleFrame.coerceAtLeast(2)
        audioPumpThread = Thread({
            val pcm = ByteArray(4096)
            val outInfo = MediaCodec.BufferInfo()
            var runtimeAudioFailureReason: String? = null
            try {
                while (!stopped.get() && audioEncoder === activeAac && audioRecord === record) {
                    if (!UltraShareScreenMirrorPolicy.hasRecordAudioPermission(context)) {
                        runtimeAudioFailureReason = "تم سحب إذن الميكروفون؛ يستمر بث صورة الشاشة."
                        break
                    }
                    val readBytes = try {
                        record.read(pcm, 0, pcm.size)
                    } catch (sec: SecurityException) {
                        runtimeAudioFailureReason = "تم سحب إذن الميكروفون؛ يستمر بث صورة الشاشة."
                        break
                    } catch (err: Throwable) {
                        UltraShareDiagnostics.failure("SCREEN_MIRROR_AUDIO_READ", err)
                        runtimeAudioFailureReason = "توقف مسار الصوت؛ يستمر بث صورة الشاشة."
                        break
                    }
                    if (readBytes < 0) {
                        UltraShareDiagnostics.event(
                            "SCREEN_MIRROR",
                            "AUDIO_RECORD_READ_ERROR",
                            mapOf("code" to readBytes, "mode" to activeMode.name)
                        )
                        runtimeAudioFailureReason = "توقف التقاط الصوت؛ يستمر بث صورة الشاشة."
                        break
                    }
                    var offset = 0
                    val alignedTotal = (readBytes / alignUnit) * alignUnit
                    while (offset < alignedTotal && !stopped.get() && audioEncoder === activeAac) {
                        val inIdx = runCatching { activeAac.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
                        if (inIdx < 0) break
                        val inBuf = activeAac.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            runCatching { activeAac.queueInputBuffer(inIdx, 0, 0, 0L, 0) }
                            break
                        }
                        inBuf.clear()
                        val writable = ((minOf(alignedTotal - offset, inBuf.remaining())) / alignUnit) * alignUnit
                        if (writable <= 0) {
                            runCatching { activeAac.queueInputBuffer(inIdx, 0, 0, 0L, 0) }
                            break
                        }
                        inBuf.put(pcm, offset, writable)
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
            } finally {
                if (runtimeAudioFailureReason != null && !stopped.get()) {
                    // Cleanly tear down only the failed audio resources while keeping video alive.
                    releaseAudioOnlyFromPump(record, activeAac)
                    activeAudioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
                    currentConfig = currentConfig?.copy(
                        audioEnabled = false,
                        audioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE
                    )
                    currentConfig?.let(onConfigChanged)
                    onAudioStateChanged(
                        UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                        runtimeAudioFailureReason
                    )
                }
            }
        }, "MalekMirrorAudioPump").apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseAudioOnlyFromPump(rec: AudioRecord, enc: MediaCodec) {
        activeAudioRunning = false
        if (audioRecord === rec) audioRecord = null
        if (audioEncoder === enc) audioEncoder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        runCatching { enc.stop() }
        runCatching { enc.release() }
    }

    private fun releaseVideoEncoderOnly() {
        videoSessionGeneration.incrementAndGet()
        runCatching { videoDrainThread?.join(400L) }
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
        audioRecord = null
        audioEncoder = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        runCatching { audioPumpThread?.join(400L) }
        audioPumpThread = null
        runCatching { enc?.stop() }
        runCatching { enc?.release() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
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
 * Handles missing frames safely by discarding orphan P-frames until the next IDR arrives.
 */
internal class AndroidScreenMirrorDecoder(
    private val onVideoDimensionsChanged: (width: Int, height: Int, rotation: Int) -> Unit,
    private val onRequestKeyframe: () -> Unit,
    private val onDecoderError: (String) -> Unit,
    private val onVideoConfigApplied: () -> Unit = {},
    private val onVideoFrameReceived: () -> Unit = {},
    private val onFrameDecoded: (renderedToSurface: Boolean) -> Unit = {},
    private val onFirstFrameRendered: () -> Unit = {},
    private val onSurfaceOrDecoderReset: () -> Unit = {}
) {
    private val lock = Any()
    private val renderCallbackThread = HandlerThread("MalekMirrorRenderCb").apply { start() }
    private val renderCallbackHandler = Handler(renderCallbackThread.looper)
    private var outputSurface: Surface? = null
    private var videoDecoder: MediaCodec? = null
    private var lastVideoConfig: ByteArray? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var consecutiveVideoDecodeFailures = 0
    private val sequenceTracker = VideoStreamSequenceTracker()
    private val droppedInputFrames = AtomicLong(0L)

    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var lastAudioConfig: ByteArray? = null
    @Volatile private var audioMuted = false
    private var notifiedFirstFrameForCurrentDecoder = false
    private var closed = false

    fun attachSurface(surface: Surface?) {
        synchronized(lock) {
            if (closed) return
            if (surface != null && outputSurface === surface && videoDecoder != null && surface.isValid) {
                return
            }
            outputSurface = surface
            if (surface == null) {
                releaseVideoDecoderOnly()
                sequenceTracker.reset(awaitIdr = true)
                onSurfaceOrDecoderReset()
            } else {
                onSurfaceOrDecoderReset()
                if (lastVideoConfig != null && configuredWidth > 0 && configuredHeight > 0) {
                    runCatching {
                        configureVideoDecoderLocked(lastVideoConfig!!, configuredWidth, configuredHeight)
                    }.onFailure { err ->
                        onDecoderError(err.localizedMessage ?: "تعذر تهيئة مفكك ترميز الفيديو.")
                    }
                    onRequestKeyframe()
                } else {
                    onRequestKeyframe()
                }
            }
        }
    }

    fun reinitializeForRetry() {
        synchronized(lock) {
            if (closed) return
            consecutiveVideoDecodeFailures = 0
            sequenceTracker.reset(awaitIdr = true)
            onSurfaceOrDecoderReset()
            val cfg = lastVideoConfig
            if (outputSurface != null && cfg != null && configuredWidth > 0 && configuredHeight > 0) {
                runCatching {
                    configureVideoDecoderLocked(cfg, configuredWidth, configuredHeight)
                }.onFailure { err ->
                    onDecoderError(err.localizedMessage ?: "تعذر إعادة تهيئة مفكك ترميز الفيديو.")
                }
            }
            onRequestKeyframe()
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
                    lastVideoConfig = frame.payload.copyOf()
                    configuredWidth = w
                    configuredHeight = h
                    onVideoDimensionsChanged(w, h, frame.header.rotation)
                    onVideoConfigApplied()
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
                    onVideoFrameReceived()
                    val isIdr = frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR
                    val decision = sequenceTracker.onVideoFrame(frame.header.mediaSeq, isIdr)
                    if (decision.shouldRequestKeyframe) {
                        onRequestKeyframe()
                    }
                    if (!decision.shouldFeedToDecoder) {
                        return
                    }

                    val codec = videoDecoder ?: return
                    if (frame.header.width > 0 && frame.header.height > 0) {
                        if (frame.header.width != configuredWidth || frame.header.height != configuredHeight) {
                            configuredWidth = frame.header.width
                            configuredHeight = frame.header.height
                            onVideoDimensionsChanged(configuredWidth, configuredHeight, frame.header.rotation)
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
        notifiedFirstFrameForCurrentDecoder = false
        val surface = outputSurface ?: return
        if (!surface.isValid) {
            throw IllegalStateException("سطح العرض (Surface) غير صالح.")
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Available on API 23+ (minSdk = 23): confirms a frame was actually rendered onto Surface.
            val activeCodec = codec
            activeCodec.setOnFrameRenderedListener(
                { _, _, _ ->
                    var shouldNotify = false
                    synchronized(lock) {
                        if (!closed && videoDecoder === activeCodec && !notifiedFirstFrameForCurrentDecoder) {
                            notifiedFirstFrameForCurrentDecoder = true
                            consecutiveVideoDecodeFailures = 0
                            shouldNotify = true
                        }
                    }
                    if (shouldNotify) {
                        onFirstFrameRendered()
                    }
                },
                renderCallbackHandler
            )
            activeCodec.configure(format, surface, null, 0)
            activeCodec.start()
            videoDecoder = activeCodec
            sequenceTracker.awaitingIdr = true
        } catch (err: Throwable) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            throw err
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
                private val info = MediaCodec.BufferInfo()
                override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
                override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
                override fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {
                    codec.queueInputBuffer(index, offset, size, ptsUs, flags)
                }
                override fun drainOutputs(renderToSurface: Boolean) {
                    while (true) {
                        val outIdx = codec.dequeueOutputBuffer(info, 0L)
                        if (outIdx >= 0) {
                            val shouldRender = renderToSurface && info.size > 0
                            codec.releaseOutputBuffer(outIdx, shouldRender)
                            if (info.size > 0) {
                                onFrameDecoded(shouldRender)
                            }
                        } else {
                            break
                        }
                    }
                }
            }
            val result = VideoDecoderBufferFeeder.feedFrame(
                adapter = adapter,
                bytes = bytes,
                ptsUs = ptsUs,
                isIdr = isIdr,
                renderToSurface = outputSurface != null && outputSurface?.isValid == true,
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
            if (result != VideoInputFeedResult.FED && isIdr) {
                sequenceTracker.awaitingIdr = true
            } else if (result == VideoInputFeedResult.FED) {
                consecutiveVideoDecodeFailures = 0
            }
        } catch (err: Exception) {
            consecutiveVideoDecodeFailures++
            sequenceTracker.awaitingIdr = true
            releaseVideoDecoderOnly()
            onSurfaceOrDecoderReset()
            val cfg = lastVideoConfig
            if (consecutiveVideoDecodeFailures >= 3 || cfg == null || configuredWidth <= 0 || configuredHeight <= 0 || outputSurface == null) {
                onDecoderError(err.localizedMessage ?: "تعذر فك ترميز بث الشاشة على هذا الجهاز.")
                return
            }
            runCatching {
                configureVideoDecoderLocked(cfg, configuredWidth, configuredHeight)
            }.onFailure { reinitErr ->
                onDecoderError(reinitErr.localizedMessage ?: "تعذر إعادة إنشاء مفكك ترميز الفيديو.")
            }
            onRequestKeyframe()
        }
    }

    private fun configureAudioDecoderLocked(asc: ByteArray) {
        releaseAudioOnly()
        val (sampleRate, channelCount) = parseAudioSpecificConfig(asc)
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
            ).coerceAtLeast(4096)
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
            releaseVideoDecoderOnly()
            releaseAudioOnly()
            runCatching { renderCallbackThread.quitSafely() }
        }
    }
}
