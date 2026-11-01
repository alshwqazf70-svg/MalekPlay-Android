package com.malekplay.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.data.preferences.PlayerSettings
import com.malekplay.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    val settings = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlayerSettings()
    )

    fun update(transform: (PlayerSettings) -> PlayerSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
