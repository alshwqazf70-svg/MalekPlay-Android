package com.malekplay.network

/**
 * Central Android-version policy for Wi-Fi Direct runtime prerequisites.
 *
 * API 23 (Android 6.0) is the app's minimum supported release. Android 13+
 * uses NEARBY_WIFI_DEVICES; Android 6.0–12L uses runtime fine-location
 * permission and requires Location services enabled for peer discovery.
 */
object UltraSharePlatformCompatibility {
    fun wifiDirectRuntimePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 33) {
            listOf("android.permission.NEARBY_WIFI_DEVICES")
        } else {
            listOf("android.permission.ACCESS_FINE_LOCATION")
        }

    fun requiresLocationServicesForWifiDirect(sdkInt: Int): Boolean =
        sdkInt in 23..32

    /** Android 6–9 still permits the legacy WifiManager.addNetwork join flow. */
    fun supportsLegacyWifiManagerJoin(sdkInt: Int): Boolean = sdkInt in 23..28

    /** Android 10+ uses WifiNetworkSpecifier for app-scoped network requests. */
    fun usesWifiNetworkSpecifier(sdkInt: Int): Boolean = sdkInt >= 29

    /** Group creation itself is not gated by the Location Services discovery prerequisite. */
    fun canCreateWifiDirectGroup(sdkInt: Int): Boolean =
        supportsWifiDirectApiLevel(sdkInt)

    fun supportsWifiDirectApiLevel(sdkInt: Int): Boolean =
        sdkInt >= 23
}
