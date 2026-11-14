package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareDFileSessionGuardTest {
    @Test fun acceptsOnlyCallbacksFromTheCurrentPositiveSession() {
        assertTrue(UltraShareDFileSessionGuard.isCurrentSession(17, 17))
        assertFalse(UltraShareDFileSessionGuard.isCurrentSession(null, 17))
        assertFalse(UltraShareDFileSessionGuard.isCurrentSession(-1, 17))
        assertFalse(UltraShareDFileSessionGuard.isCurrentSession(18, 17))
        assertFalse(UltraShareDFileSessionGuard.isCurrentSession(17, -1))
    }
}
