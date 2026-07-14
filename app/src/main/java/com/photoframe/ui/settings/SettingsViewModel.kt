package com.photoframe.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.data.sync.SyncScheduler
import com.photoframe.settings.DisplaySettings
import com.photoframe.settings.DisplaySettingsRepository
import com.photoframe.settings.ScheduleSettings
import com.photoframe.settings.ScheduleSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as PhotoFrameApp).container
    private val repository: DisplaySettingsRepository = container.displaySettingsRepository
    private val scheduleRepository: ScheduleSettingsRepository = container.scheduleSettingsRepository

    val settings: StateFlow<DisplaySettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, DisplaySettings())

    val schedule: StateFlow<ScheduleSettings> =
        scheduleRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleSettings())

    fun update(transform: (DisplaySettings) -> DisplaySettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun updateSchedule(transform: (ScheduleSettings) -> ScheduleSettings) {
        viewModelScope.launch {
            scheduleRepository.update(transform)
            // Quiet-hours changes affect whether the periodic sync runs; re-apply its schedule.
            SyncScheduler.reschedule(getApplication(), container.syncSettingsRepository.current())
        }
    }
}
