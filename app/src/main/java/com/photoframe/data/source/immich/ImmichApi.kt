package com.photoframe.data.source.immich

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Immich REST surface (DESIGN §5.1). Every endpoint the app touches is centralized here so
 * version drift is diagnosable in one place. **Verified against the owner's server, Immich
 * v3.0.2 (2026-07-13)** — see docs/API_NOTES.md. Auth is the `x-api-key` header, injected by
 * [ImmichClient]'s interceptor, not declared per-method.
 *
 * v3 note: `GET /api/albums/{id}` returns album metadata WITHOUT assets, and there is no
 * `/assets` sub-route — album assets are fetched via [searchMetadata] with `albumIds`.
 */
interface ImmichApi {

    /** Current user — doubles as the connection/auth test (200 = reachable + key valid). */
    @GET("api/users/me")
    suspend fun me(): Response<ImmichUserDto>

    /** Albums (summaries only; assets come from [searchMetadata] with `albumIds`). */
    @GET("api/albums")
    suspend fun albums(): List<ImmichAlbumDto>

    /** Recognized people (names + face thumbnail paths), paginated (500/page cap; use [size]). */
    @GET("api/people")
    suspend fun people(
        @Query("withHidden") withHidden: Boolean = false,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 500,
    ): ImmichPeopleResponseDto

    /** Metadata search — backs Favorites, People, and whole-library/rolling-window selection. */
    @POST("api/search/metadata")
    suspend fun searchMetadata(@Body body: ImmichSearchRequestDto): ImmichSearchResponseDto

    /** Pre-generated preview (~1440px), streamed. `size=preview` (vs `thumbnail`, ~250px). */
    @GET("api/assets/{id}/thumbnail")
    @Streaming
    suspend fun thumbnail(
        @Path("id") id: String,
        @Query("size") size: String = "preview",
    ): Response<ResponseBody>

    /**
     * Delete assets. `force=false` moves them to the Immich trash (recoverable); requires the
     * `asset.delete` permission on the API key (verified: 403 "Missing required permission:
     * asset.delete" otherwise). Returns 204 on success.
     */
    @HTTP(method = "DELETE", path = "api/assets", hasBody = true)
    suspend fun deleteAssets(@Body body: ImmichDeleteDto): Response<Unit>

    /**
     * Bulk-update assets (used to set isFavorite). Requires the `asset.update` permission on the
     * API key (verified: 403 "Missing required permission: asset.update" otherwise).
     */
    @PUT("api/assets")
    suspend fun updateAssets(@Body body: ImmichUpdateDto): Response<Unit>
}

@Serializable
data class ImmichDeleteDto(
    val ids: List<String>,
    /** false = trash (recoverable), true = permanent. The frame trashes. */
    val force: Boolean = false,
)

@Serializable
data class ImmichUpdateDto(
    val ids: List<String>,
    val isFavorite: Boolean,
)

// ---- DTOs (only the fields the app uses; ignoreUnknownKeys tolerates the rest) --------------

@Serializable
data class ImmichUserDto(
    val id: String? = null,
    val email: String? = null,
    val name: String? = null,
)

@Serializable
data class ImmichAlbumDto(
    val id: String,
    val albumName: String = "",
    val assetCount: Int = 0,
)

@Serializable
data class ImmichPeopleResponseDto(
    val people: List<ImmichPersonDto> = emptyList(),
    val total: Int = 0,
    val hasNextPage: Boolean = false,
)

@Serializable
data class ImmichPersonDto(
    val id: String,
    val name: String = "",
    val thumbnailPath: String? = null,
)

@Serializable
data class ImmichAssetDto(
    val id: String,
    val type: String = "IMAGE",
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val fileCreatedAt: String? = null,
    val localDateTime: String? = null,
    /** Base64 sha1 of the original — stable content hash for change detection (§5). */
    val checksum: String? = null,
    /** v3 exposes oriented display dims at the top level (in addition to raw exif dims). */
    val width: Int? = null,
    val height: Int? = null,
    val exifInfo: ImmichExifDto? = null,
) {
    val isImage: Boolean get() = type.equals("IMAGE", ignoreCase = true)
    val isDisplayable: Boolean get() = isImage && !isTrashed && !isArchived
}

@Serializable
data class ImmichExifDto(
    val exifImageWidth: Int? = null,
    val exifImageHeight: Int? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val dateTimeOriginal: String? = null,
)

@Serializable
data class ImmichSearchRequestDto(
    val type: String? = "IMAGE",
    /** v3: album assets are retrieved by filtering search on album id(s). */
    val albumIds: List<String>? = null,
    val personIds: List<String>? = null,
    val isFavorite: Boolean? = null,
    val isArchived: Boolean? = false,
    val isVisible: Boolean? = true,
    /** ISO-8601; restricts to assets taken at/after this instant (rolling window). */
    val takenAfter: String? = null,
    val withExif: Boolean? = true,
    val page: Int? = null,
    val size: Int? = null,
)

@Serializable
data class ImmichSearchResponseDto(
    val assets: ImmichSearchAssetsDto = ImmichSearchAssetsDto(),
)

@Serializable
data class ImmichSearchAssetsDto(
    val total: Int = 0,
    val count: Int = 0,
    val items: List<ImmichAssetDto> = emptyList(),
    /** Page number of the next page as a string, or null at the end. */
    @SerialName("nextPage") val nextPage: String? = null,
)
