package com.malekplay.player.effects

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.render.AssVideoEffectRegistry
import io.github.peerless2012.ass.media.type.AssRenderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real ASS effect-manager registration on ExoPlayer's application
 * looper. This guards the player/manager lifecycle, not just the registry map.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class AssVideoEffectRegistryLifecycleInstrumentedTest {

    @Test
    fun releaseDetachesRegistryAndRemovesOwnedVideoEffects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: ExoPlayer
        lateinit var handler: AssHandler

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            handler = AssHandler(AssRenderType.EFFECTS_OPEN_GL)
            handler.init(player)
            assertTrue("ASS effect manager did not attach", AssVideoEffectRegistry.isAttached(player))
            handler.release()
            assertFalse("Released player retained a registered effect manager", AssVideoEffectRegistry.isAttached(player))
            player.release()
        }
    }

    @Test
    fun rebindingHandlerDetachesPreviousPlayerBeforeAttachingNext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var firstPlayer: ExoPlayer
        lateinit var secondPlayer: ExoPlayer
        lateinit var handler: AssHandler

        instrumentation.runOnMainSync {
            firstPlayer = ExoPlayer.Builder(instrumentation.targetContext).build()
            secondPlayer = ExoPlayer.Builder(instrumentation.targetContext).build()
            handler = AssHandler(AssRenderType.EFFECTS_OPEN_GL)
            handler.init(firstPlayer)
            assertTrue(AssVideoEffectRegistry.isAttached(firstPlayer))

            handler.init(secondPlayer)
            assertFalse("Old player remained registered after rebind", AssVideoEffectRegistry.isAttached(firstPlayer))
            assertTrue("New player was not registered after rebind", AssVideoEffectRegistry.isAttached(secondPlayer))

            handler.release()
            assertFalse(AssVideoEffectRegistry.isAttached(secondPlayer))
            firstPlayer.release()
            secondPlayer.release()
        }
    }
}
