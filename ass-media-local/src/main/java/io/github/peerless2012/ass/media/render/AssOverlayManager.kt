package io.github.peerless2012.ass.media.render

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
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

    init {
        AssVideoEffectRegistry.attach(player, this)
        applyEffects()
    }

    fun setAdditionalEffects(effects: List<Effect>) {
        if (Looper.myLooper() != player.applicationLooper) {
            Handler(player.applicationLooper).post { setAdditionalEffects(effects) }
            return
        }
        additionalEffects = effects.toList()
        applyEffects()
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

    private fun applyEffects() {
        // Keep image-processing effects first and ASS subtitles last so subtitle
        // glyphs stay crisp and are not blurred or resampled by video filters.
        player.setVideoEffects(additionalEffects + listOfNotNull(subtitleEffect))
    }
}
