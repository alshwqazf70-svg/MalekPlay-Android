package com.malekplay.ui.network

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.malekplay.core.ui.theme.MalekPlayColors

// --- نماذج البيانات (Data Models) ---
enum class MediaType { VIDEO, AUDIO, IMAGE }

data class MediaFile(
    val id: String,
    val name: String,
    val size: String,
    val thumbnailUrl: String? = null,
    val uri: Uri,
    val type: MediaType
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendFileScreen(
    onBack: () -> Unit,
    viewModel: MediaPickerViewModel = hiltViewModel(),
    ultraViewModel: UltraShareViewModel = hiltViewModel() // إضافة محرك النقل
) {
    val context = LocalContext.current
    val isLoading by viewModel.isLoading.collectAsState()
    val allFiles by viewModel.mediaFiles.collectAsState()
    val ultraState by ultraViewModel.state.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("الفيديوهات", "الموسيقى", "الصور")
    val selectedFiles = remember { mutableStateListOf<MediaFile>() }
    
    // حالة فتح نافذة اختيار الأجهزة
    var showDevicePicker by remember { mutableStateOf(false) }

    // Request only the permission needed by the active media tab. On Android 14+,
    // selected visual-media access is also sufficient for the media the user selected.
    val fullMediaPermission = when (selectedTab) {
        0 -> Manifest.permission.READ_MEDIA_VIDEO
        1 -> Manifest.permission.READ_MEDIA_AUDIO
        else -> Manifest.permission.READ_MEDIA_IMAGES
    }
    val partialVisualPermission = if (
        Build.VERSION.SDK_INT >= 34 && selectedTab != 1
    ) Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED else null
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOfNotNull(fullMediaPermission, partialVisualPermission).toTypedArray()
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasCurrentMediaPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        val fullGranted = ContextCompat.checkSelfPermission(
            context, fullMediaPermission
        ) == PackageManager.PERMISSION_GRANTED
        val partialGranted = partialVisualPermission?.let { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } ?: false
        return fullGranted || partialGranted
    }

    var hasPermission by remember(selectedTab) {
        mutableStateOf(hasCurrentMediaPermission())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = hasCurrentMediaPermission()
    }

    // جلب البيانات عند تغيير التبويب أو الحصول على الإذن
    LaunchedEffect(selectedTab, hasPermission) {
        if (!hasPermission) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            val type = when (selectedTab) {
                0 -> MediaType.VIDEO
                1 -> MediaType.AUDIO
                else -> MediaType.IMAGE
            }
            viewModel.loadMedia(type)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("اختيار الملفات", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${selectedFiles.size} ملف محدد",
                            style = MaterialTheme.typography.labelSmall,
                            color = MalekPlayColors.PurpleBright
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            // الشريط السفلي العائم (Floating Bottom Bar)
            AnimatedVisibility(
                visible = selectedFiles.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .shadow(16.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("جاهز للإرسال", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${selectedFiles.size} عناصر", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = { 
                                // عند الضغط، نفتح نافذة اختيار الأجهزة
                                showDevicePicker = true
                                // نبدأ البحث عن أجهزة إذا لم يكن يبحث
                                if (!ultraState.scanning) ultraViewModel.scanNearbyDevices()
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MalekPlayColors.PurpleBright),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("إرسال الآن", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // التبويبات
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MalekPlayColors.PurpleBright,
                divider = {},
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) MalekPlayColors.PurpleBright else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            // عرض المحتوى بناءً على الحالة
            if (!hasPermission) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Text("نحتاج إذن الوصول لملفاتك", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                            Text("منح الإذن")
                        }
                    }
                }
            } else if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MalekPlayColors.PurpleBright)
                }
            } else if (allFiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("لا توجد ملفات من هذا النوع", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // عرض الملفات
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(allFiles) { file ->
                        val isSelected = selectedFiles.contains(file)
                        MediaFileCard(
                            file = file,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelected) selectedFiles.remove(file)
                                else selectedFiles.add(file)
                            }
                        )
                    }
                }
            }
        }
    }

    // --- نافذة اختيار الأجهزة (Bottom Sheet) ---
    if (showDevicePicker) {
        DevicePickerBottomSheet(
            discoveredPeers = ultraState.discoveredPeers,
            activePeers = ultraState.activePeers,
            isScanning = ultraState.scanning,
            onDismiss = { showDevicePicker = false },
            onConnect = { host, secret ->
                // عند إدخال الرمز، نطلب الاتصال
                ultraViewModel.requestConnection(host, secret)
                // ملاحظة: عملية الإرسال الفعلية قد تحتاج إلى انتظار الموافقة، 
                // يمكنك إضافة منطق هنا أو الاعتماد على تحديث الحالة في ViewModel
                Toast.makeText(context, "جاري الاتصال بالجهاز...", Toast.LENGTH_SHORT).show()
                showDevicePicker = false
            },
            onSendToApprovedPeer = { host ->
                // إرسال مباشر لجهاز متصل مسبقاً (إذا كان هناك ملفات محددة)
                val uris = selectedFiles.map { it.uri }
                if (uris.isNotEmpty()) {
                    val accepted = ultraViewModel.sendBatch(uris, host, ultraState.pairingSecret)
                    if (accepted) {
                        selectedFiles.clear()
                    } else {
                        Toast.makeText(context, "تعذر بدء الإرسال. تحقق من اتصال الجهاز ورمز الاقتران ثم حاول مجدداً.", Toast.LENGTH_LONG).show()
                    }
                }
                showDevicePicker = false
            }
        )
    }
}

@Composable
fun MediaFileCard(
    file: MediaFile,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MalekPlayColors.PurpleBright else Color.Transparent
    val backgroundColor = if (isSelected) MalekPlayColors.PurpleBright.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .border(2.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // عرض الصورة المصغرة
            if (file.thumbnailUrl != null) {
                AsyncImage(
                    model = file.thumbnailUrl,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                )
                // تدرج لوني لأسفل الصورة
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                startY = 200f
                            )
                        )
                )
            } else {
                // خلفية افتراضية للموسيقى
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF2E1065), Color(0xFF0D0D12)))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(48.dp))
                }
            }

            // علامة الاختيار
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = "تحديد",
                tint = if (isSelected) MalekPlayColors.PurpleBright else Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            )

            // معلومات الملف
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = file.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = file.size,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
