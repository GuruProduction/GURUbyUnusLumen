// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class AgentListingEntry(
    val slug: String,
    val name: String,
    val description: String,
    val tools: List<String>,
    val enabled: Boolean,
    val source: String
) : ToolResultData

@Serializable
data class ListAgentsResult(
    val total: Int,
    val agents: List<AgentListingEntry>,
    val note: String? = null
) : ToolResultData

/**
 * One turn of an agent conversation.
 *
 * [status] decides what happens next, and it exists because there are three outcomes
 * rather than two. FINISHED carries the report. ASKING carries a [question] and the
 * [sessionId] that resumeAgent needs to continue. FAILED carries [error]. A caller
 * that reads only [success] cannot tell a finished job from a waiting one, which is
 * why the status is a field rather than something to infer.
 */
@Serializable
data class SpawnAgentResult(
    val success: Boolean,
    val agent: String,
    val status: String = "FINISHED",
    val report: String = "",
    val toolRounds: Int = 0,
    /** Present while a session is alive, so resumeAgent can find it. */
    val sessionId: String? = null,
    /** The question the agent is waiting on, when [status] is ASKING. */
    val question: String? = null,
    /** Why it could not proceed without asking, when it said. */
    val why: String? = null,
    /** The answers it said it could act on, when it offered any. */
    val options: List<String> = emptyList(),
    val error: String? = null
) : ToolResultData

// ------------------------------------------------------------------
// The catalogue tools
// ------------------------------------------------------------------

/** One published agent, as the catalogue lists it. */
@Serializable
data class CatalogueEntryView(
    val slug: String,
    val name: String,
    val roleDescription: String,
    val tools: List<String>,
    val authorLabel: String? = null,
    val priceCents: Long = 0,
    /** Whether this phone already has it, so a caller does not offer an install twice. */
    val installed: Boolean = false,
    val installedVersion: Int? = null,
    val publishedVersion: Int? = null
) : ToolResultData

/**
 * The catalogue as a caller needs it.
 *
 * [reachable] is not decoration. A null list from the transport and an empty list from
 * the publisher mean opposite things: the first is "I could not ask", the second is
 * "nobody has published anything". Collapsing them is the defect class this build keeps
 * removing, so they are separate fields rather than an inference from `entries.isEmpty()`.
 */
@Serializable
data class BrowseCatalogueResult(
    val success: Boolean,
    val reachable: Boolean,
    val total: Int = 0,
    val agents: List<CatalogueEntryView> = emptyList(),
    val error: String? = null
) : ToolResultData

/** One catalogue agent in full, with what it would do if it were installed. */
@Serializable
data class FetchCatalogueAgentResult(
    val success: Boolean,
    val slug: String = "",
    val name: String = "",
    val roleDescription: String = "",
    val systemPrompt: String = "",
    val tools: List<String> = emptyList(),
    val authorLabel: String? = null,
    val installed: Boolean = false,
    val error: String? = null
) : ToolResultData

/** What an install produced, honest about whether the agent is now on the phone. */
@Serializable
data class InstallCatalogueAgentResult(
    val success: Boolean,
    val slug: String = "",
    val name: String = "",
    val version: Int = 0,
    val tools: List<String> = emptyList(),
    /** True when this replaced an already-installed agent rather than adding one. */
    val replaced: Boolean = false,
    val error: String? = null
) : ToolResultData
