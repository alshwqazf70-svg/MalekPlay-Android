package com.malekplay

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.malekplay.core.ui.theme.MalekPlayTheme
import com.malekplay.navigation.MalekPlayNavHost
import com.malekplay.player.PipController
import com.malekplay.ui.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var pipController: PipController

    /** Media handed over by another app, or null for a normal launch. */
    private var externalUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

        pipController.bind { requestPictureInPicture() }
        requestRuntimePermissions()

        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

            MalekPlayTheme(
                mode = settings.themeMode,
                accent = settings.accent,
                dynamicColor = settings.dynamicColor
            ) {
                MalekPlayNavHost(externalUri = externalUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep Activity.intent in sync: recreate()/onCreate must see the new URI.
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            externalUri = intent.data
            recreate()
        }
    }

    /**
     * Notifications are requested on Android 13+ for the media-session
     * notification. Microphone access is optional and only used by the
     * visualizer; playback must remain usable when it is denied.
     */
    private fun requestRuntimePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)

            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) return
        permissionLauncher.launch(needed.toTypedArray())
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Optional features degrade gracefully when permission is denied. */ }

    private fun requestPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        return runCatching {
            enterPictureInPictureMode(params)
            true
        }.getOrDefault(false)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController.setInPip(isInPictureInPictureMode)
    }

    fun setOrientation(landscape: Boolean) {
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    override fun onDestroy() {
        pipController.unbind()
        super.onDestroy()
    }
}
