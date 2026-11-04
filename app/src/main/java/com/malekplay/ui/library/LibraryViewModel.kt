package com.malekplay.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.PlayerSettings
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val userDataRepository: UserDataRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<PlayerSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerSettings()
    )

    private val refreshMutex = Mutex()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val allMedia = mediaRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val videos = mediaRepository.observeVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val audio = mediaRepository.observeAudio()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders = mediaRepository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching = mediaRepository.observeContinueWatching(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats = mediaRepository.observeStats()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.malekplay.domain.model.LibraryStats()
        )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (!refreshMutex.tryLock()) return@launch
            _isRefreshing.value = true
            _error.value = null
            try {
                mediaRepository.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.localizedMessage ?: "Could not refresh the library"
            } finally {
                _isRefreshing.value = false
                refreshMutex.unlock()
            }
        }
    }

    fun search(query: String) = mediaRepository.observeSearch(query)

    fun folderContents(path: String) = mediaRepository.observeFolder(path)

    fun toggleFavourite(mediaId: String) {
        viewModelScope.launch { userDataRepository.toggleFavourite(mediaId) }
    }

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { settingsRepository.update { it.copy(sortMode = mode) } }
    }

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { settingsRepository.update { it.copy(viewMode = mode) } }
    }

    /** Applies the persisted sort order to a list. */
    fun applySort(items: List<LibraryItem>, mode: SortMode): List<LibraryItem> = when (mode) {
        SortMode.NEWEST -> items.sortedByDescending { it.media.addedAtMs }
        SortMode.OLDEST -> items.sortedBy { it.media.addedAtMs }
        SortMode.NAME -> items.sortedBy { it.media.title.lowercase(Locale.ROOT) }
        SortMode.DURATION -> items.sortedByDescending { it.media.durationMs }
        SortMode.SIZE -> items.sortedByDescending { it.media.sizeBytes }
    }

    fun applyFolderSort(items: List<MediaFolder>, mode: SortMode): List<MediaFolder> = when (mode) {
        SortMode.NAME -> items.sortedBy { it.name.lowercase(Locale.ROOT) }
        SortMode.SIZE -> items.sortedByDescending { it.totalBytes }
        SortMode.NEWEST -> items.sortedByDescending { it.lastAddedMs }
        SortMode.OLDEST -> items.sortedBy { it.lastAddedMs }
        SortMode.DURATION -> items.sortedByDescending { it.itemCount }
    }
}
