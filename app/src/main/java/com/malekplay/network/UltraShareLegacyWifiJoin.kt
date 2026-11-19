package com.malekplay.network

import android.net.wifi.WifiConfiguration

/** Builds the WPA2 profile used only on Android 6–9 before WifiNetworkSpecifier existed. */
internal fun buildUltraShareLegacyWifiConfiguration(
    ssid: String,
    passphrase: String
): WifiConfiguration {
    require(ssid.isNotBlank() && ssid.length <= 32) { "Invalid Wi-Fi SSID." }
    require(passphrase.length in 8..63) { "Invalid WPA2 passphrase." }
    return WifiConfiguration().apply {
        SSID = "\"$ssid\""
        preSharedKey = "\"$passphrase\""
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        status = WifiConfiguration.Status.ENABLED
    }
}
