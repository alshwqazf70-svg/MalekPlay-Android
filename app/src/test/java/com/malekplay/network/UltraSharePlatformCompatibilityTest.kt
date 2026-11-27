package com.malekplay.network

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
            assertTrue("Wi-Fi Direct must remain available from API 23 through 36 (sdk=$sdk).",
                UltraSharePlatformCompatibility.supportsWifiDirectApiLevel(sdk))
            assertTrue("Group creation must not depend on Location Services (sdk=$sdk).",
                UltraSharePlatformCompatibility.canCreateWifiDirectGroup(sdk))
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

    @Test fun locationPermissionDeniedIsDistinguishedFromDisabledLocationService() {
        (23..32).forEach { sdk ->
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.PERMISSION_DENIED,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisites(
                    sdkInt = sdk,
                    permissionGranted = false,
                    locationServicesEnabled = false
                )
            )
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.LOCATION_SERVICES_DISABLED,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisites(
                    sdkInt = sdk,
                    permissionGranted = true,
                    locationServicesEnabled = false
                )
            )
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.READY,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisites(
                    sdkInt = sdk,
                    permissionGranted = true,
                    locationServicesEnabled = true
                )
            )
        }
        // Android 13+ does not require Location services when NEARBY_WIFI_DEVICES is granted.
        (33..36).forEach { sdk ->
            assertEquals(
                UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.READY,
                UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisites(
                    sdkInt = sdk,
                    permissionGranted = true,
                    locationServicesEnabled = false
                )
            )
        }
    }

    @Test fun qrOperationResumesAutomaticallyOnlyWhenReadyAndNotAlreadyActive() {
        assertTrue(
            UltraSharePlatformCompatibility.shouldAutoResumeQrOperation(
                prerequisiteStatus = UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.READY,
                pendingAction = UltraSharePlatformCompatibility.PendingQrLocationAction.DISCOVER_INVITATION,
                operationAlreadyActive = false
            )
        )
        assertTrue(
            UltraSharePlatformCompatibility.shouldAutoResumeQrOperation(
                prerequisiteStatus = UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.READY,
                pendingAction = UltraSharePlatformCompatibility.PendingQrLocationAction.ADVERTISE_INVITATION,
                operationAlreadyActive = false
            )
        )
        // Prevent duplicate/concurrent execution if an operation is already running.
        assertFalse(
            UltraSharePlatformCompatibility.shouldAutoResumeQrOperation(
                prerequisiteStatus = UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.READY,
                pendingAction = UltraSharePlatformCompatibility.PendingQrLocationAction.DISCOVER_INVITATION,
                operationAlreadyActive = true
            )
        )
        // Do not auto-resume while Location services remain disabled.
        assertFalse(
            UltraSharePlatformCompatibility.shouldAutoResumeQrOperation(
                prerequisiteStatus = UltraSharePlatformCompatibility.WifiDirectPrerequisiteStatus.LOCATION_SERVICES_DISABLED,
                pendingAction = UltraSharePlatformCompatibility.PendingQrLocationAction.ADVERTISE_INVITATION,
                operationAlreadyActive = false
            )
        )
    }
}
