package com.photoframe.data.source

import com.photoframe.data.db.SourceDao
import com.photoframe.data.db.SourceEntity
import com.photoframe.data.secrets.EncryptedSecrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * CRUD over configured sources (DESIGN §5/§11). Owns the mapping between the persisted
 * [SourceEntity] and the typed [ConfiguredSource], and the split between config (Room) and
 * secrets (EncryptedSharedPreferences, §12). The sync engine and source-management UI both go
 * through here so the config/secret split stays in one place.
 */
class SourceRepository(
    private val sourceDao: SourceDao,
    private val secrets: EncryptedSecrets,
) {

    fun observeSources(): Flow<List<ConfiguredSource>> =
        sourceDao.observeAll().map { rows -> rows.map(ConfiguredSource::fromEntity) }

    suspend fun listEnabled(): List<ConfiguredSource> =
        sourceDao.listEnabled().map(ConfiguredSource::fromEntity)

    suspend fun listAll(): List<ConfiguredSource> =
        sourceDao.listAll().map(ConfiguredSource::fromEntity)

    suspend fun byId(id: String): ConfiguredSource? =
        sourceDao.byId(id)?.let(ConfiguredSource::fromEntity)

    /** Create a new Immich source, stashing the API key in encrypted storage. Returns its id. */
    suspend fun addImmichSource(
        displayName: String,
        config: ImmichConfig,
        apiKey: String,
    ): String {
        val id = "immich-" + UUID.randomUUID().toString().take(8)
        secrets.setImmichApiKey(id, apiKey)
        sourceDao.upsert(
            SourceEntity(
                id = id,
                type = config.type.name,
                displayName = displayName,
                enabled = true,
                configJson = config.toJson(),
                health = SourceHealthStatus.UNKNOWN.name,
                lastSyncAt = null,
                lastError = null,
                photoCount = 0,
            ),
        )
        return id
    }

    /**
     * Create a new Google Photos Picker source, stashing the OAuth client secret + refresh token in
     * encrypted storage (DESIGN §5.2). Returns its id. Photos are populated separately by the pick
     * session — this just registers the account so periodic sync keeps (never mirrors) its set.
     */
    suspend fun addGphotosSource(
        displayName: String,
        config: GPhotosPickerConfig,
        clientSecret: String,
        refreshToken: String,
    ): String {
        val id = "gphotos-" + UUID.randomUUID().toString().take(8)
        secrets.setGphotosClientSecret(id, clientSecret)
        secrets.setGphotosRefreshToken(id, refreshToken)
        sourceDao.upsert(
            SourceEntity(
                id = id,
                type = config.type.name,
                displayName = displayName,
                enabled = true,
                configJson = config.toJson(),
                health = SourceHealthStatus.UNKNOWN.name,
                lastSyncAt = null,
                lastError = null,
                photoCount = 0,
            ),
        )
        return id
    }

    /** Replace an existing source's non-secret config; optionally rotate its API key. */
    suspend fun updateConfig(
        id: String,
        displayName: String? = null,
        config: SourceConfig? = null,
        apiKey: String? = null,
    ) {
        val existing = sourceDao.byId(id) ?: return
        if (apiKey != null && config is ImmichConfig) secrets.setImmichApiKey(id, apiKey)
        sourceDao.upsert(
            existing.copy(
                displayName = displayName ?: existing.displayName,
                configJson = config?.toJson() ?: existing.configJson,
            ),
        )
    }

    /** Record a completed pick: bump the picked count + timestamp on the source's config. */
    suspend fun recordGphotosPick(id: String, pickedCount: Int, lastPickedAtMillis: Long) {
        val existing = sourceDao.byId(id) ?: return
        val config = (runCatching { sourceConfigFromJson(existing.configJson) }.getOrNull()
            as? GPhotosPickerConfig) ?: return
        sourceDao.upsert(
            existing.copy(
                configJson = config.copy(
                    pickedCount = pickedCount,
                    lastPickedAt = lastPickedAtMillis,
                ).toJson(),
            ),
        )
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = sourceDao.setEnabled(id, enabled)

    suspend fun delete(id: String) {
        secrets.clearForSource(id)
        sourceDao.delete(id)
    }

    fun immichApiKey(sourceId: String): String? = secrets.getImmichApiKey(sourceId)

    fun gphotosRefreshToken(sourceId: String): String? = secrets.getGphotosRefreshToken(sourceId)
    fun gphotosClientSecret(sourceId: String): String? = secrets.getGphotosClientSecret(sourceId)

    suspend fun recordSync(
        id: String,
        health: SourceHealth,
        lastSyncAtMillis: Long?,
        photoCount: Int,
    ) = sourceDao.updateHealth(
        id = id,
        health = health.status.name,
        lastError = health.message,
        lastSyncAt = lastSyncAtMillis,
        photoCount = photoCount,
    )
}
