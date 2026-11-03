package com.malekplay.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the single Activity's PiP APIs to the Compose tree.
 *
 * The Activity owns `enterPictureInPictureMode`, but the player screen decides
 * when to trigger it; this object carries that request without leaking the
 * Activity into the ViewModel layer.
 */
@Singleton
class PipController @Inject constructor() {

    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    private var enterPip: (() -> Boolean)? = null

    fun bind(enter: () -> Boolean) {
        enterPip = enter
    }

    fun unbind() {
        enterPip = null
    }

    /** Returns false when PiP is unavailable or rejected by the system. */
    fun request(): Boolean = runCatching { enterPip?.invoke() ?: false }.getOrDefault(false)

    fun setInPip(value: Boolean) {
        _inPip.value = value
    }
}
