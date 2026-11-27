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

/**
 * Stateful, idempotent coordinator for resuming a paused QR / Wi-Fi Direct operation
 * after the user returns from system Location Settings (`ACTION_LOCATION_SOURCE_SETTINGS`)
 * or via `Lifecycle.Event.ON_RESUME` on Android 6.0–12L (API 23–32).
 *
 * Guarantees:
 * 1. Never resets or regenerates `invitationId` or `pairingSecret` across Settings round-trips.
 * 2. Deduplicates simultaneous `ActivityResult` + `ON_RESUME` callbacks so discovery,
 *    advertisement, and connection jobs are never launched twice.
 * 3. Surfaces an explicit Arabic diagnostic if the user returns with Location still off, while
 *    keeping the pending operation armed for a subsequent Settings visit.
 * 4. Never blocks Android 13+ (`sdkInt >= 33`) on Location services.
 */
class QrLocationResumeCoordinator {

    enum class PendingKind {
        QR_HOST_RECEIVE,
        QR_JOIN_PEER,
        WIFI_DIRECT_DISCOVERY
    }

    data class PendingOperation(
        val id: Long,
        val kind: PendingKind,
        val invitationId: String? = null,
        val pairingSecret: String? = null,
        val qrPayload: String? = null
    )

    sealed class ResumeDecision {
        data object NoPendingOperation : ResumeDecision()
        data object AlreadyResumingOrCompleted : ResumeDecision()
        data class StillDisabled(
            val operation: PendingOperation,
            val messageAr: String
        ) : ResumeDecision()
        data class MissingPermission(
            val operation: PendingOperation,
            val prerequisite: UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult,
            val messageAr: String
        ) : ResumeDecision()
        data class ResumeNow(val operation: PendingOperation) : ResumeDecision()
    }

    private val lock = Any()
    private var nextOperationId = 1L
    private var pendingOperation: PendingOperation? = null
    private var resumingOperationId: Long? = null
    private var completedOperationId: Long? = null

    fun currentPendingOperation(): PendingOperation? = synchronized(lock) { pendingOperation }

    /**
     * Record that a QR / Wi-Fi Direct flow paused specifically because system Location
     * services are disabled on API 23–32. If an identical operation with the same
     * [invitationId] and [pairingSecret] is already pending, preserves it without resetting IDs.
     */
    fun recordPausedForLocationService(
        kind: PendingKind,
        invitationId: String? = null,
        pairingSecret: String? = null,
        qrPayload: String? = null
    ): PendingOperation = synchronized(lock) {
        val existing = pendingOperation
        if (existing != null &&
            existing.kind == kind &&
            existing.invitationId == invitationId &&
            existing.pairingSecret == pairingSecret &&
            existing.qrPayload == qrPayload
        ) {
            resumingOperationId = null
            return existing
        }
        val created = PendingOperation(
            id = nextOperationId++,
            kind = kind,
            invitationId = invitationId,
            pairingSecret = pairingSecret,
            qrPayload = qrPayload
        )
        pendingOperation = created
        resumingOperationId = null
        created
    }

    /**
     * Evaluate whether returning from system Settings (`ActivityResult` or `ON_RESUME`)
     * should resume the paused operation now.
     */
    fun onReturnFromSettingsOrResume(
        sdkInt: Int,
        permissionGranted: Boolean,
        locationServiceEnabled: Boolean
    ): ResumeDecision = synchronized(lock) {
        val pending = pendingOperation ?: return ResumeDecision.NoPendingOperation
        if (completedOperationId == pending.id || resumingOperationId == pending.id) {
            return ResumeDecision.AlreadyResumingOrCompleted
        }
        val prereq = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
            sdkInt = sdkInt,
            permissionGranted = permissionGranted,
            locationServiceEnabled = locationServiceEnabled
        )
        return when (prereq) {
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK -> {
                resumingOperationId = pending.id
                ResumeDecision.ResumeNow(pending)
            }
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED -> {
                ResumeDecision.StillDisabled(
                    operation = pending,
                    messageAr = UltraSharePlatformCompatibility.messageFor(prereq)
                )
            }
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING,
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.NEARBY_PERMISSION_MISSING -> {
                ResumeDecision.MissingPermission(
                    operation = pending,
                    prerequisite = prereq,
                    messageAr = UltraSharePlatformCompatibility.messageFor(prereq)
                )
            }
        }
    }

    /** Mark that the resumed operation has been dispatched so it won't re-run on subsequent resumes. */
    fun markResumeCompleted(operationId: Long) = synchronized(lock) {
        if (pendingOperation?.id == operationId) {
            completedOperationId = operationId
            resumingOperationId = null
            pendingOperation = null
        }
    }

    /** If starting the resumed operation failed transiently, allow a future retry. */
    fun markResumeFailed(operationId: Long) = synchronized(lock) {
        if (resumingOperationId == operationId) {
            resumingOperationId = null
        }
    }

    /** Explicit user cancellation / session reset clears any pending paused operation. */
    fun clear() = synchronized(lock) {
        pendingOperation = null
        resumingOperationId = null
    }
}
