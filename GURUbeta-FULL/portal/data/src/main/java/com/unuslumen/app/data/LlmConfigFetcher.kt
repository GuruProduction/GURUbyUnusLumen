// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import com.unuslumen.app.data.tor.TorEgress
import com.unuslumen.app.domain.config.ServedConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

@Serializable
data class LlmConfig(
    @SerialName("base_model") val baseModel: String = "",
    @SerialName("fallback_models") val fallbackModels: List<String> = emptyList(),
    val temperature: Float = 1.2f,
    @SerialName("max_tokens") val maxTokens: Int = 131072,
    @SerialName("context_window") val contextWindow: Int = 1000000,
    @SerialName("thinking_level") val thinkingLevel: String = "max",
    @SerialName("top_p") val topP: Float = 0.9f,
    @SerialName("top_k") val topK: Int = 40,
    @SerialName("repeat_penalty") val repeatPenalty: Float = 1.1f,
    val seed: Int = 0
)

object LlmConfigFetcher {
    private const val TAG = "LlmConfigFetcher"
    private const val PREFS_NAME = "guru_llm_config"
    private const val KEY_CONFIG = "llm_config_json"
    private const val KEY_LAST_FETCH = "llm_config_last_fetch"
    private const val BASE_URL = "https://api.unuslumen.com"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sovereign egress: rides TorEgress like every other outbound call so the
     * user's IP never leaks to the API host over clearnet. Fail-closed when Tor
     * is down — the catch below keeps the previously cached config, exactly as
     * the "server unreachable" path always has.
     */
    /**
     * Fetch the served config and cache it, ONLY when the response is actually config
     * (issue 519).
     *
     * THE OLD BODY read the response and cached it without ever looking at the status:
     *
     *     val body = client.get("$BASE_URL/api/llm-config").bodyAsText()
     *     prefs.edit().putString(KEY_CONFIG, body).apply()
     *
     * The server answered `503 {"error":"llm_config not seeded"}`, that page was
     * stored as config, and because every field of `LlmConfig` has a default and the
     * parser ignores unknown keys it decoded cleanly into a config with an empty
     * model. The failure then appeared four layers away as "Server returned empty
     * model name", which reads like a server problem rather than a client that
     * stored an error page.
     *
     * Now the status decides, and a payload with no model in it is refused whatever
     * the status says. A refusal keeps the previous cache, so a transient 503 cannot
     * wipe a working config.
     */
    suspend fun fetchAndCache(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val client = HttpClient(TorEgress.newEngine()) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30000
                        connectTimeoutMillis = 15000
                        socketTimeoutMillis = 30000
                    }
                }
                val (statusCode, body) = try {
                    val response = client.get("$BASE_URL/api/llm-config")
                    response.status.value to response.bodyAsText()
                } finally {
                    client.close()
                }

                when (val verdict = ServedConfig.verdict(statusCode, body)) {
                    is ServedConfig.Verdict.Refuse -> {
                        // Nothing is written. The previous cache stands, which is the
                        // behaviour the "server unreachable" path always had and this
                        // path never did.
                        Log.w(TAG, "LLM config NOT cached: ${verdict.reason}")
                        return@withContext
                    }
                    is ServedConfig.Verdict.Accept -> {
                        // A well-formed body with no model is the other shape of the same
                        // failure: every field defaults, so it parses and cannot converse.
                        val decoded = runCatching {
                            json.decodeFromString<LlmConfig>(verdict.body)
                        }.getOrNull()
                        if (decoded == null || !ServedConfig.usableModelName(decoded.baseModel)) {
                            Log.w(
                                TAG,
                                "LLM config NOT cached: the served payload carries no model name " +
                                    "(base_model=\"${decoded?.baseModel ?: "<unparseable>"}\"). " +
                                    "The previously cached config is kept.",
                            )
                            return@withContext
                        }

                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString(KEY_CONFIG, verdict.body)
                            .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                            .apply()

                        Log.i(TAG, "Fetched and cached LLM config (model=${decoded.baseModel})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch LLM config: ${e.message}")
            }
        }
    }

    /**
     * The cached config, or null when there is none worth returning.
     *
     * A cache written by an older build may hold an error page that decoded into an
     * empty model. Returning it would reproduce the original failure, so it is
     * treated as absent and the caller falls back to whatever it does with no config.
     */
    fun getCachedConfig(context: Context): LlmConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG, null) ?: return null
        val decoded = try {
            json.decodeFromString<LlmConfig>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached config: ${e.message}")
            return null
        }
        if (!ServedConfig.usableModelName(decoded.baseModel)) {
            Log.w(TAG, "Cached LLM config carries no model name; treating it as absent")
            return null
        }
        return decoded
    }

    fun hasRecentCache(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        val config = prefs.getString(KEY_CONFIG, null)
        return config != null && (System.currentTimeMillis() - lastFetch) < 24 * 60 * 60 * 1000
    }
}