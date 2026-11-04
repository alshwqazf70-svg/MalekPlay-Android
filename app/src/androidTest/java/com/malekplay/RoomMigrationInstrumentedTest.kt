package com.malekplay

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.malekplay.data.local.MalekPlayDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MalekPlayDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun versionThreeDatabaseMigratesThroughRoomWithoutLosingPlaylistData() {
        val databaseName = "malekplay-migration-v3-to-v4.db"
        migrationHelper.createDatabase(databaseName, 3).apply {
            execSQL("INSERT INTO playlists (playlistId, name, createdAtMs, sortOrder) VALUES ('saved-list', 'Saved', 100, 0)")
            execSQL("INSERT INTO playlist_items (playlistId, mediaId, position, addedAtMs) VALUES ('saved-list', 'media-1', 0, 200)")
            execSQL("INSERT INTO playlist_items (playlistId, mediaId, position, addedAtMs) VALUES ('missing-list', 'orphan-media', 1, 300)")
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(
            context,
            MalekPlayDatabase::class.java,
            databaseName
        )
            .addMigrations(MalekPlayDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            val migrated = database.openHelper.writableDatabase
            migrated.query("SELECT playlistId, mediaId, position, addedAtMs FROM playlist_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("saved-list", cursor.getString(0))
                assertEquals("media-1", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(200L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }

            migrated.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse("Room migration must preserve referential integrity", cursor.moveToFirst())
            }

            migrated.execSQL("DELETE FROM playlists WHERE playlistId = 'saved-list'")
            migrated.query("SELECT COUNT(*) FROM playlist_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
