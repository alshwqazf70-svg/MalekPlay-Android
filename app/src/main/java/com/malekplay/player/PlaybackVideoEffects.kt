package com.malekplay.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private app-to-service bridge for local video-only GPU effects and authoritative state. */
object PlaybackVideoEffects {
    const val ACTION_UPSCALING = "com.malekplay.action.SET_VIDEO_UPSCALING"
    const val ACTION_NOISE_REDUCTION = "com.malekplay.action.SET_NOISE_REDUCTION"
    const val ACTION_QUALITY_PROFILE = "com.malekplay.action.SET_VIDEO_QUALITY_PROFILE"
    const val ACTION_INTERNAL_GPU_ENHANCEMENT = "com.malekplay.action.SET_INTERNAL_GPU_ENHANCEMENT"
    const val ACTION_GET_STATE = "com.malekplay.action.GET_VIDEO_EFFECT_STATE"
    const val KEY_ENABLED = "enabled"
    const val KEY_PROFILE = "profile"
    const val KEY_STATE_UPSCALING = "state_upscaling"
    const val KEY_STATE_NOISE_REDUCTION = "state_noise_reduction"
    const val KEY_STATE_PROFILE = "state_profile"
    const val KEY_DIAG_UPSCALE_SUBMITTED = "diag_upscale_submitted"
    const val KEY_DIAG_DENOISE_DRAWS = "diag_denoise_draws"
    const val KEY_DIAG_DETAIL_DRAWS = "diag_detail_draws"
    const val KEY_DIAG_TOTAL_DRAWS = "diag_total_draws"
    const val KEY_DIAG_LAST_EFFECT = "diag_last_effect"
    const val KEY_DIAG_LAST_FAILURE = "diag_last_failure"
    const val KEY_DIAG_LAST_PTS_US = "diag_last_pts_us"
    const val KEY_DIAG_RENDERER_FRAMES = "diag_renderer_frames"
    const val KEY_DIAG_AVG_OFFSET_US = "diag_avg_offset_us"
    const val KEY_DIAG_DROPPED_FRAMES = "diag_dropped_frames"
    const val KEY_DIAG_BRIDGE_ATTACHED = "diag_bridge_attached"

    val COMMANDS: List<SessionCommand>
        get() = listOf(
            ACTION_UPSCALING,
            ACTION_NOISE_REDUCTION,
            ACTION_QUALITY_PROFILE,
            ACTION_INTERNAL_GPU_ENHANCEMENT,
            ACTION_GET_STATE
        ).map { SessionCommand(it, Bundle.EMPTY) }

    val COMMAND: SessionCommand
        get() = SessionCommand(ACTION_UPSCALING, Bundle.EMPTY)

    val STATE_COMMAND: SessionCommand
        get() = SessionCommand(ACTION_GET_STATE, Bundle.EMPTY)
}
