package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable data class CreateThoughtCycleResult(val cycleId: String, val name: String, val displayName: String, val triggerType: String, val outputType: String, val enabled: Boolean, val message: String) : ToolResultData
@Serializable data class ListThoughtCyclesResult(val cycles: List<ThoughtCycleInfo>, val summary: com.unuslumen.app.domain.model.ThoughtCyclesSummary) : ToolResultData
@Serializable data class ThoughtCycleInfo(val id: String, val name: String, val displayName: String, val description: String, val triggerType: String, val outputType: String, val enabled: Boolean, val runCount: Int, val insightCount: Int) : ToolResultData
@Serializable data class GetThoughtCycleResult(val cycle: ThoughtCycleDetails) : ToolResultData
@Serializable data class ThoughtCycleDetails(val id: String, val name: String, val displayName: String, val description: String, val triggerType: String, val triggerConfig: String, val thoughtProcess: List<Map<String, @Contextual Any>>, val outputType: String, val outputConfig: String?, val enabled: Boolean, val runCount: Int, val insightCount: Int, val actionCount: Int, val proposalCount: Int, val createdAt: Long, val lastRunAt: Long?, val lastResult: String?) : ToolResultData
@Serializable data class ExecuteThoughtCycleResult(val cycleId: String, val cycleName: String, val success: Boolean, val insightsGenerated: Int, val actionsGenerated: Int, val proposalsGenerated: Int, val insights: List<InsightInfo>, val actions: List<String>, val proposals: List<String>, val error: String?, val executionTimeMs: Long) : ToolResultData
@Serializable data class InsightInfo(val id: String, val type: String, val title: String, val content: String, val confidence: Float, val actionable: Boolean, val actionTaken: Boolean, val createdAt: Long) : ToolResultData
@Serializable data class DeleteThoughtCycleResult(val cycleId: String, val name: String, val message: String) : ToolResultData
@Serializable data class EnableThoughtCycleResult(val cycleId: String, val name: String, val message: String) : ToolResultData
@Serializable data class DisableThoughtCycleResult(val cycleId: String, val name: String, val message: String) : ToolResultData
@Serializable data class GetInsightsResult(val insights: List<InsightInfo>, val total: Int, val unacknowledged: Int) : ToolResultData
@Serializable data class AcknowledgeInsightResult(val insightId: String, val message: String) : ToolResultData
@Serializable data class DismissInsightResult(val insightId: String, val message: String) : ToolResultData