package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class CreateAutomationResult(val automationId: String, val name: String, val displayName: String, val trigger: String, val enabled: Boolean, val message: String) : ToolResultData
@Serializable data class RunAutomationResult(val automationName: String, val success: Boolean, val stepsExecuted: Int, val results: List<StepExecutionInfo>, val executionTimeMs: Long, val error: String?) : ToolResultData
@Serializable data class StepExecutionInfo(val step: Int, val tool: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class ListAutomationsResult(val automations: List<AutomationInfo>, val summary: com.unuslumen.app.domain.model.AutomationSummary) : ToolResultData
@Serializable data class AutomationInfo(val id: String, val name: String, val displayName: String, val description: String, val trigger: String, val enabled: Boolean, val runCount: Int) : ToolResultData
@Serializable data class GetAutomationResult(val automation: AutomationDetails) : ToolResultData
@Serializable data class AutomationDetails(val id: String, val name: String, val displayName: String, val description: String, val trigger: String, val triggerConfig: String, val steps: List<StepInfo>, val enabled: Boolean, val runCount: Int, val createdAt: Long, val lastRunAt: Long?) : ToolResultData
@Serializable data class StepInfo(val tool: String, val params: Map<String, String>, val output: String) : ToolResultData
@Serializable data class DeleteAutomationResult(val automationId: String, val name: String, val message: String) : ToolResultData
@Serializable data class EnableAutomationResult(val automationId: String, val name: String, val message: String) : ToolResultData
@Serializable data class DisableAutomationResult(val automationId: String, val name: String, val message: String) : ToolResultData