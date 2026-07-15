package com.photoframe.data.sync

import com.photoframe.data.SourceType
import com.photoframe.data.source.ConfiguredSource
import com.photoframe.data.source.PhotoSource
import com.photoframe.data.source.SourceRepository
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.source.gphotos.GPhotosPickerSource
import com.photoframe.data.source.immich.ImmichSource

/**
 * Builds a live [PhotoSource] from a stored [ConfiguredSource] + its secret (DESIGN §5). One
 * place to add each source type as later phases land. Returns null for types not yet implemented
 * (or misconfigured), which the [SyncManager] logs and skips.
 */
class PhotoSourceFactory(
    private val sourceRepository: SourceRepository,
    private val photoDao: PhotoDao,
) {
    fun create(source: ConfiguredSource): PhotoSource? = when (source.type) {
        SourceType.IMMICH -> createImmich(source)
        SourceType.GPHOTOS_PICKER -> createGphotos(source)
        // GPHOTOS_SHARED_ALBUM (Phase 5), LOCAL_FOLDER (Phase 6).
        else -> null
    }

    private fun createImmich(source: ConfiguredSource): PhotoSource? {
        val config = source.immichConfig ?: return null
        val apiKey = sourceRepository.immichApiKey(source.id) ?: return null
        return ImmichSource(id = source.id, config = config, apiKey = apiKey)
    }

    private fun createGphotos(source: ConfiguredSource): PhotoSource? {
        val config = source.gphotosConfig ?: return null
        val secret = sourceRepository.gphotosClientSecret(source.id) ?: return null
        val refreshToken = sourceRepository.gphotosRefreshToken(source.id) ?: return null
        return GPhotosPickerSource(
            id = source.id,
            clientId = config.clientId,
            clientSecret = secret,
            refreshToken = refreshToken,
            photoDao = photoDao,
        )
    }
}
