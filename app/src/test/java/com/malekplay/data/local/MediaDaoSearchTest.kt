package com.malekplay.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.malekplay.data.local.entity.MediaItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaDaoSearchTest {

    @Test
    fun searchTreatsSqlLikeWildcardsAsLiteralCharacters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            MalekPlayDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.mediaDao().upsertMedia(
                listOf(
                    item("literal-percent", "100% genuine"),
                    item("literal-underscore", "episode_01"),
                    item("literal-backslash", "folder\\\u005cname"),
                    item("ordinary", "100x genuine")
                )
            )

            assertEquals(
                listOf("literal-percent"),
                database.mediaDao().observeSearch("\\%").first().map { it.media.mediaId }
            )
            assertEquals(
                listOf("literal-underscore"),
                database.mediaDao().observeSearch("\\_").first().map { it.media.mediaId }
            )
            assertEquals(
                listOf("literal-backslash"),
                database.mediaDao().observeSearch("\\\\").first().map { it.media.mediaId }
            )
        } finally {
            database.close()
        }
    }

    private fun item(id: String, title: String) = MediaItemEntity(
        mediaId = id,
        title = title,
        uri = "content://test/$id",
        mimeType = "video/mp4",
        sizeBytes = 1L,
        durationMs = 1_000L,
        addedAtMs = 1L,
        isVideo = true,
        folderPath = "/test",
        folderName = "test",
        artist = null,
        album = null,
        albumId = null,
        artistId = null,
        genre = null,
        width = 1,
        height = 1,
        bitrate = 1L
    )
}
