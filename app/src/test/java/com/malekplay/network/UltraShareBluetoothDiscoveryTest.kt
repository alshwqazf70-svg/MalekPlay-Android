package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UltraShareBluetoothDiscoveryTest {
    @Test
    fun mergePeersReplacesDuplicateAddressWithoutDuplicatingDevice() {
        val old = UltraShareBluetoothDiscovery.Peer("Old name", "aa:bb:cc:dd:ee:ff", false, 1)
        val updated = UltraShareBluetoothDiscovery.Peer("Updated name", "AA:BB:CC:DD:EE:FF", true, 1)

        val merged = UltraShareBluetoothDiscovery.mergePeers(listOf(old), updated)

        assertEquals(1, merged.size)
        assertEquals("Updated name", merged.single().name)
        assertEquals("AA:BB:CC:DD:EE:FF", merged.single().address)
        assertEquals(true, merged.single().bonded)
    }

    @Test
    fun mergePeersIgnoresBlankAddressAndKeepsExistingEntries() {
        val old = UltraShareBluetoothDiscovery.Peer("Known device", "11:22:33:44:55:66", true, 1)
        val result = UltraShareBluetoothDiscovery.mergePeers(listOf(old), old.copy(address = "  "))

        assertSame(old, result.single())
    }

    @Test
    fun mergePeersPrioritizesBondedDevices() {
        val nearby = UltraShareBluetoothDiscovery.Peer("Nearby", "00:00:00:00:00:02", false, 1)
        val bonded = UltraShareBluetoothDiscovery.Peer("Bonded", "00:00:00:00:00:01", true, 1)

        val result = UltraShareBluetoothDiscovery.mergePeers(
            UltraShareBluetoothDiscovery.mergePeers(emptyList(), nearby),
            bonded
        )

        assertEquals(listOf("Bonded", "Nearby"), result.map { it.name })
    }
}
