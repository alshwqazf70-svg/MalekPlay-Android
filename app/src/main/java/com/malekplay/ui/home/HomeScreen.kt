package com.malekplay.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.library.LibraryViewModel

@Composable
fun HomeScreen(
    onOpenMedia: (MediaItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recent by viewModel.continueWatching.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val featured = recent.firstOrNull()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(contentPadding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_malekplay_logo),
                contentDescription = "MalekPlay",
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("MalekPlay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(stringResource(R.string.home_library_tagline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(238.dp)
                .clip(RoundedCornerShape(24.dp)).background(
                    Brush.linearGradient(listOf(Color(0xFF10051F), Color(0xFF321064), Color(0xFF090711)))
                )
        ) {
            if (featured != null) {
                MediaThumbnail(featured.media, Modifier.fillMaxSize(), cornerRadius = 0.dp)
            } else {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_malekplay_logo),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp).size(170.dp)
                )
            }
            Box(Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF5090610), Color(0xA6090610), Color(0x18090610)))
            ))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(color = Color(0xFF7B2CFF), shape = RoundedCornerShape(50)) {
                    Text("MALEKPLAY  •  PREMIUM PLAYER", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    featured?.media?.displayTitle ?: stringResource(R.string.home_hero_title),
                    color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    featured?.let { stringResource(R.string.home_continue_watching) } ?: stringResource(R.string.home_hero_subtitle),
                    color = Color.White.copy(alpha = .86f), style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        color = Color(0xFF8D32FF), shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable { if (featured != null) onOpenMedia(featured.media) else onOpenLibrary() }
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                            Text(if (featured != null) stringResource(R.string.home_continue_watching) else stringResource(R.string.home_open_library), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(stringResource(R.string.home_stat_videos, stats.videoCount), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.home_stat_audio, stats.audioCount), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (recent.isNotEmpty()) {
            SectionTitle(stringResource(R.string.home_continue_watching))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.media.id }) { item ->
                    Card(
                        Modifier.width(224.dp).clickable { onOpenMedia(item.media) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column {
                            Box {
                                MediaThumbnail(item.media, Modifier.fillMaxWidth().aspectRatio(16f / 9f), cornerRadius = 0.dp)
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(38.dp))
                            }
                            Text(item.media.displayTitle, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.home_recent_videos), Modifier.weight(1f))
            Text(stringResource(R.string.home_open_library), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable(onClick = onOpenLibrary).padding(6.dp))
        }
        if (videos.isEmpty()) {
            Text(stringResource(R.string.lib_empty_body), Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(videos.take(12), key = { it.media.id }) { item ->
                    Column(Modifier.width(132.dp).clickable { onOpenMedia(item.media) }) {
                        MediaThumbnail(item.media, Modifier.fillMaxWidth().aspectRatio(4f / 5f), cornerRadius = 14.dp)
                        Text(item.media.displayTitle, Modifier.padding(top = 7.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}
