package com.malekplay.ui.network

import android.net.Uri

/**
 * Shared media-picker models.
 *
 * Kept separate from screen composables so repositories and UI entry points
 * can use the same stable types without depending on a particular screen file.
 */
enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE
}

data class MediaFile(
    val id: String,
    val name: String,
    val size: String,
    val thumbnailUrl: String? = null,
    val uri: Uri,
    val type: MediaType
)
