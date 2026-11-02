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

/**
 * نموذج بيانات المجلد المشابه لـ MX Player
 */
data class FolderUiModel(
    val folderPath: String,
    val folderName: String,
    val videoCount: Int,
    val newVideosCount: Int = 0,
    val latestMediaUri: Uri? = null,
    val mediaList: List<MediaWithDetails> = emptyList()
)

/**
 * خيارات الترتيب كما في المشغلات الاحترافية
 */
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

    /**
     * تجميع الوسائط في مجلدات، مع البحث والترتيب.
     */
    val folders: StateFlow<List<FolderUiModel>> =
        combine(_rawMedia, _searchQuery, _sortMode) { mediaList, query, sort ->
            if (mediaList.isEmpty()) {
                return@combine emptyList<FolderUiModel>()
            }

            val grouped = mediaList.groupBy { it.media.bucketId }
            val folderList = grouped.map { (bucketId, items) ->
                val folderName = items.firstOrNull()?.media?.bucketName ?: "مجلدات أخرى"
                val newCount = items.count(::isMediaConsideredNew)
                val latestItemUri = runCatching {
                    Uri.parse(items.firstOrNull()?.media?.uri.orEmpty())
                }.getOrNull()

                FolderUiModel(
                    folderPath = bucketId,
                    folderName = folderName,
                    videoCount = items.size,
                    newVideosCount = newCount,
                    latestMediaUri = latestItemUri,
                    mediaList = items
                )
            }

            val filtered = if (query.isBlank()) {
                folderList
            } else {
                folderList.filter { folder ->
                    folder.folderName.contains(query, ignoreCase = true) ||
                        folder.mediaList.any {
                            it.media.title.contains(query, ignoreCase = true)
                        }
                }
            }

            when (sort) {
                SortMode.NAME_ASC -> filtered.sortedBy { it.folderName.lowercase() }
                SortMode.NAME_DESC -> filtered.sortedByDescending { it.folderName.lowercase() }
                SortMode.COUNT_DESC -> filtered.sortedByDescending { it.videoCount }
                SortMode.DATE_NEWEST -> filtered
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000L),
                emptyList()
            )

    init {
        refresh()
    }

    /**
     * تحديث المكتبة ومراقبة أي تغييرات من المستودع.
     */
    fun refresh() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                repository.observeLibrary().collect { records ->
                    _rawMedia.value = records
                    _isLoading.value = false

                    _selectedFolder.value?.let { current ->
                        _selectedFolder.value =
                            folders.value.find { it.folderPath == current.folderPath }
                    }
                }
            } catch (security: SecurityException) {
                _error.value =
                    "تعذر الوصول إلى بعض ملفات الوسائط. تحقق من أذونات المكتبة."
                _isLoading.value = false
            } catch (error: Exception) {
                val errorMessage = error.localizedMessage ?: "خطأ غير معروف"
                _error.value = "تعذر تحديث المكتبة: $errorMessage"
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
        val position = item.playbackState?.position ?: 0L
        val isFinished = item.playbackState?.isCompleted ?: false
        return position == 0L && !isFinished
    }

    override fun onCleared() {
        observationJob?.cancel()
        super.onCleared()
    }
}
