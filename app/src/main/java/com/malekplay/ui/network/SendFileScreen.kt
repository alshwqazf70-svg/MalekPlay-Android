package com.malekplay.ui.network

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.malekplay.core.ui.theme.MalekPlayColors

// --- نموذج بيانات تجريبي (سنستبدله بـ MediaStore لاحقاً) ---
data class MediaFile(
    val id: String,
    val name: String,
    val size: String,
    val thumbnailUrl: String = "",
    val type: String // "video", "audio", "image"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendFileScreen(
    onBack: () -> Unit,
    onSendClick: (List<MediaFile>) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("الفيديوهات", "الموسيقى", "الصور")
    
    // حالة الملفات المختارة
    val selectedFiles = remember { mutableStateListOf<MediaFile>() }

    // بيانات تجريبية (للعرض فقط)
    val mockFiles = remember {
        listOf(
            MediaFile("1", "Avatar_The_Way_of_Water.mp4", "2.4 GB", "https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60d0KjUyI6J0iZ.jpg", "video"),
            MediaFile("2", "Interstellar_4K.mkv", "5.1 GB", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg", "video"),
            MediaFile("3", "One Piece EP_1080.mp4", "1.2 GB", "https://image.tmdb.org/t/p/w500/fc4L07N1C3x1H0.jpg", "video"),
            MediaFile("4", "Relaxing_Music.mp3", "8.5 MB", "", "audio"),
            MediaFile("5", "Nature_Wallpaper.jpg", "4.2 MB", "https://images.unsplash.com/photo-1470071459604-3b5ec3a7fe05?w=500", "image"),
            MediaFile("6", "Travel_Vlog_4K.mp4", "850 MB", "https://images.unsplash.com/photo-1469854523086-cc02fe5d8800?w=500", "video")
        )
    }

    // تصفية الملفات حسب التبويب المختار
    val currentFiles = mockFiles.filter { 
        when(selectedTab) {
            0 -> it.type == "video"
            1 -> it.type == "audio"
            else -> it.type == "image"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("اختيار الملفات", fontWeight = FontWeight.Bold)
                        Text("${selectedFiles.size} ملف محدد", style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.PurpleBright)
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
            // شريط سفلي عائم يظهر فقط عند اختيار ملفات
            AnimatedVisibility(
                visible = selectedFiles.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .shadow(16.dp, RoundedCornerShape(24.dp)), // ظل خرافي
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
                            onClick = { onSendClick(selectedFiles.toList()) },
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
            // 1. التبويبات الزجاجية
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MalekPlayColors.PurpleBright,
                divider = {}, // إخفاء الخط الفاصل الافتراضي
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

            // 2. شبكة الملفات (Grid)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(currentFiles) { file ->
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

@Composable
fun MediaFileCard(
    file: MediaFile,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // تأثير النيون عند الاختيار
    val borderColor = if (isSelected) MalekPlayColors.PurpleBright else Color.Transparent
    val backgroundColor = if (isSelected) MalekPlayColors.PurpleBright.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f) // شكل البطاقة
            .border(2.dp, borderColor, RoundedCornerShape(16.dp)), // حدود متوهجة
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // الصورة المصغرة
            if (file.thumbnailUrl.isNotEmpty()) {
                AsyncImage(
                    model = file.thumbnailUrl,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                )
                // تدرج لوني لأسفل الصورة لقراءة النص
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                                startY = 200f
                            )
                        )
                )
            } else {
                // لون افتراضي للموسيقى
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF2E1065), Color(0xFF0D0D12)))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(48.dp))
                }
            }

            // علامة الاختيار (Checkbox)
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

            // معلومات الملف في الأسفل
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
