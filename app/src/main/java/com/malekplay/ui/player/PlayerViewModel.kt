package com.malekplay.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.data.repository.MediaRepository
import com.malekplay.domain.model.MediaWithDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderUiModel(
    val folderPath: String,
    val folderName: String,
    val videoCount: Int,
    val newVideosCount: Int = 0,
    val latestMediaUri: Uri? = null,
    val mediaList: List<MediaWithDetails> = emptyList()
)

enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    DATE_NEWEST,
    COUNT_DESC
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val _rawMedia = MutableStateFlow<List<MediaWithDetails>>(emptyList())
    val rawMedia: StateFlow<List<MediaWithDetails>> = _rawMedia.asStateFlow()
    val media: StateFlow<List<MediaWithDetails>> = _rawMedia.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _selectedFolder = MutableStateFlow<FolderUiModel?>(null)
    val selectedFolder: StateFlow<FolderUiModel?> = _selectedFolder.asStateFlow()

    private var observationJob: Job? = null

    val folders: StateFlow<List<FolderUiModel>> = combine(_rawMedia, _searchQuery, _sortMode) { mediaList, query, sort ->
        if (mediaList.isEmpty()) {
            return@combine emptyList<FolderUiModel>()
        }

        val grouped = mediaList.groupBy { it.media.bucketId }
        val list = mutableListOf<FolderUiModel>()

        for ((bucketId, items) in grouped) {
            val fName = items.firstOrNull()?.media?.bucketName ?: "مجلدات أخرى"
            val nVideos = items.count { isMediaConsideredNew(it) }
            val u = runCatching { Uri.parse(items.firstOrNull()?.media?.uri.orEmpty()) }.getOrNull()
            list.add(
                FolderUiModel(
                    folderPath = bucketId,
                    folderName = fName,
                    videoCount = items.size,
                    newVideosCount = nVideos,
                    latestMediaUri = u,
                    mediaList = items
                )
            )
        }

        val filtered = if (query.isBlank()) {
            list
        } else {
            list.filter { folder ->
                folder.folderName.contains(query, ignoreCase = true) ||
                folder.mediaList.any { it.media.title.contains(query, ignoreCase = true) }
            }
        }

        when (sort) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.folderName.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.folderName.lowercase() }
            SortMode.COUNT_DESC -> filtered.sortedByDescending { it.videoCount }
            SortMode.DATE_NEWEST -> filtered
        }
    }.flowOn(Dispatchers.Default)
     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                repository.observeLibrary().collect { records ->
                    _rawMedia.value = records
                    _isLoading.value = false
                    val current = _selectedFolder.value
                    if (current != null) {
                        val updated = folders.value.find { it.folderPath == current.folderPath }
                        _selectedFolder.value = updated
                    }
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "حدث خطأ أثناء تحميل الوسائط"
                _isLoading.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun selectFolder(folder: FolderUiModel) {
        _selectedFolder.value = folder
    }

    fun navigateBackFromFolder(): Boolean {
        if (_selectedFolder.value != null) {
            _selectedFolder.value = null
            return true
        }
        return false
    }

    fun clearForPermissionDenied() {
        observationJob?.cancel()
        observationJob = null
        _rawMedia.value = emptyList()
        _isLoading.value = false
        _error.value = null
        _selectedFolder.value = null
    }

    private fun isMediaConsideredNew(item: MediaWithDetails): Boolean {
        val pos = item.playbackState?.position ?: 0L
        val finished = item.playbackState?.isCompleted ?: false
        return pos == 0L && !finished
    }

    override fun onCleared() {
        observationJob?.cancel()
        super.onCleared()
    }
}
