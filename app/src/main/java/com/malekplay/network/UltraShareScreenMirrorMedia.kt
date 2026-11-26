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

/**
 * Real Android MediaProjection + VirtualDisplay + Hardware/Platform MediaCodec H.264
 * screen capturer with optional Android 10+ AudioPlaybackCapture AAC audio.
 */
internal class AndroidScreenMirrorCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val hub: ScreenMirrorBroadcasterHub,
    private val enableAudio: Boolean,
    private val onConfigChanged: (UltraShareScreenMirrorProtocol.StreamConfig) -> Unit,
    private val onProjectionStopped: (String?) -> Unit
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
    @Volatile private var activeRotation = 0
    @Volatile private var activeFps = UltraShareScreenMirrorPolicy.DEFAULT_FPS
    @Volatile private var activeBitrate = UltraShareScreenMirrorPolicy.DEFAULT_BITRATE_BPS
    @Volatile private var activeAudioRunning = false
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
        val audioOk = if (enableAudio) startAudioPipelineIfSupported() else false
        activeAudioRunning = audioOk

        val finalConfig = config.copy(audioEnabled = audioOk)
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

        val (rawW, rawH, _) = readDisplayMetrics()
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
            val updated = synchronized(encoderRestartLock) {
                if (stopped.get()) return
                runCatching { virtualDisplay?.surface = null }
                releaseVideoEncoderOnly()
                startVideoPipeline().copy(audioEnabled = activeAudioRunning)
            }
            currentConfig = updated
            onConfigChanged(updated)
        }
    }

    private fun startAudioPipelineIfSupported(): Boolean {
        if (!UltraShareScreenMirrorPolicy.hasAudioCapturePermission(context)) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        return runCatching {
            startAudioPipelineApi29()
        }.onFailure { failure ->
            UltraShareDiagnostics.failure("SCREEN_MIRROR_AUDIO_INIT", failure)
            releaseAudioOnly()
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAudioPipelineApi29(): Boolean {
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
            val pcm = ByteArray(4096)
            val outInfo = MediaCodec.BufferInfo()
            while (!stopped.get() && audioEncoder === activeAac) {
                val readBytes = record.read(pcm, 0, pcm.size)
                if (readBytes > 0) {
                    val inIdx = runCatching { activeAac.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
                    if (inIdx >= 0) {
                        val inBuf = activeAac.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val count = minOf(readBytes, inBuf.remaining())
                            inBuf.put(pcm, 0, count)
                            val ptsUs = System.nanoTime() / 1_000L
                            activeAac.queueInputBuffer(inIdx, 0, count, ptsUs, 0)
                        }
                    }
                }
                while (!stopped.get()) {
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
        }, "MalekMirrorAudioPump").apply {
            isDaemon = true
            start()
        }
        return true
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
    private val onDecoderError: (String) -> Unit
) {
    private val lock = Any()
    private var outputSurface: Surface? = null
    private var videoDecoder: MediaCodec? = null
    private var lastVideoConfig: ByteArray? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
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
            outputSurface = surface
            if (surface == null) {
                releaseVideoDecoderOnly()
                sequenceTracker.reset(awaitIdr = true)
            } else if (lastVideoConfig != null && configuredWidth > 0 && configuredHeight > 0) {
                runCatching {
                    configureVideoDecoderLocked(lastVideoConfig!!, configuredWidth, configuredHeight)
                }.onFailure { err ->
                    onDecoderError(err.localizedMessage ?: "تعذر تهيئة مفكك ترميز الفيديو.")
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
                    lastVideoConfig = frame.payload.copyOf()
                    configuredWidth = w
                    configuredHeight = h
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
        val surface = outputSurface ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            videoDecoder = codec
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
                            codec.releaseOutputBuffer(outIdx, renderToSurface)
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
            if (result != VideoInputFeedResult.FED && isIdr) {
                sequenceTracker.awaitingIdr = true
            }
        } catch (err: Exception) {
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
        val sampleRate = 48_000
        val channelCount = 2
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
                AudioFormat.CHANNEL_OUT_STEREO,
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
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
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
                            audioTrack?.write(pcm, 0, pcm.size)
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
        }
    }
}
