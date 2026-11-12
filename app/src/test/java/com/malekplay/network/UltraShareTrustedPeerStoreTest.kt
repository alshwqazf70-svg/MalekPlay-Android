package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareTrustedPeerStoreTest {
    @Test
    fun stablePeerIdentityMustBeExactlyThirtyTwoHexCharacters() {
        assertTrue(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef0123456789abcdef"))
        assertTrue(UltraShareTrustedPeerStore.isValidPeerId("ABCDEF0123456789ABCDEF0123456789"))
        assertTrue(UltraShareTrustedPeerStore.isValidPeerId("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(UltraShareTrustedPeerStore.isValidPeerId("123E4567-E89B-12D3-A456-426614174000"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId(""))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("123e4567-e89b-12d3-a456-42661417400z"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("123e4567-e89b-12d3-a456-42661417400"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef0123456789abcdefg"))
        assertFalse(UltraShareTrustedPeerStore.isValidPeerId("0123456789abcdef0123456789abcde!"))
    }
}
