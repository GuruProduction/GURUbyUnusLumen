package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class PlanResult(val planId: String, val title: String, val steps: List<String>, val createdNoteId: String) : ToolResultData
@Serializable data class PlanSummary(val id: String, val title: String, val stepCount: Int, val completedSteps: Int, val createdDate: Long, val updatedDate: Long) : ToolResultData
@Serializable data class PlansResult(val plans: List<PlanSummary>) : ToolResultData
@Serializable data class DeletePlanResult(val deletedPlanId: String) : ToolResultData