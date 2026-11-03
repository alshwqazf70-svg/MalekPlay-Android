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
    version = 3,
    exportSchema = true
)
abstract class MalekPlayDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun userDataDao(): UserDataDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Progress must survive for SAF/network URIs without a media_items row.
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS playback_progress_new (
                        mediaId TEXT NOT NULL,
                        positionMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        playbackSpeed REAL NOT NULL,
                        isCompleted INTEGER NOT NULL,
                        lastPlayedAtMs INTEGER NOT NULL,
                        playCount INTEGER NOT NULL,
                        PRIMARY KEY(mediaId)
                    )"""
                )
                database.execSQL(
                    """INSERT INTO playback_progress_new
                       (mediaId, positionMs, durationMs, playbackSpeed, isCompleted, lastPlayedAtMs, playCount)
                       SELECT mediaId, positionMs, durationMs, playbackSpeed, isCompleted, lastPlayedAtMs, playCount
                       FROM playback_progress"""
                )
                database.execSQL("DROP TABLE playback_progress")
                database.execSQL("ALTER TABLE playback_progress_new RENAME TO playback_progress")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_lastPlayedAtMs ON playback_progress(lastPlayedAtMs)")
            }
        }

        @Volatile
        private var instance: MalekPlayDatabase? = null

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
                .addMigrations(MIGRATION_2_3)
                // Schema 1 only ever existed in development builds and stored no
                // user data that cannot be re-derived from MediaStore.
                .fallbackToDestructiveMigrationOnDowngrade(false)
                .fallbackToDestructiveMigration()
                .build()
    }
}
