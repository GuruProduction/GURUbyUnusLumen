package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.HookEventType
import com.unuslumen.app.domain.model.TriggerTiming
import com.unuslumen.app.domain.repository.HookRepository
import kotlinx.serialization.json.Json

class HookToolExecutor(private val hookRepository: HookRepository) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    private fun com.unuslumen.app.domain.model.GuruHook.toInfo() = HookInfo(id, name, displayName, description, eventType.name, triggerTiming.name, enabled, triggerCount)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        HookToolDefinitions.CREATE_HOOK -> createHook(args)
        HookToolDefinitions.LIST_HOOKS -> { val hooks = hookRepository.getAllHooks(); val r = ListHooksResult(hooks.map { it.toInfo() }, hookRepository.getSummary()); ToolExecutionResult.success(r, json.encodeToString(ListHooksResult.serializer(), r)) }
        HookToolDefinitions.GET_HOOK -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val hook = hookRepository.getHookByName(name) ?: hookRepository.getHook(name) ?: return ToolExecutionResult.error("Hook not found: $name"); val r = GetHookResult(HookDetails(hook.id, hook.name, hook.displayName, hook.description, hook.eventType.name, hook.triggerTiming.name, hook.condition?.let { mapOf("field" to it.field, "operator" to it.operator, "value" to it.value) }, mapOf("type" to hook.action.type, "target" to hook.action.target, "params" to hook.action.params, "async" to hook.action.async), hook.priority, hook.enabled, hook.triggerCount, hook.createdAt, hook.lastTriggeredAt)); ToolExecutionResult.success(r, json.encodeToString(GetHookResult.serializer(), r)) }
        HookToolDefinitions.DELETE_HOOK -> { val id = args["hookId"] as? String ?: return ToolExecutionResult.error("Missing 'hookId'"); val hook = hookRepository.getHook(id) ?: return ToolExecutionResult.error("Hook not found: $id"); hookRepository.deleteHook(id); val r = DeleteHookResult(id, hook.name, "Deleted."); ToolExecutionResult.success(r, json.encodeToString(DeleteHookResult.serializer(), r)) }
        HookToolDefinitions.ENABLE_HOOK -> { val id = args["hookId"] as? String ?: return ToolExecutionResult.error("Missing 'hookId'"); val hook = hookRepository.enableHook(id); val r = EnableHookResult(id, hook.name, "Enabled."); ToolExecutionResult.success(r, json.encodeToString(EnableHookResult.serializer(), r)) }
        HookToolDefinitions.DISABLE_HOOK -> { val id = args["hookId"] as? String ?: return ToolExecutionResult.error("Missing 'hookId'"); val hook = hookRepository.disableHook(id); val r = DisableHookResult(id, hook.name, "Disabled."); ToolExecutionResult.success(r, json.encodeToString(DisableHookResult.serializer(), r)) }
        HookToolDefinitions.TRIGGER_HOOK -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val eventData = args["eventData"] as? String ?: return ToolExecutionResult.error("Missing 'eventData'"); val hook = hookRepository.getHookByName(name) ?: hookRepository.getHook(name) ?: return ToolExecutionResult.error("Hook not found: $name"); val parsedData = try { json.decodeFromString<Map<String, Any?>>(eventData) } catch (e: Exception) { emptyMap() }; val results = hookRepository.executeHooks(hook.eventType, hook.triggerTiming, parsedData); val result = results.find { it.hookId == hook.id }; val r = TriggerHookResult(hook.id, hook.name, result?.success ?: false, if (result?.success == true) "Hook executed" else "Hook did not execute", result?.error); ToolExecutionResult.success(r, json.encodeToString(TriggerHookResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createHook(args: Map<String, Any?>): ToolExecutionResult {
        val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val displayName = args["displayName"] as? String ?: return ToolExecutionResult.error("Missing 'displayName'")
        val description = args["description"] as? String ?: return ToolExecutionResult.error("Missing 'description'")
        val eventTypeStr = args["eventType"] as? String ?: return ToolExecutionResult.error("Missing 'eventType'")
        val triggerTimingStr = args["triggerTiming"] as? String ?: return ToolExecutionResult.error("Missing 'triggerTiming'")
        val condition = args["condition"] as? String
        val actionStr = args["action"] as? String ?: return ToolExecutionResult.error("Missing 'action'")
        val priority = (args["priority"] as? Number)?.toInt() ?: 100

        val timing = try { TriggerTiming.valueOf(triggerTimingStr.uppercase()) } catch (e: Exception) { return ToolExecutionResult.error("Invalid timing: $triggerTimingStr") }
        val event = try { HookEventType.valueOf(eventTypeStr.uppercase()) } catch (e: Exception) { return ToolExecutionResult.error("Invalid event: $eventTypeStr") }
        val parsedCondition = condition?.let { try { json.decodeFromString<com.unuslumen.app.domain.model.HookCondition>(it) } catch (e: Exception) { return ToolExecutionResult.error("Invalid condition JSON") } }
        val parsedAction = try { json.decodeFromString<com.unuslumen.app.domain.model.HookAction>(actionStr) } catch (e: Exception) { return ToolExecutionResult.error("Invalid action JSON") }
        val req = com.unuslumen.app.domain.model.CreateHookRequest(name, displayName, description, event, timing, parsedCondition, parsedAction, priority)
        val validation = hookRepository.validateHook(req)
        if (!validation.valid) return ToolExecutionResult.error("Invalid: ${validation.errors.joinToString("; ")}")
        val hook = hookRepository.createHook(req)
        val r = CreateHookResult(hook.id, hook.name, hook.displayName, hook.eventType.name, hook.triggerTiming.name, hook.enabled, "Hook created.")
        return ToolExecutionResult.success(r, json.encodeToString(CreateHookResult.serializer(), r))
    }
}