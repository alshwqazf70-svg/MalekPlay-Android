package com.malekplay.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParentalControlManagerTest {

    private lateinit var manager: ParentalControlManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("malekplay_family", Context.MODE_PRIVATE)
            .edit().clear().commit()
        manager = ParentalControlManager(context)
    }

    @Test
    fun approvedMediaListIsPersistedAndCopiedSafely() {
        manager.setAllowedMediaIds(setOf("video-1", "song-2", " ", "video-1", "x".repeat(513)))

        assertEquals(setOf("video-1", "song-2"), manager.allowedMediaIds)
        assertTrue(manager.isMediaAllowed("video-1"))
        assertFalse(manager.isMediaAllowed("not-approved"))

        val secondInstance = ParentalControlManager(context)
        assertEquals(setOf("video-1", "song-2"), secondInstance.allowedMediaIds)

        val mutableSnapshot = manager.allowedMediaIds.toMutableSet()
        mutableSnapshot.clear()
        assertEquals(setOf("video-1", "song-2"), manager.allowedMediaIds)
    }

    @Test
    fun emptyAllowlistFailsClosed() {
        assertTrue(manager.allowedMediaIds.isEmpty())
        assertFalse(manager.isMediaAllowed("any-media"))
        assertFalse(manager.isMediaAllowed(""))
        assertFalse(manager.isMediaAllowed("content://not-approved"))
    }

    @Test
    fun onlyVerifiedParentPinCanEnableOrDisableKidsMode() {
        manager.setPin("4826")

        assertFalse(manager.enableAfterPin("1111"))
        assertFalse(manager.isEnabled)
        assertTrue(manager.enableAfterPin("4826"))
        assertTrue(manager.isEnabled)
        assertFalse(manager.disableAfterPin("1111"))
        assertTrue(manager.isEnabled)
        assertTrue(manager.disableAfterPin("4826"))
        assertFalse(manager.isEnabled)
    }
    @Test
    fun repeatedWrongPinsTemporarilyBlockEvenTheCorrectPin() {
        manager.setPin("4826")

        repeat(5) {
            assertFalse(manager.verifyPin("1111"))
        }

        assertFalse(manager.verifyPin("4826"))
    }

}
