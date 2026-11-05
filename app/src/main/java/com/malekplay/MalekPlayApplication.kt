package com.malekplay

import android.app.Application
import com.malekplay.ui.components.trimMediaThumbnailCache
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MalekPlayApplication : Application() {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        trimMediaThumbnailCache(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        trimMediaThumbnailCache(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
