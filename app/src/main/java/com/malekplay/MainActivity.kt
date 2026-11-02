package com.malekplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.malekplay.domain.model.MediaWithDetails
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.library.LibraryScreen
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.ui.network.NetworkSourcesDialog
import com.malekplay.ui.player.PlayerScreen
import com.malekplay.ui.settings.SettingsDialog
import com.malekplay.ui.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

private data class MediaEntry(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    val isVideo: Boolean,
    val bucketId: String = "0",
    val bucketName: String = "مجلدات أخرى"
)

private val mediaEntrySaver = Saver<MediaEntry?, List<String>>(
    save = { entry ->
        if (entry == null) emptyList() else listOf(
            entry.uri.toString(), entry.name, entry.durationMs.toString(),
            entry.sizeBytes.toString(), entry.dateAdded.toString(), entry.isVideo.toString(),
            entry.bucketId, entry.bucketName
        )
    },
    restore = { values ->
        if (values.size != 8) null else runCatching {
            MediaEntry(
                uri = Uri.parse(values[0]), name = values[1],
                durationMs = values[2].toLongOrNull() ?: 0L,
                sizeBytes = values[3].toLongOrNull() ?: 0L,
                dateAdded = values[4].toLongOrNull() ?: 0L,
                isVideo = values[5] == "true", bucketId = values[6], bucketName = values[7]
            )
        }.getOrNull()
    }
)

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MalekPlayApp() }
    }
}

private val MalekPlayLightColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    secondary = Color(0xFFDAA84A),
    background = Color(0xFFF8FAF9),
    surface = Color.White,
    onSurface = Color(0xFF17211F)
)

private val MalekPlayDarkColors = darkColorScheme(
    primary = Color(0xFF78C7B2),
    onPrimary = Color(0xFF073B32),
    secondary = Color(0xFFE5BD70),
    background = Color(0xFF101715),
    surface = Color(0xFF19231F),
    onSurface = Color(0xFFE5EEE9)
)

private fun hasMediaPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@OptIn(UnstableApi::class)
@Composable
private fun MalekPlayApp() {
    val context = LocalContext.current
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val playerSettings by settingsViewModel.settings.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showNetworkSources by remember { mutableStateOf(false) }
    var childModeEnabled by remember(context) { mutableStateOf(ParentalControlManager(context).isEnabled) }
    var permitted by remember { mutableStateOf(hasMediaPermission(context)) }

    // الفيديو المحدد للتشغيل حالياً
    var selected by rememberSaveable(stateSaver = mediaEntrySaver) { mutableStateOf<MediaEntry?>(null) }
    var queueUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var queueStartIndex by remember { mutableIntStateOf(0) }

    var isFullscreen by rememberSaveable { mutableStateOf(false) }

    val setFullscreen: (Boolean) -> Unit = { enabled ->
        isFullscreen = enabled
        val activity = context as? ComponentActivity
        activity?.requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // زر الرجوع عند تشغيل الفيديو يعيدنا للمكتبة
    BackHandler(enabled = selected != null) {
        selected = null
        setFullscreen(false)
    }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permitted = hasMediaPermission(context)
        if (permitted) libraryViewModel.refresh()
    }

    LaunchedEffect(permitted) {
        if (permitted) libraryViewModel.refresh()
        else libraryViewModel.clearForPermissionDenied()
    }

    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (playerSettings.theme) {
        "dark" -> true
        "system" -> systemDark
        else -> false
    }

    MaterialTheme(colorScheme = if (useDarkTheme) MalekPlayDarkColors else MalekPlayLightColors) {
        if (showSettings) {
            SettingsDialog(
                settings = playerSettings,
                onSettingsChange = settingsViewModel::update,
                onChildModeChanged = { childModeEnabled = it },
                onDismiss = { showSettings = false }
            )
        }

        if (showNetworkSources) {
            NetworkSourcesDialog(
                onDismiss = { showNetworkSources = false },
                onPlayNetworkMedia = { uri, title ->
                    val networkEntry = MediaEntry(
                        uri = uri,
                        name = title,
                        durationMs = 0L,
                        sizeBytes = 0L,
                        dateAdded = System.currentTimeMillis() / 1000L,
                        isVideo = true
                    )
                    queueUris = listOf(uri)
                    queueStartIndex = 0
                    selected = networkEntry
                    setFullscreen(true)
                    showNetworkSources = false
                }
            )
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                // 1. حالة تشغيل الفيديو (عرض مشغل الفيديو الخارق)
                selected != null -> {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        PlayerScreen(
                            uri = selected!!.uri,
                            isVideo = selected!!.isVideo,
                            title = selected!!.name,
                            queueUris = queueUris,
                            startIndex = queueStartIndex,
                            isFullscreen = isFullscreen,
                            onToggleFullscreen = setFullscreen,
                            resumePlayback = playerSettings.resumePlayback,
                            defaultPlaybackSpeed = playerSettings.defaultPlaybackSpeed,
                            rememberBrightness = playerSettings.rememberBrightness,
                            subtitleSizeSp = playerSettings.subtitleSizeSp
                        )
                    }
                }

                // 2. حالة عدم وجود أذونات
                !permitted -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("مرحباً بك في MalekPlay", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "نحتاج إذن الوصول إلى الوسائط لعرض ملفات الفيديو والمجلدات على جهازك.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { permissionRequest.launch(mediaPermissions()) }) {
                            Text("منح الإذن الآن")
                        }
                    }
                }

                // 3. الحالة الرئيسية الافتراضية: عرض شاشة المجلدات الخرافية (MX Style)
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // الشريط العلوي الرئيسي للتطبيق
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
                                Text(
                                    "M",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!childModeEnabled) {
                                    IconButton(onClick = { showNetworkSources = true }) {
                                        Icon(Icons.Default.FolderOpen, "مصادر الشبكة", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, "الإعدادات", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        // استدعاء شاشة المجلدات التي صممناها
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onVideoClick = { mediaWithDetails ->
                                val media = mediaWithDetails.media
                                val uri = Uri.parse(media.uriString ?: media.path ?: "")
                                val title = media.displayName ?: media.title ?: "فيديو"
                                val isVideo = media.isVideo ?: true

                                queueUris = listOf(uri)
                                queueStartIndex = 0
                                selected = MediaEntry(
                                    uri = uri,
                                    name = title,
                                    durationMs = media.durationMs ?: 0L,
                                    sizeBytes = media.sizeBytes ?: 0L,
                                    dateAdded = media.dateAdded ?: 0L,
                                    isVideo = isVideo
                                )
                                setFullscreen(true) // تشغيل بملء الشاشة فوراً كما في MX
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
