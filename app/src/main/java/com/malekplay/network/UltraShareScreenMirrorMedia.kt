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

        val config = startVideoPipeline()
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

    private fun selectH264Profile(
        sourceW: Int,
        sourceH: Int
    ): Triple<String?, Pair<Int, Int>, Pair<Int, Int>> {
        val memMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.memoryClass ?: 256
        val baseProfile = UltraShareScreenMirrorPolicy.selectCaptureProfile(
            memoryClassMb = memMb,
            activeViewers = hub.activeViewerCount().coerceAtLeast(1)
        )
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var chosenName: String? = null
        var widthAlign = 16
        var heightAlign = 16
        var maxFps = baseProfile.fps
        var maxBitrate = baseProfile.bitrateBps

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
            chosenName = info.name
            widthAlign = videoCaps.widthAlignment.coerceAtLeast(2)
            heightAlign = videoCaps.heightAlignment.coerceAtLeast(2)
            val candidateSize = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
                sourceWidth = sourceW,
                sourceHeight = sourceH,
                maxLongEdge = baseProfile.maxWidth,
                maxShortEdge = baseProfile.maxHeight,
                widthAlignment = widthAlign,
                heightAlignment = heightAlign
            )
            if (videoCaps.isSizeSupported(candidateSize.first, candidateSize.second)) {
                val fpsUpper = videoCaps.supportedFrameRates.upper.toInt().coerceAtLeast(15)
                val brUpper = videoCaps.bitrateRange.upper.coerceAtLeast(500_000)
                maxFps = minOf(baseProfile.fps, fpsUpper)
                maxBitrate = minOf(baseProfile.bitrateBps, brUpper)
            }
            if (!isSoftwareOnly) break // Prefer hardware encoder over software fallback
        }

        val finalSize = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            maxLongEdge = baseProfile.maxWidth,
            maxShortEdge = baseProfile.maxHeight,
            widthAlignment = widthAlign,
            heightAlignment = heightAlign
        )
        return Triple(chosenName, finalSize, maxFps to maxBitrate)
    }

    private fun startVideoPipeline(): UltraShareScreenMirrorProtocol.StreamConfig {
        synchronized(encoderRestartLock) {
            val (rawW, rawH, dpi) = readDisplayMetrics()
            val (codecName, targetSize, fpsAndBitrate) = selectH264Profile(rawW, rawH)
            val width = targetSize.first
            val height = targetSize.second
            val fps = fpsAndBitrate.first
            val bitrate = fpsAndBitrate.second

            activeWidth = width
            activeHeight = height
            activeFps = fps
            activeBitrate = bitrate

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
                }
            }

            val codec = if (codecName != null) {
                runCatching { MediaCodec.createByCodecName(codecName) }
                    .getOrElse { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            } else {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

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
            releaseVideoEncoderOnly()
            val updated = startVideoPipeline().copy(audioEnabled = activeAudioRunning)
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

        val encFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aac.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aac.start()
        record.startRecording()

        audioRecord = record
        audioEncoder = aac

        audioPumpThread = Thread({
            val pcm = ByteArray(4096)
            val outInfo = MediaCodec.BufferInfo()
            while (!stopped.get() && audioEncoder === aac) {
                val readBytes = record.read(pcm, 0, pcm.size)
                if (readBytes > 0) {
                    val inIdx = runCatching { aac.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
                    if (inIdx >= 0) {
                        val inBuf = aac.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val count = minOf(readBytes, inBuf.remaining())
                            inBuf.put(pcm, 0, count)
                            val ptsUs = System.nanoTime() / 1_000L
                            aac.queueInputBuffer(inIdx, 0, count, ptsUs, 0)
                        }
                    }
                }
                while (!stopped.get()) {
                    val outIdx = runCatching { aac.dequeueOutputBuffer(outInfo, 0L) }.getOrDefault(-1)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val csd = runCatching { aac.outputFormat.getByteBuffer("csd-0") }.getOrNull()
                        if (csd != null) {
                            val asc = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                            if (asc.isNotEmpty()) hub.publishAudioConfig(asc)
                        }
                    } else if (outIdx >= 0) {
                        try {
                            val outBuf = aac.getOutputBuffer(outIdx)
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
                            runCatching { aac.releaseOutputBuffer(outIdx, false) }
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
    private var awaitingIdr = true
    private var lastMediaSeq = -1L

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
                awaitingIdr = true
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
                    val gapDetected = lastMediaSeq >= 0L && frame.header.mediaSeq != lastMediaSeq + 1L
                    lastMediaSeq = frame.header.mediaSeq
                    if (gapDetected && !isIdr) {
                        awaitingIdr = true
                        onRequestKeyframe()
                        return
                    }
                    if (awaitingIdr && !isIdr) {
                        return
                    }
                    if (isIdr) awaitingIdr = false

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
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, surface, null, 0)
        codec.start()
        videoDecoder = codec
        awaitingIdr = true
    }

    private fun feedVideoBufferLocked(
        codec: MediaCodec,
        bytes: ByteArray,
        ptsUs: Long,
        isIdr: Boolean
    ) {
        try {
            val inIdx = codec.dequeueInputBuffer(15_000L)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)
                if (buf != null) {
                    buf.clear()
                    val count = minOf(bytes.size, buf.remaining())
                    buf.put(bytes, 0, count)
                    val flags = if (isIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(inIdx, 0, count, ptsUs, flags)
                }
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 0L)
                if (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, outputSurface != null)
                } else break
            }
        } catch (err: Exception) {
            awaitingIdr = true
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
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, 0)
        codec.start()

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        val track = AudioTrack.Builder()
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
    }

    private fun feedAudioBufferLocked(codec: MediaCodec, bytes: ByteArray, ptsUs: Long) {
        runCatching {
            val inIdx = codec.dequeueInputBuffer(5_000L)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    val count = minOf(bytes.size, inBuf.remaining())
                    inBuf.put(bytes, 0, count)
                    codec.queueInputBuffer(inIdx, 0, count, ptsUs, 0)
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
