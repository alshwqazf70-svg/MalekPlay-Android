package com.malekplay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackStateEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity

@Database(
    entities = [MediaItemEntity::class, PlaybackStateEntity::class, TrackPreferenceEntity::class],
    version = 1,
    exportSchema = true
)
abstract class MalekPlayDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile private var instance: MalekPlayDatabase? = null

        fun get(context: Context): MalekPlayDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MalekPlayDatabase::class.java,
                    "malekplay.db"
                ).build().also { instance = it }
            }
    }
}
