package com.malekplay.network

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkCredentialVaultInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearCredentialPreferences() {
        context.getSharedPreferences("malekplay_network_secrets", 0)
            .edit().clear().commit()
    }

    @Test
    fun encryptsLoadsAndRemovesCredentialsUsingAndroidKeystore() {
        val vault = NetworkCredentialVault(context)
        vault.save("profile_note9", "media-user", "correct horse battery staple")

        assertEquals(
            "media-user" to "correct horse battery staple",
            vault.load("profile_note9")
        )

        vault.remove("profile_note9")
        assertNull(vault.load("profile_note9"))
    }

    @Test
    fun encryptedCredentialsCannotBeMovedBetweenProfiles() {
        val vault = NetworkCredentialVault(context)
        vault.save("profile_alpha", "alice", "secret-one")
        val stored = context.getSharedPreferences("malekplay_network_secrets", 0)
            .getString("profile_alpha", null)
            ?: error("Encrypted credential record was not persisted")
        context.getSharedPreferences("malekplay_network_secrets", 0)
            .edit().putString("profile_beta", stored).commit()

        assertThrows(Exception::class.java) {
            vault.load("profile_beta")
        }
    }
}
