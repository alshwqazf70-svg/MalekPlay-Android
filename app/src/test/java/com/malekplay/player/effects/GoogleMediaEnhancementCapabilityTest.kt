package com.malekplay.player.effects

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class GoogleMediaEnhancementCapabilityTest {

    @Test
    @Config(sdk = [29])
    fun androidTenAndEarlierDoNotInitializeGoogleEnhancementClient() {
        var reported: GoogleMediaEnhancementCapability.Status? = null

        GoogleMediaEnhancementCapability.inspect(RuntimeEnvironment.getApplication()) {
            reported = it
        }

        assertEquals(
            GoogleMediaEnhancementCapability.Status.ANDROID_VERSION_UNSUPPORTED,
            reported
        )
    }
}
