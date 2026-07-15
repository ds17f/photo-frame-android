package com.photoframe.data.source.gphotos

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Google Photos **Picker API** (DESIGN §5.2): create a picking session, poll it until the user has
 * finished picking, page the picked media items, then delete the session. Auth is a per-call
 * `Authorization: Bearer <access token>` (tokens are short-lived and refreshed by [GPhotosAuth], so
 * we don't bake one into an interceptor).
 *
 * DTO shapes are transcribed from Google's spec and are **provisional** — verify against the live
 * API and log discrepancies in docs/API_NOTES.md (the "trust reality" rule).
 */
interface GPhotosPickerApi {

    /** Create a picking session. Body is empty; the response carries the `pickerUri` + polling config. */
    @POST("v1/sessions")
    suspend fun createSession(@Header("Authorization") auth: String): PickerSessionDto

    /** Poll a session; `mediaItemsSet` flips true once the user finishes picking in Google Photos. */
    @GET("v1/sessions/{sessionId}")
    suspend fun getSession(
        @Header("Authorization") auth: String,
        @Path("sessionId") sessionId: String,
    ): PickerSessionDto

    /** Page the picked media items for a session. */
    @GET("v1/mediaItems")
    suspend fun mediaItems(
        @Header("Authorization") auth: String,
        @Query("sessionId") sessionId: String,
        @Query("pageSize") pageSize: Int = 100,
        @Query("pageToken") pageToken: String? = null,
    ): PickerMediaItemsResponse

    /** Delete the session once its items are downloaded (grants/baseUrls are short-lived). */
    @DELETE("v1/sessions/{sessionId}")
    suspend fun deleteSession(
        @Header("Authorization") auth: String,
        @Path("sessionId") sessionId: String,
    )

    companion object {
        private const val BASE_URL = "https://photospicker.googleapis.com/"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun create(): GPhotosPickerApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(GPhotosPickerApi::class.java)
        }

        /** `Authorization` header value for a bearer access token. */
        fun bearer(accessToken: String) = "Bearer $accessToken"
    }
}

@Serializable
data class PickerSessionDto(
    val id: String,
    @SerialName("pickerUri") val pickerUri: String? = null,
    @SerialName("mediaItemsSet") val mediaItemsSet: Boolean = false,
    @SerialName("pollingConfig") val pollingConfig: PollingConfigDto? = null,
)

@Serializable
data class PollingConfigDto(
    /** protobuf Duration string, e.g. "5s" / "5.000s". */
    @SerialName("pollInterval") val pollInterval: String? = null,
    @SerialName("timeoutIn") val timeoutIn: String? = null,
)

@Serializable
data class PickerMediaItemsResponse(
    @SerialName("mediaItems") val mediaItems: List<PickerMediaItemDto> = emptyList(),
    @SerialName("nextPageToken") val nextPageToken: String? = null,
)

@Serializable
data class PickerMediaItemDto(
    val id: String,
    @SerialName("createTime") val createTime: String? = null,
    val type: String? = null, // PHOTO / VIDEO
    @SerialName("mediaFile") val mediaFile: PickerMediaFileDto? = null,
)

@Serializable
data class PickerMediaFileDto(
    @SerialName("baseUrl") val baseUrl: String? = null,
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("mediaFileMetadata") val mediaFileMetadata: PickerMediaFileMetadataDto? = null,
)

@Serializable
data class PickerMediaFileMetadataDto(
    val width: Int? = null,
    val height: Int? = null,
)
