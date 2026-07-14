package com.photoframe.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.data.cache.CacheLocation
import com.photoframe.settings.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live storage facts for the Storage screen: which locations exist and their free space. */
data class StorageInfo(
    val location: CacheLocation = CacheLocation.INTERNAL,
    val sdCardAvailable: Boolean = false,
    val internalFreeBytes: Long = 0,
    val sdFreeBytes: Long? = null,
    val switching: Boolean = false,
)

/**
 * Backs the Storage screen (streaming-cache model): cache location (Internal / SD card) + the cache
 * cap. Switching location deletes the old files, nulls all cache paths, and kicks the prefetcher to
 * repopulate the new location — the index and show history are untouched.
 */
class StorageViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PhotoFrameApp).container
    private val storageRepository = container.storageSettingsRepository
    private val syncSettingsRepository = container.syncSettingsRepository
    private val cache = container.cache
    private val cacheMaintainer = container.cacheMaintainer
    private val prefetcher = container.photoPrefetcher

    val syncSettings: StateFlow<SyncSettings> =
        syncSettingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSettings())

    val cacheUsageBytes: StateFlow<Long> =
        container.database.photoDao().observeTotalBytes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _info = MutableStateFlow(StorageInfo())
    val info: StateFlow<StorageInfo> = _info.asStateFlow()

    init {
        viewModelScope.launch { refreshInfo() }
    }

    private suspend fun refreshInfo() {
        val location = storageRepository.current().location
        val (sdAvailable, internalFree, sdFree) = withContext(Dispatchers.IO) {
            Triple(
                cache.sdCardAvailable(),
                cache.usableBytes(CacheLocation.INTERNAL) ?: 0L,
                cache.usableBytes(CacheLocation.SD_CARD),
            )
        }
        _info.value = _info.value.copy(
            location = location,
            sdCardAvailable = sdAvailable,
            internalFreeBytes = internalFree,
            sdFreeBytes = sdFree,
        )
    }

    fun setLocation(location: CacheLocation) {
        if (location == _info.value.location || _info.value.switching) return
        _info.value = _info.value.copy(switching = true)
        viewModelScope.launch {
            storageRepository.setLocation(location)
            cacheMaintainer.switchLocation(location)
            prefetcher.kick() // repopulate the new location now, not on the next 30s tick
            refreshInfo()
            _info.value = _info.value.copy(switching = false)
        }
    }

    fun setCap(capBytes: Long) {
        viewModelScope.launch {
            syncSettingsRepository.update { it.copy(cacheCapBytes = capBytes) }
        }
    }
}
