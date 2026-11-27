package com.malekplay.network

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraSharePlatformCompatibilityTest {
    @Test fun androidSixThroughTwelveUseFineLocationRuntimePermission() {
        (23..32).forEach { sdk ->
            assertEquals(
                "android.permission.ACCESS_FINE_LOCATION",
                UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(sdk).single()
            )
            assertTrue(UltraSharePlatformCompatibility.requiresLocationServicesForWifiDirect(sdk))
            assertTrue(UltraSharePlatformCompatibility.supportsWifiDirectApiLevel(sdk))
        }
    }

    @Test fun androidThirteenAndNewerUseNearbyWifiPermissionWithoutLocationServiceGate() {
        (33..40).forEach { sdk ->
            assertEquals(
                "android.permission.NEARBY_WIFI_DEVICES",
                UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(sdk).single()
            )
            assertFalse(UltraSharePlatformCompatibility.requiresLocationServicesForWifiDirect(sdk))
            assertTrue(UltraSharePlatformCompatibility.supportsWifiDirectApiLevel(sdk))
        }
    }

    @Test fun everySupportedAndroidApiHasWifiDirectGroupCreationSupport() {
        (23..36).forEach { sdk ->
            assertTrue(
                "Wi-Fi Direct must remain available from API 23 through 36 (sdk=$sdk).",
                UltraSharePlatformCompatibility.supportsWifiDirectApiLevel(sdk)
            )
            assertTrue(
                "Group creation must not depend on Location Services (sdk=$sdk).",
                UltraSharePlatformCompatibility.canCreateWifiDirectGroup(sdk)
            )
        }
    }

    @Test fun legacyWifiManagerJoinIsLimitedToAndroidSixThroughNine() {
        (23..28).forEach { sdk ->
            assertTrue(UltraSharePlatformCompatibility.supportsLegacyWifiManagerJoin(sdk))
            assertFalse(UltraSharePlatformCompatibility.usesWifiNetworkSpecifier(sdk))
        }
        (29..36).forEach { sdk ->
            assertFalse(UltraSharePlatformCompatibility.supportsLegacyWifiManagerJoin(sdk))
            assertTrue(UltraSharePlatformCompatibility.usesWifiNetworkSpecifier(sdk))
        }
    }

    @Test fun legacyWifiJoinWaitsForTheExpectedSupplicantNetwork() {
        assertTrue(
            UltraSharePlatformCompatibility.isLegacyWifiNetworkConnected(
                expectedNetworkId = 7,
                actualNetworkId = 7,
                expectedSsid = "DIRECT-MalekPlay",
                actualSsid = "\"DIRECT-MalekPlay\"",
                supplicantCompleted = true
            )
        )
        assertTrue(
            UltraSharePlatformCompatibility.isLegacyWifiNetworkConnected(
                expectedNetworkId = 7,
                actualNetworkId = -1,
                expectedSsid = "DIRECT-MalekPlay",
                actualSsid = "DIRECT-MalekPlay",
                supplicantCompleted = true
            )
        )
        assertFalse(
            UltraSharePlatformCompatibility.isLegacyWifiNetworkConnected(
                expectedNetworkId = 7,
                actualNetworkId = 7,
                expectedSsid = "DIRECT-MalekPlay",
                actualSsid = "DIRECT-Other",
                supplicantCompleted = false
            )
        )
        assertFalse(
            UltraSharePlatformCompatibility.isLegacyWifiNetworkConnected(
                expectedNetworkId = 7,
                actualNetworkId = -1,
                expectedSsid = "DIRECT-MalekPlay",
                actualSsid = "<unknown ssid>",
                supplicantCompleted = true
            )
        )
    }

    @Test fun apiBelowAppMinimumIsNotSupported() {
        (1..22).forEach { sdk ->
            assertFalse(UltraSharePlatformCompatibility.supportsWifiDirectApiLevel(sdk))
        }
    }

    @Test fun legacyWifiJoinAcceptsUnknownSsidOnlyWhenVerifiedTargetSubnetRouteExists() {
        assertTrue(
            UltraSharePlatformCompatibility.isLegacyWifiNetworkConnected(
                expectedNetworkId = 7,
                actualNetworkId = -1,
                expectedSsid = "DIRECT-MalekPlay",
                actualSsid = "<unknown ssid>",
                supplicantCompleted = true,
                hasVerifiedTargetSubnetRoute = true
            )
        )
        assertFalse(
            UltraSharePlatformCompatibility.isLegacyWifiNetworkConnected(
                expectedNetworkId = 7,
                actualNetworkId = -1,
                expectedSsid = "DIRECT-MalekPlay",
                actualSsid = "<unknown ssid>",
                supplicantCompleted = true,
                hasVerifiedTargetSubnetRoute = false
            )
        )
    }

    @Test fun prerequisiteEvaluatorSeparatesPermissionFromLocationServiceAcrossAllSdks() {
        for (sdk in Build.VERSION_CODES.M..Build.VERSION_CODES.S_V2) {
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
                    sdkInt = sdk,
                    permissionGranted = false,
                    locationServiceEnabled = false
                )
            )
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
                    sdkInt = sdk,
                    permissionGranted = false,
                    locationServiceEnabled = true
                )
            )
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
                    sdkInt = sdk,
                    permissionGranted = true,
                    locationServiceEnabled = false
                )
            )
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
                    sdkInt = sdk,
                    permissionGranted = true,
                    locationServiceEnabled = true
                )
            )
        }

        for (sdk in Build.VERSION_CODES.TIRAMISU..36) {
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.NEARBY_PERMISSION_MISSING,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
                    sdkInt = sdk,
                    permissionGranted = false,
                    locationServiceEnabled = false
                )
            )
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
                    sdkInt = sdk,
                    permissionGranted = true,
                    locationServiceEnabled = false
                )
            )
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
                    sdkInt = sdk,
                    permissionGranted = true,
                    locationServiceEnabled = true
                )
            )
        }
    }

    @Test fun arabicMessagesMatchExactFailureCauseWithoutAndroid13MisattributionOnAndroid10() {
        val permMsg = UltraSharePlatformCompatibility.messageFor(
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING
        )
        val serviceMsg = UltraSharePlatformCompatibility.messageFor(
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED
        )
        val nearbyMsg = UltraSharePlatformCompatibility.messageFor(
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.NEARBY_PERMISSION_MISSING
        )
        assertEquals(
            "",
            UltraSharePlatformCompatibility.messageFor(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK
            )
        )
        assertTrue(permMsg.isNotBlank())
        assertTrue(serviceMsg.isNotBlank())
        assertTrue(nearbyMsg.isNotBlank())
        assertFalse(permMsg == serviceMsg)
        assertFalse(permMsg == nearbyMsg)
        assertFalse(serviceMsg == nearbyMsg)
        assertFalse(permMsg.contains("الأجهزة القريبة"))
        assertFalse(serviceMsg.contains("الأجهزة القريبة"))
        assertTrue(serviceMsg.contains("إعدادات"))
        assertTrue(nearbyMsg.contains("الأجهزة القريبة"))
    }
}
