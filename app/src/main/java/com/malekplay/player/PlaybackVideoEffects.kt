package com.malekplay.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private app-to-service bridge for local video-only GPU effects and authoritative state. */
object PlaybackVideoEffects {
    const val ACTION_UPSCALING = "com.malekplay.action.SET_VIDEO_UPSCALING"
    const val ACTION_NOISE_REDUCTION = "com.malekplay.action.SET_NOISE_REDUCTION"
    const val ACTION_MOTION_SMOOTHING = "com.malekplay.action.SET_MOTION_SMOOTHING"
    const val ACTION_QUALITY_PROFILE = "com.malekplay.action.SET_VIDEO_QUALITY_PROFILE"
    const val ACTION_GET_STATE = "com.malekplay.action.GET_VIDEO_EFFECT_STATE"
    const val KEY_ENABLED = "enabled"
    const val KEY_PROFILE = "profile"
    const val KEY_STATE_UPSCALING = "state_upscaling"
    const val KEY_STATE_NOISE_REDUCTION = "state_noise_reduction"
    const val KEY_STATE_MOTION_SMOOTHING = "state_motion_smoothing"
    const val KEY_STATE_PROFILE = "state_profile"

    val COMMANDS: List<SessionCommand>
        get() = listOf(
            ACTION_UPSCALING,
            ACTION_NOISE_REDUCTION,
            ACTION_MOTION_SMOOTHING,
            ACTION_QUALITY_PROFILE,
            ACTION_GET_STATE
        ).map { SessionCommand(it, Bundle.EMPTY) }

    val COMMAND: SessionCommand
        get() = SessionCommand(ACTION_UPSCALING, Bundle.EMPTY)

    val STATE_COMMAND: SessionCommand
        get() = SessionCommand(ACTION_GET_STATE, Bundle.EMPTY)
}
