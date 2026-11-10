package com.malekplay.core.ui.util

import android.Manifest
import android.app.Application
import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MediaPermissionsTest {

    @Test
    @Config(sdk = [32])
    fun android12UsesLegacySharedMediaPermission() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            MediaPermissions.required()
        )
    }

    @Test
    @Config(sdk = [33])
    fun android13RequestsVideoAndAudioSeparately() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ),
            MediaPermissions.required()
        )
    }

    @Test
    @Config(sdk = [34])
    fun android14AlsoRequestsSelectedVisualMediaPermission() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ),
            MediaPermissions.required()
        )
    }

    @Test
    @Config(sdk = [34])
    fun selectedVideoAccessIsUsableButNotMistakenForFullLibraryAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        assertTrue(MediaPermissions.hasPartialAccess(context))
        assertFalse(MediaPermissions.hasFullVideoAccess(context))
        assertTrue(MediaPermissions.isGranted(context))
    }

    @Test
    @Config(sdk = [34])
    fun fullVideoAndAudioGrantsExposeCompleteLibraryAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )

        assertTrue(MediaPermissions.hasFullVideoAccess(context))
        assertTrue(MediaPermissions.hasAudioAccess(context))
        assertFalse(MediaPermissions.hasPartialAccess(context))
        assertTrue(MediaPermissions.isGranted(context))
    }
}
