package com.malekplay.di

import android.content.Context
import androidx.room.Room
import com.malekplay.data.local.ExternalPlaybackProgressStore
import com.malekplay.data.local.MalekPlayDatabase
import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.dao.PlaylistDao
import com.malekplay.data.local.dao.UserDataDao
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.repository.PlaylistRepository
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.data.scanner.MediaStoreScanner
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.OnlineSubtitleRepository
import com.malekplay.network.NetworkProfileStore
import com.malekplay.network.SftpClient
import com.malekplay.network.SmbClient
import com.malekplay.network.WebDavClient
import com.malekplay.player.audio.AudioEffectsController
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
        MalekPlayDatabase.get(context)

    @Provides
    fun provideMediaDao(database: MalekPlayDatabase): MediaDao = database.mediaDao()

    @Provides
    fun providePlaylistDao(database: MalekPlayDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideUserDataDao(database: MalekPlayDatabase): UserDataDao = database.userDataDao()

    @Provides
    @Singleton
    fun provideMediaStoreScanner(@ApplicationContext context: Context): MediaStoreScanner =
        MediaStoreScanner(context)

    @Provides
    @Singleton
    fun provideMediaRepository(
        mediaDao: MediaDao,
        userDataDao: UserDataDao,
        scanner: MediaStoreScanner,
        externalProgressStore: ExternalPlaybackProgressStore
    ): MediaRepository = MediaRepository(mediaDao, userDataDao, scanner, externalProgressStore)

    @Provides
    @Singleton
    fun providePlaylistRepository(playlistDao: PlaylistDao): PlaylistRepository =
        PlaylistRepository(playlistDao)

    @Provides
    @Singleton
    fun provideUserDataRepository(userDataDao: UserDataDao): UserDataRepository =
        UserDataRepository(userDataDao)

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
    fun provideOnlineSubtitleRepository(
        @ApplicationContext context: Context,
        vault: NetworkCredentialVault
    ): OnlineSubtitleRepository = OnlineSubtitleRepository(context, vault)

    @Provides
    @Singleton
    fun provideNetworkProfileStore(@ApplicationContext context: Context): NetworkProfileStore =
        NetworkProfileStore(context)

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
    fun provideSubtitleTranslationEngine(
        @ApplicationContext context: Context
    ): SubtitleTranslationEngine = SubtitleTranslationEngine(context)

    // MalekPlayBackupManager is @Singleton with an @Inject constructor, so Hilt
    // supplies it without an explicit provider.

    /** One effect chain shared by every screen so EQ settings follow the session. */
    @Provides
    @Singleton
    fun provideAudioEffectsController(): AudioEffectsController = AudioEffectsController()
}
