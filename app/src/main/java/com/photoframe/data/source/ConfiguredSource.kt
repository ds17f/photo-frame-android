package com.photoframe.data.source

import com.photoframe.data.SourceType
import com.photoframe.data.db.SourceEntity
import java.time.Instant

/**
 * Domain view of a configured source: the persisted [SourceEntity] with its [configJson] parsed
 * into a typed [SourceConfig] and its health projected into [SourceHealth]. The UI and sync
 * engine work with this; the DAO row is an implementation detail of [SourceRepository].
 */
data class ConfiguredSource(
    val id: String,
    val type: SourceType,
    val displayName: String,
    val enabled: Boolean,
    val config: SourceConfig,
    val health: SourceHealth,
    val lastSyncAt: Instant?,
    val photoCount: Int,
) {
    val immichConfig: ImmichConfig? get() = config as? ImmichConfig

    companion object {
        fun fromEntity(entity: SourceEntity): ConfiguredSource = ConfiguredSource(
            id = entity.id,
            type = runCatching { SourceType.valueOf(entity.type) }.getOrDefault(SourceType.IMMICH),
            displayName = entity.displayName,
            enabled = entity.enabled,
            config = sourceConfigFromJson(entity.configJson),
            health = SourceHealth(
                status = runCatching { SourceHealthStatus.valueOf(entity.health) }
                    .getOrDefault(SourceHealthStatus.UNKNOWN),
                message = entity.lastError,
            ),
            lastSyncAt = entity.lastSyncAt?.let(Instant::ofEpochMilli),
            photoCount = entity.photoCount,
        )
    }
}
