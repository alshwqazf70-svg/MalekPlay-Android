package com.malekplay

import android.app.Application
import com.malekplay.network.UltraShareDiagnostics
import com.malekplay.ui.components.trimMediaThumbnailCache
import com.malekplay.ui.music.trimMusicArtworkCache
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MalekPlayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        UltraShareDiagnostics.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        trimMediaThumbnailCache(level)
        trimMusicArtworkCache(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        trimMediaThumbnailCache(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        trimMusicArtworkCache(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
