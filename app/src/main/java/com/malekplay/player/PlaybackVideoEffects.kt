package com.malekplay.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private app-to-service bridge for local video-only GPU effects. */
object PlaybackVideoEffects {
    const val ACTION_UPSCALING = "com.malekplay.action.SET_VIDEO_UPSCALING"
    const val KEY_ENABLED = "enabled"
    val COMMAND: SessionCommand
        get() = SessionCommand(ACTION_UPSCALING, Bundle.EMPTY)
}
