package com.malekplay.di

import android.content.Context
import androidx.room.Room
import com.malekplay.data.local.MalekPlayDatabase
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.scanner.MediaStoreScanner
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.WebDavClient
import com.malekplay.network.SftpClient
import com.malekplay.network.SmbClient
import com.malekplay.translation.SubtitleTranslationEngine
import com.malekplay.backup.MalekPlayBackupManager
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
    @Provides
    @Singleton
    fun provideNetworkCredentialVault(@ApplicationContext context: Context) =
        NetworkCredentialVault(context)

    @Provides
    @Singleton
    fun provideWebDavClient() = WebDavClient()

    @Provides
    @Singleton
    fun provideSftpClient() = SftpClient()

    @Provides
    @Singleton
    fun provideSmbClient() = SmbClient()

    @Provides
    @Singleton
    fun provideSubtitleTranslationEngine(@ApplicationContext context: Context) =
        SubtitleTranslationEngine(context)

    @Provides
    @Singleton
    fun provideBackupManager(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository
    ) = MalekPlayBackupManager(context, settingsRepository)

}
