package com.photoframe.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.data.db.SyncLogEntity
import com.photoframe.data.source.ConfiguredSource
import com.photoframe.data.source.ImmichConfig
import com.photoframe.data.source.ImmichSelection
import com.photoframe.data.source.SourceHealth
import com.photoframe.data.source.SourceHealthStatus
import com.photoframe.data.source.immich.ImmichAlbumDto
import com.photoframe.data.source.immich.ImmichCatalog
import com.photoframe.data.source.immich.ImmichImport
import com.photoframe.data.source.immich.ImmichPersonDto
import com.photoframe.data.sync.SyncScheduler
import com.photoframe.settings.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-progress Immich add/edit flow (DESIGN §5.1/§11). */
data class ImmichSetupState(
    val editingId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val testing: Boolean = false,
    val connection: SourceHealth? = null,
    val loadingCatalog: Boolean = false,
    val albums: List<ImmichAlbumDto> = emptyList(),
    val people: List<ImmichPersonDto> = emptyList(),
    val selection: ImmichSelection = ImmichSelection(),
    val saving: Boolean = false,
) {
    val connected: Boolean get() = connection?.status == SourceHealthStatus.OK
    val canSave: Boolean get() = connected && name.isNotBlank() && !selection.isEmpty && !saving
}

/**
 * Backs the source-management screens (DESIGN §11): observes configured sources, health and the
 * sync log, and drives the Immich add/edit flow (test connection → list albums/people → save →
 * kick a sync). All source config goes through [com.photoframe.data.source.SourceRepository].
 */
class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PhotoFrameApp).container
    private val sourceRepository = container.sourceRepository
    private val syncSettingsRepository = container.syncSettingsRepository
    private val syncLogDao = container.database.syncLogDao()

    val sources: StateFlow<List<ConfiguredSource>> =
        sourceRepository.observeSources()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null = still loading; true = no sources configured (show the first-run wizard). */
    val firstRunNeeded: StateFlow<Boolean?> =
        sourceRepository.observeSources()
            .map { it.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncSettings: StateFlow<SyncSettings> =
        syncSettingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSettings())

    val syncLog: StateFlow<List<SyncLogEntity>> =
        syncLogDao.observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cacheUsageBytes: StateFlow<Long> =
        container.database.photoDao().observeTotalBytes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _setup = MutableStateFlow<ImmichSetupState?>(null)
    val setup: StateFlow<ImmichSetupState?> = _setup.asStateFlow()

    init {
        // adb key hand-off: if a config was pushed (see `make immich-config`), open the setup
        // flow pre-filled and auto-test so the user never types the API key on the tablet.
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) { ImmichImport.consume(getApplication()) }
            if (payload != null) {
                _setup.value = ImmichSetupState(
                    name = payload.name,
                    baseUrl = payload.baseUrl,
                    apiKey = payload.apiKey,
                )
                testAndLoad()
            }
        }
    }

    // ---- Immich setup flow ----------------------------------------------------------------

    fun beginAddImmich() {
        _setup.value = ImmichSetupState()
    }

    fun beginEditImmich(source: ConfiguredSource) {
        val config = source.immichConfig ?: return
        _setup.value = ImmichSetupState(
            editingId = source.id,
            name = source.displayName,
            baseUrl = config.baseUrl,
            apiKey = sourceRepository.immichApiKey(source.id).orEmpty(),
            selection = config.selection,
        )
    }

    fun cancelSetup() { _setup.value = null }

    fun editName(value: String) = _setup.update { it.copy(name = value) }
    fun editBaseUrl(value: String) = _setup.update { it.copy(baseUrl = value, connection = null) }
    fun editApiKey(value: String) = _setup.update { it.copy(apiKey = value, connection = null) }

    /** Test the connection; on success, load albums + people for selection. */
    fun testAndLoad() {
        val state = _setup.value ?: return
        if (state.baseUrl.isBlank() || state.apiKey.isBlank()) return
        _setup.update { it.copy(testing = true, connection = null) }
        viewModelScope.launch {
            val catalog = ImmichCatalog.create(state.baseUrl, state.apiKey)
            val health = catalog.testConnection()
            _setup.update { it.copy(testing = false, connection = health) }
            if (health.status != SourceHealthStatus.OK) return@launch
            _setup.update { it.copy(loadingCatalog = true) }
            val albums = runCatching { catalog.albums() }.getOrDefault(emptyList())
            val people = runCatching { catalog.people() }.getOrDefault(emptyList())
            _setup.update { it.copy(loadingCatalog = false, albums = albums, people = people) }
        }
    }

    fun toggleAlbum(id: String) = _setup.update { s ->
        val next = s.selection.albumIds.toggle(id)
        s.copy(selection = s.selection.copy(albumIds = next))
    }

    fun togglePerson(id: String) = _setup.update { s ->
        val next = s.selection.personIds.toggle(id)
        s.copy(selection = s.selection.copy(personIds = next))
    }

    fun setFavorites(on: Boolean) = _setup.update {
        it.copy(selection = it.selection.copy(favoritesOnly = on))
    }

    fun setWholeLibrary(on: Boolean) = _setup.update {
        it.copy(selection = it.selection.copy(wholeLibrary = on))
    }

    fun setRollingWindow(days: Int?) = _setup.update {
        it.copy(selection = it.selection.copy(rollingWindowDays = days))
    }

    fun save() {
        val state = _setup.value ?: return
        if (!state.canSave) return
        _setup.update { it.copy(saving = true) }
        viewModelScope.launch {
            val config = ImmichConfig(baseUrl = state.baseUrl.trim(), selection = state.selection)
            if (state.editingId == null) {
                sourceRepository.addImmichSource(state.name.trim(), config, state.apiKey.trim())
            } else {
                sourceRepository.updateConfig(
                    id = state.editingId,
                    displayName = state.name.trim(),
                    config = config,
                    apiKey = state.apiKey.trim(),
                )
            }
            ensureScheduledAndSyncNow()
            _setup.value = null
        }
    }

    // ---- Source list actions --------------------------------------------------------------

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        sourceRepository.setEnabled(id, enabled)
    }

    fun delete(id: String) = viewModelScope.launch { sourceRepository.delete(id) }

    fun syncNow() = viewModelScope.launch { ensureScheduledAndSyncNow() }

    fun updateSyncSettings(transform: (SyncSettings) -> SyncSettings) = viewModelScope.launch {
        syncSettingsRepository.update(transform)
        SyncScheduler.reschedule(getApplication(), syncSettingsRepository.current())
    }

    private suspend fun ensureScheduledAndSyncNow() {
        val app = getApplication<Application>()
        SyncScheduler.reschedule(app, syncSettingsRepository.current())
        SyncScheduler.syncNow(app)
    }

    private fun List<String>.toggle(id: String): List<String> =
        if (contains(id)) this - id else this + id

    private inline fun MutableStateFlow<ImmichSetupState?>.update(
        crossinline transform: (ImmichSetupState) -> ImmichSetupState,
    ) {
        value = value?.let(transform)
    }
}
