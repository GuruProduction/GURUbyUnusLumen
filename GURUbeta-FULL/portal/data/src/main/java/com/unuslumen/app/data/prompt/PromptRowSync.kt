// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.prompt

import com.unuslumen.app.data.tor.TorEgress
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.koin.core.annotation.Single

/**
 * Pulling the prompt rows down from the publisher.
 *
 * THE CHEAP CHECK COMES FIRST. `GET /v1/manifest` answers a list of `{slug, version}` for
 * every prompt, which is a few bytes per row. Only the slugs whose version differs from the
 * one this device holds are then fetched in full from `GET /v1/prompts/{slug}`. Fetching
 * every prompt body on every sync would move the entire text of the stack to learn that
 * nothing changed.
 *
 * ONE-WAY AND ADDITIVE. This writes what the server holds and never deletes a local row. A
 * row the server has withdrawn is marked inactive rather than removed, because the text on
 * the device may be the owner's and a withdrawal is not permission to discard it. The store
 * refuses to overwrite an edited row, so the decision about whose words win is made once, in
 * one place.
 *
 * A FAILED SYNC IS A REPORT, NOT AN EXCEPTION. Tor being down, the publisher being
 * unreachable and a malformed response are all ordinary states, and each one has to be told
 * apart from "the stack is empty". The result carries which of those happened.
 */
@Single
class PromptRowSync(
    private val store: PromptRowStore,
) {

    /** What a sync did, or why it did not. */
    data class SyncReport(
        val reachable: Boolean,
        val manifestRows: Int = 0,
        val fetched: Int = 0,
        val written: Int = 0,
        val skippedEdited: Int = 0,
        val unchanged: Int = 0,
        val message: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The publisher's base URL, matching every other content route. */
    private val baseUrl = "https://api.unuslumen.com/"

    /**
     * Compare the manifest against what is on the device and fetch only what moved.
     *
     * A ROW WITH NO LOCAL COPY IS ALWAYS FETCHED. The manifest is the list of what exists, so
     * a slug in it that the device has never seen is new rather than unchanged, and a version
     * comparison alone would skip it.
     */
    suspend fun sync(): SyncReport = withContext(Dispatchers.IO) {
        val manifest = fetchManifest()
            ?: return@withContext SyncReport(
                reachable = false,
                message = "The publisher could not be reached, so this is not an answer about what exists. " +
                    "Tor may be down or the server may be unreachable. Rows already on this device still work.",
            )

        val local = store.slugVersions().associateBy { it.slug }
        var fetched = 0
        var written = 0
        var skippedEdited = 0
        var unchanged = 0

        for ((slug, remoteVersion) in manifest) {
            val held = local[slug]
            if (held != null && held.version == remoteVersion && held.syncedVersion == remoteVersion) {
                unchanged++
                continue
            }
            val row = fetchRow(slug) ?: continue
            fetched++

            val applied = store.applyFromServer(
                slug = row.slug,
                id = row.id,
                category = row.category,
                name = row.name,
                content = row.content,
                deliveryMode = row.deliveryMode,
                triggerKeywordsJson = row.triggerKeywordsJson,
                matchThreshold = row.matchThreshold,
                globalThreshold = row.globalThreshold,
                version = row.version,
                isActive = true,
            )
            if (applied) written++ else skippedEdited++
        }

        SyncReport(
            reachable = true,
            manifestRows = manifest.size,
            fetched = fetched,
            written = written,
            skippedEdited = skippedEdited,
            unchanged = unchanged,
            message = when {
                manifest.isEmpty() ->
                    "The publisher answered and has no prompts published. That is an answer about the " +
                        "server, not a failure to ask."
                fetched == 0 ->
                    "Nothing moved: all $unchanged row(s) on this device are already at the version the " +
                        "publisher holds."
                else ->
                    "$fetched row(s) fetched, $written written, $unchanged already current" +
                        if (skippedEdited > 0) {
                            ", $skippedEdited left alone because they hold a local edit."
                        } else "."
            },
        )
    }

    /** `{slug: version}` from the manifest, or null when the publisher could not be reached. */
    private suspend fun fetchManifest(): Map<String, Int>? {
        val body = get("v1/manifest") ?: return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val prompts = root["prompts"] as? JsonArray ?: return emptyMap()
        return prompts.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val slug = (obj["slug"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val version = (obj["version"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            slug to version
        }.toMap()
    }

    /** One row in full, or null when the fetch failed. */
    private suspend fun fetchRow(slug: String): RemoteRow? {
        val body = get("v1/prompts/$slug") ?: return null
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null

        fun text(key: String, fallback: String = ""): String =
            (obj[key] as? JsonPrimitive)?.content ?: fallback

        val id = text("id").ifBlank { return null }
        val keywords = (obj["trigger_keywords"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content
        } ?: (obj["triggerKeywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?: emptyList()

        return RemoteRow(
            id = id,
            slug = text("slug", slug),
            category = text("category"),
            name = text("name"),
            content = text("content"),
            deliveryMode = text("delivery_mode").ifBlank { text("deliveryMode", "always") },
            triggerKeywordsJson = JsonArray(keywords.map { JsonPrimitive(it) }).toString(),
            matchThreshold = (obj["match_threshold"] as? JsonPrimitive)?.doubleOrNull
                ?: (obj["matchThreshold"] as? JsonPrimitive)?.doubleOrNull,
            globalThreshold = (obj["global_threshold"] as? JsonPrimitive)?.doubleOrNull
                ?: (obj["globalThreshold"] as? JsonPrimitive)?.doubleOrNull
                ?: 0.75,
            version = (obj["version"] as? JsonPrimitive)?.intOrNull ?: 1,
            isActive = (obj["is_active"] as? JsonPrimitive)?.booleanOrNull
                ?: (obj["isActive"] as? JsonPrimitive)?.booleanOrNull
                ?: true,
        )
    }

    /**
     * One GET through the same egress every other content route uses.
     *
     * TOR IS NOT OPTIONAL HERE. The app's egress policy is that content leaves through the
     * same client everything else does, so a second path that skipped it would be a route
     * the owner's own privacy rules did not cover.
     *
     * NULL MEANS THE ASK FAILED, NEVER THAT THE ANSWER WAS EMPTY. A 404 is the server saying
     * a row does not exist; a transport failure is the server not having been asked. Both
     * collapse to null here because the caller treats either as "this row cannot be read",
     * but the row is never written as though the server had said it was withdrawn.
     */
    private suspend fun get(path: String): String? = runCatching {
        val body: String = TorEgress.httpClient().get(
            baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        ).bodyAsText()
        body
    }.getOrNull()

    private data class RemoteRow(
        val id: String,
        val slug: String,
        val category: String,
        val name: String,
        val content: String,
        val deliveryMode: String,
        val triggerKeywordsJson: String,
        val matchThreshold: Double?,
        val globalThreshold: Double,
        val version: Int,
        val isActive: Boolean,
    )
}
