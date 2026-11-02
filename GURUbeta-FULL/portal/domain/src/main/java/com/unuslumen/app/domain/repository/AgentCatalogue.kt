// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import kotlinx.serialization.Serializable

/**
 * One agent as the publisher serves it.
 *
 * This is the catalogue's shape, not the phone's. It becomes a [GuruAgent] with
 * source "synced" once installed, and the two are deliberately separate types so
 * a downloaded definition can never be mistaken for one the person wrote.
 */
@Serializable
data class CatalogueAgent(
    val slug: String,
    val name: String,
    val roleDescription: String = "",
    val systemPrompt: String = "",
    val toolPermissions: List<String> = emptyList(),
    val isBuiltin: Boolean = false,
    val source: String = "community",
    val authorLabel: String? = null,
    val priceCents: Long = 0,
    /**
     * What the agent is for, as the market browses by: code, memory, planning, life,
     * creative or other.
     *
     * SEPARATE FROM [source] ON PURPOSE. `source` says whose content this is, which is
     * the trust boundary the sync worker relies on. The market was reading it as the
     * category, so its aisle strip showed "builtin" and "community" instead of what
     * any agent actually does.
     */
    val category: String = "other",
    val isActive: Boolean = true
)

/** What a publish attempt produced, honest about which refusal happened. */
data class PublishOutcome(
    val success: Boolean,
    val message: String,
    val submissionId: String? = null
)

/**
 * A maker, as the market knows them.
 *
 * A market is people, so an agent carries the identity of whoever published it
 * rather than a loose name string. Every field here is served by the publisher
 * and is public by definition, like a package maintainer's page.
 *
 * [agentCount] and [since] are computed from their published content rather than
 * stored, so neither can drift from the catalogue it describes. [since] is null
 * for a maker with nothing published, because inventing today's date would be a
 * claim about tenure that is not true.
 */
@Serializable
data class CataloguePublisher(
    val handle: String,
    val displayName: String,
    val bio: String = "",
    val location: String? = null,
    val website: String? = null,
    val isVerified: Boolean = false,
    val isOfficial: Boolean = false,
    val agentCount: Int = 0,
    val since: String? = null,
)

/** What a publisher's page holds: them, and everything they have published. */
data class PublisherPage(
    val publisher: CataloguePublisher,
    val agents: List<CatalogueAgent>,
)

/**
 * The real numbers behind one agent.
 *
 * [ratingAverage] is deliberately nullable. Null means nobody has rated it, and
 * that is a different fact from an average of zero stars. Showing a zero would
 * tell a person an agent was rated badly when it simply has not been rated.
 */
@Serializable
data class AgentStats(
    val installs: Long = 0,
    val ratingCount: Long = 0,
    val ratingAverage: Double? = null,
)

/** One review, with no identifier of who left it, because a reader has no business knowing. */
@Serializable
data class AgentReview(
    val stars: Int,
    val comment: String? = null,
    val createdAt: String = "",
)

/**
 * The publisher's agent catalogue: read it, and send one back for review.
 *
 * Declared in domain so the presentation layer can browse and publish without
 * reaching into the data module, which is the same shape every other repository
 * here follows.
 */
interface AgentCatalogue {

    /**
     * The whole published catalogue. Null means the transport could not answer
     * (offline, Tor down, publisher unreachable); an empty list means the
     * publisher answered and has nothing published. Callers treat the two
     * differently, because one keeps the installed set and the other does not.
     */
    suspend fun listCatalogue(): List<CatalogueAgent>?

    /** One agent in full by slug. Null when the slug is wrong or the publisher is unreachable. */
    suspend fun fetchAgent(slug: String): CatalogueAgent?

    /** Published version per slug, from the manifest. Null when offline. */
    suspend fun publishedVersions(): Map<String, Int>?

    /**
     * Publish one agent for review. The server is public on this route by
     * design, so no credential is sent; a refused slug, an oversized payload or
     * a key that looks like personal data each come back as a message worth
     * showing the person rather than a generic failure.
     */
    suspend fun submitForReview(
        slug: String,
        name: String,
        roleDescription: String,
        systemPrompt: String,
        toolPermissions: List<String>,
        authorLabel: String?,
        /**
         * What the agent sells for, in pence. Zero is free.
         *
         * IT TRAVELS WITH THE SUBMISSION because the catalogue publishes it. An author
         * sets a price in the creator and that number has to survive review; a paid
         * agent that quietly promotes as free is worse than a refusal, because the
         * person finds out from a buyer.
         */
        priceCents: Long = 0,
        /** What it is for, as the market browses: code, memory, planning, life, creative or other. */
        category: String = "other",
    ): PublishOutcome

    // ------------------------------------------------------------------
    // The market: makers, real numbers, ownership
    // ------------------------------------------------------------------

    /**
     * One maker's page. Null when the handle is unknown or the market cannot be
     * reached, which the caller reports as two different things.
     */
    suspend fun fetchPublisher(handle: String): PublisherPage?

    /** The real numbers for one agent, or null when the market cannot be reached. */
    suspend fun fetchStats(slug: String): AgentStats?

    /** Reviews for one agent, newest first. Empty when there is no market answer. */
    suspend fun fetchReviews(slug: String): List<AgentReview>

    /**
     * Record that this phone installed an agent.
     *
     * Returns whether it counted. False means this pseudonym already had it, so
     * the published number did not move and the caller must not imply it did.
     */
    suspend fun recordInstall(slug: String): Boolean

    /** Leave or change a rating. Returns whether it replaced a previous one. */
    suspend fun submitRating(slug: String, stars: Int, comment: String?): Boolean

    /**
     * The slugs this pseudonym owns, so a bought agent can be re-downloaded and
     * never sold twice to the same buyer.
     */
    suspend fun ownedSlugs(): Set<String>
}
