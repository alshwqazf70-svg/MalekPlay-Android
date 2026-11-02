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
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode = _sortMode.asStateFlow()

    private val _selectedFolder = MutableStateFlow<FolderUiModel?>(null)
    val selectedFolder = _selectedFolder.asStateFlow()

    private var observationJob: Job? = null

    init {
        refresh()
    }

    /**
     * تدفق معالجة وتجميع المجلدات تلقائياً في الخلفية (MX Player Logic)
     */
    val folders: StateFlow<List<FolderUiModel>> = combine(_rawMedia, _searchQuery, _sortMode) { mediaList, query, sort ->
        if (mediaList.isEmpty()) return@combine emptyList()

        // 1. تجميع الفيديوهات حسب معرف المجلد الحقيقي
        val grouped = mediaList.groupBy { it.media.bucketId }

        // 2. تحويل المجموعات إلى FolderUiModel
        val folderList = grouped.map { (bucketId, items) ->
            val folderName = items.firstOrNull()?.media?.bucketName ?: "مجلدات أخرى"
            val newCount = items.count { isMediaConsideredNew(it) }
            val latestItemUri = runCatching { Uri.parse(items.firstOrNull()?.media?.uri.orEmpty()) }.getOrNull()

            FolderUiModel(
                folderPath = bucketId,
                folderName = folderName,
                videoCount = items.size,
                newVideosCount = newCount,
                latestMediaUri = latestItemUri,
                mediaList = items
            )
        }

        // 3. فلترة البحث
        val filtered = if (query.isBlank()) {
            folderList
        } else {
            folderList.filter { folder ->
                folder.folderName.contains(query, ignoreCase = true) ||
                folder.mediaList.any { it.media.title.contains(query, ignoreCase = true) }
            }
        }

        // 4. تطبيق الترتيب
        when (sort) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.folderName.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.folderName.lowercase() }
            SortMode.COUNT_DESC -> filtered.sortedByDescending { it.videoCount }
            SortMode.DATE_NEWEST -> filtered
        }
    }.flowOn(Dispatchers.D
