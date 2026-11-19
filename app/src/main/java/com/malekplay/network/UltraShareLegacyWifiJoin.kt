package com.malekplay.network

import android.net.wifi.WifiConfiguration
import java.nio.charset.StandardCharsets

/** Builds the WPA2 profile used only on Android 6–9 before WifiNetworkSpecifier existed. */
internal fun buildUltraShareLegacyWifiConfiguration(
    ssid: String,
    passphrase: String
): WifiConfiguration {
    val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
    require(
        ssid.isNotBlank() &&
            ssidBytes.size <= 32 &&
            ssid.none { it.isISOControl() || it == '"' || it == '\\\\' }
    ) { "Invalid Wi-Fi SSID." }
    require(
        passphrase.length in 8..63 &&
            passphrase.all { it.code in 0x20..0x7e && it != '"' && it != '\\\\' }
    ) { "Invalid WPA2 passphrase." }
    return WifiConfiguration().apply {
        SSID = "\"$ssid\""
        preSharedKey = "\"$passphrase\""
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        status = WifiConfiguration.Status.ENABLED
    }
}
