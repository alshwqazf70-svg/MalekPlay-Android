package com.malekplay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackStateEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity

@Database(
    entities = [MediaItemEntity::class, PlaybackStateEntity::class, TrackPreferenceEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MalekPlayDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        /** Preserve existing playback history and track preferences when folder metadata is added. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN bucketId TEXT NOT NULL DEFAULT '0'")
                db.execSQL("ALTER TABLE media_items ADD COLUMN bucketName TEXT NOT NULL DEFAULT 'مجلدات أخرى'")
            }
        }

        /** Track the last successful MediaStore scan without deleting rows on partial permission grants. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN lastSeenAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: MalekPlayDatabase? = null

        fun get(context: Context): MalekPlayDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    MalekPlayDatabase::class.java,
                    "malekplay.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
