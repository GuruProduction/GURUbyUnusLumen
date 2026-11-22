// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.unuslumen.app.data.tor.TorEgress
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * HivePromptBundle — the four hive prompt shapes authored superadmin-side and
 * delivered like every other prompt in GURU.
 *
 * Every field defaults to empty string so a partially seeded bundle parses clean, and a
 * field the publisher has not written is an empty field rather than a fabricated one.
 */
@Serializable
data class HivePromptBundle(
    val fact_extraction: String = "",
    val thread_discovery: String = "",
    val dedup: String = "",
    val titling: String = "",
    /*
     * THE SAME FOUR ROWS UNDER THE SLUGS THEY ACTUALLY HAVE SERVER-SIDE.
     *
     * The route builds its JSON by keying each row on its `slug`, so what a real row
     * produces is `guru-reflection-fact-extraction`, not `fact_extraction`. The short keys
     * above were therefore never satisfiable by any row that exists: a bundle of real slugs
     * decoded into four empty strings, which the fetcher reported as "empty server-side"
     * and the old fallback then answered with compiled-in prose.
     *
     * Both shapes are accepted so a delivery under either key works, and [served] prefers
     * the short key when the publisher sends both.
     */
    val `guru-reflection-fact-extraction`: String = "",
    val `guru-reflection-thread-discovery`: String = "",
    val `guru-reflection-fact-deduplication`: String = "",
    val `guru-reflection-conversation-titling`: String = "",
)

/**
 * The hive prompt lane: served from the publisher, held on the device, and NOTHING ELSE.
 *
 * THERE IS NO FALLBACK AND THERE MUST NOT BE ONE.
 *
 * This class used to carry four hardcoded prompts in an object called
 * `HiveStructuredFallback`, and returned one of them whenever the served field was blank.
 * Those constants were a second authoring surface inside the APK: the same instructions
 * existed in a server row and in client source, the client version silently won whenever
 * delivery failed, and nothing on the device said which one the model had been given.
 *
 * That is not a theoretical duplication. The server route filters on
 * `category = 'hive_drive'`, no row in the live content table uses that category, so the
 * route answers 503 on every request, the cache is never written, and the hardcoded
 * constants have been the only extraction, dedup and thread-discovery instructions that
 * ever reached a model. The authored rows have never been used at all.
 *
 * So the contract is now the one the architecture says: if the publisher has delivered an
 * instruction, that instruction is used verbatim; if it has not, the answer is blank and
 * the caller does not run the lane. A blank answer is a real state and it is reported,
 * never papered over with words nobody authored.
 *
 * Server route: GET /api/hive-prompts (legacy.rs, prompt_sections rows joined into one
 * object). The caller treats a blank field as "no instruction for this lane".
 */
object HivePromptFetcher {
    private const val TAG = "HivePromptFetcher"
    private const val PREFS_NAME = "guru_hive_prompts"
    private const val KEY_BUNDLE = "hive_bundle_v1"
    private const val KEY_LAST_FETCH = "hive_last_fetch"
    private const val BASE_URL = "https://api.unuslumen.com"

    private val json = Json { ignoreUnknownKeys = true }

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
                    client.get("$BASE_URL/api/hive-prompts").bodyAsText()
                } finally {
                    client.close()
                }

                val parsed = json.decodeFromString(HivePromptBundle.serializer(), body)
                val hasExtraction = parsed.fact_extraction.isNotBlank() ||
                    parsed.`guru-reflection-fact-extraction`.isNotBlank()
                val hasDiscovery = parsed.thread_discovery.isNotBlank() ||
                    parsed.`guru-reflection-thread-discovery`.isNotBlank()
                if (!hasExtraction && !hasDiscovery) {
                    Log.w(TAG, "Hive prompt bundle empty server-side; keeping existing cache")
                    return@withContext
                }

                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_BUNDLE, body)
                    .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "Fetched and cached hive prompt bundle (${body.length} chars)")
            } catch (e: Exception) {
                // An unreachable publisher leaves the device with whatever was last
                // delivered, which is the locally persisted copy. It never becomes an
                // instruction that was authored on this device.
                Log.w(TAG, "Hive prompt fetch failed (delivered copy stays authoritative): ${e.message}")
            }
        }
    }

    /**
     * The served extraction instruction, or blank when nothing has been delivered.
     *
     * A BLANK RETURN MEANS DO NOT RUN THE LANE. It does not mean "use a default": a model
     * sent a transcript with no instruction invents its own, and a memory written from an
     * invented instruction is worse than a memory not written.
     */
    fun factExtraction(context: Context): String = served(context) {
        it.fact_extraction.ifBlank { it.`guru-reflection-fact-extraction` }
    }

    /** The served thread-discovery instruction, or blank. Same rule as [factExtraction]. */
    fun threadDiscovery(context: Context): String = served(context) {
        it.thread_discovery.ifBlank { it.`guru-reflection-thread-discovery` }
    }

    /** The served deduplication instruction, or blank. Same rule as [factExtraction]. */
    fun factDeduplication(context: Context): String = served(context) {
        it.dedup.ifBlank { it.`guru-reflection-fact-deduplication` }
    }

    /**
     * The served titling instruction, or blank.
     *
     * NO CALLER YET, AND IT STAYS. The row exists server-side and the bundle carries it, so
     * a titling lane that reads `HivePromptFetcher` gets the served text rather than
     * inventing one. An unused accessor is not a defect; an accessor that never appears
     * because the only version was hardcoded is.
     */
    fun conversationTitling(context: Context): String = served(context) {
        it.titling.ifBlank { it.`guru-reflection-conversation-titling` }
    }

    /**
     * One field of the delivered bundle, trimmed, or blank.
     *
     * Every failure path answers blank: no cache, unparseable cache, and a field the
     * publisher left empty are all the same state to a caller, because all three mean
     * "this device holds no instruction for this lane".
     */
    private fun served(context: Context, pick: (HivePromptBundle) -> String): String {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUNDLE, null) ?: return ""
        return runCatching { pick(json.decodeFromString(HivePromptBundle.serializer(), raw)) }
            .getOrDefault("")
            .trim()
    }

    fun hasRecentCache(context: Context): Boolean {
        val lastFetch = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_FETCH, 0)
        return lastFetch > 0 && (System.currentTimeMillis() - lastFetch) < 24 * 60 * 60 * 1000
    }
}
