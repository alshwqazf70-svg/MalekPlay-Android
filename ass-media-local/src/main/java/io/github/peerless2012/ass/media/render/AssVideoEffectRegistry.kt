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

    /** True only when a manager is registered and its latest setVideoEffects call succeeded. */
    @Synchronized
    fun isAttached(player: ExoPlayer): Boolean =
        managers[player]?.isOperational == true

    /** Most recent bridge application error, including a rejected replacement list. */
    @Synchronized
    fun lastFailure(player: ExoPlayer): String? =
        managers[player]?.lastApplyFailure

    /**
     * Returns false when no operational manager exists, the call is made off the
     * ExoPlayer application looper, or Media3 rejects the effect list.
     */
    @Synchronized
    fun setAdditionalEffects(player: ExoPlayer, effects: List<Effect>): Boolean {
        val manager = managers[player] ?: return false
        return manager.setAdditionalEffects(effects)
    }
}
