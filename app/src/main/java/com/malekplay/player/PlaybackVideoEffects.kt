package com.malekplay.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private app-to-service bridge for local video-only GPU effects. */
object PlaybackVideoEffects {
    const val ACTION_UPSCALING = "com.malekplay.action.SET_VIDEO_UPSCALING"
    const val ACTION_GET_STATE = "com.malekplay.action.GET_VIDEO_EFFECT_STATE"
    const val KEY_ENABLED = "enabled"
    const val KEY_STATE_ENABLED = "state_enabled"
    val COMMAND: SessionCommand
        get() = SessionCommand(ACTION_UPSCALING, Bundle.EMPTY)
    val STATE_COMMAND: SessionCommand
        get() = SessionCommand(ACTION_GET_STATE, Bundle.EMPTY)
}
