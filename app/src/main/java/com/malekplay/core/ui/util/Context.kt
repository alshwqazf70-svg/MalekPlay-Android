package com.malekplay.core.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Walks the context chain up to the hosting Activity, or null in previews. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
