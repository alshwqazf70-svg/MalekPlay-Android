package com.malekplay.network

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UltraShareTransferLocksTest {
    @Test
    fun finishingOneSendDoesNotReleaseLocksOwnedByAnotherPeer() {
        val locks = UltraShareTransferLocks(ApplicationProvider.getApplicationContext<Context>())
        locks.beginTransfer(isReceiving = false)
        locks.beginTransfer(isReceiving = false)

        assertTrue(locks.hasActiveTransfers())
        assertFalse(locks.endTransfer(isReceiving = false))
        assertTrue(locks.hasActiveTransfers())
        assertTrue(locks.endTransfer(isReceiving = false))
        assertFalse(locks.hasActiveTransfers())
    }

    @Test
    fun fullDuplexSendAndReceiveKeepLocksUntilBothDirectionsFinish() {
        val locks = UltraShareTransferLocks(ApplicationProvider.getApplicationContext<Context>())
        locks.beginTransfer(isReceiving = false)
        locks.beginTransfer(isReceiving = true)

        assertFalse(locks.endTransfer(isReceiving = false))
        assertTrue(locks.hasActiveTransfers())
        assertTrue(locks.endTransfer(isReceiving = true))
        assertFalse(locks.hasActiveTransfers())
    }
}
