package com.malekplay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 2,
    exportSchema = true
)
abstract class MalekPlayDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun userDataDao(): UserDataDao

    companion object {
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
                // Schema 1 only ever existed in development builds and stored no
                // user data that cannot be re-derived from MediaStore.
                .fallbackToDestructiveMigrationOnDowngrade(false)
                .fallbackToDestructiveMigration()
                .build()
    }
}
