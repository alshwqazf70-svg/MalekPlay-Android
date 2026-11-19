package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Serializes native libass rendering and keeps only the latest completed frame.
 */
class AssExecutor(private val render: AssRender) {

    private val assFrameNotChange = AssFrame(null, 0)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastFrame: AssFrame? = null

    @Volatile
    private var executorBusy = false

    @Volatile
    private var released = false

    private val submitLock = Any()

    fun renderFrame(presentationTimeUs: Long, type: AssTexType): AssFrame? {
        if (released) return null
        val future = synchronized(submitLock) {
            if (released) return null
            if (executorBusy) return lastFrame ?: assFrameNotChange
            executorBusy = true
            try {
                executor.submit<AssFrame?> {
                    try {
                        render.renderFrame(presentationTimeUs / 1000, type).also {
                            if (!released) lastFrame = it
                        }
                    } finally {
                        executorBusy = false
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                executorBusy = false
                return lastFrame ?: assFrameNotChange
            }
        }

        return try {
            future.get(8, TimeUnit.MILLISECONDS)
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            lastFrame ?: assFrameNotChange
        }
    }

    fun asyncRenderFrame(
        presentationTimeUs: Long,
        type: AssTexType,
        callback: (AssFrame?) -> Unit
    ) {
        var callbackImmediately = false
        var immediateResult: AssFrame? = null

        synchronized(submitLock) {
            when {
                released -> {
                    callbackImmediately = true
                    immediateResult = null
                }
                executorBusy -> {
                    callbackImmediately = true
                    immediateResult = lastFrame ?: assFrameNotChange
                }
                else -> {
                    executorBusy = true
                    try {
                        executor.execute {
                            val result = try {
                                render.renderFrame(presentationTimeUs / 1000, type).also {
                                    if (!released) lastFrame = it
                                }
                            } catch (_: Exception) {
                                lastFrame
                            } finally {
                                executorBusy = false
                            }
                            runCatching { callback.invoke(if (released) null else result) }
                        }
                    } catch (_: RejectedExecutionException) {
                        executorBusy = false
                        callbackImmediately = true
                        immediateResult = lastFrame ?: assFrameNotChange
                    }
                }
            }
        }

        // Never invoke client code while holding submitLock; callbacks may re-enter this executor.
        if (callbackImmediately) {
            runCatching { callback.invoke(immediateResult) }
        }
    }

    fun shutdown() {
        synchronized(submitLock) {
            if (released) return
            released = true
            lastFrame = null
            executor.shutdownNow()
        }
    }
}
