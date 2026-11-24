package com.malekplay.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private app-to-service bridge for local video-only GPU effects and authoritative state. */
object PlaybackVideoEffects {
    const val ACTION_UPSCALING = "com.malekplay.action.SET_VIDEO_UPSCALING"
    const val ACTION_NOISE_REDUCTION = "com.malekplay.action.SET_NOISE_REDUCTION"
    const val ACTION_QUALITY_PROFILE = "com.malekplay.action.SET_VIDEO_QUALITY_PROFILE"
    const val ACTION_INTERNAL_GPU_ENHANCEMENT = "com.malekplay.action.SET_INTERNAL_GPU_ENHANCEMENT"
    const val ACTION_VIBRANCE = "com.malekplay.action.SET_VIDEO_VIBRANCE"
    const val ACTION_CUSTOM_TUNING = "com.malekplay.action.SET_VIDEO_CUSTOM_TUNING"
    const val ACTION_COMPARISON_BYPASS = "com.malekplay.action.SET_VIDEO_COMPARISON_BYPASS"
    const val ACTION_GET_STATE = "com.malekplay.action.GET_VIDEO_EFFECT_STATE"
    const val KEY_ENABLED = "enabled"
    const val KEY_PROFILE = "profile"
    const val KEY_CUSTOM_MODE = "custom_mode"
    const val KEY_DETAIL_STRENGTH = "detail_strength"
    const val KEY_DENOISE_STRENGTH = "denoise_strength"
    const val KEY_CONTRAST_BOOST = "contrast_boost"
    const val KEY_VIBRANCE_STRENGTH = "vibrance_strength"
    const val KEY_STATE_UPSCALING = "state_upscaling"
    const val KEY_STATE_NOISE_REDUCTION = "state_noise_reduction"
    const val KEY_STATE_VIBRANCE = "state_vibrance"
    const val KEY_STATE_AI_BOOST = "state_ai_boost"
    const val KEY_STATE_BYPASSED = "state_bypassed"
    const val KEY_STATE_CUSTOM_MODE = "state_custom_mode"
    const val KEY_STATE_PROFILE = "state_profile"
    const val KEY_STATE_EFFECTIVE_PROFILE = "state_effective_profile"
    const val KEY_STATE_DOWNGRADE_REASON = "state_downgrade_reason"
    const val KEY_STATE_DETAIL_STRENGTH = "state_detail_strength"
    const val KEY_STATE_DENOISE_STRENGTH = "state_denoise_strength"
    const val KEY_STATE_CONTRAST_BOOST = "state_contrast_boost"
    const val KEY_STATE_VIBRANCE_STRENGTH = "state_vibrance_strength"
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
    const val KEY_DIAG_PROGRAM_CREATIONS = "diag_program_creations"
    const val KEY_DIAG_CONFIGURE_CALLS = "diag_configure_calls"
    const val KEY_DIAG_DRAW_FRAME_CALLS = "diag_draw_frame_calls"
    const val KEY_DIAG_DRAW_FRAME_SUCCESSES = "diag_draw_frame_successes"
    const val KEY_DIAG_CONFIGURED_WIDTH = "diag_configured_width"
    const val KEY_DIAG_CONFIGURED_HEIGHT = "diag_configured_height"
    const val KEY_DIAG_PIPELINE_STAGE = "diag_pipeline_stage"
    const val KEY_DIAG_RENDERED_FIRST_FRAMES = "diag_rendered_first_frames"
    const val KEY_DIAG_LAST_RENDERED_OUTPUT = "diag_last_rendered_output"
    const val KEY_DIAG_LAST_RENDER_TIME_MS = "diag_last_render_time_ms"

    val COMMANDS: List<SessionCommand>
        get() = listOf(
            ACTION_UPSCALING,
            ACTION_NOISE_REDUCTION,
            ACTION_QUALITY_PROFILE,
            ACTION_INTERNAL_GPU_ENHANCEMENT,
            ACTION_VIBRANCE,
            ACTION_CUSTOM_TUNING,
            ACTION_COMPARISON_BYPASS,
            ACTION_GET_STATE
        ).map { SessionCommand(it, Bundle.EMPTY) }

    val COMMAND: SessionCommand
        get() = SessionCommand(ACTION_UPSCALING, Bundle.EMPTY)

    val STATE_COMMAND: SessionCommand
        get() = SessionCommand(ACTION_GET_STATE, Bundle.EMPTY)
}
