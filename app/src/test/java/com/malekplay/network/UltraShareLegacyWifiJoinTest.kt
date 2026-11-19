package com.malekplay.network

import android.net.wifi.WifiConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28])
class UltraShareLegacyWifiJoinTest {
    @Test
    fun createsQuotedWpa2ProfileForAndroidSixThroughNine() {
        val configuration = buildUltraShareLegacyWifiConfiguration(
            ssid = "DIRECT-MalekPlay",
            passphrase = "share12345"
        )

        assertEquals("\"DIRECT-MalekPlay\"", configuration.SSID)
        assertEquals("\"share12345\"", configuration.preSharedKey)
        assertTrue(configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK))
        assertEquals(WifiConfiguration.Status.ENABLED, configuration.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPassphrasesShorterThanWpa2Minimum() {
        buildUltraShareLegacyWifiConfiguration("DIRECT-MalekPlay", "short")
    }
}
