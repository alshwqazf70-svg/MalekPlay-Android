package com.malekplay.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.components.MediaDeleteButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavouritesViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val userDataRepository: UserDataRepository
) : ViewModel() {
    val favourites: StateFlow<List<LibraryItem>> = mediaRepository.observeAll()
        .map { items -> items.filter { it.isFavourite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() { viewModelScope.launch { mediaRepository.refresh() } }

    fun remove(mediaId: String) {
        viewModelScope.launch { userDataRepository.setFavourite(mediaId, false) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FavouritesScreen(
    onBack: () -> Unit,
    onOpenMedia: (MediaItem) -> Unit
) {
    val viewModel: FavouritesViewModel = hiltViewModel()
    val items by viewModel.favourites.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val visibleItems = remember(items, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) items else items.filter { item ->
            item.media.displayTitle.contains(normalizedQuery, ignoreCase = true) ||
                item.media.artist.orEmpty().contains(normalizedQuery, ignoreCase = true) ||
                item.media.folderName.contains(normalizedQuery, ignoreCase = true)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_favourites)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.favourites_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).background(
                    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background))
                )
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text("بحث في المفضلة") }
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("مساحتك الموسيقية المفضلة", style = MaterialTheme.typography.titleSmall)
                        Text("${visibleItems.size} عنصر محفوظ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                }
                if (visibleItems.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "لا توجد نتائج مطابقة لبحثك",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleItems, key = { it.media.id }) { item ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                                    .clickable { onOpenMedia(item.media) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MediaThumbnail(item.media, Modifier.size(76.dp, 58.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.media.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text(item.media.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                MediaDeleteButton(media = item.media, onDeleted = viewModel::refresh)
                                IconButton(onClick = { viewModel.remove(item.media.id) }) {
                                    Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.lib_remove_favourite), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
