package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable data class CreateHookResult(val hookId: String, val name: String, val displayName: String, val eventType: String, val triggerTiming: String, val enabled: Boolean, val message: String) : ToolResultData
@Serializable data class ListHooksResult(val hooks: List<HookInfo>, val summary: com.unuslumen.app.domain.model.HooksSummary) : ToolResultData
@Serializable data class HookInfo(val id: String, val name: String, val displayName: String, val description: String, val eventType: String, val triggerTiming: String, val enabled: Boolean, val triggerCount: Int) : ToolResultData
@Serializable data class GetHookResult(val hook: HookDetails) : ToolResultData
@Serializable data class HookDetails(val id: String, val name: String, val displayName: String, val description: String, val eventType: String, val triggerTiming: String, val condition: Map<String, String>?, val action: Map<String, @Contextual Any>, val priority: Int, val enabled: Boolean, val triggerCount: Int, val createdAt: Long, val lastTriggeredAt: Long?) : ToolResultData
@Serializable data class DeleteHookResult(val hookId: String, val name: String, val message: String) : ToolResultData
@Serializable data class EnableHookResult(val hookId: String, val name: String, val message: String) : ToolResultData
@Serializable data class DisableHookResult(val hookId: String, val name: String, val message: String) : ToolResultData
@Serializable data class TriggerHookResult(val hookId: String, val hookName: String, val success: Boolean, val message: String, val error: String?) : ToolResultData