package io.github.peerless2012.ass.media.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.WeakHashMap

/**
 * Lets the host app provide video effects without replacing the ASS subtitle overlay.
 * All mutations are applied by AssOverlayManager on ExoPlayer's application looper.
 */
@UnstableApi
object AssVideoEffectRegistry {
    private val managers = WeakHashMap<ExoPlayer, AssOverlayManager>()

    @Synchronized
    internal fun attach(player: ExoPlayer, manager: AssOverlayManager) {
        managers[player] = manager
    }

    @Synchronized
    fun detach(player: ExoPlayer) {
        managers.remove(player)
    }

    @Synchronized
    fun setAdditionalEffects(player: ExoPlayer, effects: List<Effect>) {
        managers[player]?.setAdditionalEffects(effects)
    }
}
