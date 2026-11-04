package com.malekplay.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.malekplay.data.local.entity.MediaItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaDaoReconciliationTest {

    private lateinit var database: MalekPlayDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MalekPlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reconcilesLargeVideoAndAudioLibrariesInBoundedPages() = runBlocking {
        val rows = buildList {
            repeat(2_500) { index ->
                val id = "media-\${index.toString().padStart(5, '0')}"
                add(
                    MediaItemEntity(
                        mediaId = id,
                        title = id,
                        uri = "content://media/$id",
                        mimeType = if (index % 2 == 0) "video/mp4" else "audio/mpeg",
                        sizeBytes = 1_024L,
                        durationMs = 60_000L,
                        addedAtMs = index.toLong(),
                        isVideo = index % 2 == 0,
                        folderPath = "/Movies",
                        folderName = "Movies",
                        artist = null,
                        album = null,
                        albumId = null,
                        artistId = null,
                        genre = null,
                        width = if (index % 2 == 0) 1920 else 0,
                        height = if (index % 2 == 0) 1080 else 0,
                        bitrate = 128_000L
                    )
                )
            }
        }
        database.mediaDao().upsertMedia(rows)

        val keepVideos = rows.filter { it.isVideo }.take(375).map { it.mediaId }
        val keepAudio = rows.filterNot { it.isVideo }.take(125).map { it.mediaId }
        database.mediaDao().reconcileVideos(keepVideos)
        database.mediaDao().reconcileAudio(keepAudio)

        val remaining = database.mediaDao().getMediaList(keepVideos + keepAudio)
        assertEquals(keepVideos.size + keepAudio.size, remaining.size)
        database.query("SELECT COUNT(*) FROM media_items").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(500, cursor.getInt(0))
        }
    }
}
