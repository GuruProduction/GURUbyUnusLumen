// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.content.Context
import android.util.Log
import com.unuslumen.app.data.skills.SkillForge
import com.unuslumen.app.data.tor.TorEgress
import com.unuslumen.app.domain.repository.AgentReview
import com.unuslumen.app.domain.repository.AgentStats
import com.unuslumen.app.domain.repository.CataloguePublisher
import com.unuslumen.app.domain.repository.PublisherPage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The agent-catalogue client. The sibling of SkillForge, pointed at the agent
 * half of the same publisher: /v1/agents lists the catalogue and
 * /v1/agents/{slug} carries one definition.
 *
 * The base URL comes from SkillForge.baseUrl so a GURU pointed at a private
 * publisher reaches agents and skills at the same host with one setting,
 * rather than needing a second override that could disagree with the first.
 *
 * Submission is the other direction and it is deliberately here rather than in
 * a screen, so the payload shape lives beside the parser for the same shape.
 * POST /v1/submit/agent is public and requires no credential; the server
 * refuses a slug that is not lowercase-dashed, a payload over 2MB, and any
 * key that looks like personal data, and it reports those refusals with a
 * message worth showing to the person.
 */
object AgentForge {

    private const val TAG = "AgentForge"

    private val json = Json { ignoreUnknownKeys = true }

    /** One catalogue listing entry. The list route carries definitions in full. */
    @Serializable
    data class CatalogueAgent(
        val id: String = "",
        val slug: String,
        val name: String,
        @SerialName("role_description") val roleDescription: String = "",
        @SerialName("system_prompt") val systemPrompt: String = "",
        @SerialName("tool_permissions") val toolPermissions: List<String> = emptyList(),
        val wave: Int = 1,
        @SerialName("is_builtin") val isBuiltin: Boolean = false,
        val source: String = "community",
        @SerialName("author_label") val authorLabel: String? = null,
        @SerialName("price_cents") val priceCents: Long = 0,
        /**
         * What the agent is for, as the market browses by.
         *
         * THE SERVER'S OWN COLUMN, NOT THE SOURCE FIELD. `source` says whose content
         * it is and is a trust boundary; using it as a category made the market's
         * aisle strip read "builtin" and "community", which is a fact about
         * provenance rather than about what the agent does.
         */
        val category: String = "other",
        @SerialName("is_active") val isActive: Boolean = true
    )

    /** What a submission attempt produced, honest about which refusal happened. */
    data class SubmitOutcome(
        val success: Boolean,
        val message: String,
        val submissionId: String? = null
    )

    private fun baseUrl(context: Context): String = SkillForge.baseUrl(context)

    private suspend fun client(): HttpClient = HttpClient(TorEgress.newEngine()) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * The whole published agent catalogue.
     *
     * Null means the transport could not answer (offline, Tor down, publisher
     * unreachable). An empty list means the publisher answered and has nothing
     * published. The difference matters to a caller deciding whether to keep
     * what it already has.
     */
    suspend fun listCatalogue(context: Context): List<CatalogueAgent>? = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        try {
            val http = client()
            try {
                val response = http.get("$base/v1/agents")
                if (!response.status.isSuccess()) {
                    Log.w(TAG, "Agent catalogue fetch failed: HTTP ${response.status.value}")
                    return@withContext null
                }
                val parsed = json.decodeFromString(
                    ListSerializer(CatalogueAgent.serializer()),
                    response.bodyAsText()
                )
                parsed.filter { it.isActive }
            } finally {
                http.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Agent catalogue unreachable: ${e.message}")
            null
        }
    }

    /** One agent in full by slug. Null when the slug is wrong or the publisher is unreachable. */
    suspend fun fetchAgent(context: Context, slug: String): CatalogueAgent? = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        try {
            val http = client()
            try {
                val response = http.get("$base/v1/agents/$slug")
                if (!response.status.isSuccess()) {
                    Log.w(TAG, "Agent '$slug' fetch failed: HTTP ${response.status.value}")
                    return@withContext null
                }
                json.decodeFromString(CatalogueAgent.serializer(), response.bodyAsText())
            } finally {
                http.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Agent '$slug' unreachable: ${e.message}")
            null
        }
    }

    /** The manifest's agent entries as slug to version. Null when offline. */
    suspend fun manifestVersions(context: Context): Map<String, Int>? = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        try {
            val http = client()
            try {
                val response = http.get("$base/v1/manifest")
                if (!response.status.isSuccess()) return@withContext null
                val body = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                    ?: return@withContext null
                val agents = body["agents"] as? JsonArray ?: return@withContext emptyMap<String, Int>()
                agents.mapNotNull { entry ->
                    val obj = entry as? JsonObject ?: return@mapNotNull null
                    val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val version = obj["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                    if (slug.isBlank()) null else slug to version
                }.toMap()
            } finally {
                http.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Manifest unreachable: ${e.message}")
            null
        }
    }

    /**
     * Publish one agent for review. The payload carries exactly the fields the
     * server's promote_agent reads, so an approved submission lands without the
     * approver having to fill a gap the submission could have filled.
     *
     * The server is public on this route by design, which is why no credential
     * is sent. A slug in the wrong shape, a payload over 2MB, or a key that
     * looks like personal data are each refused with a message, and that
     * message is returned verbatim rather than flattened into a generic failure.
     */
    suspend fun submitForReview(
        context: Context,
        slug: String,
        name: String,
        roleDescription: String,
        systemPrompt: String,
        toolPermissions: List<String>,
        authorLabel: String?,
        priceCents: Long = 0,
        category: String = "other",
    ): SubmitOutcome = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        val payload = buildString {
            append("{\"slug\":").append(jsonString(slug))
            append(",\"name\":").append(jsonString(name))
            append(",\"role_description\":").append(jsonString(roleDescription))
            append(",\"system_prompt\":").append(jsonString(systemPrompt))
            append(",\"tool_permissions\":[").append(
                toolPermissions.joinToString(",") { jsonString(it) }
            ).append("]")
            // PRICE AND CATEGORY TRAVEL WITH THE SUBMISSION. Both are written into
            // the catalogue by the promotion path, so leaving them out here would
            // mean an author could set a price in the creator and the approved agent
            // would still land free, in the wrong aisle.
            append(",\"price_cents\":").append(priceCents.coerceAtLeast(0))
            append(",\"category\":").append(jsonString(category))
            if (!authorLabel.isNullOrBlank()) {
                append(",\"author_label\":").append(jsonString(authorLabel))
            }
            append("}")
        }
        try {
            val http = client()
            try {
                val response = http.post("$base/v1/submit/agent") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                val body = response.bodyAsText()
                when {
                    response.status == HttpStatusCode.Created -> {
                        val id = runCatching {
                            (json.parseToJsonElement(body) as JsonObject)["id"]?.jsonPrimitive?.content
                        }.getOrNull()
                        SubmitOutcome(
                            success = true,
                            message = "Submitted. It waits in the review queue until it is checked, then it appears in the catalogue.",
                            submissionId = id
                        )
                    }
                    response.status == HttpStatusCode.TooManyRequests ->
                        SubmitOutcome(false, "The catalogue is busy right now. Nothing was submitted; try again shortly.")
                    else -> SubmitOutcome(
                        success = false,
                        message = body.ifBlank { "Submission refused (HTTP ${response.status.value})." }
                    )
                }
            } finally {
                http.close()
            }
        } catch (e: Exception) {
            SubmitOutcome(
                success = false,
                message = "Could not reach the catalogue (${e.message ?: "offline"}). Nothing was submitted."
            )
        }
    }

    // ------------------------------------------------------------------
    // The market: publishers, real numbers, ownership
    // ------------------------------------------------------------------

    /**
     * A pseudonym for this install.
     *
     * IT IS RANDOM AND HASHED AGAIN BEFORE IT LEAVES. The server has no accounts
     * by design, so a rating or an entitlement still has to attach to something
     * that survives a reinstall of the app but identifies nobody. This is a UUID
     * generated once, kept in local preferences, and SHA-256 hashed on the way
     * out. The wire and the database both see a string with no person behind it.
     */
    fun subjectHash(context: Context): String {
        val prefs = context.getSharedPreferences("guru_market", Context.MODE_PRIVATE)
        val id = prefs.getString("subject_id", null)
            ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("subject_id", it).apply()
            }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** One maker's page, or null when the handle is unknown or the market is unreachable. */
    suspend fun fetchPublisher(context: Context, handle: String): PublisherPage? =
        withContext(Dispatchers.IO) {
            val base = baseUrl(context)
            try {
                val http = client()
                try {
                    val response = http.get("$base/v1/publishers/$handle")
                    if (!response.status.isSuccess()) return@withContext null
                    val body = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                        ?: return@withContext null
                    val publisher = body["publisher"] as? JsonObject ?: return@withContext null
                    // THE WIRE TYPE IS CONVERTED HERE, NOT LEAKED OUT.
                    //
                    // AgentForge's own CatalogueAgent is the JSON shape; the domain
                    // CatalogueAgent is what a screen is allowed to see. Returning the
                    // wire type compiled into a type mismatch because the two share a
                    // name but not a package, and the honest fix is to map at the
                    // boundary rather than widen the return type.
                    val agents = (body["agents"] as? JsonArray).orEmpty().mapNotNull { entry ->
                        runCatching {
                            json.decodeFromString(CatalogueAgent.serializer(), entry.toString())
                        }.getOrNull()
                    }.map { it.toDomain() }
                    PublisherPage(publisher = publisher.toPublisher(), agents = agents)
                } finally {
                    http.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Publisher '$handle' unreachable: ${e.message}")
                null
            }
        }

    /** The real numbers for one agent. Null when the market cannot be reached. */
    suspend fun fetchStats(context: Context, slug: String): AgentStats? =
        withContext(Dispatchers.IO) {
            val base = baseUrl(context)
            try {
                val http = client()
                try {
                    val response = http.get("$base/v1/agents/$slug/stats")
                    if (!response.status.isSuccess()) return@withContext null
                    json.decodeFromString(AgentStats.serializer(), response.bodyAsText())
                } finally {
                    http.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stats for '$slug' unreachable: ${e.message}")
                null
            }
        }

    /** Reviews for one agent, newest first. Empty when there is no answer. */
    suspend fun fetchReviews(context: Context, slug: String): List<AgentReview> =
        withContext(Dispatchers.IO) {
            val base = baseUrl(context)
            try {
                val http = client()
                try {
                    val response = http.get("$base/v1/agents/$slug/reviews")
                    if (!response.status.isSuccess()) return@withContext emptyList()
                    json.decodeFromString(
                        ListSerializer(AgentReview.serializer()),
                        response.bodyAsText(),
                    )
                } finally {
                    http.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reviews for '$slug' unreachable: ${e.message}")
                emptyList()
            }
        }

    /**
     * Record an install, returning whether it counted.
     *
     * A failure returns false rather than throwing. The install is what the
     * person asked for; the count is bookkeeping, and bookkeeping must never be
     * the reason an install fails.
     */
    suspend fun recordInstall(context: Context, slug: String): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        try {
            val http = client()
            try {
                val response = http.post("$base/v1/agents/$slug/install") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"subject_hash\":${jsonString(subjectHash(context))}}")
                }
                if (!response.status.isSuccess()) return@withContext false
                val body = runCatching {
                    json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                }.getOrNull() ?: return@withContext false
                body["counted"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            } finally {
                http.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Install for '$slug' not recorded: ${e.message}")
            false
        }
    }

    /** Leave or change a rating. Returns whether it replaced a previous one. */
    suspend fun submitRating(
        context: Context,
        slug: String,
        stars: Int,
        comment: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        val payload = buildString {
            append("{\"subject_hash\":").append(jsonString(subjectHash(context)))
            append(",\"stars\":").append(stars.coerceIn(1, 5))
            if (!comment.isNullOrBlank()) {
                append(",\"comment\":").append(jsonString(comment))
            }
            append("}")
        }
        try {
            val http = client()
            try {
                val response = http.post("$base/v1/agents/$slug/rating") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (!response.status.isSuccess()) return@withContext false
                val body = runCatching {
                    json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                }.getOrNull() ?: return@withContext false
                body["replaced"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            } finally {
                http.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rating for '$slug' not recorded: ${e.message}")
            false
        }
    }

    /** The slugs this pseudonym owns. Empty when the market cannot be reached. */
    suspend fun ownedSlugs(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        try {
            val http = client()
            try {
                val subject = java.net.URLEncoder.encode(subjectHash(context), "UTF-8")
                val response = http.get("$base/v1/entitlements?subject_hash=$subject")
                if (!response.status.isSuccess()) return@withContext emptySet()
                val body = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                    ?: return@withContext emptySet()
                (body["owned"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    entry.jsonPrimitive.content.takeIf { it.isNotBlank() }
                }.toSet()
            } finally {
                http.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Entitlements unreachable: ${e.message}")
            emptySet()
        }
    }

    /**
     * The wire shape as the domain shape.
     *
     * Kept here, beside the parser, so the mapping lives with the type it maps and
     * cannot drift from a second copy written elsewhere.
     */
    private fun CatalogueAgent.toDomain() = com.unuslumen.app.domain.repository.CatalogueAgent(
        slug = slug,
        name = name,
        roleDescription = roleDescription,
        systemPrompt = systemPrompt,
        toolPermissions = toolPermissions,
        isBuiltin = isBuiltin,
        source = source,
        authorLabel = authorLabel,
        priceCents = priceCents,
        category = category,
        isActive = isActive,
    )

    /** Read a publisher off the wire, tolerating a server that omits optional fields. */
    private fun JsonObject.toPublisher() = CataloguePublisher(
        handle = this["handle"]?.jsonPrimitive?.content.orEmpty(),
        displayName = this["display_name"]?.jsonPrimitive?.content.orEmpty(),
        bio = this["bio"]?.jsonPrimitive?.content.orEmpty(),
        location = this["location"]?.jsonPrimitive?.contentOrNull,
        website = this["website"]?.jsonPrimitive?.contentOrNull,
        isVerified = this["is_verified"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        isOfficial = this["is_official"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        agentCount = this["agent_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        since = this["since"]?.jsonPrimitive?.contentOrNull,
    )

    /** Minimal JSON string escaping, so a prompt containing quotes survives the wire intact. */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') {
                    append("\\u").append(String.format("%04x", c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}
