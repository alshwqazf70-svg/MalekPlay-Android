package com.malekplay.player.effects

import android.content.Context
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.Status
import com.google.android.gms.media.effect.enhancement.Enhancement
import com.google.android.gms.media.effect.enhancement.EnhancementCallback
import com.google.android.gms.media.effect.enhancement.EnhancementMode
import com.google.android.gms.media.effect.enhancement.EnhancementOptions
import com.google.android.gms.media.effect.enhancement.EnhancementSession
import com.google.android.gms.media.effect.enhancement.EnhancementSessionCallback
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one Google Media Enhancement surface session.
 *
 * The caller remains responsible for routing the player's decoded output to [inputSurface]
 * and restoring its normal output surface before [close]. This class never changes a Player,
 * View, or subtitle renderer itself.
 *
 * A session is created only after the device is supported and the optional model module is
 * already installed. Model installation is intentionally left to an explicit user action.
 */
@RequiresApi(Build.VERSION_CODES.R)
class GoogleMediaEnhancementSurfaceBackend(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val outputSurface: Surface,
    private val upscaleVideo: Boolean,
    private val denoiseVideo: Boolean,
    private val toneMap: Boolean,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onReady(inputSurface: Surface)
        fun onFirstFrameProcessed(timestampNs: Long)
        fun onModuleInstallRequired()
        fun onUnsupported()
        fun onFailure(reason: String)
    }

    private val appContext = context.applicationContext
    private val executor: Executor = ContextCompat.getMainExecutor(appContext)
    private val client = Enhancement.getClient(appContext)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    @Volatile private var session: EnhancementSession? = null
    @Volatile private var firstFrameReported = false

    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
        require(outputSurface.isValid) { "Output surface is not valid" }
        require(upscaleVideo || denoiseVideo || toneMap) {
            "At least one video enhancement must be enabled"
        }
    }

    fun start() {
        if (closed.get()) return
        val request = generation.incrementAndGet()

        client.isDeviceSupported()
            .addOnCompleteListener(executor) { supportTask ->
                if (!isCurrent(request)) return@addOnCompleteListener
                if (!supportTask.isSuccessful) {
                    fail(request, "Google AI capability check failed")
                    return@addOnCompleteListener
                }
                if (supportTask.result != true) {
                    listener.onUnsupported()
                    return@addOnCompleteListener
                }
                checkModule(request)
            }
    }

    private fun checkModule(request: Long) {
        client.isModuleInstalled()
            .addOnCompleteListener(executor) { moduleTask ->
                if (!isCurrent(request)) return@addOnCompleteListener
                if (!moduleTask.isSuccessful) {
                    fail(request, "Google AI model availability check failed")
                } else if (moduleTask.result != true) {
                    listener.onModuleInstallRequired()
                } else {
                    createSession(request)
                }
            }
    }

    private fun createSession(request: Long) {
        val options = EnhancementOptions(
            width,
            height,
            EnhancementMode.SURFACE,
            toneMap,
            false,
            denoiseVideo,
            false,
            upscaleVideo
        )
        try {
            client.createSession(options, object : EnhancementSessionCallback {
                override fun onSessionCreated(created: EnhancementSession) {
                    if (!isCurrent(request)) {
                        runCatching { created.release() }
                        return
                    }
                    session = created
                    try {
                        created.setOutputSurface(
                            outputSurface,
                            options,
                            object : EnhancementCallback {
                                override fun onBitmapProcessed(bitmap: android.graphics.Bitmap) = Unit

                                override fun onSurfaceProcessed(timestamp: Long) {
                                    if (!isCurrent(request)) return
                                    if (!firstFrameReported) {
                                        firstFrameReported = true
                                        listener.onFirstFrameProcessed(timestamp)
                                    }
                                }

                                override fun onError(statusCode: Int) {
                                    fail(request, "Google AI frame processing failed (status $statusCode)")
                                }
                            }
                        )
                        listener.onReady(created.inputSurface)
                    } catch (error: Throwable) {
                        fail(request, error.message ?: "Could not connect Google AI output surface")
                    }
                }

                override fun onSessionCreationFailed(status: Status) {
                    fail(request, "Google AI session creation failed: ${status.statusCode}")
                }

                override fun onSessionDestroyed() {
                    if (isCurrent(request) && !closed.get()) {
                        fail(request, "Google AI session was destroyed")
                    }
                }

                override fun onSessionDisconnected(status: Status) {
                    fail(request, "Google AI session disconnected: ${status.statusCode}")
                }
            }).addOnFailureListener(executor) { error ->
                fail(request, error.message ?: "Google AI session request failed")
            }
        } catch (error: Throwable) {
            fail(request, error.message ?: "Google AI session initialization failed")
        }
    }

    private fun isCurrent(request: Long): Boolean =
        !closed.get() && generation.get() == request

    private fun fail(request: Long, reason: String) {
        if (!isCurrent(request)) return
        generation.incrementAndGet()
        releaseSession()
        listener.onFailure(reason)
    }

    private fun releaseSession() {
        val active = session
        session = null
        if (active != null) runCatching { active.release() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        releaseSession()
    }
}
