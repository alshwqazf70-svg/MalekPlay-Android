package com.malekplay.network

import java.net.InetAddress
import java.net.Inet4Address
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareNetworkRoutingTest {
    @Test
    fun matchesAddressWithinFullAndPartialIpv4Prefixes() {
        val network = InetAddress.getByName("192.168.49.1") as Inet4Address
        assertTrue(
            UltraShareNetworkRouting.isInPrefix(
                InetAddress.getByName("192.168.49.200") as Inet4Address,
                network,
                24
            )
        )
        assertTrue(
            UltraShareNetworkRouting.isInPrefix(
                InetAddress.getByName("192.168.49.1") as Inet4Address,
                network,
                32
            )
        )
        assertFalse(
            UltraShareNetworkRouting.isInPrefix(
                InetAddress.getByName("192.168.50.2") as Inet4Address,
                network,
                24
            )
        )
    }

    @Test
    fun rejectsInvalidPrefixLengths() {
        val address = InetAddress.getByName("10.0.0.1") as Inet4Address
        assertFalse(UltraShareNetworkRouting.isInPrefix(address, address, -1))
        assertFalse(UltraShareNetworkRouting.isInPrefix(address, address, 33))
    }

    @Test
    fun identifiesAndroidWifiDirectInterfaceNames() {
        assertTrue(UltraShareNetworkRouting.isWifiDirectInterfaceName("p2p-wlan0-0"))
        assertTrue(UltraShareNetworkRouting.isWifiDirectInterfaceName("P2P0"))
        assertFalse(UltraShareNetworkRouting.isWifiDirectInterfaceName("wlan0"))
        assertFalse(UltraShareNetworkRouting.isWifiDirectInterfaceName("rmnet_data0"))
    }

    @Test
    fun recognizesAndroidWifiDirectGroupSubnet() {
        assertTrue(
            UltraShareNetworkRouting.isAndroidWifiDirectGroupAddress(
                InetAddress.getByName("192.168.49.1") as Inet4Address
            )
        )
        assertTrue(
            UltraShareNetworkRouting.isAndroidWifiDirectGroupAddress(
                InetAddress.getByName("192.168.49.23") as Inet4Address
            )
        )
        assertFalse(
            UltraShareNetworkRouting.isAndroidWifiDirectGroupAddress(
                InetAddress.getByName("192.168.50.1") as Inet4Address
            )
        )
    }

    @Test
    fun supportsZeroLengthPrefix() {
        val first = InetAddress.getByName("10.0.0.1") as Inet4Address
        val second = InetAddress.getByName("192.168.1.1") as Inet4Address
        assertTrue(UltraShareNetworkRouting.isInPrefix(first, second, 0))
    }
}
