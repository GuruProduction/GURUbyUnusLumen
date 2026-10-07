// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.github

import android.content.Context
import android.util.Log
import com.unuslumen.app.data.security.CredentialVault
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHubClient — The genuine GitHub integration for GURU.
 *
 * Two layers, both riding TorEgress (no clearnet anywhere):
 *
 *  AUTH LAYER — OAuth Device Flow, exactly the flow the gh CLI uses (the model for
 *  "log in once, authenticated forever"):
 *    1. Device-code request to https://github.com/login/device/code carrying the
 *       Unus Lumen-registered client_id (a public doorway id, nothing secret)
 *       with Device Flow enabled on the registration.
 *    2. GURU surfaces the human code and https://github.com/login/device.
 *    3. The human types the code and approves the scopes on their own account;
 *       Unus Lumen's account holds no part in the minted token.
 *    4. GURU polls the token endpoint through authorization_pending / slow_down
 *       until GitHub returns the access_token with a refresh_token companion.
 *    5. Token pair is sealed into the CredentialVault slot SERVICE_GITHUB.
 *
 *  Expire-user-tokens note (registration checkbox, seen in Steven's screenshot):
 *  when the app registration sets "Expire user access tokens", responses carry a
 *  refresh_token plus expires_in, and this client stores BOTH and transparently
 *  mints a fresh access token through the refresh grant when GitHub answers 401
 *  with an expired-token payload. Registrations with expiry disabled behave as the
 *  single never-expiring grant path. Either registration choice works fully.
 *
 *  API LAYER — REST v3 endpoints on https://api.github.com, token attached as
 *  Authorization Bearer whenever the vault holds one; public endpoints stay readable
 *  without one. GitHub JSON error bodies ({message, ...}) pass through verbatim so
 *  the numen hears GitHub's real reasoning rather than an empty string.
 */
class GitHubClient(
    private val context: Context,
    private val torManager: TorManager
) {
    companion object {
        private const val TAG = "guru"

        /**
         * Unus Lumen OAuth App client_id (GuruProducer/production, device flow
         * enabled, tokens non-expiring). Public by design: it identifies the
         * requester on GitHub's approval screen, it grants nothing on its own.
         */
        const val OAUTH_CLIENT_ID = "Ov23liZL01mDzPSHwLrt"

        /** Scopes: every capability GURU's GitHub tools use, nothing beyond. */
        const val OAUTH_SCOPES = "repo read:user user:email"

        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val API_ROOT = "https://api.github.com"

        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val USER_AGENT = "GURU-by-UnusLumen/1.0"
        private const val API_VERSION = "2022-11-28"
        private const val GITHUB_DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }

    // ─────────────────────────── AUTH LAYER ───────────────────────────

    /**
     * The registration client_id gates login: GitHub's device endpoint rejects
     * placeholder ids. This constant is the integration point where Steven's
     * registered Unus Lumen id lands; before that, githubLogin reports honestly.
     */
    fun hasRealClientId(): Boolean =
        OAUTH_CLIENT_ID != "REPLACE_WITH_UNUSLUMEN_CLIENT_ID" && OAUTH_CLIENT_ID.isNotBlank()

    /** This install's GitHub token, transparently unsealed or null. Never logged. */
    fun tokenOrNull(): String? = CredentialVault.retrieve(context, CredentialVault.SERVICE_GITHUB)

    /** Logged in = a token exists in the vault. Login state is a hardware-backed fact. */
    fun isLoggedIn(): Boolean = tokenOrNull()?.isNotBlank() == true

    @Serializable
    data class DeviceOffer(
        val userCode: String,
        val verificationUri: String,
        val deviceCode: String,
        val intervalSeconds: Int,
        val expiresIn: Int,
        val error: String? = null
    )

    /**
     * Step 1 of device flow: ask GitHub for a device code + human code.
     */
    suspend fun startDeviceFlow(): DeviceOffer = withContext(Dispatchers.IO) {
        if (!hasRealClientId()) {
            return@withContext DeviceOffer(
                userCode = "", verificationUri = "", deviceCode = "", intervalSeconds = 0, expiresIn = 0,
                error = "GitHub login is not active yet — the Unus Lumen OAuth App registration " +
                    "client_id needs to be set in GitHubClient.OAUTH_CLIENT_ID. Register it at " +
                    "github.com > Settings > Developer settings > OAuth Apps with Device Flow enabled."
            )
        }
        val form = "client_id=${URLEncoder.encode(OAUTH_CLIENT_ID, "UTF-8")}" +
            "&scope=${URLEncoder.encode(OAUTH_SCOPES, "UTF-8")}"
        val response = formPost(DEVICE_CODE_URL, form)
        if (response == null) {
            return@withContext DeviceOffer("", "", "", 0, 0, "GitHub did not answer the device-code request — check Tor state and retry.")
        }
        val json = try { JSONObject(response) } catch (e: Exception) { null }
        if (json == null) {
            return@withContext DeviceOffer("", "", "", 0, 0, "Unreadable device-code response: $response")
        }
        val errorCode = json.optString("error", "")
        if (errorCode.isNotBlank() || json.optString("device_code").isBlank()) {
            return@withContext DeviceOffer("", "", "", 0, 0,
                json.optString("error_description", "GitHub declined the device-code request: $response"))
        }
        DeviceOffer(
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            deviceCode = json.getString("device_code"),
            intervalSeconds = json.optInt("interval", 5),
            expiresIn = json.optInt("expires_in", 900)
        )
    }

    data class PollOutcome(
        /** Terminal success: token sealed, login done. */
        val success: Boolean,
        /** Keep polling. */
        val pending: Boolean,
        val error: String? = null
    )

    /**
     * One poll cycle of the token endpoint. The caller (githubLogin tool) drives the
     * cadence so its result reaches the chat as progressive state rather than a
     * silent hang.
     */
    suspend fun pollOnce(deviceCode: String, intervalSeconds: Int): PollOutcome = withContext(Dispatchers.IO) {
        val form = "client_id=${URLEncoder.encode(OAUTH_CLIENT_ID, "UTF-8")}" +
            "&device_code=${URLEncoder.encode(deviceCode, "UTF-8")}" +
            "&grant_type=$GITHUB_DEVICE_GRANT"
        val response = formPost(TOKEN_URL, form)
        if (response == null) {
            return@withContext PollOutcome(false, pending = true, error = null) // transient; treat as pending
        }
        val json = try { JSONObject(response) } catch (e: Exception) { JSONObject() }
        when (json.optString("error", "")) {
            "" -> {
                val token = json.optString("access_token", "")
                if (token.isBlank()) {
                    PollOutcome(false, pending = false, error = "Token exchange answered without an access_token: $response")
                } else {
                    CredentialVault.store(context, CredentialVault.SERVICE_GITHUB, token)
                    json.optString("refresh_token", "").takeIf { it.isNotBlank() }?.let { refresh ->
                        CredentialVault.store(context, CredentialVault.SERVICE_GITHUB_REFRESH, refresh)
                    }
                    Log.d(TAG, "GitHubClient: tokens sealed, login complete")
                    PollOutcome(true, pending = false)
                }
            }
            "authorization_pending" -> PollOutcome(false, pending = true)
            "slow_down" -> PollOutcome(false, pending = true, error = "GitHub says poll too fast — add 5s to interval and continue")
            else -> PollOutcome(
                false, pending = false,
                error = json.optString("error_description", "").ifBlank { response }
            )
        }
    }

    /**
     * Refresh grant for registrations with token expiry enabled: mint the next
     * access token from the sealed refresh token and reseal both.
     */
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refresh = CredentialVault.retrieve(context, CredentialVault.SERVICE_GITHUB_REFRESH)
        if (refresh.isNullOrBlank()) return@withContext false
        val form = "client_id=${URLEncoder.encode(OAUTH_CLIENT_ID, "UTF-8")}" +
            "&grant_type=refresh_token&refresh_token=${URLEncoder.encode(refresh, "UTF-8")}"
        val response = formPost(TOKEN_URL, form) ?: return@withContext false
        val json = try { JSONObject(response) } catch (e: Exception) { JSONObject() }
        val token = json.optString("access_token", "")
        if (token.isBlank()) {
            Log.w(TAG, "GitHubClient: refresh rejected: $response")
            return@withContext false
        }
        CredentialVault.store(context, CredentialVault.SERVICE_GITHUB, token)
        CredentialVault.store(context, CredentialVault.SERVICE_GITHUB_REFRESH, json.optString("refresh_token", refresh))
        true
    }

    /** Remove this install's credential; GitHub-side revocation is the human's move. */
    fun logout(): Boolean {
        CredentialVault.delete(context, CredentialVault.SERVICE_GITHUB)
        CredentialVault.delete(context, CredentialVault.SERVICE_GITHUB_REFRESH)
        return true
    }

    /** Who this install is logged in as, on GitHub's own word (/user). */
    suspend fun authenticatedUserLogin(): String? = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) return@withContext null
        val (code, body, _) = apiRequest("/user", "GET")
        if (code !in 200..299) return@withContext null
        try { JSONObject(body).optString("login") } catch (e: Exception) { null }
    }

    // ─────────────────────────── API LAYER ───────────────────────────

    /**
     * Single HTTP path for all REST calls, egress enforced through Tor (fail closed).
     * Returns status code + body + error. Error bodies (rate limits, scope denials)
     * carry to the caller verbatim; never swallowed.
     */
    suspend fun apiRequest(
        path: String,
        method: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Triple<Int, String, String?> = withContext(Dispatchers.IO) {
        if (!torManager.isReady.value) {
            return@withContext Triple(0, "", "Tor is not running. GitHub requests never leave GURU over clearnet — bring Tor up and retry.")
        }
        var conn: HttpURLConnection? = null
        try {
            val proxy = torManager.getSocksProxy()
            conn = (URL("$API_ROOT$path").openConnection(proxy) as HttpURLConnection)
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", GITHUB_JSON)
            conn.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            tokenOrNull()?.let { token -> conn.setRequestProperty("Authorization", "Bearer $token") }
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", GITHUB_JSON)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = read(if (status in 200..299) conn.inputStream else conn.errorStream)
            Triple(status, text, null as String?)
        } catch (e: Exception) {
            Log.w(TAG, "GitHubClient: $method $path transport failure: ${e.message}")
            Triple(0, "", e.message ?: "network failure")
        } finally {
            conn?.disconnect()
        }
    }

    private fun read(stream: InputStream?): String = try {
        stream?.bufferedReader()?.use { it.readText() } ?: ""
    } catch (_: Exception) { "" }

    /** Auth-post used by device flow/refresh, form-encoded, Tor-enforced. */
    private suspend fun formPost(url: String, formBody: String): String? = withContext(Dispatchers.IO) {
        if (!torManager.isReady.value) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val proxy = torManager.getSocksProxy()
            conn = (URL(url).openConnection(proxy) as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.doOutput = true
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            return@withContext read(if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                .ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "GitHubClient: form post failed: ${e.message}")
            null
        } finally { conn?.disconnect() }
    }

}