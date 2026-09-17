package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.AutomationTrigger
import com.unuslumen.app.domain.repository.AutomationRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AutomationToolExecutor(private val automationRepository: AutomationRepository) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    private fun com.unuslumen.app.domain.model.GuruAutomation.toInfo() = AutomationInfo(id, name, displayName, description, trigger.name, enabled, runCount)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        AutomationToolDefinitions.CREATE_AUTOMATION -> createAutomation(args)
        AutomationToolDefinitions.RUN_AUTOMATION -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val paramsStr = args["params"] as? String; val params = if (paramsStr.isNullOrBlank()) emptyMap() else try { json.parseToJsonElement(paramsStr).jsonObject.mapValues { it.value.jsonPrimitive.content } } catch (e: Exception) { emptyMap() }; val result = automationRepository.executeAutomationByName(name, params); val r = RunAutomationResult(name, result.success, result.results.size, result.results.map { StepExecutionInfo(it.step, it.tool, it.success, it.error) }, result.executionTimeMs, result.error); ToolExecutionResult.success(r, json.encodeToString(RunAutomationResult.serializer(), r)) }
        AutomationToolDefinitions.LIST_AUTOMATIONS -> { val autos = automationRepository.getAllAutomations(); val r = ListAutomationsResult(autos.map { it.toInfo() }, automationRepository.getSummary()); ToolExecutionResult.success(r, json.encodeToString(ListAutomationsResult.serializer(), r)) }
        AutomationToolDefinitions.GET_AUTOMATION -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val auto = automationRepository.getAutomationByName(name) ?: automationRepository.getAutomation(name) ?: return ToolExecutionResult.error("Not found: $name"); val r = GetAutomationResult(AutomationDetails(auto.id, auto.name, auto.displayName, auto.description, auto.trigger.name, auto.triggerConfig ?: "", auto.steps.map { StepInfo(it.tool, it.params, it.output) }, auto.enabled, auto.runCount, auto.createdAt, auto.lastRunAt)); ToolExecutionResult.success(r, json.encodeToString(GetAutomationResult.serializer(), r)) }
        AutomationToolDefinitions.DELETE_AUTOMATION -> { val id = args["automationId"] as? String ?: return ToolExecutionResult.error("Missing 'automationId'"); val auto = automationRepository.getAutomation(id) ?: return ToolExecutionResult.error("Not found: $id"); automationRepository.deleteAutomation(id); val r = DeleteAutomationResult(id, auto.name, "Deleted."); ToolExecutionResult.success(r, json.encodeToString(DeleteAutomationResult.serializer(), r)) }
        AutomationToolDefinitions.ENABLE_AUTOMATION -> { val id = args["automationId"] as? String ?: return ToolExecutionResult.error("Missing 'automationId'"); val auto = automationRepository.enableAutomation(id); val r = EnableAutomationResult(id, auto.name, "Enabled."); ToolExecutionResult.success(r, json.encodeToString(EnableAutomationResult.serializer(), r)) }
        AutomationToolDefinitions.DISABLE_AUTOMATION -> { val id = args["automationId"] as? String ?: return ToolExecutionResult.error("Missing 'automationId'"); val auto = automationRepository.disableAutomation(id); val r = DisableAutomationResult(id, auto.name, "Disabled."); ToolExecutionResult.success(r, json.encodeToString(DisableAutomationResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createAutomation(args: Map<String, Any?>): ToolExecutionResult {
        val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val displayName = args["displayName"] as? String ?: return ToolExecutionResult.error("Missing 'displayName'")
        val description = args["description"] as? String ?: return ToolExecutionResult.error("Missing 'description'")
        val triggerStr = args["trigger"] as? String ?: return ToolExecutionResult.error("Missing 'trigger'")
        val triggerConfig = args["triggerConfig"] as? String
        val stepsStr = args["steps"] as? String ?: return ToolExecutionResult.error("Missing 'steps'")

        val trigger = try { AutomationTrigger.valueOf(triggerStr.uppercase()) } catch (e: Exception) { return ToolExecutionResult.error("Invalid trigger: $triggerStr") }
        val parsedSteps = try { json.parseToJsonElement(stepsStr).jsonArray.map { se -> val so = se.jsonObject; com.unuslumen.app.domain.model.AutomationStep(so["tool"]?.jsonPrimitive?.content ?: "", so["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(), so["output"]?.jsonPrimitive?.content ?: "") } } catch (e: Exception) { return ToolExecutionResult.error("Invalid steps JSON") }
        val req = com.unuslumen.app.domain.model.CreateAutomationRequest(name, displayName, description, trigger, triggerConfig, parsedSteps)
        val validation = automationRepository.validateAutomation(req)
        if (!validation.valid) return ToolExecutionResult.error("Invalid: ${validation.errors.joinToString("; ")}")
        val auto = automationRepository.createAutomation(req)
        val r = CreateAutomationResult(auto.id, auto.name, auto.displayName, auto.trigger.name, auto.enabled, "Created.")
        return ToolExecutionResult.success(r, json.encodeToString(CreateAutomationResult.serializer(), r))
    }
}