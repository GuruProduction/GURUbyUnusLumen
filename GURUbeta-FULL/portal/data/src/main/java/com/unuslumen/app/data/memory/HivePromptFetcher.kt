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
 * HivePromptBundle — the three real hive prompt shapes authored superadmin-side
 * and fetched like every other prompt in GURU (no hardcoded prompt prose in
 * client source). Every field defaults to empty string so a partially seeded
 * bundle parses clean and the hive's own gates decide what runs bare.
 */
@Serializable
data class HivePromptBundle(
    val fact_extraction: String = "",
    val thread_discovery: String = "",
    val dedup: String = "",
    val titling: String = "",
)

/**
 * HivePromptFetcher — fetches + caches the hive prompt bundle from the
 * Unus Lumen API. Same delivery discipline as PromptFetcher: TorEgress egress
 * (LAN direct, remote via Tor, fail-closed when Tor is down), boot fetch plus
 * a refresh cadence, SharedPreferences cache so the hive never runs bare.
 *
 * Server route: GET /api/hive-prompts (legacy.rs, hive_drive prompt_sections
 * rows joined into one object). 503 when not seeded: caller keeps cache,
 * and if cache is cold the consumer must fail closed (skip the lane) rather
 * than send the model anywhere without instructions.
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
                if (parsed.fact_extraction.isBlank() && parsed.thread_discovery.isBlank()) {
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
                Log.w(TAG, "Hive prompt fetch failed (cache stays authoritative): ${e.message}")
            }
        }
    }

    /**
     * Real prompt text, never a blank string, so the hive can send with
     * instructions. Returns the fetched row when it exists server-side,
     * or the structured fallback lane when the bundle row is blank/missing.
     */
    fun factExtraction(context: Context): String =
        bundleOrStructuredFallback(context, HiveStructuredFallback.FACT_EXTRACTION) { it.fact_extraction }

    fun threadDiscovery(context: Context): String =
        bundleOrStructuredFallback(context, HiveStructuredFallback.THREAD_DISCOVERY) { it.thread_discovery }

    fun factDeduplication(context: Context): String =
        bundleOrStructuredFallback(context, HiveStructuredFallback.FACT_DEDUPLICATION) { it.dedup }

    fun conversationTitling(context: Context): String =
        bundleOrStructuredFallback(context, HiveStructuredFallback.CONVERSATION_TITLING) { it.titling }

    private fun bundleOrStructuredFallback(
        context: Context,
        fallback: String,
        pick: (HivePromptBundle) -> String,
    ): String {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUNDLE, null) ?: return fallback
        return runCatching { pick(json.decodeFromString(HivePromptBundle.serializer(), raw)) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    fun hasRecentCache(context: Context): Boolean {
        val lastFetch = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_FETCH, 0)
        return lastFetch > 0 && (System.currentTimeMillis() - lastFetch) < 24 * 60 * 60 * 1000
    }
}

/**
 * Structured offline fallbacks used only when the server row is blank/absent.
 * These are CONSTRUCTS (contract spec headers with fixed placeholders the
 * hive fills), not persona prompt prose. The actual creative instruction
 * content lives server-side; a fully offline phone keeps a functioning hive
 * shape instead of a dead one.
 */
object HiveStructuredFallback {

    /**
     * Every category a fact may be filed under, read from the one vocabulary.
     *
     * THE FALLBACK LISTED SEVEN AND THE APP PERMITS SEVENTEEN. `MemoryCategories` is what the
     * write path enforces, so a model told to emit seven names could never produce
     * `tool_defect`, `workaround`, `lesson`, `correction`, `capability` or `commitment`: the
     * six added for the self-correction loop. That made them unreachable exactly when the
     * server was unreachable, which is when a phone most needs its own memory working.
     *
     * It is read from `MemoryCategories.ALL` rather than typed out again, so the prompt and
     * the filter cannot drift.
     */
    private val CATEGORY_LIST: String =
        com.unuslumen.app.domain.memory.MemoryCategories.ALL.joinToString("|")

    /** The extraction reply shape, stated once so a prompt and its parser cannot diverge. */
    const val FACT_EXTRACTION_SCHEMA: String =
        "{\"facts\":[{\"category\":\"<CATEGORIES>\",\"fact\":\"<concise literal sentence>\"," +
            "\"confidence\":<0.0-1.0>}]}"

    /**
     * The dedup reply shape, stated once.
     *
     * THIS WAS THE CONTRACT MISMATCH. The fallback asked for
     * `{"merge_map":[{"keepFactIndex":0,"mergeFactIndices":[1]}]}`, while `HiveMindWorker`
     * parses a `DedupJudgement` with `duplicates_of_existing` and `internal_duplicates`, and
     * sends that instruction in the same request. The system prompt contradicted the user
     * prompt, the model answered one of them, parsing found the other absent, and the pass
     * concluded everything was novel.
     *
     * That is the silent half of the resurrection defect: a dedup pass that always says
     * "novel" re-proposes facts the brain already holds, and the write that follows lands on
     * the id derived from that content.
     */
    const val FACT_DEDUPLICATION_SCHEMA: String =
        "{\"duplicates_of_existing\":[<new-fact index in the EXISTING list>]," +
            "\"internal_duplicates\":[<index in the EXISTING list>,<index in the EXISTING list>]}"

    val FACT_EXTRACTION: String =
        "You are the memory extraction mind of a phone AI.\n" +
            "Study the conversation transcript inside the USER block.\n" +
            "Extract ZERO OR MORE facts worth durable recall (identity; stable likes and dislikes; plans and commitments people made; important relationships; recurring routines; and, when a tool was broken or a way round it was found, the defect and the workaround).\n" +
            "Output STRICT JSON ONLY, byte-one, no prose outside the object:\n" +
            FACT_EXTRACTION_SCHEMA.replace("<CATEGORIES>", CATEGORY_LIST) + "\n" +
            "Rules: an empty transcript or nothing worth remembering produces {\"facts\":[]}. Never wrap in code fences or reasoning."

    const val THREAD_DISCOVERY: String =
        "You are the thread discovery mind of a phone AI.\n" +
            "Study the conversation fragments inside the USER block.\n" +
            "Discover ZERO OR MORE recurring themes connecting them.\n" +
            "Output STRICT JSON ONLY, byte-one, no prose outside the object:\n" +
            "{\"threads\":[{\"title\":\"<max 6 words>\",\"summary\":\"<one sentence>\",\"conversationIds\":[\"<ids referenced>\"]}]}\n" +
            "Rules: no real theme produces {\"threads\":[]}. Never wrap in code fences or reasoning."

    val FACT_DEDUPLICATION: String =
        "You are the memory dedup mind of a phone AI. Two fact groups arrive in the USER block: " +
            "EXISTING FACTS, then NEW FACTS with index numbers. " +
            "Report which NEW facts duplicate an EXISTING one, and which NEW facts duplicate each other. " +
            "Output STRICT JSON ONLY, byte-one, no prose outside the object: " +
            FACT_DEDUPLICATION_SCHEMA + ". " +
            "Rules: everything distinct produces [] in both lists. Never wrap in code fences."

    const val CONVERSATION_TITLING: String =
        "Title the conversation in the USER block: max six words, no quotes, no punctuation. " +
            "Output STRICT JSON ONLY, byte-one, no prose outside the object: " +
            "{\"title\":\"<words>\"}. Never wrap in code fences."
}