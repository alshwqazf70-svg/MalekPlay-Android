package com.malekplay.di

import android.content.Context
import com.malekplay.backup.MalekPlayBackupManager
import com.malekplay.data.local.MalekPlayDatabase
import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.scanner.MediaStoreScanner
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.SftpClient
import com.malekplay.network.SmbClient
import com.malekplay.network.WebDavClient
import com.malekplay.translation.SubtitleTranslationEngine
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
        MalekPlayDatabase.get(context) // استخدام البناء الآمن مع كود التهجير وحماية الانهيار

    @Provides
    @Singleton
    fun provideMediaDao(database: MalekPlayDatabase): MediaDao = 
        database.mediaDao()

    @Provides
    @Singleton
    fun provideMediaStoreScanner(@ApplicationContext context: Context): MediaStoreScanner =
        MediaStoreScanner(context)

    @Provides
    @Singleton
    fun provideMediaRepository(
        dao: MediaDao,
        scanner: MediaStoreScanner
    ): MediaRepository = MediaRepository(dao, scanner)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideNetworkCredentialVault(@ApplicationContext context: Context): NetworkCredentialVault =
        NetworkCredentialVault(context)

    @Provides
    @Singleton
    fun provideWebDavClient(): WebDavClient = WebDavClient()

    @Provides
    @Singleton
    fun provideSftpClient(): SftpClient = SftpClient()

    @Provides
    @Singleton
    fun provideSmbClient(): SmbClient = SmbClient()

    @Provides
    @Singleton
    fun provideSubtitleTranslationEngine(@ApplicationContext context: Context): SubtitleTranslationEngine =
        SubtitleTranslationEngine(context)

    @Provides
    @Singleton
    fun provideBackupManager(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository
    ): MalekPlayBackupManager = MalekPlayBackupManager(context, settingsRepository)
}
