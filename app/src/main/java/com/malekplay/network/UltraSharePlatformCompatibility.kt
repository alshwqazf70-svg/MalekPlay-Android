package com.malekplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Central Android-version policy for Wi-Fi Direct runtime prerequisites.
 *
 * API 23 (Android 6.0) is the app's minimum supported release. Android 13+
 * uses NEARBY_WIFI_DEVICES; Android 6.0–12L uses runtime fine-location
 * permission and requires Location services enabled for peer discovery.
 */
object UltraSharePlatformCompatibility {

    enum class WifiDirectPrerequisiteStatus {
        READY,
        PERMISSION_DENIED,
        LOCATION_SERVICES_DISABLED
    }

    enum class PendingQrLocationAction {
        ADVERTISE_INVITATION,
        DISCOVER_INVITATION
    }

    fun wifiDirectRuntimePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 33) {
            listOf("android.permission.NEARBY_WIFI_DEVICES")
        } else {
            listOf("android.permission.ACCESS_FINE_LOCATION")
        }

    fun requiresLocationServicesForWifiDirect(sdkInt: Int): Boolean =
        sdkInt in 23..32

    fun evaluateWifiDirectPrerequisites(
        sdkInt: Int,
        permissionGranted: Boolean,
        locationServicesEnabled: Boolean
    ): WifiDirectPrerequisiteStatus {
        if (!permissionGranted) return WifiDirectPrerequisiteStatus.PERMISSION_DENIED
        if (requiresLocationServicesForWifiDirect(sdkInt) && !locationServicesEnabled) {
            return WifiDirectPrerequisiteStatus.LOCATION_SERVICES_DISABLED
        }
        return WifiDirectPrerequisiteStatus.READY
    }

    fun hasWifiDirectRuntimePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        wifiDirectRuntimePermissions(sdkInt).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun isLocationServicesEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrElse { false }
    }

    fun currentWifiDirectPrerequisites(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): WifiDirectPrerequisiteStatus = evaluateWifiDirectPrerequisites(
        sdkInt = sdkInt,
        permissionGranted = hasWifiDirectRuntimePermission(context, sdkInt),
        locationServicesEnabled = isLocationServicesEnabled(context)
    )

    fun shouldAutoResumeQrOperation(
        prerequisiteStatus: WifiDirectPrerequisiteStatus,
        pendingAction: PendingQrLocationAction?,
        operationAlreadyActive: Boolean
    ): Boolean =
        prerequisiteStatus == WifiDirectPrerequisiteStatus.READY &&
            pendingAction != null &&
            !operationAlreadyActive

    /** Android 6–9 still permits the legacy WifiManager.addNetwork join flow. */
    fun supportsLegacyWifiManagerJoin(sdkInt: Int): Boolean = sdkInt in 23..28

    /** Android 10+ uses WifiNetworkSpecifier for app-scoped network requests. */
    fun usesWifiNetworkSpecifier(sdkInt: Int): Boolean = sdkInt >= 29

    /** Android 6–9 association is complete only after supplicant completion. */
    fun isLegacyWifiNetworkConnected(
        expectedNetworkId: Int,
        actualNetworkId: Int,
        expectedSsid: String,
        actualSsid: String?,
        supplicantCompleted: Boolean
    ): Boolean {
        if (!supplicantCompleted) return false
        val expected = expectedSsid.trim().removeSurrounding("\"")
        val actual = actualSsid?.trim()?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return (expectedNetworkId >= 0 && actualNetworkId == expectedNetworkId) ||
            (!actual.isNullOrBlank() && actual == expected)
    }

    /** Group creation itself is not gated by the Location Services discovery prerequisite. */
    fun canCreateWifiDirectGroup(sdkInt: Int): Boolean =
        supportsWifiDirectApiLevel(sdkInt)

    fun supportsWifiDirectApiLevel(sdkInt: Int): Boolean =
        sdkInt >= 23
}
