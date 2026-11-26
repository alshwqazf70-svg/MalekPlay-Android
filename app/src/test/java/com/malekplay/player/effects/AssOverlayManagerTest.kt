package com.malekplay.player.effects

import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.render.AssOverlayManager
import io.github.peerless2012.ass.media.render.AssVideoEffectRegistry
import io.github.peerless2012.ass.media.type.AssRenderType
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssOverlayManagerTest {

    @Test
    fun startsWithEmptyVideoEffectsAndAttachesOnlyWhenManuallyEnabled() {
        val effectCalls = mutableListOf<List<Effect>>()
        val lifecycleCalls = mutableListOf<String>()
        var playbackState = Player.STATE_READY
        var playWhenReady = true
        val mediaItemCount = 1
        val currentMediaItemIndex = 0
        val currentPosition = 12_345L

        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "getPlaybackState" -> playbackState
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> {
                    playWhenReady = args?.get(0) as? Boolean ?: false
                    lifecycleCalls += "setPlayWhenReady($playWhenReady)"
                    null
                }
                "getMediaItemCount" -> mediaItemCount
                "getCurrentMediaItemIndex" -> currentMediaItemIndex
                "getCurrentPosition" -> currentPosition
                "setVideoEffects" -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = (args?.get(0) as? List<Effect>).orEmpty()
                    effectCalls += list.toList()
                    lifecycleCalls += "setVideoEffects(${list.size})"
                    null
                }
                "stop" -> {
                    playbackState = Player.STATE_IDLE
                    lifecycleCalls += "stop"
                    null
                }
                "seekTo" -> {
                    lifecycleCalls += "seekTo"
                    null
                }
                "prepare" -> {
                    playbackState = Player.STATE_READY
                    lifecycleCalls += "prepare"
                    null
                }
                "equals" -> false
                "hashCode" -> 0
                "toString" -> "FakeExoPlayer"
                else -> defaultReturn(method.returnType)
            }
        } as ExoPlayer

        val manager = AssOverlayManager(
            handler = AssHandler(AssRenderType.EFFECTS_OPEN_GL),
            player = player,
            tex = true
        )

        // Default startup must keep video effects completely empty (no GPU shaders).
        assertEquals(1, effectCalls.size)
        assertTrue(effectCalls.first().isEmpty())
        assertTrue(AssVideoEffectRegistry.isAttached(player))

        // Manual enable installs the pipeline and rebinds cleanly at the current position.
        val pipeline = VideoEnhancementPipelineEffect()
        assertTrue(manager.setAdditionalEffects(listOf(pipeline)))
        assertEquals(listOf(pipeline), effectCalls.last())
        assertTrue(lifecycleCalls.contains("stop"))
        assertTrue(lifecycleCalls.contains("seekTo"))
        assertTrue(lifecycleCalls.contains("prepare"))

        // Manual disable removes all GPU stages and restores empty effects.
        assertTrue(manager.setAdditionalEffects(emptyList()))
        assertTrue(effectCalls.last().isEmpty())

        AssVideoEffectRegistry.detach(player)
    }

    private fun defaultReturn(returnType: Class<*>): Any? = when (returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }
}
