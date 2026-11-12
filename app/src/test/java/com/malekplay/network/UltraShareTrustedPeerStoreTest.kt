package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareTrustedPeerStoreTest {
    @Test
    fun stablePeerIdentityMustBeExactlyThirtyTwoHexCharacters() {
        assertTrue(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef0123456789abcdef"))
        assertTrue(UltraShareTrustedPeerStore.isValidPeerId("ABCDEF0123456789ABCDEF0123456789"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId(""))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef0123456789abcdefg"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef0123456789abcde!"))
    }
}
