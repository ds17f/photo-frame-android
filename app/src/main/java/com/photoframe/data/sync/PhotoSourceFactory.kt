package com.photoframe.data.sync

import com.photoframe.data.SourceType
import com.photoframe.data.source.ConfiguredSource
import com.photoframe.data.source.PhotoSource
import com.photoframe.data.source.SourceRepository
import com.photoframe.data.source.immich.ImmichSource

/**
 * Builds a live [PhotoSource] from a stored [ConfiguredSource] + its secret (DESIGN §5). One
 * place to add each source type as later phases land. Returns null for types not yet implemented
 * (or misconfigured), which the [SyncManager] logs and skips.
 */
class PhotoSourceFactory(
    private val sourceRepository: SourceRepository,
) {
    fun create(source: ConfiguredSource): PhotoSource? = when (source.type) {
        SourceType.IMMICH -> createImmich(source)
        // GPHOTOS_PICKER (Phase 4), GPHOTOS_SHARED_ALBUM (Phase 5), LOCAL_FOLDER (Phase 6).
        else -> null
    }

    private fun createImmich(source: ConfiguredSource): PhotoSource? {
        val config = source.immichConfig ?: return null
        val apiKey = sourceRepository.immichApiKey(source.id) ?: return null
        return ImmichSource(id = source.id, config = config, apiKey = apiKey)
    }
}
