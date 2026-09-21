package com.unuslumen.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import com.unuslumen.app.data.tor.TorEgress
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
                val body = try {
                    client.get("$BASE_URL/api/llm-config").bodyAsText()
                } finally {
                    client.close()
                }

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CONFIG, body)
                    .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                    .apply()

                Log.i(TAG, "Fetched and cached LLM config ($body)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch LLM config: ${e.message}")
            }
        }
    }

    fun getCachedConfig(context: Context): LlmConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            json.decodeFromString<LlmConfig>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached config: ${e.message}")
            null
        }
    }

    fun hasRecentCache(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        val config = prefs.getString(KEY_CONFIG, null)
        return config != null && (System.currentTimeMillis() - lastFetch) < 24 * 60 * 60 * 1000
    }
}