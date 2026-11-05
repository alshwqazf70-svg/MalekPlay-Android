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
    fun malformedIndividualProfileIsReportedInsteadOfSilentlySkipped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
            .edit().putString(
                "profiles",
                """[{"id":"valid","protocol":"WEBDAV","host":"media.example.test","port":443},{"broken":true}]"""
            ).commit()

        try {
            NetworkProfileStore(context).load()
            fail("Malformed profile entries must be reported")
        } catch (expected: IllegalStateException) {
            assertEquals("Saved network profile at index 1 is invalid", expected.message)
        }
    }

    @Test
    fun invalidProfileFieldsAreReported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
            .edit().putString(
                "profiles",
                """[{"id":"bad id","protocol":"SMB","host":"media.example.test","port":445}]"""
            ).commit()

        try {
            NetworkProfileStore(context).load()
            fail("Invalid profile fields must be reported")
        } catch (expected: IllegalStateException) {
            assertEquals("Saved network profile at index 0 is invalid", expected.message)
        }
    }

    @Test
    fun duplicateProfileIdsAreReported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = """{"id":"same-id","protocol":"SMB","host":"media.example.test","port":445}"""
        context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
            .edit().putString("profiles", "[$profile,$profile]").commit()

        try {
            NetworkProfileStore(context).load()
            fail("Duplicate profile IDs must be reported")
        } catch (expected: IllegalStateException) {
            assertEquals("Saved network profile at index 1 is invalid", expected.message)
        }
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

    @Test
    fun loadsLegacyNameFieldAsDisplayName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "profiles",
                """[{"id":"legacy-1","protocol":"SMB","host":"nas.example.test","port":445,"name":"Old NAS label"}]"""
            )
            .commit()

        val loaded = NetworkProfileStore(context).load().single()
        assertEquals("Old NAS label", loaded.displayName)
    }

    @Test
    fun normalizesLegacyUrlHostAndUsesItAsDisplayFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "profiles",
                """[{"id":"legacy-host","protocol":"WEBDAV","host":"https://media.example.test/","port":443}]"""
            )
            .commit()

        val loaded = NetworkProfileStore(context).load().single()
        assertEquals("media.example.test", loaded.host)
        assertEquals("media.example.test", loaded.displayName)
    }
}
