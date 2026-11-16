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

    @Test fun apiBelowAppMinimumIsNotSupported() {
        (1..22).forEach { sdk ->
            assertFalse(UltraSharePlatformCompatibility.supportsWifiDirectApiLevel(sdk))
        }
    }
}
