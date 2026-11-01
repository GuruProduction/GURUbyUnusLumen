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

@Serializable
data class SpawnAgentResult(
    val success: Boolean,
    val agent: String,
    val report: String = "",
    val toolRounds: Int = 0,
    val error: String? = null
) : ToolResultData
