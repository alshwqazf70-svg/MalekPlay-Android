package com.malekplay.di

import android.content.Context
import androidx.room.Room
import com.malekplay.data.local.MalekPlayDatabase
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.scanner.MediaStoreScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MalekPlayDatabase =
        Room.databaseBuilder(
            context,
            MalekPlayDatabase::class.java,
            "malekplay.db"
        ).build()

    @Provides
    fun provideMediaDao(database: MalekPlayDatabase) = database.mediaDao()

    @Provides
    @Singleton
    fun provideMediaStoreScanner(@ApplicationContext context: Context) =
        MediaStoreScanner(context)

    @Provides
    @Singleton
    fun provideMediaRepository(
        dao: com.malekplay.data.local.dao.MediaDao,
        scanner: MediaStoreScanner
    ) = MediaRepository(dao, scanner)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context) =
        SettingsRepository(context)
}
