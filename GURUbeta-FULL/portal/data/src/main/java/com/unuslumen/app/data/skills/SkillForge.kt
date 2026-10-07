// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.skills

import android.content.Context
import android.util.Log
import com.unuslumen.app.data.tor.TorEgress
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The skills-library client. One read-surface into the Unus Lumen
 * publisher for the whole device half:
 *  - the numen's tool verbs land here
 *  - the force-push sync worker lands here
 *
 * Every request rides TorEgress like all other outbound traffic: loopback
 * direct, everything else through Tor, fail-closed when Tor is down.
 *
 * Publisher contract (server, already live): /v1/manifest lists every
 * content type with slug+version and a content-state ETag; /v1/skills and
 * /v1/skills/{slug} carry the payloads with per-skill ETags.
 */

@Serializable
data class LibrarySkillSummary(
    val slug: String,
    val name: String,
    val description: String,
    val whenToUse: String,
    val version: Int
)

@Serializable
data class LibrarySkill(
    val slug: String,
    val name: String,
    val description: String,
    val whenToUse: String,
    val allowedTools: List<String>,
    val content: String,
    val version: Int
)

@Serializable
data class ManifestState(
    val skills: List<LibrarySkillSummary>,
    val etag: String?
)

object SkillForge {

    private const val TAG = "SkillForge"
    private const val PREFS_NAME = "guru_skill_library"
    private const val KEY_ETAG = "library_manifest_etag"
    private const val KEY_LAST_SYNC = "library_last_sync"

    /**
     * Base URL override for dev instance testing: when a GURU points at a
     * private server (or the loopback tunnel is off), this key replaces the
     * default publisher root. Empty = default https://api.unuslumen.com.
     */
    const val KEY_BASE_URL_OVERRIDE = "library_base_url"

    private val json = Json { ignoreUnknownKeys = true }

    fun baseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val override = prefs.getString(KEY_BASE_URL_OVERRIDE, null)
        return if (override.isNullOrBlank()) "https://api.unuslumen.com" else override.trimEnd('/')
    }

    @Serializable
    private data class ManifestEntryDto(val slug: String, val version: Int)

    @Serializable
    private data class ManifestDto(
        val skills: List<ManifestEntryDto> = emptyList()
    )

    @Serializable
    private data class SkillPayloadDto(
        val slug: String? = null,
        val name: String? = null,
        val description: String? = null,
        val when_to_use: String? = null,
        val allowed_tools: List<String> = emptyList(),
        val content: String? = null,
        val version: Int? = null
    )

    /**
     * Cheap manifest check with If-None-Match. 304 returns cached list,
     * 200 refreshes both list and stored etag. Null when the network path
     * says so (device offline, Tor down, publisher unreachable) — callers
     * treat null as "stay offline, keep what you have".
     */
    suspend fun checkManifest(context: Context): ManifestState? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = baseUrl(context)
        try {
            val client = baseHttpClient()
            try {
                val stored = prefs.getString(KEY_ETAG, null)
                val response = client.get("$base/v1/manifest") {
                    stored?.let { headers[HttpHeaders.IfNoneMatch] = it }
                }
                if (response.status.value == 304) {
                    val cachedJson = prefs.getString("last_manifest", null) ?: "[]"
                    return@withContext ManifestState(
                        skills = parseManifestSkills(cachedJson),
                        etag = stored
                    )
                }
                if (!response.status.isSuccess()) {
                    Log.w(TAG, "Manifest fetch failed: HTTP ${response.status.value}")
                    return@withContext null
                }
                val body = response.bodyAsText()
                prefs.edit()
                    .putString("last_manifest", extractManifestSkillsJson(body))
                    .putString(KEY_ETAG, response.headers[HttpHeaders.ETag])
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .apply()

                ManifestState(
                    skills = parseManifestSkills(body),
                    etag = response.headers[HttpHeaders.ETag]
                )
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Manifest unreachable: ${e.message}")
            null
        }
    }

    /**
     * Fetch one skill's full payload by slug.
     */
    suspend fun fetchSkill(context: Context, slug: String): LibrarySkill? = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        try {
            val client = baseHttpClient()
            try {
                /* Fetch the skill by slug. */
                val response = client.get("$base/v1/skills/$slug")
                if (!response.status.isSuccess()) {
                    Log.w(TAG, "Skill '$slug' fetch failed: HTTP ${response.status.value}")
                    return@withContext null
                }
                val dto = json.decodeFromString(SkillPayloadDto.serializer(), response.bodyAsText())
                LibrarySkill(
                    slug = dto.slug ?: slug,
                    name = dto.name ?: slug,
                    description = dto.description ?: "",
                    whenToUse = dto.when_to_use ?: "",
                    allowedTools = dto.allowed_tools.filter { it.isNotBlank() },
                    content = dto.content ?: "",
                    version = dto.version ?: 1
                )
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skill '$slug' unreachable: ${e.message}")
            null
        }
    }

    private suspend fun baseHttpClient(): HttpClient = HttpClient(TorEgress.newEngine()) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * Minimal extraction that tolerates the manifest shape:
     * { "skills": [ {"slug","version"}, ... ], ... }, with etag supplied separately.
     */
    private fun extractManifestSkillsJson(body: String): String = try {
        val obj = json.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject }
        obj?.get("skills")?.toString() ?: "[]"
    } catch (e: Exception) {
        "[]"
    }

    private fun parseManifestSkills(body: String): List<LibrarySkillSummary> {
        /* Skill bodies live per-slug; the manifest only carries slug+version. */
        val raw = try { json.parseToJsonElement(body) } catch (e: Exception) { return emptyList() }
            .let { it as? kotlinx.serialization.json.JsonArray } ?: return emptyList()
        return json.decodeFromString(ListSerializer(ManifestEntryDto.serializer()), raw.toString())
            .map { LibrarySkillSummary(slug = it.slug, name = slugToName(it.slug), description = "", whenToUse = "", version = it.version) }
    }

    fun slugToName(slug: String): String = slug.split("-", "_").joinToString(" ") { w ->
        if (w.isEmpty()) w else w.replaceFirstChar { it.uppercase() }
    }

    fun markSyncError(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("last_sync_error", message).apply()
    }

    fun markSynced(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .putString("last_sync_error", null)
            .apply()
    }
}