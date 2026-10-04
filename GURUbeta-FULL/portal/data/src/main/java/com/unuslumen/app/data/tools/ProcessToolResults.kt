// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class ProcessInfo(
    val pid: String,
    val name: String,
    val state: String
) : ToolResultData

@Serializable
data class ProcessListResult(
    val processes: List<ProcessInfo>,
    val error: String?
) : ToolResultData

@Serializable
data class ProcessActionResult(
    val success: Boolean,
    val error: String?
) : ToolResultData