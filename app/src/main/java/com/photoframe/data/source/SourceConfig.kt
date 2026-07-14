package com.photoframe.data.source

import com.photoframe.data.SourceType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-source-type configuration, persisted as JSON in [com.photoframe.data.db.SourceEntity.configJson]
 * (DESIGN §5). Secrets (API keys, OAuth tokens, share URLs) are NEVER stored here — they live in
 * [com.photoframe.data.secrets.EncryptedSecrets], keyed by source id (§12).
 *
 * Only [ImmichConfig] is implemented in Phase 2; the sealed hierarchy leaves room for the Google
 * and local sources in later phases.
 */
@Serializable
sealed interface SourceConfig {
    val type: SourceType
}

/**
 * Immich source config (DESIGN §5.1). The `x-api-key` is held separately in EncryptedSecrets;
 * only the base URL and non-secret selection live here. Selection facets are combinable — the
 * desired set is their union.
 */
@Serializable
@SerialName("immich")
data class ImmichConfig(
    val baseUrl: String,
    val selection: ImmichSelection = ImmichSelection(),
) : SourceConfig {
    override val type: SourceType get() = SourceType.IMMICH
}

@Serializable
data class ImmichSelection(
    val albumIds: List<String> = emptyList(),
    val personIds: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
    /** Everything (respecting archived/trashed/video filters), optionally windowed by [rollingWindowDays]. */
    val wholeLibrary: Boolean = false,
    /** When set (and > 0), restrict to assets taken within this many days of now. */
    val rollingWindowDays: Int? = null,
) {
    /** True when nothing is selected yet — the source has no work to do until the user picks. */
    val isEmpty: Boolean
        get() = albumIds.isEmpty() && personIds.isEmpty() && !favoritesOnly && !wholeLibrary
}

/** Deletion policy for photos that vanish from a source's remote selection (DESIGN §6). */
@Serializable
enum class DeletionPolicy {
    /** Remote removal removes from the frame (default — "I deleted it for a reason"). */
    MIRROR,

    /** Keep locally until LRU eviction reclaims the space. */
    KEEP_UNTIL_EVICTED,
}

/** Single Json instance for source config (de)serialization; class-discriminator sealed. */
val SourceConfigJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "kind"
}

fun SourceConfig.toJson(): String = SourceConfigJson.encodeToString(SourceConfig.serializer(), this)

fun sourceConfigFromJson(json: String): SourceConfig =
    SourceConfigJson.decodeFromString(SourceConfig.serializer(), json)
