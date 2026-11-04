package com.photoframe.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.data.SourceType
import com.photoframe.data.db.SyncLogEntity
import com.photoframe.data.source.ConfiguredSource
import com.photoframe.data.source.GPhotosPickerConfig
import com.photoframe.data.source.ImmichConfig
import com.photoframe.data.source.ImmichSelection
import com.photoframe.data.source.SourceHealth
import com.photoframe.data.source.SourceHealthStatus
import com.photoframe.data.source.gphotos.GPhotosAuth
import com.photoframe.data.source.gphotos.GPhotosPickerApi
import com.photoframe.data.source.gphotos.GPhotosPickerSession
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
    /** Optional key from the partner's account; delete and favorite fall back to it. Blank = none. */
    val partnerApiKey: String = "",
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

/** Where the Google Photos add/pick flow is (DESIGN §5.2). */
enum class GPhotosPhase { CONFIG, SIGNING_IN, SESSION, DOWNLOADING, DONE }

/**
 * In-progress Google Photos Picker add / "add more photos" flow (DESIGN §5.2). One-time sign-in
 * happens on the tablet (loopback OAuth); each pick is scanned on the phone from the picker QR.
 */
data class GPhotosSetupState(
    /** Non-null once the source exists (editing / "add more" / after a fresh sign-in created it). */
    val sourceId: String? = null,
    val name: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val phase: GPhotosPhase = GPhotosPhase.CONFIG,
    /** Replace the existing picked set instead of appending. */
    val replace: Boolean = false,
    val pickerUri: String? = null,
    val downloadedDone: Int = 0,
    val downloadedTotal: Int = 0,
    val addedCount: Int? = null,
    val error: String? = null,
) {
    val canSignIn: Boolean
        get() = name.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank() &&
            phase == GPhotosPhase.CONFIG
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
                ?: return@launch
            // If an Immich source with this URL already exists, edit it (rotating the key — e.g.
            // to one that adds asset.delete) instead of creating a duplicate.
            val existing = sourceRepository.listAll().firstOrNull {
                it.type == SourceType.IMMICH && it.immichConfig?.baseUrl == payload.baseUrl
            }
            _setup.value = if (existing != null) {
                ImmichSetupState(
                    editingId = existing.id,
                    name = existing.displayName,
                    baseUrl = payload.baseUrl,
                    apiKey = payload.apiKey,
                    partnerApiKey = payload.partnerApiKey
                        ?: sourceRepository.immichPartnerApiKey(existing.id).orEmpty(),
                    selection = existing.immichConfig?.selection ?: ImmichSelection(),
                )
            } else {
                ImmichSetupState(
                    name = payload.name,
                    baseUrl = payload.baseUrl,
                    apiKey = payload.apiKey,
                    partnerApiKey = payload.partnerApiKey.orEmpty(),
                )
            }
            testAndLoad()
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
            partnerApiKey = sourceRepository.immichPartnerApiKey(source.id).orEmpty(),
            selection = config.selection,
        )
    }

    fun cancelSetup() { _setup.value = null }

    fun editName(value: String) = _setup.update { it.copy(name = value) }
    fun editBaseUrl(value: String) = _setup.update { it.copy(baseUrl = value, connection = null) }
    fun editApiKey(value: String) = _setup.update { it.copy(apiKey = value, connection = null) }
    fun editPartnerApiKey(value: String) = _setup.update { it.copy(partnerApiKey = value) }

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
                sourceRepository.addImmichSource(
                    state.name.trim(), config, state.apiKey.trim(), state.partnerApiKey.trim(),
                )
            } else {
                sourceRepository.updateConfig(
                    id = state.editingId,
                    displayName = state.name.trim(),
                    config = config,
                    apiKey = state.apiKey.trim(),
                    partnerApiKey = state.partnerApiKey.trim(),
                )
            }
            ensureScheduledAndSyncNow()
            _setup.value = null
        }
    }

    // ---- Google Photos Picker flow --------------------------------------------------------

    private val _gphotos = MutableStateFlow<GPhotosSetupState?>(null)
    val gphotos: StateFlow<GPhotosSetupState?> = _gphotos.asStateFlow()

    private val gphotosApi by lazy { GPhotosPickerApi.create() }
    @Volatile private var pickCancelled = false

    /** Begin adding a new Google Photos source (pre-fill the client id from an existing one). */
    fun beginAddGphotos() {
        val existingClientId = sources.value
            .firstOrNull { it.type == SourceType.GPHOTOS_PICKER }?.gphotosConfig?.clientId
            .orEmpty()
        _gphotos.value = GPhotosSetupState(clientId = existingClientId)
    }

    /** "Add more photos" on an existing Google source — skip sign-in, go straight to a pick. */
    fun beginPickMore(source: ConfiguredSource) {
        val config = source.gphotosConfig ?: return
        _gphotos.value = GPhotosSetupState(
            sourceId = source.id,
            name = source.displayName,
            clientId = config.clientId,
            phase = GPhotosPhase.SESSION,
        )
        startPickSession(source.id)
    }

    fun editGphotosName(value: String) = _gphotos.update { it.copy(name = value, error = null) }
    fun editGphotosClientId(value: String) = _gphotos.update { it.copy(clientId = value, error = null) }
    fun editGphotosClientSecret(value: String) = _gphotos.update { it.copy(clientSecret = value, error = null) }
    fun setGphotosReplace(on: Boolean) = _gphotos.update { it.copy(replace = on) }

    fun cancelGphotos() {
        pickCancelled = true
        _gphotos.value = null
    }

    /** Launch the tablet-browser sign-in, create the source on success, then start a pick session. */
    fun signInGphotos() {
        val state = _gphotos.value ?: return
        if (!state.canSignIn) return
        _gphotos.update { it.copy(phase = GPhotosPhase.SIGNING_IN, error = null) }
        viewModelScope.launch {
            try {
                val tokens = GPhotosAuth.authorize(
                    getApplication(), state.clientId.trim(), state.clientSecret.trim(),
                )
                val refresh = tokens.refreshToken ?: throw GPhotosAuth.AuthException(
                    "Google didn't return a refresh token. Set the OAuth consent screen to " +
                        "\"Production\" (Testing tokens expire), then sign in again.",
                )
                val id = sourceRepository.addGphotosSource(
                    displayName = state.name.trim(),
                    config = GPhotosPickerConfig(clientId = state.clientId.trim()),
                    clientSecret = state.clientSecret.trim(),
                    refreshToken = refresh,
                )
                _gphotos.update { it.copy(sourceId = id, phase = GPhotosPhase.SESSION) }
                startPickSession(id)
            } catch (e: Exception) {
                _gphotos.update {
                    it.copy(phase = GPhotosPhase.CONFIG, error = e.message ?: "Sign-in failed")
                }
            }
        }
    }

    /** Create a picker session, wait for the phone pick, download every item, then finish. */
    private fun startPickSession(sourceId: String) {
        pickCancelled = false
        viewModelScope.launch {
            try {
                val session = GPhotosPickerSession(
                    sourceId = sourceId,
                    api = gphotosApi,
                    cache = container.cache,
                    photoDao = container.database.photoDao(),
                    accessTokenProvider = tokenProvider(sourceId),
                )
                val info = session.createSession()
                _gphotos.update { it.copy(pickerUri = info.pickerUri, error = null) }

                val picked = session.awaitPicked(info) { !pickCancelled && _gphotos.value != null }
                if (!picked) {
                    session.deleteSession(info.sessionId)
                    if (!pickCancelled) {
                        _gphotos.update { it.copy(error = "Picking timed out — try again") }
                    }
                    return@launch
                }

                _gphotos.update { it.copy(phase = GPhotosPhase.DOWNLOADING) }
                val replace = _gphotos.value?.replace ?: false
                val added = session.downloadPicked(info, replace) { done, total ->
                    _gphotos.update { it.copy(downloadedDone = done, downloadedTotal = total) }
                }
                session.deleteSession(info.sessionId)

                val count = container.database.photoDao().countForSource(sourceId)
                sourceRepository.recordGphotosPick(sourceId, count, System.currentTimeMillis())
                ensureScheduledAndSyncNow()
                _gphotos.update { it.copy(phase = GPhotosPhase.DONE, addedCount = added) }
            } catch (e: Exception) {
                _gphotos.update { it.copy(error = e.message ?: "Picking failed") }
            }
        }
    }

    /** Fresh-access-token provider backed by the source's stored client secret + refresh token. */
    private fun tokenProvider(sourceId: String): suspend () -> String = {
        val config = sourceRepository.byId(sourceId)?.gphotosConfig
            ?: throw GPhotosAuth.AuthException("Source is missing its Google configuration")
        val secret = sourceRepository.gphotosClientSecret(sourceId)
            ?: throw GPhotosAuth.AuthException("Source is missing its Google client secret")
        val refresh = sourceRepository.gphotosRefreshToken(sourceId)
            ?: throw GPhotosAuth.AuthException("Not signed in to Google — add the source again")
        GPhotosAuth.refresh(config.clientId, secret, refresh).accessToken
    }

    @JvmName("updateGphotos")
    private inline fun MutableStateFlow<GPhotosSetupState?>.update(
        crossinline transform: (GPhotosSetupState) -> GPhotosSetupState,
    ) {
        value = value?.let(transform)
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
