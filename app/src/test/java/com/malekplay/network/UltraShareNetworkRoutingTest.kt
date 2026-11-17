package com.malekplay.network

import java.net.InetAddress
import java.net.Inet4Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareNetworkRoutingTest {
    @Test
    fun rejectsThisDevicesOwnAddressAsARemotePeer() {
        val localAddresses = setOf("192.168.43.163", "10.0.20.48")
        assertTrue(UltraShareNetworkRouting.isLocalIpv4Address("192.168.43.163", localAddresses))
        assertTrue(UltraShareNetworkRouting.isLocalIpv4Address("10.0.20.48", localAddresses))
        assertFalse(UltraShareNetworkRouting.isLocalIpv4Address("192.168.43.164", localAddresses))
    }

    @Test
    fun appendsDefaultRouteOnlyAfterAllExplicitNetworks() {
        assertEquals(
            listOf("p2p", "wlan", null),
            UltraShareNetworkRouting.orderedRoutesWithDefaultFallback(listOf("p2p", "wlan"))
        )
        assertEquals(
            listOf(null),
            UltraShareNetworkRouting.orderedRoutesWithDefaultFallback(emptyList<String>())
        )
    }

    @Test
    fun failedPrimaryRouteFallsThroughToNextWorkingNetworkOnly() {
        val attempted = mutableListOf<String>()
        val selected = UltraShareNetworkRouting.firstSuccessfulRoute(
            listOf("preferred-p2p", "secondary-wlan", "hotspot", "unused")
        ) { route ->
            attempted += route
            route == "hotspot"
        }

        assertEquals("hotspot", selected)
        assertEquals(listOf("preferred-p2p", "secondary-wlan", "hotspot"), attempted)
    }

    @Test
    fun returnsNullOnlyAfterEveryNetworkRouteFails() {
        val attempted = mutableListOf<String>()
        val selected = UltraShareNetworkRouting.firstSuccessfulRoute(
            listOf("p2p", "wlan", "hotspot")
        ) { route ->
            attempted += route
            false
        }

        assertEquals(null, selected)
        assertEquals(listOf("p2p", "wlan", "hotspot"), attempted)
    }

    @Test
    fun pinsQrWifiDirectInvitationToConfirmedGroupOwnerAddress() {
        assertEquals(
            listOf("192.168.49.1"),
            UltraShareNetworkRouting.invitationHostCandidates(
                confirmedHost = "192.168.49.1",
                advertisedHosts = listOf("192.168.43.1", "192.168.1.20"),
                pinToConfirmedHost = true
            )
        )
    }

    @Test
    fun permitsAdvertisedFallbackHostsOnlyOutsidePinnedP2pInvitation() {
        assertEquals(
            listOf("192.168.49.1", "192.168.43.1", "192.168.1.20"),
            UltraShareNetworkRouting.invitationHostCandidates(
                confirmedHost = "192.168.49.1",
                advertisedHosts = listOf("192.168.43.1", "192.168.1.20", "192.168.43.1"),
                pinToConfirmedHost = false
            )
        )
    }

    @Test
    fun preservesPreferredNetworkThenAttemptsEveryDistinctAlternative() {
        assertEquals(
            listOf("preferred-p2p", "wlan", "hotspot"),
            UltraShareNetworkRouting.orderedDistinctRoutes(
                "preferred-p2p",
                listOf("wlan", "preferred-p2p", "hotspot", "wlan")
            )
        )
        assertEquals(
            listOf("first", "second"),
            UltraShareNetworkRouting.orderedDistinctRoutes(null, listOf("first", "second", "first"))
        )
    }

    @Test
    fun attemptsEveryDistinctAndroidNetworkBeforeSystemDefaultFallback() {
        assertEquals(
            listOf("p2p", "wlan", "hotspot", null),
            UltraShareNetworkRouting.orderedRoutesWithDefaultFallback(
                listOf("p2p", "wlan", "p2p", "hotspot", "wlan")
            )
        )
        assertEquals(
            listOf(null),
            UltraShareNetworkRouting.orderedRoutesWithDefaultFallback(emptyList<String>())
        )
    }

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
    fun identifiesLocalOnlyHotspotInterfaceNameVariants() {
        assertTrue(UltraShareNetworkRouting.isHotspotInterfaceName("ap0"))
        assertTrue(UltraShareNetworkRouting.isHotspotInterfaceName("softap0"))
        assertTrue(UltraShareNetworkRouting.isHotspotInterfaceName("swlan0"))
        assertFalse(UltraShareNetworkRouting.isHotspotInterfaceName("p2p-wlan0-0"))
        assertFalse(UltraShareNetworkRouting.isHotspotInterfaceName("wlan0"))
    }

    @Test
    fun neverTreatsWifiDirectInterfaceAsOrdinaryLanFallback() {
        assertTrue(UltraShareNetworkRouting.isWifiDirectInterfaceName("p2p-wlan0-0"))
        assertFalse(UltraShareNetworkRouting.isWifiDirectInterfaceName("wlan0"))
        assertFalse(UltraShareNetworkRouting.isWifiDirectInterfaceName("ap0"))
    }

    @Test
    fun acceptsOnlyKnownNonP2pLanInterfaceFamiliesForQrFallback() {
        assertTrue(UltraShareNetworkRouting.isNonP2pLanInterfaceName("wlan0"))
        assertTrue(UltraShareNetworkRouting.isNonP2pLanInterfaceName("ap0"))
        assertTrue(UltraShareNetworkRouting.isNonP2pLanInterfaceName("eth0"))
        assertFalse(UltraShareNetworkRouting.isNonP2pLanInterfaceName("p2p-wlan0-0"))
        assertFalse(UltraShareNetworkRouting.isNonP2pLanInterfaceName("rmnet_data0"))
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
    @Test
    fun acceptsPrivatePeerAsP2pRouteOnlyOnP2pInterface() {
        val privatePeer = InetAddress.getByName("192.168.43.1") as Inet4Address
        val publicPeer = InetAddress.getByName("8.8.8.8") as Inet4Address
        assertTrue(UltraShareNetworkRouting.isP2pGroupOwnerRoute("p2p-wlan0-0", privatePeer))
        assertFalse(UltraShareNetworkRouting.isP2pGroupOwnerRoute("wlan0", privatePeer))
        assertFalse(UltraShareNetworkRouting.isP2pGroupOwnerRoute("p2p-wlan0-0", publicPeer))
    }

    @Test
    fun recognizesPrivateGroupOwnerAddressOnNonDefaultP2pSubnet() {
        assertTrue(
            UltraShareNetworkRouting.isPrivateIpv4Address(
                InetAddress.getByName("192.168.43.1") as Inet4Address
            )
        )
        assertTrue(
            UltraShareNetworkRouting.isPrivateIpv4Address(
                InetAddress.getByName("10.0.0.1") as Inet4Address
            )
        )
        assertFalse(
            UltraShareNetworkRouting.isPrivateIpv4Address(
                InetAddress.getByName("8.8.8.8") as Inet4Address
            )
        )
    }

    @Test
    fun labelsMissingAndUnknownP2pRadioStates() {
        assertEquals("MISSING", wifiP2pRadioStateName(Int.MIN_VALUE))
        assertEquals("UNKNOWN", wifiP2pRadioStateName(99))
        assertEquals("ENABLED", wifiP2pRadioStateName(2))
        assertEquals("DISABLED", wifiP2pRadioStateName(1))
    }

    @Test
    fun requiresLocationServicesForAndroid6Through12LWifiDirectDiscovery() {
        assertTrue(requiresWifiDirectLocationService(23))  // Android 6
        assertTrue(requiresWifiDirectLocationService(24))  // Android 7
        assertTrue(requiresWifiDirectLocationService(25))  // Android 7.1
        assertTrue(requiresWifiDirectLocationService(26))  // Android 8
        assertTrue(requiresWifiDirectLocationService(27))  // Android 8.1
        assertTrue(requiresWifiDirectLocationService(28))  // Android 9
        assertTrue(requiresWifiDirectLocationService(29))  // Android 10
        assertTrue(requiresWifiDirectLocationService(31))  // Android 12
        assertTrue(requiresWifiDirectLocationService(32))  // Android 12L
        assertFalse(requiresWifiDirectLocationService(33)) // Android 13: nearby Wi-Fi permission
        assertFalse(requiresWifiDirectLocationService(35)) // Android 15
        assertFalse(requiresWifiDirectLocationService(36)) // Android 16
    }

    @Test
    fun ownerPairingRequiresBothAndroidCallbacksAndExpectedClient() {
        assertTrue(
            isConfirmedWifiDirectOwnerPeer(
                groupFormed = true,
                connectionInfoSaysOwner = true,
                groupInfoSaysOwner = true,
                clientCount = 1,
                expectedClientPresent = true
            )
        )
        assertFalse(
            isConfirmedWifiDirectOwnerPeer(
                groupFormed = true,
                connectionInfoSaysOwner = true,
                groupInfoSaysOwner = false,
                clientCount = 1,
                expectedClientPresent = true
            )
        )
        assertFalse(
            isConfirmedWifiDirectOwnerPeer(
                groupFormed = true,
                connectionInfoSaysOwner = true,
                groupInfoSaysOwner = true,
                clientCount = 1,
                expectedClientPresent = false
            )
        )
        assertFalse(
            isConfirmedWifiDirectOwnerPeer(
                groupFormed = false,
                connectionInfoSaysOwner = true,
                groupInfoSaysOwner = true,
                clientCount = 1,
                expectedClientPresent = true
            )
        )
    }

    @Test
    fun rejectsConnectionInfoCallbacksFromOlderQueriesOrClosedSessions() {
        assertTrue(isCurrentWifiDirectInfoRequest(false, 12, 12))
        assertFalse(isCurrentWifiDirectInfoRequest(false, 13, 12))
        assertFalse(isCurrentWifiDirectInfoRequest(true, 12, 12))
    }

    @Test
    fun rejectsStaleWifiDirectConnectionCallbacks() {
        assertTrue(isCurrentWifiDirectConnectionAttempt(false, true, 7, 7))
        assertFalse(isCurrentWifiDirectConnectionAttempt(true, true, 7, 7))
        assertFalse(isCurrentWifiDirectConnectionAttempt(false, false, 7, 7))
        assertFalse(isCurrentWifiDirectConnectionAttempt(false, true, 8, 7))
    }

    @Test
    fun rejectsStaleWifiDirectGroupSetupCallbacks() {
        assertTrue(isCurrentWifiDirectGroupAttempt(false, 7, 7))
        assertFalse(isCurrentWifiDirectGroupAttempt(true, 7, 7))
        assertFalse(isCurrentWifiDirectGroupAttempt(false, 8, 7))
    }

}
