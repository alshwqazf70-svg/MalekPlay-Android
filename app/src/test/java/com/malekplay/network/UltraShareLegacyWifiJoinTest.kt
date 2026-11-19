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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonAsciiOrControlCharactersInWpa2Passphrase() {
        buildUltraShareLegacyWifiConfiguration("DIRECT-MalekPlay", "share😀123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQuotedPassphrasesThatCannotBeSafelyRepresentedInWifiConfiguration() {
        buildUltraShareLegacyWifiConfiguration("DIRECT-MalekPlay", "share\"12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSsidsLongerThanThirtyTwoUtf8Bytes() {
        buildUltraShareLegacyWifiConfiguration("شبكةشبكةشبكةشبكة", "share12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQuotedSsidsThatCannotBeSafelyRepresentedInWifiConfiguration() {
        buildUltraShareLegacyWifiConfiguration("DIRECT-\"bad", "share12345")
    }
}
