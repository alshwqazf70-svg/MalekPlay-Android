package io.github.peerless2012.ass.media.render

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.media.AssHandler

@OptIn(UnstableApi::class)
class AssOverlayManager(
    private val handler: AssHandler,
    private val player: ExoPlayer,
    private val tex: Boolean
) {
    private var currentRenderer: AssRender? = null
    private var subtitleEffect: OverlayEffect? = null
    // Media3 creates PlaybackVideoGraphWrapper/VideoSink only when a non-empty
    // video-effect chain is present before prepare(). Keep an identity GL effect
    // installed for the lifetime of the player so runtime effect updates never
    // have to create the video graph from an empty initial chain.
    private val alwaysReadyVideoEffect: Effect = ScaleAndRotateTransformation.Builder().build()
    private var additionalEffects: List<Effect> = emptyList()

    /** True only after setVideoEffects completed successfully on the player looper. */
    @Volatile
    var isOperational: Boolean = false
        private set

    @Volatile
    var lastApplyFailure: String? = null
        private set

    init {
        AssVideoEffectRegistry.attach(player, this)
        applyEffects()
    }

    /**
     * Applies synchronously on ExoPlayer's application looper. Returning success
     * while merely posting a task made the host report a connected bridge before
     * Media3 had actually accepted the effect list.
     */
    fun setAdditionalEffects(effects: List<Effect>): Boolean {
        if (Looper.myLooper() != player.applicationLooper) return false
        val previous = additionalEffects
        additionalEffects = effects.toList()
        if (applyEffects()) return true

        // Keep the last known-good pipeline if Media3 rejects the new effect list,
        // but preserve the failed-attempt reason for the diagnostics screen.
        val failure = lastApplyFailure
        additionalEffects = previous
        applyEffects()
        lastApplyFailure = failure
        return false
    }

    fun enable(renderer: AssRender) {
        if (renderer == currentRenderer) return
        currentRenderer = renderer
        val overlay = if (tex) {
            AssTexOverlay(handler, renderer)
        } else {
            AssCanvasOverlay(handler, renderer)
        }
        subtitleEffect = OverlayEffect(listOf(overlay))
        applyEffects()
    }

    fun disable() {
        if (currentRenderer == null && subtitleEffect == null) return
        currentRenderer = null
        subtitleEffect = null
        applyEffects()
    }

    private fun applyEffects(): Boolean {
        // Keep image-processing effects first and ASS subtitles last so subtitle
        // glyphs stay crisp and are not blurred or resampled by video filters.
        return try {
            player.setVideoEffects(listOf(alwaysReadyVideoEffect) + additionalEffects + listOfNotNull(subtitleEffect))
            isOperational = true
            lastApplyFailure = null
            true
        } catch (error: Exception) {
            isOperational = false
            lastApplyFailure = error.javaClass.simpleName + ": " + error.message.orEmpty().take(160)
            false
        }
    }
}
