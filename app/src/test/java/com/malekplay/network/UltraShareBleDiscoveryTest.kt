package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UltraShareBleDiscoveryTest {
    @Test
    fun mergePeersDeduplicatesAddressesCaseInsensitively() {
        val first = UltraShareBleDiscovery.Peer("Phone", "aa:bb:cc", 2)
        val update = UltraShareBleDiscovery.Peer("MalekPlay", "AA:BB:CC", 2)
        val merged = UltraShareBleDiscovery.mergePeers(listOf(first), update)
        assertEquals(1, merged.size)
        assertEquals("MalekPlay", merged.single().name)
        assertEquals("AA:BB:CC", merged.single().address)
    }

    @Test
    fun mergePeersIgnoresBlankAddressesAndKeepsDistinctPeers() {
        val first = UltraShareBleDiscovery.Peer("A", "11:22", 2)
        val ignored = UltraShareBleDiscovery.Peer("Blank", "  ", 2)
        val second = UltraShareBleDiscovery.Peer("B", "33:44", 2)
        val merged = UltraShareBleDiscovery.mergePeers(listOf(first), ignored)
        assertEquals(1, merged.size)
        assertEquals(2, UltraShareBleDiscovery.mergePeers(merged, second).size)
    }
}
