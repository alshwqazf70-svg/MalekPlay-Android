package io.github.peerless2012.ass.media.render

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
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
    private var additionalEffects: List<Effect> = emptyList()
    // Track whether the player currently has a non-empty effect graph so idle
    // playback stays on the zero-copy hardware surface path without GPU shaders.
    private var videoGraphActive = false

    /** True only after setVideoEffects completed successfully on the player looper. */
    @Volatile
    var isOperational: Boolean = false
        private set

    @Volatile
    var lastApplyFailure: String? = null
        private set

    init {
        AssVideoEffectRegistry.attach(player, this)
        applyEffects(allowRebind = false)
    }

    /**
     * Applies synchronously on ExoPlayer's application looper. Returning success
     * while merely posting a task made the host report a connected bridge before
     * Media3 had actually accepted the effect list.
     */
    fun setAdditionalEffects(effects: List<Effect>, allowRebind: Boolean = true): Boolean {
        if (Looper.myLooper() != player.applicationLooper) {
            lastApplyFailure =
                "IllegalStateException: setAdditionalEffects must run on ExoPlayer applicationLooper"
            return false
        }
        val previous = additionalEffects
        additionalEffects = effects.toList()
        if (applyEffects(allowRebind = allowRebind)) return true

        // Keep the last known-good pipeline if Media3 rejects the new effect list,
        // but preserve the failed-attempt reason for the diagnostics screen.
        val failure = lastApplyFailure
        additionalEffects = previous
        applyEffects(allowRebind = false)
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
        applyEffects(allowRebind = true)
    }

    fun disable(allowRebind: Boolean = true) {
        if (currentRenderer == null && subtitleEffect == null) return
        currentRenderer = null
        subtitleEffect = null
        applyEffects(allowRebind = allowRebind)
    }

    /**
     * Detaches this manager from a player that is being released or rebound.
     */
    fun release(): Boolean {
        if (Looper.myLooper() != player.applicationLooper) {
            lastApplyFailure =
                "IllegalStateException: release must run on ExoPlayer applicationLooper"
            return false
        }
        currentRenderer = null
        subtitleEffect = null
        additionalEffects = emptyList()
        videoGraphActive = false
        return try {
            player.setVideoEffects(emptyList())
            isOperational = false
            lastApplyFailure = null
            true
        } catch (error: Exception) {
            isOperational = false
            lastApplyFailure = error.javaClass.simpleName + ": " + error.message.orEmpty().take(160)
            false
        }
    }

    private fun applyEffects(allowRebind: Boolean): Boolean {
        // Keep image-processing effects first and ASS subtitles last so subtitle
        // glyphs stay crisp and are not blurred or resampled by video filters.
        val desiredEffects = additionalEffects + listOfNotNull(subtitleEffect)
        val needsGraph = desiredEffects.isNotEmpty()
        val graphToggledDuringPlayback = (needsGraph != videoGraphActive) &&
            player.playbackState != Player.STATE_IDLE
        return try {
            if (allowRebind && graphToggledDuringPlayback) {
                val wasPlaying = player.playWhenReady
                val mediaIndex = player.currentMediaItemIndex
                val positionMs = player.currentPosition.coerceAtLeast(0L)
                val hasCurrentItem = player.mediaItemCount > 0 && mediaIndex != C.INDEX_UNSET
                player.stop()
                player.setVideoEffects(desiredEffects)
                if (hasCurrentItem) {
                    player.seekTo(mediaIndex, positionMs)
                    player.prepare()
                    player.playWhenReady = wasPlaying
                }
            } else {
                player.setVideoEffects(desiredEffects)
            }
            videoGraphActive = needsGraph
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
