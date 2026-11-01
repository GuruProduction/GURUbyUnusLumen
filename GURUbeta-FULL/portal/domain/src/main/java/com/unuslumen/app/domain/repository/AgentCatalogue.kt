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
    val isActive: Boolean = true
)

/** What a publish attempt produced, honest about which refusal happened. */
data class PublishOutcome(
    val success: Boolean,
    val message: String,
    val submissionId: String? = null
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
        authorLabel: String?
    ): PublishOutcome
}
