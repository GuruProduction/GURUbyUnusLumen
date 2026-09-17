package com.unuslumen.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the assembled system prompt from the GURU server at startup.
 * Caches it in SharedPreferences so the app doesn't need to fetch every session.
 * The hardcoded prompts in GuruPrompts.kt serve as fallback if the server is unreachable.
 */
object PromptFetcher {
    private const val TAG = "PromptFetcher"
    private const val PREFS_NAME = "guru_prompts"
    private const val KEY_PROMPT = "assembled_prompt"
    private const val KEY_VERSION = "prompt_version"
    private const val KEY_LAST_FETCH = "prompt_last_fetch"
    private const val BASE_URL = "https://api.unuslumen.com"

    /**
     * Fetch the assembled prompt from the server and cache it.
     * Call this at app startup and on a refresh cadence. The API connection is
     * unconditional: no auth headers, no gating, the app is wired to the
     * Unus Lumen API from first boot.
     * Runs on IO dispatcher — safe to call from main thread.
     */
    suspend fun fetchAndCache(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/prompts/assembled")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Server returned $responseCode, keeping cached prompt")
                    return@withContext
                }

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                // Parse the JSON to extract the prompt string
                val promptStart = body.indexOf("\"prompt\":\"")
                if (promptStart == -1) {
                    Log.w(TAG, "Invalid response format")
                    return@withContext
                }

                // Extract the prompt string (handle JSON escaping)
                val promptValue = extractJsonString(body, promptStart + 10)
                if (promptValue == null) {
                    Log.w(TAG, "Failed to parse prompt from response")
                    return@withContext
                }

                // Extract version
                val versionStart = body.indexOf("\"version\":")
                val version = if (versionStart != -1) {
                    body.substring(versionStart + 10).takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                } else {
                    1
                }

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_PROMPT, promptValue)
                    .putInt(KEY_VERSION, version)
                    .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                    .apply()

                Log.i(TAG, "Fetched and cached prompt (${promptValue.length} chars, v$version)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch prompt: ${e.message}")
            }
        }
    }

    /**
     * Get the cached assembled prompt, or null if not yet fetched.
     */
    fun getCachedPrompt(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PROMPT, null)
    }

    /**
     * Get the cached prompt version, or 0 if not yet fetched.
     */
    fun getCachedVersion(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_VERSION, 0)
    }

    /**
     * Check if we have a cached prompt that's less than 24 hours old.
     */
    fun hasRecentCache(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        val prompt = prefs.getString(KEY_PROMPT, null)
        return prompt != null && (System.currentTimeMillis() - lastFetch) < 24 * 60 * 60 * 1000
    }

    /**
     * Extract a JSON string value, handling escape sequences.
     * Starts reading after the opening quote.
     */
    private fun extractJsonString(json: String, startIndex: Int): String? {
        val sb = StringBuilder()
        var i = startIndex
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }
}
