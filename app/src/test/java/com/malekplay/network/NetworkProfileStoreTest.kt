package com.malekplay.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkProfileStoreTest {

    @Test
    fun savedProfilesSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val profile = NetworkProfile(
            id = "profile-1",
            protocol = NetworkProtocol.WEBDAV,
            host = "media.example.test",
            port = 443,
            username = "viewer",
            rootPath = "/Videos",
            displayName = "Media server"
        )

        NetworkProfileStore(context).save(profile)

        assertEquals(listOf(profile), NetworkProfileStore(context).load())
    }

    @Test
    fun corruptedProfileJsonIsNotSilentlyTreatedAsEmpty() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
            .edit().putString("profiles", "{broken-json").commit()

        try {
            NetworkProfileStore(context).load()
            fail("Corrupt profile storage must be reported")
        } catch (expected: IllegalStateException) {
            assertEquals("Saved network profiles are corrupted", expected.message)
        }
    }
}
