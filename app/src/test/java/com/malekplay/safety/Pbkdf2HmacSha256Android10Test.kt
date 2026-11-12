package com.malekplay.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Pbkdf2HmacSha256Android10Test {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("malekplay_family", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun derivesRfc8018Sha256KnownVectorWithoutSecretKeyFactory() {
        val actual = Pbkdf2HmacSha256.derive(
            password = "password".toCharArray(),
            salt = "salt".toByteArray(Charsets.UTF_8),
            iterations = 1,
            keyLengthBytes = 32
        ).joinToString("") { "%02x".format(it) }

        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            actual
        )
    }

    @Test
    fun parentalPinCanBeCreatedAndVerifiedOnAndroid10() {
        val manager = ParentalControlManager(context)
        manager.setPin("4826")

        assertTrue(manager.verifyPin("4826"))
        assertTrue(ParentalControlManager(context).verifyPin("4826"))
    }
}
