package com.photoframe.data.source.immich

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds a configured [ImmichApi] for one server (base URL + API key). Centralizes networking so
 * every Immich call shares the same timeouts, JSON config, and `x-api-key` header. Plain HTTP is
 * permitted for LAN servers via the app's network-security-config (DESIGN §12); OkHttp itself
 * imposes no HTTPS requirement.
 */
object ImmichClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Omit nulls (drops unused facet filters) but DO send default-valued filters like
        // type=IMAGE / isArchived=false / isVisible=true — otherwise the server applies none of
        // them and we'd rely on weaker client-side filtering (misses hidden/locked assets).
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Base URL as the user entered it, normalized to a Retrofit-acceptable form (trailing slash).
     * A schemeless host defaults to **https** — modern Immich installs sit behind TLS, and
     * cleartext is blocked by default on our targetSdk; LAN plain-HTTP users type `http://`.
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        // Immich installs are commonly rooted at `/`; the `/api/...` prefix lives in the paths.
        url = url.trimEnd('/')
        // Tolerate the user pasting the ".../api" root: paths already include `api/`.
        if (url.endsWith("/api")) url = url.removeSuffix("/api")
        return "$url/"
    }

    fun create(baseUrl: String, apiKey: String): ImmichApi {
        val okhttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("x-api-key", apiKey)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(okhttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ImmichApi::class.java)
    }
}
