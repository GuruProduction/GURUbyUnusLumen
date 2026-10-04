// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class ToolResultEntry(
    val toolName: String,
    val parameters: String,
    val result: String,
    val timestamp: Long,
    val ageMinutes: Long,
    val isStale: Boolean
) : ToolResultData

@Serializable
data class ToolResultRetrievalResult(
    val results: List<ToolResultEntry>
) : ToolResultData