package com.malekplay.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.malekplay.data.preferences.PlayerSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: com.malekplay.data.preferences.SettingsRepository
) : ViewModel() {

    val settings = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlayerSettings()
    )

    fun update(transform: (PlayerSettings) -> PlayerSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
