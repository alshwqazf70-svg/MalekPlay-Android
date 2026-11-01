package com.malekplay.ui.library

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.domain.model.MediaWithDetails
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns library loading and Room observation outside the Activity/Composable.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: com.malekplay.data.repository.MediaRepository
) : ViewModel() {

    private val _media = MutableStateFlow<List<MediaWithDetails>>(emptyList())
    val media = _media.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var observationJob: Job? = null

    fun refresh() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                repository.observeLibrary().collect { records ->
                    _media.value = records
                    _isLoading.value = false
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

    fun clearForPermissionDenied() {
        observationJob?.cancel()
        observationJob = null
        _media.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }

    override fun onCleared() {
        observationJob?.cancel()
        super.onCleared()
    }
}
