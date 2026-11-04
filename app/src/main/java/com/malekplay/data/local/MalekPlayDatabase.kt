package com.malekplay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.dao.PlaylistDao
import com.malekplay.data.local.dao.UserDataDao
import com.malekplay.data.local.entity.BookmarkEntity
import com.malekplay.data.local.entity.FavouriteEntity
import com.malekplay.data.local.entity.HistoryEntity
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackProgressEntity
import com.malekplay.data.local.entity.PlaylistEntity
import com.malekplay.data.local.entity.PlaylistItemEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity
import com.malekplay.data.local.model.PlaylistWithItems

@Database(
    entities = [
        MediaItemEntity::class,
        PlaybackProgressEntity::class,
        TrackPreferenceEntity::class,
        BookmarkEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        FavouriteEntity::class,
        HistoryEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class MalekPlayDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun userDataDao(): UserDataDao

    companion object {
        @Volatile
        private var instance: MalekPlayDatabase? = null

        /** Matches the released legacy migration that added folder identifiers. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "media_items", "bucketId")) {
                    db.execSQL(
                        "ALTER TABLE media_items ADD COLUMN bucketId TEXT NOT NULL DEFAULT '0'"
                    )
                }
                if (!hasColumn(db, "media_items", "bucketName")) {
                    db.execSQL(
                        "ALTER TABLE media_items ADD COLUMN bucketName TEXT NOT NULL DEFAULT 'مجلدات أخرى'"
                    )
                }
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_items_bucketId ON media_items(bucketId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_items_isVideo ON media_items(isVideo)"
                )
            }
        }

        /**
         * Converts the old v2 schema (duration/dateAdded/bucket fields and
         * playback_states) to the current schema without discarding user data.
         *
         * The updated v2 schema already has durationMs and all current tables;
         * for that database this migration is intentionally a no-op.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (hasColumn(db, "media_items", "durationMs")) return
                check(hasColumn(db, "media_items", "duration")) {
                    "Unsupported version 2 media_items schema; refusing destructive migration"
                }

                if (tableExists(db, "track_preferences")) {
                    db.execSQL("ALTER TABLE track_preferences RENAME TO track_preferences_legacy")
                }
                if (tableExists(db, "playback_states")) {
                    db.execSQL("ALTER TABLE playback_states RENAME TO playback_states_legacy")
                }
                db.execSQL("ALTER TABLE media_items RENAME TO media_items_legacy")

                // Old index names survive table renames and must be released first.
                db.execSQL("DROP INDEX IF EXISTS index_media_items_bucketId")
                db.execSQL("DROP INDEX IF EXISTS index_media_items_isVideo")
                db.execSQL("DROP INDEX IF EXISTS index_track_preferences_mediaId")

                db.execSQL(
                    """CREATE TABLE media_items (
                        mediaId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        addedAtMs INTEGER NOT NULL,
                        isVideo INTEGER NOT NULL,
                        folderPath TEXT NOT NULL,
                        folderName TEXT NOT NULL,
                        artist TEXT,
                        album TEXT,
                        albumId INTEGER,
                        artistId INTEGER,
                        genre TEXT,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        bitrate INTEGER NOT NULL,
                        PRIMARY KEY(mediaId)
                    )"""
                )
                db.execSQL(
                    """INSERT INTO media_items (
                        mediaId, title, uri, mimeType, sizeBytes, durationMs, addedAtMs,
                        isVideo, folderPath, folderName, artist, album, albumId, artistId,
                        genre, width, height, bitrate
                    )
                    SELECT mediaId, title, uri, mimeType, sizeBytes, duration, dateAdded,
                           isVideo, COALESCE(bucketId, '0'),
                           COALESCE(bucketName, 'مجلدات أخرى'),
                           NULL, NULL, NULL, NULL, NULL, 0, 0, 0
                    FROM media_items_legacy"""
                )

                db.execSQL(
                    """CREATE TABLE track_preferences (
                        mediaId TEXT NOT NULL,
                        audioTrackId TEXT,
                        subtitleTrackId TEXT,
                        subtitleDelayMs INTEGER NOT NULL,
                        audioDelayMs INTEGER NOT NULL,
                        isSubtitleEnabled INTEGER NOT NULL,
                        externalSubtitleUri TEXT,
                        PRIMARY KEY(mediaId),
                        FOREIGN KEY(mediaId) REFERENCES media_items(mediaId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                if (tableExists(db, "track_preferences_legacy")) {
                    db.execSQL(
                        """INSERT INTO track_preferences (
                            mediaId, audioTrackId, subtitleTrackId, subtitleDelayMs,
                            audioDelayMs, isSubtitleEnabled, externalSubtitleUri
                        )
                        SELECT mediaId, audioTrackId, subtitleTrackId, subtitleDelayMs,
                               0, isSubtitleEnabled, NULL
                        FROM track_preferences_legacy"""
                    )
                    db.execSQL("DROP TABLE track_preferences_legacy")
                }

                db.execSQL(
                    """CREATE TABLE playback_progress (
                        mediaId TEXT NOT NULL,
                        positionMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        playbackSpeed REAL NOT NULL,
                        isCompleted INTEGER NOT NULL,
                        lastPlayedAtMs INTEGER NOT NULL,
                        playCount INTEGER NOT NULL,
                        PRIMARY KEY(mediaId),
                        FOREIGN KEY(mediaId) REFERENCES media_items(mediaId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                if (tableExists(db, "playback_states_legacy")) {
                    db.execSQL(
                        """INSERT INTO playback_progress (
                            mediaId, positionMs, durationMs, playbackSpeed,
                            isCompleted, lastPlayedAtMs, playCount
                        )
                        SELECT old.mediaId, old.position, COALESCE(media.durationMs, 0),
                               old.playbackSpeed, old.isCompleted, 0, 0
                        FROM playback_states_legacy AS old
                        LEFT JOIN media_items AS media ON media.mediaId = old.mediaId"""
                    )
                    db.execSQL("DROP TABLE playback_states_legacy")
                }

                db.execSQL("DROP TABLE media_items_legacy")

                db.execSQL(
                    """CREATE TABLE bookmarks (
                        bookmarkId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mediaId TEXT NOT NULL,
                        positionMs INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        FOREIGN KEY(mediaId) REFERENCES media_items(mediaId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE playlists (
                        playlistId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        PRIMARY KEY(playlistId)
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
                db.execSQL(
                    """CREATE TABLE favourites (
                        mediaId TEXT NOT NULL,
                        addedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(mediaId)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE history (
                        mediaId TEXT NOT NULL,
                        lastPlayedAtMs INTEGER NOT NULL,
                        playCount INTEGER NOT NULL,
                        PRIMARY KEY(mediaId)
                    )"""
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_folderPath ON media_items(folderPath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isVideo ON media_items(isVideo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_addedAtMs ON media_items(addedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isVideo_album ON media_items(isVideo, album)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isVideo_artist ON media_items(isVideo, artist)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_mediaId ON playback_progress(mediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_lastPlayedAtMs ON playback_progress(lastPlayedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_preferences_mediaId ON track_preferences(mediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_mediaId ON bookmarks(mediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_items_mediaId ON playlist_items(mediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_items_playlistId_position ON playlist_items(playlistId, position)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_favourites_addedAtMs ON favourites(addedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_lastPlayedAtMs ON history(lastPlayedAtMs)")

                db.query("PRAGMA foreign_key_check").use { cursor ->
                    check(!cursor.moveToFirst()) {
                        "Foreign-key validation failed while migrating MalekPlay database"
                    }
                }
            }
        }

        private fun hasColumn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String
        ): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
            }
            return false
        }

        private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table' LIMIT 1"
            ).use { cursor -> return cursor.moveToFirst() }
        }

        /** Adds referential integrity for playlist entries without losing valid rows. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Entries without a parent playlist are unreachable application data.
                db.execSQL(
                    "DELETE FROM playlist_items WHERE playlistId NOT IN (SELECT playlistId FROM playlists)"
                )
                db.execSQL("ALTER TABLE playlist_items RENAME TO playlist_items_legacy")
                db.execSQL("DROP INDEX IF EXISTS index_playlist_items_mediaId")
                db.execSQL("DROP INDEX IF EXISTS index_playlist_items_playlistId_position")
                db.execSQL(
                    """CREATE TABLE playlist_items (
                        playlistId TEXT NOT NULL,
                        mediaId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        addedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(playlistId, mediaId),
                        FOREIGN KEY(playlistId) REFERENCES playlists(playlistId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    """INSERT INTO playlist_items (playlistId, mediaId, position, addedAtMs)
                    SELECT playlistId, mediaId, position, addedAtMs
                    FROM playlist_items_legacy"""
                )
                db.execSQL("DROP TABLE playlist_items_legacy")
                db.execSQL(
                    "CREATE INDEX index_playlist_items_mediaId ON playlist_items(mediaId)"
                )
                db.execSQL(
                    "CREATE INDEX index_playlist_items_playlistId_position ON playlist_items(playlistId, position)"
                )
                db.query("PRAGMA foreign_key_check").use { cursor ->
                    check(!cursor.moveToFirst()) {
                        "Playlist foreign-key validation failed during migration"
                    }
                }
            }
        }

        fun get(context: Context): MalekPlayDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): MalekPlayDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MalekPlayDatabase::class.java,
                "malekplay.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
