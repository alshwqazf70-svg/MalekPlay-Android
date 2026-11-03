package com.malekplay.core.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime access helpers for the two independently permissioned media types.
 * A partial visual grant is usable for browsing selected videos, but is never
 * considered a complete video-library snapshot.
 */
object MediaPermissions {

    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasFullVideoAccess(context: Context): Boolean =
        hasPermission(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasAudioAccess(context: Context): Boolean =
        hasPermission(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPartialAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val selected = hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return selected && !hasFullVideoAccess(context)
    }

    /** True when at least one library type can be displayed. */
    fun isGranted(context: Context): Boolean =
        hasFullVideoAccess(context) || hasAudioAccess(context) || hasPartialAccess(context)

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
