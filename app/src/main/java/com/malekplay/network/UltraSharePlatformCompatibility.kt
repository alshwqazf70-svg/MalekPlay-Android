package com.malekplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Central Android-version policy for Wi-Fi Direct runtime prerequisites.
 *
 * API 23 (Android 6.0) is the app's minimum supported release. Android 13+
 * uses NEARBY_WIFI_DEVICES; Android 6.0–12L uses runtime fine-location
 * permission and requires Location services enabled for peer discovery.
 *
 * The three concerns below are deliberately kept separate so the UI and
 * Wi-Fi Direct / LocalOnlyHotspot engines surface the exact root-cause message
 * instead of conflating "permission not granted" with "location service switched off":
 *   1. The *runtime permission* granted to the app by the user.
 *   2. The *Location service* actually enabled in system Settings (API 23–32).
 *   3. `NEARBY_WIFI_DEVICES` (API 33+) vs `ACCESS_FINE_LOCATION` (API 23–32).
 */
object UltraSharePlatformCompatibility {

    /**
     * Outcome of checking whether Wi-Fi Direct discovery/advertisement may proceed.
     * Each value maps to a distinct Arabic user-facing message (see [messageFor]).
     */
    enum class WifiDirectPrerequisiteResult {
        /** Permission granted (and, on API 23–32, Location service enabled). */
        OK,
        /** API 23–32 only: the Location service is switched off in system Settings. */
        LOCATION_SERVICE_DISABLED,
        /** API 23–32 only: the ACCESS_FINE_LOCATION app permission was not granted. */
        LOCATION_PERMISSION_MISSING,
        /** API 33+ only: the NEARBY_WIFI_DEVICES app permission was not granted. */
        NEARBY_PERMISSION_MISSING
    }

    fun wifiDirectRuntimePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 33) {
            listOf("android.permission.NEARBY_WIFI_DEVICES")
        } else {
            listOf("android.permission.ACCESS_FINE_LOCATION")
        }

    /**
     * Pure, JVM-testable evaluation that separates Location permission, system Location
     * service state, and Android 13+ NEARBY_WIFI_DEVICES without framework dependencies.
     */
    fun evaluateWifiDirectPrerequisite(
        sdkInt: Int,
        permissionGranted: Boolean,
        locationServiceEnabled: Boolean
    ): WifiDirectPrerequisiteResult {
        return when {
            sdkInt >= 33 -> if (permissionGranted) {
                WifiDirectPrerequisiteResult.OK
            } else {
                WifiDirectPrerequisiteResult.NEARBY_PERMISSION_MISSING
            }
            sdkInt in 23..32 -> when {
                !permissionGranted -> WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING
                !locationServiceEnabled -> WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED
                else -> WifiDirectPrerequisiteResult.OK
            }
            else -> WifiDirectPrerequisiteResult.OK
        }
    }

    /**
     * Live device wrapper around [evaluateWifiDirectPrerequisite].
     */
    fun evaluateWifiDirectPrerequisite(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): WifiDirectPrerequisiteResult {
        val required = wifiDirectRuntimePermissions(sdkInt)
        val permissionGranted = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        return evaluateWifiDirectPrerequisite(
            sdkInt = sdkInt,
            permissionGranted = permissionGranted,
            locationServiceEnabled = isLocationServiceEnabled(context, sdkInt)
        )
    }

    /** Distinct Arabic user-facing message for each prerequisite outcome. */
    fun messageFor(result: WifiDirectPrerequisiteResult): String = when (result) {
        WifiDirectPrerequisiteResult.OK -> ""
        WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED ->
            "يلزم تشغيل خدمة الموقع (Location) في إعدادات الهاتف لإتمام الاكتشاف والاتصال عبر Wi-Fi Direct. فعّل خدمة الموقع ثم عُد لإكمال العملية."
        WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING ->
            "يلزم منح إذن الموقع للتطبيق لاكتشاف أجهزة Wi-Fi Direct على هذا الإصدار من Android. امنح الإذن ثم أعد المحاولة."
        WifiDirectPrerequisiteResult.NEARBY_PERMISSION_MISSING ->
            "يلزم منح إذن الأجهزة القريبة (Nearby devices) للتطبيق لاكتشاف أجهزة Wi-Fi Direct. امنح الإذن ثم أعد المحاولة."
    }

    /**
     * Accurate Arabic error message when `WifiP2pManager` or `WifiManager.startLocalOnlyHotspot`
     * throws [SecurityException]. Prevents Android 6–12 from falsely asking for
     * `NEARBY_WIFI_DEVICES` ("إذن الأجهزة القريبة") when Location service is disabled.
     */
    fun securityExceptionMessage(
        context: Context,
        operationSuffix: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): String {
        val result = evaluateWifiDirectPrerequisite(context, sdkInt)
        return when (result) {
            WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED ->
                "خدمة الموقع (Location) متوقفة في إعدادات الهاتف؛ يلزم تشغيلها $operationSuffix."
            WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING ->
                "يلزم منح إذن الموقع للتطبيق $operationSuffix."
            WifiDirectPrerequisiteResult.NEARBY_PERMISSION_MISSING ->
                "يلزم منح إذن الأجهزة القريبة $operationSuffix."
            WifiDirectPrerequisiteResult.OK ->
                if (sdkInt in 23..32) {
                    "تعذر المتابعة $operationSuffix؛ تحقق من تفعيل خدمة الموقع وإذن الموقع للتطبيق."
                } else {
                    "يلزم منح إذن الأجهزة القريبة $operationSuffix."
                }
        }
    }

    fun requiresLocationServicesForWifiDirect(sdkInt: Int): Boolean =
        sdkInt in 23..32

    fun requiresLocationServiceForWifiDirect(sdkInt: Int): Boolean =
        requiresLocationServicesForWifiDirect(sdkInt)

    /** True when the Location service is enabled in system Settings (or not required). */
    fun isLocationServiceEnabled(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!requiresLocationServicesForWifiDirect(sdkInt)) return true
        val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            if (sdkInt >= Build.VERSION_CODES.P && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                location.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

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
    ): Boolean = isLegacyWifiNetworkConnected(
        expectedNetworkId = expectedNetworkId,
        actualNetworkId = actualNetworkId,
        expectedSsid = expectedSsid,
        actualSsid = actualSsid,
        supplicantCompleted = supplicantCompleted,
        hasVerifiedTargetSubnetRoute = false
    )

    /**
     * Extended overload that also recognizes association on Android 8.1–9 devices where
     * `WifiInfo.getSSID()` is masked as `<unknown ssid>` and `networkId` transiently reports `-1`
     * while Location service is off, provided `SupplicantState.COMPLETED` holds and the kernel
     * already assigned a local IPv4 route matching the target group owner's subnet.
     */
    fun isLegacyWifiNetworkConnected(
        expectedNetworkId: Int,
        actualNetworkId: Int,
        expectedSsid: String,
        actualSsid: String?,
        supplicantCompleted: Boolean,
        hasVerifiedTargetSubnetRoute: Boolean
    ): Boolean {
        if (!supplicantCompleted) return false
        val expected = expectedSsid.trim().removeSurrounding("\"")
        val actual = actualSsid?.trim()?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return (expectedNetworkId >= 0 && actualNetworkId == expectedNetworkId) ||
            (!actual.isNullOrBlank() && actual == expected) ||
            (actual == null && actualNetworkId == -1 && hasVerifiedTargetSubnetRoute)
    }

    /** Group creation itself is not gated by the Location Services discovery prerequisite. */
    fun canCreateWifiDirectGroup(sdkInt: Int): Boolean =
        supportsWifiDirectApiLevel(sdkInt)

    fun supportsWifiDirectApiLevel(sdkInt: Int): Boolean =
        sdkInt >= 23
}
