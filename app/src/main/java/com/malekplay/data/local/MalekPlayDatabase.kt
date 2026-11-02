package com.malekplay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackStateEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity

@Database(
    entities = [MediaItemEntity::class, PlaybackStateEntity::class, TrackPreferenceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MalekPlayDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile private var instance: MalekPlayDatabase? = null

        // تهجير قاعدة البيانات من الإصدار 1 إلى 2 لإضافة دعم المجلدات كـ MX Player
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN bucketId TEXT NOT NULL DEFAULT '0'")
                db.execSQL("ALTER TABLE media_items ADD COLUMN bucketName TEXT NOT NULL DEFAULT 'مجلدات أخرى'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_bucketId ON media_items(bucketId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isVideo ON media_items(isVideo)")
            }
        }

        fun get(context: Context): MalekPlayDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MalekPlayDatabase::class.java,
                    "malekplay.db"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration() // حماية إضافية تمنع أي كراش نهائياً
                .build()
                .also { instance = it }
            }
    }
}
