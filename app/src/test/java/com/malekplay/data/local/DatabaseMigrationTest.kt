package com.malekplay.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    @Test
    fun legacyVersionTwoMigrationPreservesMediaProgressAndTrackPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE media_items (
                            mediaId TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            duration INTEGER NOT NULL,
                            uri TEXT NOT NULL,
                            mimeType TEXT NOT NULL,
                            sizeBytes INTEGER NOT NULL,
                            dateAdded INTEGER NOT NULL,
                            isVideo INTEGER NOT NULL,
                            bucketId TEXT NOT NULL DEFAULT '0',
                            bucketName TEXT NOT NULL DEFAULT 'مجلدات أخرى',
                            thumbnailPath TEXT
                        )"""
                    )
                    db.execSQL("CREATE INDEX index_media_items_bucketId ON media_items(bucketId)")
                    db.execSQL("CREATE INDEX index_media_items_isVideo ON media_items(isVideo)")
                    db.execSQL(
                        """CREATE TABLE playback_states (
                            mediaId TEXT NOT NULL PRIMARY KEY,
                            position INTEGER NOT NULL,
                            playbackSpeed REAL NOT NULL,
                            isCompleted INTEGER NOT NULL,
                            FOREIGN KEY(mediaId) REFERENCES media_items(mediaId) ON DELETE CASCADE
                        )"""
                    )
                    db.execSQL("CREATE INDEX index_playback_states_mediaId ON playback_states(mediaId)")
                    db.execSQL(
                        """CREATE TABLE track_preferences (
                            mediaId TEXT NOT NULL PRIMARY KEY,
                            audioTrackId TEXT,
                            subtitleTrackId TEXT,
                            subtitleDelayMs INTEGER NOT NULL,
                            isSubtitleEnabled INTEGER NOT NULL,
                            FOREIGN KEY(mediaId) REFERENCES media_items(mediaId) ON DELETE CASCADE
                        )"""
                    )
                    db.execSQL("CREATE INDEX index_track_preferences_mediaId ON track_preferences(mediaId)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)

        try {
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL(
                """INSERT INTO media_items (
                    mediaId, title, duration, uri, mimeType, sizeBytes, dateAdded,
                    isVideo, bucketId, bucketName, thumbnailPath
                ) VALUES (
                    'content://media/external/video/media/1', 'sample.mp4', 120000,
                    'content://media/external/video/media/1', 'video/mp4', 4096,
                    1700000000000, 1, 'bucket-1', 'Movies', NULL
                )"""
            )
            db.execSQL(
                """INSERT INTO playback_states(mediaId, position, playbackSpeed, isCompleted)
                VALUES('content://media/external/video/media/1', 45000, 1.25, 0)"""
            )
            db.execSQL(
                """INSERT INTO track_preferences(
                    mediaId, audioTrackId, subtitleTrackId, subtitleDelayMs, isSubtitleEnabled
                ) VALUES('content://media/external/video/media/1', 'audio-1', 'text-1', 250, 1)"""
            )

            MalekPlayDatabase.MIGRATION_2_3.migrate(db)

            db.query(
                "SELECT durationMs, addedAtMs, folderPath, folderName FROM media_items"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(120000L, cursor.getLong(0))
                assertEquals(1700000000000L, cursor.getLong(1))
                assertEquals("bucket-1", cursor.getString(2))
                assertEquals("Movies", cursor.getString(3))
            }
            db.query(
                "SELECT positionMs, durationMs, playbackSpeed, isCompleted FROM playback_progress"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(45000L, cursor.getLong(0))
                assertEquals(120000L, cursor.getLong(1))
                assertEquals(1.25, cursor.getDouble(2), 0.001)
                assertEquals(0, cursor.getInt(3))
            }
            db.query(
                "SELECT audioTrackId, subtitleTrackId, subtitleDelayMs, audioDelayMs, isSubtitleEnabled FROM track_preferences"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("audio-1", cursor.getString(0))
                assertEquals("text-1", cursor.getString(1))
                assertEquals(250L, cursor.getLong(2))
                assertEquals(0L, cursor.getLong(3))
                assertEquals(1, cursor.getInt(4))
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse("Migration must leave no foreign-key violations", cursor.moveToFirst())
            }
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('media_items_legacy', 'playback_states_legacy', 'track_preferences_legacy')"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun playlistForeignKeyMigrationPreservesRowsAndCascadesDeletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE playlists (
                            playlistId TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            createdAtMs INTEGER NOT NULL,
                            sortOrder INTEGER NOT NULL
                        )"""
                    )
                    db.execSQL(
                        """CREATE TABLE playlist_items (
                            playlistId TEXT NOT NULL,
                            mediaId TEXT NOT NULL,
                            position INTEGER NOT NULL,
                            addedAtMs INTEGER NOT NULL,
                            PRIMARY KEY(playlistId, mediaId)
                        )"""
                    )
                    db.execSQL("CREATE INDEX index_playlist_items_mediaId ON playlist_items(mediaId)")
                    db.execSQL("CREATE INDEX index_playlist_items_playlistId_position ON playlist_items(playlistId, position)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO playlists VALUES('list-1', 'Favorites', 1, 0)")
            db.execSQL("INSERT INTO playlist_items VALUES('list-1', 'media-1', 0, 2)")
            MalekPlayDatabase.MIGRATION_3_4.migrate(db)

            db.query("SELECT playlistId, mediaId FROM playlist_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("list-1", cursor.getString(0))
                assertEquals("media-1", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
            db.execSQL("DELETE FROM playlists WHERE playlistId = 'list-1'")
            db.query("SELECT COUNT(*) FROM playlist_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        } finally {
            helper.close()
        }
    }

}
