package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Executor to render.
 */
class AssExecutor(private val render: AssRender) {

    private val assFrameNotChange = AssFrame(null, 0)

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var lastFrame: AssFrame? = null

    @Volatile private var executorBusy = false

    private val submitLock = Any()

    public fun renderFrame(presentationTimeUs: Long, type: AssTexType): AssFrame? {
        val future = synchronized(submitLock) {
            if (executorBusy) return lastFrame ?: assFrameNotChange
            executorBusy = true
            executor.submit<AssFrame?> {
                try {
                    render.renderFrame(presentationTimeUs / 1000, type).also { lastFrame = it }
                } finally {
                    executorBusy = false
                }
            }
        }
        return try {
            future.get(8, TimeUnit.MILLISECONDS) ?: lastFrame
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            lastFrame ?: assFrameNotChange
        }
    }

    public fun asyncRenderFrame(presentationTimeUs: Long, type: AssTexType, callback: (AssFrame?) -> Unit) {
        synchronized(submitLock) {
            if (executorBusy) {
                callback.invoke(lastFrame ?: assFrameNotChange)
                return
            }
            executorBusy = true
            try {
                executor.execute {
                    val result = try {
                        render.renderFrame(presentationTimeUs / 1000, type).also { lastFrame = it }
                    } catch (exception: Exception) {
                        lastFrame
                    } finally {
                        executorBusy = false
                    }
                    runCatching { callback.invoke(result) }
                }
            } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                executorBusy = false
                callback.invoke(lastFrame ?: assFrameNotChange)
            }
        }
    }

    public fun shutdown() {
        executor.shutdownNow()
    }

}