package com.malekplay.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.data.repository.MediaPickerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaPickerViewModel @Inject constructor(
    private val repository: MediaPickerRepository
) : ViewModel() {
    private val _mediaFiles = MutableStateFlow<List<MediaFile>>(emptyList())
    val mediaFiles = _mediaFiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var loadJob: Job? = null

    fun loadMedia(type: MediaType) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _mediaFiles.value = repository.getMediaFiles(type)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SecurityException) {
                _mediaFiles.value = emptyList()
                _error.value = "يلزم منح إذن الوصول إلى هذا النوع من الوسائط."
            } catch (failure: Exception) {
                _mediaFiles.value = emptyList()
                _error.value = failure.localizedMessage ?: "تعذر تحميل ملفات الوسائط."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clear() {
        loadJob?.cancel()
        _mediaFiles.value = emptyList()
        _error.value = null
        _isLoading.value = false
    }
}
