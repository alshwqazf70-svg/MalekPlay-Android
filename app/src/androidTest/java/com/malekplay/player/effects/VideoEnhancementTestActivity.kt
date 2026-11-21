package com.malekplay.player.effects

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.ViewGroup

/** Minimal real SurfaceView host used only by the MediaCodec/ExoPlayer instrumentation test. */
class VideoEnhancementTestActivity : Activity() {
    lateinit var surfaceView: SurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        surfaceView.holder.setFixedSize(320, 240)
        surfaceView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(surfaceView)
    }
}
