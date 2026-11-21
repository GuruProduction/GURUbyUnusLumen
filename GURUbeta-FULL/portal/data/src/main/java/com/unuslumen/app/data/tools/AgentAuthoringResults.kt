// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/**
 * One agent as a caller needs it to choose between agents, rather than to call one.
 *
 * `listAgents` already reports name, purpose and tools, and that is enough to spawn
 * a name you already know. It is not enough to route work: Deep Thinker, Wingman and
 * Director all decide who should do a job, and a decision needs the instructions, the
 * category, whether the agent is on, where it came from, and what it may touch. Those
 * are the fields here.
 */
@Serializable
data class AgentDetail(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    /** The full instructions, because routing needs to know what it was told to be. */
    val systemPrompt: String,
    val tools: List<String>,
    val toolCount: Int = 0,
    val enabled: Boolean = true,
    val source: String = "",
    /** True when this is the person's own agent, which the catalogue may never overwrite. */
    val userOwned: Boolean = false,
    val version: Int = 1,
    val updatedAt: Long = 0
) : ToolResultData

@Serializable
data class AgentDetailResult(
    val success: Boolean,
    val agent: AgentDetail? = null,
    val error: String? = null
) : ToolResultData

/** What a create or an edit produced. */
@Serializable
data class SaveAgentToolResult(
    val success: Boolean,
    val created: Boolean = false,
    val agent: AgentDetail? = null,
    val error: String? = null
) : ToolResultData

/** What enabling, disabling or deleting produced. */
@Serializable
data class AgentStateResult(
    val success: Boolean,
    val name: String = "",
    val enabled: Boolean = true,
    val deleted: Boolean = false,
    val error: String? = null
) : ToolResultData

/**
 * One agent that can do a job, with the reason it matched.
 *
 * [why] is not decoration: a caller choosing between three matches needs to know
 * whether the match was the name, the description, a tool it holds, or a word in its
 * instructions, because those are four different strengths of evidence.
 */
@Serializable
data class AgentMatch(
    val slug: String,
    val name: String,
    val description: String,
    val category: String = "",
    val tools: List<String> = emptyList(),
    val enabled: Boolean = true,
    val score: Int = 0,
    val why: String = ""
) : ToolResultData

@Serializable
data class SearchAgentsResult(
    val success: Boolean,
    val total: Int = 0,
    val matches: List<AgentMatch> = emptyList(),
    val error: String? = null
) : ToolResultData

/**
 * One tool the registry holds, for `listTools`.
 *
 * WHY THIS EXISTS SEPARATELY FROM getMyTools. `getMyTools` reports the tools the model
 * defined itself, which answers "what have I made" and cannot answer "what do I have".
 * Every routing decision, every permission audit and every "can I do X" question needs
 * the whole registry, so it is reported here with the category and the permission each
 * tool needs, which is what makes the answer actionable rather than a wall of names.
 */
@Serializable
data class ToolCatalogueEntry(
    val name: String,
    val category: String,
    val description: String,
    val permissions: List<String> = emptyList(),
    val parameters: List<String> = emptyList()
) : ToolResultData

@Serializable
data class ListToolsResult(
    val success: Boolean,
    val total: Int = 0,
    /** True when a filter was applied, so a count is a count of the filter, not the registry. */
    val filtered: Boolean = false,
    val tools: List<ToolCatalogueEntry> = emptyList(),
    val error: String? = null
) : ToolResultData

/**
 * One schedule, as a caller needs it rather than as the table stores it.
 *
 * THE MISSED COUNT AND THE LAST FAILURE ARE THE POINT. A schedule that has been failing silently for
 * a week looks exactly like one that never ran, and both look like work that simply did not happen.
 * Reporting what went wrong and how many windows went by is what turns an empty history into an
 * answer.
 */
@Serializable
data class ScheduleEntry(
    val scheduleId: String,
    val agentId: String,
    val title: String,
    /** The job as set up. It is frozen, so this is what will actually run next time. */
    val job: String,
    val enabled: Boolean,
    val intervalMinutes: Long,
    /** When it next runs, or absent when it is disarmed and therefore has no next time. */
    val nextDueAt: Long? = null,
    val lastStartedAt: Long? = null,
    val lastRunId: String? = null,
    /** How many due windows have gone by with no run started. */
    val missedCount: Int = 0,
    /** Why the most recent attempt did not start, when it did not. */
    val lastFailure: String? = null,
) : ToolResultData

@Serializable
data class SchedulesResult(
    val success: Boolean,
    val total: Int = 0,
    val schedules: List<ScheduleEntry> = emptyList(),
    val error: String? = null,
) : ToolResultData

/** What creating, arming or removing one schedule produced. */
@Serializable
data class ScheduleResult(
    val success: Boolean,
    val agent: String = "",
    val scheduleId: String = "",
    val title: String = "",
    val intervalMinutes: Long = 0,
    val nextDueAt: Long? = null,
    val error: String? = null,
) : ToolResultData
