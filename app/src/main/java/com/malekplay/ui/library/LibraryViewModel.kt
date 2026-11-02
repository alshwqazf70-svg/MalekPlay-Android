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
import java.io.File
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

    // القائمة الخام لجميع الوسائط من قاعدة البيانات
    private val _rawMedia = MutableStateFlow<List<MediaWithDetails>>(emptyList())
    val rawMedia: StateFlow<List<MediaWithDetails>> = _rawMedia.asStateFlow()

    // حالة التحميل والأخطاء
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // نص البحث
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // نوع الترتيب
    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode = _sortMode.asStateFlow()

    // المجلد المفتوح حالياً (إذا كان null فنحن في الصفحة الرئيسية للمجلدات)
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

        // 1. تجميع الفيديوهات حسب مسار المجلد الأب
        val grouped = mediaList.groupBy { mediaItem ->
            extractFolderNameAndPath(mediaItem)
        }

        // 2. تحويل المجموعات إلى FolderUiModel
        val folderList = grouped.map { (folderInfo, items) ->
            val (folderName, folderPath) = folderInfo
            
            // حساب الفيديوهات الجديدة أو غير المكتملة
            val newCount = items.count { isMediaConsideredNew(it) }
            val latestItemUri = extractThumbnailUri(items.firstOrNull())

            FolderUiModel(
                folderPath = folderPath,
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
            folderList.filter { 
                it.folderName.contains(query, ignoreCase = true) ||
                it.mediaList.any { media -> extractDisplayName(media).contains(query, ignoreCase = true) }
            }
        }

        // 4. تطبيق الترتيب
        when (sort) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.folderName.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.folderName.lowercase() }
            SortMode.COUNT_DESC -> filtered.sortedByDescending { it.videoCount }
            SortMode.DATE_NEWEST -> filtered // حسب ترتيب MediaStore التلقائي
        }
    }.flowOn(Dispatchers.Default)
     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * تحديث الوسائط ومراقبة التغييرات
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
                    
                    // تحديث المجلد المحدد إذا كان مفتوحاً
                    _selectedFolder.value?.let { current ->
                        val updated = folders.value.find { it.folderPath == current.folderPath }
                        _selectedFolder.value = updated
                    }
                }
            } catch (security: SecurityException) {
                _error.value = "تعذر الوصول إلى بعض ملفات الوسائط. تحقق من أذونات المكتبة."
                _isLoading.value = false
            } catch (error: Exception) {
                _error.value = "تعذر تحديث المكتبة: ${error.localizedMessage ?: "خطأ غير معروف"}"
                _isLoading.value = false
            }
        }
    }

    // دوال التحكم بالواجهة (UI Actions)
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
            return true // تم الرجوع للمجلدات
        }
        return false // كان أصلاً في الرئيسية
    }

    fun clearForPermissionDenied() {
        observationJob?.cancel()
        observationJob = null
        _rawMedia.value = emptyList()
        _isLoading.value = false
        _error.value = null
        _selectedFolder.value = null
    }

    override fun onCleared() {
        observationJob?.cancel()
        super.onCleared()
    }

    // --- دوال مساعدة لاستخراج البيانات بأمان ومرونة ---

    private fun extractFolderNameAndPath(item: MediaWithDetails): Pair<String, String> {
        return try {
            // محاولة قراءة المسار من الكائن، أو اشتقاقه من الـ Uri/Path
            val path = item.media.path ?: item.media.uriString ?: ""
            if (path.isNotEmpty() && path.contains(File.separator)) {
                val file = File(path)
                val parentFile = file.parentFile
                val name = parentFile?.name ?: "وحدة التخزين"
                val parentPath = parentFile?.absolutePath ?: "/"
                Pair(name, parentPath)
            } else {
                Pair(item.media.bucketDisplayName ?: "أخرى", item.media.bucketId ?: "default")
            }
        } catch (e: Exception) {
            Pair("أخرى", "other")
        }
    }

    private fun extractDisplayName(item: MediaWithDetails): String {
        return item.media.displayName ?: item.media.title ?: "فيديو بدون عنوان"
    }

    private fun extractThumbnailUri(item: MediaWithDetails?): Uri? {
        if (item == null) return null
        return try {
            item.media.uriString?.let { Uri.parse(it) } ?: item.media.contentUri
        } catch (e: Exception) {
            null
        }
    }

    private fun isMediaConsideredNew(item: MediaWithDetails): Boolean {
        // إذا كان الفيديو غير مكتمل المشاهدة أو مضاف حديثاً
        return try {
            val position = item.playbackState?.lastPositionMs ?: 0L
            val isFinished = item.playbackState?.isCompleted ?: false
            position == 0L && !isFinished
        } catch (e: Exception) {
            false
        }
    }
}
