// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class PlanResult(val planId: String, val title: String, val steps: List<String>, val createdNoteId: String) : ToolResultData
@Serializable data class PlanSummary(val id: String, val title: String, val stepCount: Int, val completedSteps: Int, val createdDate: Long, val updatedDate: Long) : ToolResultData
@Serializable data class PlansResult(val plans: List<PlanSummary>) : ToolResultData
@Serializable data class DeletePlanResult(val deletedPlanId: String) : ToolResultData