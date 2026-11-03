package com.malekplay.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalPlaybackProgressStoreTest {

    private lateinit var store: ExternalPlaybackProgressStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("malekplay_external_progress", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = ExternalPlaybackProgressStore(context)
    }

    @Test
    fun repeatedProgressSavesDoNotIncrementPlayCount() {
        val mediaId = "content://provider/document/episode-1"

        store.save(mediaId, 12_000L, 60_000L, 1.5f, markPlayed = true)
        store.save(mediaId, 18_000L, 60_000L, 1.25f, markPlayed = false)

        val saved = store.get(mediaId)
        assertNotNull(saved)
        assertEquals(18_000L, saved!!.positionMs)
        assertEquals(60_000L, saved.durationMs)
        assertEquals(1.25f, saved.playbackSpeed, 0.001f)
        assertEquals(1, saved.playCount)
        assertTrue(saved.lastPlayedAtMs > 0L)
    }

    @Test
    fun shortMediaIsNotMarkedCompleteAtTheBeginning() {
        val mediaId = "content://provider/document/short-clip"

        store.save(mediaId, 0L, 1_500L, 1f, markPlayed = true)

        val saved = store.get(mediaId)
        assertNotNull(saved)
        assertEquals(0L, saved!!.positionMs)
        assertEquals(false, saved.isCompleted)
        assertEquals(1, saved.playCount)
    }

    @Test
    fun completedMediaResetsResumePositionWithoutLosingPlayCount() {
        val mediaId = "https://example.invalid/video.mp4"

        store.save(mediaId, 58_500L, 60_000L, 1f, markPlayed = true)

        val saved = store.get(mediaId)
        assertNotNull(saved)
        assertEquals(0L, saved!!.positionMs)
        assertTrue(saved.isCompleted)
        assertEquals(1, saved.playCount)
    }
}
