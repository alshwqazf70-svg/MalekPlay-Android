package com.malekplay

import android.Manifest
import android.app.AlertDialog
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.malekplay.core.ui.theme.MalekPlayTheme
import com.malekplay.core.ui.util.MediaPermissions
import com.malekplay.navigation.MalekPlayNavHost
import com.malekplay.player.PipController
import com.malekplay.ui.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import com.malekplay.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var pipController: PipController

    @Inject
    lateinit var mediaRepository: MediaRepository

    /** Media handed over by another app, or null for a normal launch. */
    private var externalUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

        pipController.bind { requestPictureInPicture() }
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

            MalekPlayTheme(
                mode = settings.themeMode,
                accent = settings.accent,
                dynamicColor = settings.dynamicColor,
                textColorMode = settings.textColorMode
            ) {
                MalekPlayNavHost(externalUri = externalUri)
            }
        }
        showFirstLaunchPermissionMessage()
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
     * Explain the core permissions once on first launch, then let Android show
     * its own consent dialogs. Camera and microphone stay deferred until needed.
     */
    private fun showFirstLaunchPermissionMessage() {
        val preferences = getSharedPreferences("startup_permissions", MODE_PRIVATE)
        val libraryAccessReady = MediaPermissions.hasAudioAccess(this) &&
            (MediaPermissions.hasFullVideoAccess(this) || MediaPermissions.hasPartialAccess(this))
        if (preferences.getBoolean("intro_shown", false) && libraryAccessReady) return
        AlertDialog.Builder(this)
            .setTitle("تجهيز MalekPlay")
            .setMessage(
                "لإظهار الفيديوهات والموسيقى الموجودة على هاتفك، وتشغيل إشعارات النقل والتشغيل، " +
                    "سيطلب التطبيق الأذونات المناسبة لإصدار Android لديك. " +
                    "لن يصل التطبيق إلى الكاميرا أو الميكروفون إلا عند استخدام ميزتي QR ومحلل الصوت."
            )
            .setPositiveButton("متابعة ومنح الأذونات") { _, _ ->
                preferences.edit().putBoolean("intro_shown", true).apply()
                requestRuntimePermissions()
            }
            .setNegativeButton("لاحقاً", null)
            .setCancelable(false)
            .show()
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            MediaPermissions.required().forEach { permission ->
                if (ContextCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                    add(permission)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }.distinct()

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // The first library scan may have happened before Android displayed its
        // permission dialog. Reconcile MediaStore now so newly granted media is
        // visible immediately without requiring an app restart or manual refresh.
        val mediaAccessChanged = grants.any { (permission, granted) ->
            granted && permission in MediaPermissions.required()
        }
        val hasAnyMediaAccess = MediaPermissions.isGranted(this)
        if (!hasAnyMediaAccess) {
            // If the user chose "later" or denied the library access, explain
            // the requirement again on the next launch instead of silently
            // leaving the library empty forever.
            getSharedPreferences("startup_permissions", MODE_PRIVATE)
                .edit().putBoolean("intro_shown", false).apply()
        }
        if (mediaAccessChanged || hasAnyMediaAccess) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { mediaRepository.refresh() }
            }
        }
    }

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
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onDestroy() {
        pipController.unbind()
        super.onDestroy()
    }
}
