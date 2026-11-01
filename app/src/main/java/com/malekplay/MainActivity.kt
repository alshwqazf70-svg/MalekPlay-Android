package com.malekplay

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    private val selectedVideo = mutableStateOf<Uri?>(null)

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers issue temporary rather than persistable grants.
            }
            selectedVideo.value = uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MalekPlayApp(
                selectedUri = selectedVideo.value,
                onChooseVideo = {
                    videoPicker.launch(arrayOf("video/*", "audio/*"))
                }
            )
        }
    }
}

private val MalekPlayColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    secondary = Color(0xFFDAA84A),
    background = Color(0xFFF5F7F6),
    surface = Color.White,
    onSurface = Color(0xFF17211F)
)

@Composable
private fun MalekPlayApp(
    selectedUri: Uri?,
    onChooseVideo: () -> Unit
) {
    MaterialTheme(colorScheme = MalekPlayColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "M",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .background(Color(0xFF176B5B), RoundedCornerShape(14.dp))
                            .padding(horizontal = 15.dp, vertical = 9.dp)
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "MalekPlay",
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF17211F)
                        )
                        Text(
                            text = "مشغّل الوسائط الخاص بك",
                            fontSize = 13.sp,
                            color = Color(0xFF687570)
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                Text(
                    text = "مكتبتك، بطريقتك",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF17211F)
                )
                Text(
                    text = "شغّل ملفات الفيديو والصوت الموجودة على جهازك.",
                    fontSize = 14.sp,
                    color = Color(0xFF687570),
                    modifier = Modifier.padding(top = 7.dp)
                )

                Spacer(Modifier.height(22.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF172B27))
                ) {
                    if (selectedUri == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .padding(22.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.PlayCircle,
                                    contentDescription = null,
                                    tint = Color(0xFFDAA84A),
                                    modifier = Modifier.height(58.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "مساحة المشاهدة",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "اختر ملفاً لبدء التشغيل",
                                    color = Color(0xFFB9C8C3),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        VideoPlayer(uri = selectedUri)
                    }
                }

                Spacer(Modifier.height(22.dp))

                Button(
                    onClick = onChooseVideo,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF176B5B)
                    )
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null
                    )
                    Text(
                        text = "  اختيار ملف من الجهاز",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(17.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "مصمم لجهازك",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF176B5B),
                            fontSize = 15.sp
                        )
                        Text(
                            text = "واجهة عربية • تشغيل محلي • Android 10 فأحدث",
                            color = Color(0xFF687570),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                Text(
                    text = "M A L E K P L A Y  •  الإصدار 1.0",
                    color = Color(0xFF98A49F),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = exoPlayer
                useController = true
            }
        },
        update = { it.player = exoPlayer },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    )
}
