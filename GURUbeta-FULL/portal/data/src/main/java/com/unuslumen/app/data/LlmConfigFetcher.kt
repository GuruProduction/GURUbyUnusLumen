package com.unuslumen.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

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

    suspend fun fetchAndCache(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/api/llm-config")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Server returned $responseCode, keeping cached config")
                    return@withContext
                }

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

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