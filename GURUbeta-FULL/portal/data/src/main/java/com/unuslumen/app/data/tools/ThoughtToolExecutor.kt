package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.InsightType
import com.unuslumen.app.domain.model.ThoughtOutputType
import com.unuslumen.app.domain.model.ThoughtTriggerType
import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ThoughtToolExecutor(private val thoughtCycleRepository: ThoughtCycleRepository) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    private fun com.unuslumen.app.domain.model.GuruThoughtCycle.toInfo() = ThoughtCycleInfo(id, name, displayName, description, triggerType.name, outputType.name, enabled, runCount, insightCount)
    private fun com.unuslumen.app.domain.model.GuruInsight.toInfo() = InsightInfo(id, type.name, title, content, confidence, actionable, actionTaken, createdAt)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ThoughtToolDefinitions.CREATE_THOUGHT_CYCLE -> createCycle(args)
        ThoughtToolDefinitions.LIST_THOUGHT_CYCLES -> { val cycles = thoughtCycleRepository.getAllCycles(); val r = ListThoughtCyclesResult(cycles.map { it.toInfo() }, thoughtCycleRepository.getSummary()); ToolExecutionResult.success(r, json.encodeToString(ListThoughtCyclesResult.serializer(), r)) }
        ThoughtToolDefinitions.GET_THOUGHT_CYCLE -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val cycle = thoughtCycleRepository.getCycleByName(name) ?: thoughtCycleRepository.getCycle(name) ?: return ToolExecutionResult.error("Not found: $name"); val r = GetThoughtCycleResult(ThoughtCycleDetails(cycle.id, cycle.name, cycle.displayName, cycle.description, cycle.triggerType.name, cycle.triggerConfig, cycle.thoughtProcess.map { mapOf("type" to it.type, "input" to it.input, "params" to it.params) }, cycle.outputType.name, cycle.outputConfig, cycle.enabled, cycle.runCount, cycle.insightCount, cycle.actionCount, cycle.proposalCount, cycle.createdAt, cycle.lastRunAt, cycle.lastResult)); ToolExecutionResult.success(r, json.encodeToString(GetThoughtCycleResult.serializer(), r)) }
        ThoughtToolDefinitions.EXECUTE_THOUGHT_CYCLE -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val cycle = thoughtCycleRepository.getCycleByName(name) ?: thoughtCycleRepository.getCycle(name) ?: return ToolExecutionResult.error("Not found: $name"); val result = thoughtCycleRepository.executeCycle(cycle.id); val r = ExecuteThoughtCycleResult(cycle.id, cycle.name, result.success, result.insights.size, result.actions.size, result.proposals.size, result.insights.map { it.toInfo() }, result.actions, result.proposals, result.error, result.executionTimeMs); ToolExecutionResult.success(r, json.encodeToString(ExecuteThoughtCycleResult.serializer(), r)) }
        ThoughtToolDefinitions.DELETE_THOUGHT_CYCLE -> { val id = args["cycleId"] as? String ?: return ToolExecutionResult.error("Missing 'cycleId'"); val cycle = thoughtCycleRepository.getCycle(id) ?: return ToolExecutionResult.error("Not found: $id"); thoughtCycleRepository.deleteCycle(id); val r = DeleteThoughtCycleResult(id, cycle.name, "Deleted."); ToolExecutionResult.success(r, json.encodeToString(DeleteThoughtCycleResult.serializer(), r)) }
        ThoughtToolDefinitions.ENABLE_THOUGHT_CYCLE -> { val id = args["cycleId"] as? String ?: return ToolExecutionResult.error("Missing 'cycleId'"); val cycle = thoughtCycleRepository.enableCycle(id); val r = EnableThoughtCycleResult(id, cycle.name, "Enabled."); ToolExecutionResult.success(r, json.encodeToString(EnableThoughtCycleResult.serializer(), r)) }
        ThoughtToolDefinitions.DISABLE_THOUGHT_CYCLE -> { val id = args["cycleId"] as? String ?: return ToolExecutionResult.error("Missing 'cycleId'"); val cycle = thoughtCycleRepository.disableCycle(id); val r = DisableThoughtCycleResult(id, cycle.name, "Disabled."); ToolExecutionResult.success(r, json.encodeToString(DisableThoughtCycleResult.serializer(), r)) }
        ThoughtToolDefinitions.GET_INSIGHTS -> { val type = args["type"] as? String; val unack = (args["unacknowledgedOnly"] as? Boolean) ?: false; val insights = when { type != null -> { val it2 = try { InsightType.valueOf(type.uppercase()) } catch (e: Exception) { return ToolExecutionResult.error("Invalid insight type: $type") }; thoughtCycleRepository.getInsightsByType(it2) }; unack -> thoughtCycleRepository.getUnacknowledgedInsights(); else -> thoughtCycleRepository.getAllInsights() }; val r = GetInsightsResult(insights.map { it.toInfo() }, insights.size, insights.count { it.acknowledgedAt == null && it.dismissedAt == null }); ToolExecutionResult.success(r, json.encodeToString(GetInsightsResult.serializer(), r)) }
        ThoughtToolDefinitions.ACKNOWLEDGE_INSIGHT -> { val id = args["insightId"] as? String ?: return ToolExecutionResult.error("Missing 'insightId'"); thoughtCycleRepository.acknowledgeInsight(id); val r = AcknowledgeInsightResult(id, "Acknowledged."); ToolExecutionResult.success(r, json.encodeToString(AcknowledgeInsightResult.serializer(), r)) }
        ThoughtToolDefinitions.DISMISS_INSIGHT -> { val id = args["insightId"] as? String ?: return ToolExecutionResult.error("Missing 'insightId'"); thoughtCycleRepository.dismissInsight(id); val r = DismissInsightResult(id, "Dismissed."); ToolExecutionResult.success(r, json.encodeToString(DismissInsightResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createCycle(args: Map<String, Any?>): ToolExecutionResult {
        val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val displayName = args["displayName"] as? String ?: return ToolExecutionResult.error("Missing 'displayName'")
        val description = args["description"] as? String ?: return ToolExecutionResult.error("Missing 'description'")
        val triggerTypeStr = args["triggerType"] as? String ?: return ToolExecutionResult.error("Missing 'triggerType'")
        val triggerConfigRaw = args["triggerConfig"] ?: return ToolExecutionResult.error("Missing 'triggerConfig'")
        val thoughtProcessRaw = args["thoughtProcess"] ?: return ToolExecutionResult.error("Missing 'thoughtProcess'")
        val outputTypeStr = args["outputType"] as? String ?: return ToolExecutionResult.error("Missing 'outputType'")
        val outputConfigRaw = args["outputConfig"]

        // Normalize object/array arguments to their JSON text (model sends real JSON,
        // the dispatcher stringifies anything complex before we receive it here)
        fun normalize(v: Any?): String = v as? String ?: v.toString()
        val triggerConfig = normalize(triggerConfigRaw)
        val outputConfig = outputConfigRaw?.let { normalize(it) }

        val type = try { ThoughtTriggerType.valueOf(triggerTypeStr.uppercase().trim()) } catch (e: Exception) {
            return ToolExecutionResult.error(
                "Invalid trigger type '$triggerTypeStr'. Valid values: SCHEDULED, EVENT, THRESHOLD"
            )
        }
        val output = try { ThoughtOutputType.valueOf(outputTypeStr.uppercase().trim()) } catch (e: Exception) {
            return ToolExecutionResult.error(
                "Invalid output type '$outputTypeStr'. Valid values: INSIGHT, ACTION, MEMORY, PROPOSAL"
            )
        }

        // thoughtProcess accepts a JSON array whose elements are EITHER plain strings
        // (treated as step input, type defaults to ANALYZE) or step objects with
        // {type, input, params: {key: value}}. Bare strings or top-level objects die here.
        val parsedProcess: MutableList<com.unuslumen.app.domain.model.ThoughtStep> = mutableListOf()
        try {
            val root = json.parseToJsonElement(normalize(thoughtProcessRaw))
            if (root !is kotlinx.serialization.json.JsonArray) {
                return ToolExecutionResult.error(
                    "Invalid thoughtProcess: expected a JSON array of steps (got ${root::class.simpleName}). " +
                        "Each element may be a plain string or an object {type: \"ANALYZE\"|\"REFLECT\"|\"SYNTHESIZE\"|\"PROPOSE\", input: string, params: {}}"
                )
            }
            root.forEachIndexed { index, el ->
                when (el) {
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        parsedProcess.add(
                            com.unuslumen.app.domain.model.ThoughtStep(
                                type = "ANALYZE",
                                input = el.jsonPrimitive.content,
                                params = emptyMap()
                            )
                        )
                    }
                    is kotlinx.serialization.json.JsonObject -> {
                        val o = el.jsonObject
                        parsedProcess.add(
                            com.unuslumen.app.domain.model.ThoughtStep(
                                type = o["type"]?.jsonPrimitive?.content ?: "ANALYZE",
                                input = o["input"]?.jsonPrimitive?.content ?: "",
                                params = o["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                            )
                        )
                    }
                    else -> throw IllegalArgumentException(
                        "element $index is ${el::class.simpleName}; expected a string or object {type, input, params}"
                    )
                }
            }
        } catch (e: Throwable) {
            return ToolExecutionResult.error(
                "Invalid thought process JSON: ${e.message}"

            )
        }

        val req = com.unuslumen.app.domain.model.CreateThoughtCycleRequest(name, displayName, description, type, triggerConfig, parsedProcess, output, outputConfig)
        val validation = thoughtCycleRepository.validateCycle(req)
        if (!validation.valid) return ToolExecutionResult.error("Invalid: ${validation.errors.joinToString("; ")}")
        val cycle = thoughtCycleRepository.createCycle(req)
        val r = CreateThoughtCycleResult(cycle.id, cycle.name, cycle.displayName, cycle.triggerType.name, cycle.outputType.name, cycle.enabled, "Created.")
        return ToolExecutionResult.success(r, json.encodeToString(CreateThoughtCycleResult.serializer(), r))
    }
}