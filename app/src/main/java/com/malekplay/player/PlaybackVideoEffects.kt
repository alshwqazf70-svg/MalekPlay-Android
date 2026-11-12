package com.malekplay.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private app-to-service bridge for local video-only GPU effects. */
object PlaybackVideoEffects {
    const val ACTION_UPSCALING = "com.malekplay.action.SET_VIDEO_UPSCALING"
    const val ACTION_NOISE_REDUCTION = "com.malekplay.action.SET_NOISE_REDUCTION"
    const val ACTION_MOTION_SMOOTHING = "com.malekplay.action.SET_MOTION_SMOOTHING"
    const val KEY_ENABLED = "enabled"

    val COMMANDS: List<SessionCommand>
        get() = listOf(ACTION_UPSCALING, ACTION_NOISE_REDUCTION, ACTION_MOTION_SMOOTHING)
            .map { SessionCommand(it, Bundle.EMPTY) }

    val COMMAND: SessionCommand
        get() = SessionCommand(ACTION_UPSCALING, Bundle.EMPTY)
}
