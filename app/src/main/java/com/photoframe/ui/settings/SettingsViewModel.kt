package com.photoframe.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.settings.DisplaySettings
import com.photoframe.settings.DisplaySettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: DisplaySettingsRepository =
        (app as PhotoFrameApp).container.displaySettingsRepository

    val settings: StateFlow<DisplaySettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, DisplaySettings())

    fun update(transform: (DisplaySettings) -> DisplaySettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
