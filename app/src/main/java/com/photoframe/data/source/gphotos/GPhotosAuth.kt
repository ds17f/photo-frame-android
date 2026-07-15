package com.photoframe.data.source.gphotos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Google OAuth for the Photos Picker source (DESIGN §5.2), using the **loopback IP redirect** with
 * a **Desktop-type** OAuth client — the only browser flow left that works with no Google Play
 * Services *and* the Picker scope (Google deprecated custom-scheme + loopback for the *Android*
 * client type, and the device flow disallows this scope; see docs/API_NOTES.md).
 *
 * Flow: bind a short-lived `ServerSocket` on `127.0.0.1:<ephemeral port>`, open the system browser
 * (Silk-safe — plain `ACTION_VIEW`) at Google's auth endpoint with `redirect_uri` pointing back at
 * that socket, catch the `?code=…` redirect, then exchange the code (PKCE) at the token endpoint.
 * The one-time sign-in therefore happens **on the tablet**; picking photos later still happens on
 * the phone via the picker QR.
 *
 * The Desktop client's "secret" is non-confidential for installed apps (it ships in the binary);
 * it is still kept in EncryptedSecrets, never logged.
 */
object GPhotosAuth {

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    /** Read-only access to items the user picks in the Google Photos picker. */
    const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    /** How long to wait for the user to complete sign-in in the browser before giving up. */
    private const val REDIRECT_TIMEOUT_MS = 5 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Tokens(
        val accessToken: String,
        /** Present on the initial authorization; a refresh response may omit it (reuse the old one). */
        val refreshToken: String?,
        val expiresInSec: Long,
    )

    class AuthException(message: String) : Exception(message)

    /**
     * Run the full interactive sign-in on this device: open the browser, wait for the loopback
     * redirect, and exchange the code. Returns tokens (including a **refresh token** — we request
     * `access_type=offline` + `prompt=consent`). Throws [AuthException] on denial/timeout/failure.
     */
    suspend fun authorize(context: Context, clientId: String, clientSecret: String): Tokens =
        withContext(Dispatchers.IO) {
            val verifier = randomVerifier()
            val challenge = codeChallenge(verifier)
            ServerSocket().use { server ->
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                val redirectUri = "http://127.0.0.1:${server.localPort}"
                val authUrl = buildAuthUrl(clientId, redirectUri, challenge)
                launchBrowser(context, authUrl)
                val code = awaitRedirectCode(server)
                exchangeCode(clientId, clientSecret, code, verifier, redirectUri)
            }
        }

    /** Trade a stored refresh token for a fresh access token (silent; no browser). */
    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): Tokens =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()
            postToken(body)
        }

    // ---- Steps ------------------------------------------------------------------------------

    private fun buildAuthUrl(clientId: String, redirectUri: String, challenge: String): Uri =
        AUTH_ENDPOINT.toUri().buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

    private fun launchBrowser(context: Context, url: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw AuthException("No browser available to sign in: ${e.message}")
        }
    }

    /**
     * Accept loopback connections until one carries `?code=` (success) or `?error=` (denied),
     * answering each with a small HTML page. Bounded by [REDIRECT_TIMEOUT_MS] overall.
     */
    private fun awaitRedirectCode(server: ServerSocket): String {
        server.soTimeout = REDIRECT_TIMEOUT_MS
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                throw AuthException("Sign-in timed out — no response from the browser")
            }
            socket.use {
                val requestLine = BufferedReader(InputStreamReader(it.getInputStream()))
                    .readLine().orEmpty() // "GET /?code=… HTTP/1.1"
                val params = parseQuery(requestLine)
                val code = params["code"]
                val error = params["error"]
                val done = code != null || error != null
                writeResponse(it, done)
                when {
                    code != null -> return code
                    error != null -> throw AuthException("Google sign-in was denied ($error)")
                    // Otherwise a stray request (e.g. /favicon.ico) — keep waiting.
                }
            }
        }
    }

    private fun writeResponse(socket: java.net.Socket, done: Boolean) {
        val message = if (done) {
            "Signed in. You can close this tab and return to the photo frame."
        } else {
            "Waiting for sign-in…"
        }
        val html = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<title>Photo Frame</title></head><body style=\"font-family:sans-serif;text-align:center;" +
            "padding:3rem;background:#111;color:#eee\"><h2>$message</h2></body></html>"
        val response = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" +
            html
        runCatching { socket.getOutputStream().apply { write(response.toByteArray()); flush() } }
    }

    private fun exchangeCode(
        clientId: String,
        clientSecret: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): Tokens {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .build()
        return postToken(body)
    }

    private fun postToken(body: FormBody): Tokens {
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        val raw = try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful && text.isBlank()) {
                    throw AuthException("Token request failed (HTTP ${resp.code})")
                }
                text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException("Could not reach Google to sign in: ${e.message}")
        }
        val parsed = runCatching { json.decodeFromString<TokenResponse>(raw) }.getOrNull()
            ?: throw AuthException("Unexpected token response from Google")
        if (parsed.error != null) {
            throw AuthException(parsed.errorDescription ?: parsed.error)
        }
        val access = parsed.accessToken
            ?: throw AuthException("Google did not return an access token")
        return Tokens(access, parsed.refreshToken, parsed.expiresIn ?: 3600L)
    }

    // ---- PKCE -------------------------------------------------------------------------------

    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun parseQuery(requestLine: String): Map<String, String> {
        // requestLine: "GET /path?a=b&c=d HTTP/1.1"
        val path = requestLine.split(' ').getOrNull(1) ?: return emptyMap()
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.substring(0, idx)
            val value = runCatching {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrDefault(pair.substring(idx + 1))
            key to value
        }.toMap()
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )
}
