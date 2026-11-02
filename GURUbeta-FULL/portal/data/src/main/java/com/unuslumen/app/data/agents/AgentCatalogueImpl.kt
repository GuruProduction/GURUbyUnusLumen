// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.AgentReview
import com.unuslumen.app.domain.repository.AgentStats
import com.unuslumen.app.domain.repository.CatalogueAgent
import com.unuslumen.app.domain.repository.PublishOutcome
import com.unuslumen.app.domain.repository.PublisherPage
import org.koin.core.annotation.Single

/**
 * The [AgentCatalogue] binding over [AgentForge].
 *
 * Kept thin on purpose: AgentForge owns the wire and this exists so the
 * presentation layer can reach the catalogue through the domain interface
 * instead of importing the data module, which it does not depend on.
 */
@Single(binds = [AgentCatalogue::class])
class AgentCatalogueImpl(
    private val context: android.content.Context
) : AgentCatalogue {

    override suspend fun listCatalogue(): List<CatalogueAgent>? {
        val raw = AgentForge.listCatalogue(context) ?: return null
        return raw.map { it.toDomain() }
    }

    override suspend fun fetchAgent(slug: String): CatalogueAgent? =
        AgentForge.fetchAgent(context, slug)?.toDomain()

    override suspend fun publishedVersions(): Map<String, Int> =
        AgentForge.manifestVersions(context) ?: emptyMap()

    override suspend fun submitForReview(
        slug: String,
        name: String,
        roleDescription: String,
        systemPrompt: String,
        toolPermissions: List<String>,
        authorLabel: String?,
        priceCents: Long,
        category: String,
    ): PublishOutcome {
        val outcome = AgentForge.submitForReview(
            context = context,
            slug = slug,
            name = name,
            roleDescription = roleDescription,
            systemPrompt = systemPrompt,
            toolPermissions = toolPermissions,
            authorLabel = authorLabel,
            priceCents = priceCents,
            category = category,
        )
        return PublishOutcome(
            success = outcome.success,
            message = outcome.message,
            submissionId = outcome.submissionId
        )
    }

    // ------------------------------------------------------------------
    // The market
    // ------------------------------------------------------------------

    override suspend fun fetchPublisher(handle: String): PublisherPage? =
        AgentForge.fetchPublisher(context, handle)

    override suspend fun fetchStats(slug: String): AgentStats? =
        AgentForge.fetchStats(context, slug)

    override suspend fun fetchReviews(slug: String): List<AgentReview> =
        AgentForge.fetchReviews(context, slug)

    override suspend fun recordInstall(slug: String): Boolean =
        AgentForge.recordInstall(context, slug)

    override suspend fun submitRating(slug: String, stars: Int, comment: String?): Boolean =
        AgentForge.submitRating(context, slug, stars, comment)

    override suspend fun ownedSlugs(): Set<String> = AgentForge.ownedSlugs(context)

    private fun AgentForge.CatalogueAgent.toDomain() = CatalogueAgent(
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
        isActive = isActive
    )
}
