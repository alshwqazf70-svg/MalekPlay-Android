package com.malekplay.player.effects

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import java.util.concurrent.atomic.AtomicLong

/**
 * Routes a local Media3 player's decoded frames through Google's enhancement surface.
 *
 * PlayerView continues to own the visible SurfaceView and its subtitle overlay. Only the
 * player's video output is temporarily redirected to the enhancement input Surface; the
 * enhancement session writes its output into the original SurfaceView surface.
 *
 * This bridge is deliberately limited to clear, local, unrotated video on a local playback
 * route. It never runs beside the existing OpenGL enhancement chain.
 */
enum class GoogleMediaEnhancementState {
    DISABLED,
    WAITING_FOR_VIDEO,
    CHECKING_DEVICE,
    INSTALL_REQUIRED,
    INSTALLING,
    WAITING_FOR_FIRST_FRAME,
    ACTIVE,
    UNSUPPORTED,
    FAILED
}

@RequiresApi(Build.VERSION_CODES.R)
class GoogleMediaEnhancementPlayerBridge(
    context: Context,
    private val player: Player,
    private val surfaceView: SurfaceView,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onStateChanged(state: GoogleMediaEnhancementState, progress: Int = 0, message: String? = null)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private var enabled = false
    private var closed = false
    private var backend: GoogleMediaEnhancementSurfaceBackend? = null
    private var originalSurface: Surface? = null
    private var routedInputSurface: Surface? = null
    private var activeItemKey: String? = null
    private var activeWidth = 0
    private var activeHeight = 0
    private var activeRotationDegrees = 0
    private var activePixelWidthHeightRatio = 1f
    private var retryBlocked = false

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            // A newly created output surface is a fresh opportunity after a prior
            // surface/session failure; do not leave the bridge permanently retry-blocked.
            retryBlocked = false
            scheduleStart()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            val current = holder.surface
            if (backend != null && originalSurface !== current) {
                // A recreated output surface invalidates the old Google session.
                retryBlocked = false
                releaseBackend(restoreOutput = false)
                scheduleStart()
                return
            }
            // PlayerView may re-attach its holder during a size/format callback.
            // Re-assert the enhancement input after PlayerView's own callback runs.
            val input = routedInputSurface
            if (input != null && current.isValid) {
                mainHandler.post {
                    if (!closed && enabled && routedInputSurface === input && input.isValid &&
                        player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)
                    ) {
                        runCatching { player.setVideoSurface(input) }
                            .onFailure { publish(GoogleMediaEnhancementState.FAILED, message = it.message) }
                    }
                }
            } else {
                scheduleStart()
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            releaseBackend(restoreOutput = false)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            retryBlocked = false
            releaseBackend(restoreOutput = true)
            scheduleStart()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val hasSelectedVideo = tracks.groups.any {
                it.type == C.TRACK_TYPE_VIDEO && it.isSelected
            }
            if (!hasSelectedVideo && backend != null) {
                releaseBackend(restoreOutput = true)
                publish(GoogleMediaEnhancementState.WAITING_FOR_VIDEO)
                return
            }
            retryBlocked = false
            scheduleStart()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            retryBlocked = false
            if (backend != null && (
                    videoSize.width != activeWidth ||
                        videoSize.height != activeHeight ||
                        videoSize.unappliedRotationDegrees != activeRotationDegrees ||
                        kotlin.math.abs(
                            videoSize.pixelWidthHeightRatio - activePixelWidthHeightRatio
                        ) > 0.001f
                    )
            ) {
                // Google sessions are dimensioned at creation and do not support a
                // changed rotation or pixel aspect ratio; rebuild instead of stretching.
                releaseBackend(restoreOutput = true)
            }
            scheduleStart()
        }

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                retryBlocked = true
                releaseBackend(restoreOutput = true)
                publish(GoogleMediaEnhancementState.UNSUPPORTED, message = "AI enhancement is unavailable during remote playback")
            } else {
                retryBlocked = false
                scheduleStart()
            }
        }
    }

    init {
        player.addListener(playerListener)
        surfaceView.holder.addCallback(surfaceCallback)
    }

    fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        retryBlocked = false
        if (value) {
            publish(GoogleMediaEnhancementState.WAITING_FOR_VIDEO)
            scheduleStart()
        } else {
            releaseBackend(restoreOutput = true)
            publish(GoogleMediaEnhancementState.DISABLED)
        }
    }

    /** Call only after the user explicitly approves the additional Google model download. */
    fun installModule() {
        if (closed || !enabled) return
        val activeBackend = backend
        if (activeBackend == null) {
            scheduleStart()
            return
        }
        publish(GoogleMediaEnhancementState.INSTALLING, 0)
        activeBackend.installModule()
    }

    private fun scheduleStart() {
        if (!closed) mainHandler.post { startIfReady() }
    }

    private fun startIfReady() {
        if (closed || !enabled || retryBlocked || backend != null) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            retryBlocked = true
            publish(GoogleMediaEnhancementState.UNSUPPORTED, message = "Google AI enhancement requires Android 11 or newer")
            return
        }
        if (!player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
            retryBlocked = true
            publish(GoogleMediaEnhancementState.UNSUPPORTED, message = "The current player does not expose video surface routing")
            return
        }
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            retryBlocked = true
            publish(GoogleMediaEnhancementState.UNSUPPORTED, message = "AI enhancement is available only for local playback")
            return
        }

        val localConfiguration = player.currentMediaItem?.localConfiguration
        val uri = localConfiguration?.uri
        if (localConfiguration == null || uri == null ||
            (uri.scheme != "file" && uri.scheme != "content") ||
            localConfiguration.drmConfiguration != null
        ) {
            retryBlocked = true
            publish(GoogleMediaEnhancementState.UNSUPPORTED, message = "AI enhancement requires a clear local video")
            return
        }

        val selectedVideo = player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO && group.isSelected
        }
        val videoSize = player.videoSize
        if (!selectedVideo || videoSize.width <= 0 || videoSize.height <= 0) {
            publish(GoogleMediaEnhancementState.WAITING_FOR_VIDEO)
            return
        }
        if (videoSize.unappliedRotationDegrees != 0 ||
            kotlin.math.abs(videoSize.pixelWidthHeightRatio - 1f) > 0.001f
        ) {
            retryBlocked = true
            publish(GoogleMediaEnhancementState.UNSUPPORTED, message = "This video requires rotation or pixel-aspect correction")
            return
        }

        val output = surfaceView.holder.surface
        if (!output.isValid) {
            publish(GoogleMediaEnhancementState.WAITING_FOR_VIDEO)
            return
        }

        val itemKey = uri.toString()
        val request = generation.incrementAndGet()
        activeItemKey = itemKey
        activeWidth = videoSize.width
        activeHeight = videoSize.height
        activeRotationDegrees = videoSize.unappliedRotationDegrees
        activePixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
        originalSurface = output
        publish(GoogleMediaEnhancementState.CHECKING_DEVICE)

        val created = try {
            GoogleMediaEnhancementSurfaceBackend(
                context = appContext,
                width = videoSize.width,
                height = videoSize.height,
                outputSurface = output,
                upscaleVideo = true,
                denoiseVideo = true,
                toneMap = false,
                listener = object : GoogleMediaEnhancementSurfaceBackend.Listener {
                    override fun onReady(inputSurface: Surface) {
                        mainHandler.post {
                            if (!isCurrent(request)) return@post
                            if (!output.isValid) {
                                fail(request, "The original video output surface was destroyed")
                                return@post
                            }
                            if (!inputSurface.isValid) {
                                fail(request, "Google AI returned an invalid input surface")
                                return@post
                            }
                            if (!player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                                fail(request, "The player no longer allows video surface routing")
                                return@post
                            }
                            // Register the routed surface before changing the player so
                            // every partial failure can restore the original output surface.
                            routedInputSurface = inputSurface
                            try {
                                player.setVideoSurface(inputSurface)
                                publish(GoogleMediaEnhancementState.WAITING_FOR_FIRST_FRAME)
                            } catch (error: Throwable) {
                                fail(request, error.message ?: "Could not route decoded video into Google AI")
                            }
                        }
                    }

                    override fun onFirstFrameProcessed(timestampNs: Long) {
                        mainHandler.post {
                            if (isCurrent(request)) publish(GoogleMediaEnhancementState.ACTIVE)
                        }
                    }

                    override fun onModuleInstallRequired() {
                        mainHandler.post {
                            if (isCurrent(request)) publish(GoogleMediaEnhancementState.INSTALL_REQUIRED)
                        }
                    }

                    override fun onModuleInstallProgress(progress: Int) {
                        mainHandler.post {
                            if (isCurrent(request)) publish(GoogleMediaEnhancementState.INSTALLING, progress)
                        }
                    }

                    override fun onModuleInstalled() {
                        mainHandler.post {
                            if (isCurrent(request)) {
                                publish(GoogleMediaEnhancementState.CHECKING_DEVICE)
                                backend?.start()
                            }
                        }
                    }

                    override fun onModuleInstallCancelled() {
                        mainHandler.post {
                            if (isCurrent(request)) publish(GoogleMediaEnhancementState.INSTALL_REQUIRED)
                        }
                    }

                    override fun onUnsupported() {
                        mainHandler.post {
                            if (isCurrent(request)) {
                                retryBlocked = true
                                releaseBackend(restoreOutput = true)
                                publish(GoogleMediaEnhancementState.UNSUPPORTED, message = "This device does not support Google AI enhancement")
                            }
                        }
                    }

                    override fun onFailure(reason: String) {
                        mainHandler.post { fail(request, reason) }
                    }
                }
            )
        } catch (error: Throwable) {
            retryBlocked = true
            publish(GoogleMediaEnhancementState.FAILED, message = error.message ?: "Could not initialize Google AI enhancement")
            return
        }
        if (!isCurrent(request)) {
            created.close()
            return
        }
        backend = created
        created.start()
    }

    private fun fail(request: Long, reason: String) {
        if (!isCurrent(request)) return
        retryBlocked = true
        releaseBackend(restoreOutput = true)
        publish(GoogleMediaEnhancementState.FAILED, message = reason)
    }

    private fun isCurrent(request: Long): Boolean {
        if (closed || !enabled || generation.get() != request) return false
        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        return activeItemKey == currentUri
    }

    private fun releaseBackend(restoreOutput: Boolean) {
        generation.incrementAndGet()
        val input = routedInputSurface
        val output = originalSurface
        if (input != null) {
            runCatching {
                if (restoreOutput && output?.isValid == true &&
                    player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)
                ) {
                    player.setVideoSurface(output)
                } else if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                    player.clearVideoSurface(input)
                }
            }
        }
        routedInputSurface = null
        originalSurface = null
        backend?.close()
        backend = null
        activeItemKey = null
        activeWidth = 0
        activeHeight = 0
        activeRotationDegrees = 0
        activePixelWidthHeightRatio = 1f
    }

    private fun publish(state: GoogleMediaEnhancementState, progress: Int = 0, message: String? = null) {
        if (!closed) listener.onStateChanged(state, progress.coerceIn(0, 100), message)
    }

    override fun close() {
        if (closed) return
        enabled = false
        releaseBackend(restoreOutput = true)
        closed = true
        player.removeListener(playerListener)
        surfaceView.holder.removeCallback(surfaceCallback)
    }
}
