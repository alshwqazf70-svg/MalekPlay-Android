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
enum class MediaPermissionType { VIDEO, AUDIO, IMAGE }

object MediaPermissions {

    /**
     * Permissions for the currently visible media tab. Android 14+ may grant
     * selected visual access instead of full image/video library access.
     */
    fun requiredFor(type: MediaPermissionType): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val fullPermission = when (type) {
            MediaPermissionType.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
            MediaPermissionType.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            MediaPermissionType.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
        }
        val partialPermission = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            type != MediaPermissionType.AUDIO
        ) Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED else null
        return listOfNotNull(fullPermission, partialPermission).toTypedArray()
    }

    /** A partial visual grant is sufficient only for the selected visual-media tab. */
    fun hasAccess(context: Context, type: MediaPermissionType): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val fullPermission = when (type) {
            MediaPermissionType.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
            MediaPermissionType.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            MediaPermissionType.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
        }
        val hasFullAccess = hasPermission(context, fullPermission)
        val hasPartialVisualAccess =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                type != MediaPermissionType.AUDIO &&
                hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return hasFullAccess || hasPartialVisualAccess
    }

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
