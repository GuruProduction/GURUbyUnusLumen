// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.automation

import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.database.dao.GuruAutomationDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.unuslumen.app.database.entity.GuruAutomationEntity
import com.unuslumen.app.domain.model.AutomationStepTrace
import com.unuslumen.app.domain.model.AutomationRunStatus
import com.unuslumen.app.domain.model.AutomationRunTrigger
import com.unuslumen.app.domain.model.CreateAutomationRequest
import com.unuslumen.app.domain.model.GuruAutomation
import com.unuslumen.app.domain.model.AutomationExecutionResult
import com.unuslumen.app.domain.model.AutomationStep
import com.unuslumen.app.domain.model.AutomationTrigger
import com.unuslumen.app.domain.model.AutomationSummary
import com.unuslumen.app.domain.model.StepResult
import com.unuslumen.app.domain.repository.AutomationRepository
import com.unuslumen.app.domain.repository.AutomationRunRepository
import com.unuslumen.app.domain.repository.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [AutomationRepository::class])
class AutomationRepositoryImpl(
    private val automationDao: GuruAutomationDao,
    private val dynamicToolExecutor: com.unuslumen.app.data.gurutools.DynamicToolExecutor
) : AutomationRepository, KoinComponent {

    private val toolRegistryHolder: ToolRegistryHolder by inject()
    private val toolRegistry get() = toolRegistryHolder.toolRegistry

    // Lazy Koin inject: the run repository binds in AiDataModule and has no
    // dependency on this class, so resolution at first use breaks nothing at
    // construction time and keeps the single-constructor Koin binding stable.
    private val automationRunRepository: AutomationRunRepository by inject()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createAutomation(request: CreateAutomationRequest): GuruAutomation = withContext(Dispatchers.IO) {
        val automation = GuruAutomationEntity(
            id = Uuid.random().toString(),
            name = request.name,
            displayName = request.displayName,
            description = request.description,
            trigger = request.trigger.name,
            triggerConfig = request.triggerConfig ?: "{}",
            steps = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AutomationStep.serializer()), request.steps),
            createdAt = System.currentTimeMillis(),
            enabled = true
        )
        automationDao.insertAutomation(automation)
        automation.toDomain()
    }

    override suspend fun getAllAutomations(): List<GuruAutomation> = withContext(Dispatchers.IO) {
        automationDao.getAllAutomations().map { it.toDomain() }
    }

    override fun getAllAutomationsFlow(): Flow<List<GuruAutomation>> =
        automationDao.getAllAutomationsFlow().map { automations -> automations.map { it.toDomain() } }

    override suspend fun getEnabledAutomations(): List<GuruAutomation> = withContext(Dispatchers.IO) {
        automationDao.getEnabledAutomations().map { it.toDomain() }
    }

    override fun getEnabledAutomationsFlow(): Flow<List<GuruAutomation>> =
        automationDao.getEnabledAutomationsFlow().map { automations -> automations.map { it.toDomain() } }

    override suspend fun getAutomationsByTrigger(trigger: AutomationTrigger): List<GuruAutomation> = withContext(Dispatchers.IO) {
        automationDao.getAutomationsByTrigger(trigger.name).map { it.toDomain() }
    }

    override suspend fun getAutomation(id: String): GuruAutomation? = withContext(Dispatchers.IO) {
        automationDao.getAutomationById(id)?.toDomain()
    }

    override suspend fun getAutomationByName(name: String): GuruAutomation? = withContext(Dispatchers.IO) {
        automationDao.getAutomationByName(name)?.toDomain()
    }

    override suspend fun updateAutomation(id: String, request: CreateAutomationRequest): GuruAutomation = withContext(Dispatchers.IO) {
        val existing = automationDao.getAutomationById(id) ?: throw IllegalArgumentException("Automation not found: $id")
        val updated = existing.copy(
            displayName = request.displayName,
            description = request.description,
            trigger = request.trigger.name,
            triggerConfig = request.triggerConfig ?: "{}",
            steps = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AutomationStep.serializer()), request.steps)
        )
        automationDao.updateAutomation(updated)
        updated.toDomain()
    }

    override suspend fun enableAutomation(id: String): GuruAutomation = withContext(Dispatchers.IO) {
        automationDao.setEnabled(id, true)
        automationDao.getAutomationById(id)!!.toDomain()
    }

    override suspend fun disableAutomation(id: String): GuruAutomation = withContext(Dispatchers.IO) {
        automationDao.setEnabled(id, false)
        automationDao.getAutomationById(id)!!.toDomain()
    }

    override suspend fun deleteAutomation(id: String) = withContext(Dispatchers.IO) {
        automationDao.deleteAutomation(id)
    }

    override suspend fun executeAutomation(id: String, params: Map<String, Any?>): AutomationExecutionResult {
        val automation = getAutomation(id) ?: throw IllegalArgumentException("Automation not found: $id")

        if (!automation.enabled) {
            throw IllegalStateException("Automation '${automation.name}' is disabled")
        }

        return executeAutomationTraced(automation, params, AutomationRunTrigger.MANUAL)
    }

    override suspend fun executeAutomationByName(name: String, params: Map<String, Any?>): AutomationExecutionResult {
        val automation = getAutomationByName(name) ?: throw IllegalArgumentException("Automation not found: $name")
        return executeAutomation(automation.id, params)
    }

    override suspend fun executeAutomationByNameAsJob(name: String, params: Map<String, Any?>): AutomationExecutionResult {
        val automation = getAutomationByName(name) ?: throw IllegalArgumentException("Automation not found: $name")
        if (!automation.enabled) {
            throw IllegalStateException("Automation '${automation.name}' is disabled")
        }
        return executeAutomationTraced(automation, params, AutomationRunTrigger.JOB)
    }

    override suspend fun executeAutomationByNameAsHook(name: String, params: Map<String, Any?>): AutomationExecutionResult {
        val automation = getAutomationByName(name) ?: throw IllegalArgumentException("Automation not found: $name")
        if (!automation.enabled) {
            throw IllegalStateException("Automation '${automation.name}' is disabled")
        }
        return executeAutomationTraced(automation, params, AutomationRunTrigger.HOOK)
    }

    override suspend fun executeAutomationAsJob(id: String, params: Map<String, Any?>): AutomationExecutionResult {
        val automation = getAutomation(id) ?: throw IllegalArgumentException("Automation not found: $id")
        if (!automation.enabled) {
            throw IllegalStateException("Automation '${automation.name}' is disabled")
        }
        return executeAutomationTraced(automation, params, AutomationRunTrigger.JOB)
    }

    override suspend fun executeAutomationAsHook(id: String, params: Map<String, Any?>): AutomationExecutionResult {
        val automation = getAutomation(id) ?: throw IllegalArgumentException("Automation not found: $id")
        if (!automation.enabled) {
            throw IllegalStateException("Automation '${automation.name}' is disabled")
        }
        return executeAutomationTraced(automation, params, AutomationRunTrigger.HOOK)
    }

    /**
     * The one execution path. Manual, job and hook runs all flow through
     * here, so the trace hook covers every automation the system fires.
     * Steps land on the run trace as they execute; the Observatory watches
     * the row rewrite live.
     */
    private suspend fun executeAutomationTraced(
        automation: GuruAutomation,
        params: Map<String, Any?>,
        triggerPath: AutomationRunTrigger
    ): AutomationExecutionResult {
        val runId = try {
            automationRunRepository.startRun(automation.id, automation.name, triggerPath)
        } catch (e: Exception) {
            null // Tracing must never break execution
        }

        val startTime = System.currentTimeMillis()
        val results = mutableListOf<StepResult>()
        val context = mutableMapOf<String, Any?>("params" to params)

        for ((index, step) in automation.steps.withIndex()) {
            val stepStart = System.currentTimeMillis()
            try {
                val resolvedParams = resolveStepParams(step, context)
                val result = executeStep(step, resolvedParams)
                context[step.output] = result
                results.add(StepResult(
                    step = index,
                    tool = step.tool,
                    success = true,
                    result = result.toString()
                ))
                if (runId != null) {
                    try {
                        automationRunRepository.traceStep(runId, AutomationStepTrace(
                            index = index,
                            tool = step.tool,
                            success = true,
                            paramsSummary = step.params.keys.joinToString(", "),
                            resultSummary = result?.toString()?.take(280),
                            durationMs = System.currentTimeMillis() - stepStart
                        ))
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                results.add(StepResult(
                    step = index,
                    tool = step.tool,
                    success = false,
                    result = null,
                    error = e.message
                ))
                if (runId != null) {
                    try {
                        automationRunRepository.traceStep(runId, AutomationStepTrace(
                            index = index,
                            tool = step.tool,
                            success = false,
                            paramsSummary = step.params.keys.joinToString(", "),
                            error = e.message,
                            durationMs = System.currentTimeMillis() - stepStart
                        ))
                    } catch (_: Exception) {}
                }
                // Doctrine: a failing step halts the chain — sequence order is
                // part of the automation's meaning, and later steps typically
                // depend on earlier outputs. Intended policy, not a bug.
                break
            }
        }

        val executionTime = System.currentTimeMillis() - startTime

        // Record execution
        recordExecution(automation.id)

        // Finalise the run trace. Doctrine vocabulary only: a run with a
        // failed step is NEEDS_ATTENTION — an honest ask, never "failed".
        if (runId != null) {
            try {
                val status = if (results.all { it.success }) AutomationRunStatus.COMPLETED else AutomationRunStatus.NEEDS_ATTENTION
                automationRunRepository.completeRun(runId, status, executionTime)
            } catch (_: Exception) {}
        }

        return AutomationExecutionResult(
            success = results.all { it.success },
            results = results,
            executionTimeMs = executionTime
        )
    }

    override suspend fun recordExecution(id: String) = withContext(Dispatchers.IO) {
        automationDao.recordRun(id, System.currentTimeMillis())
    }

    override suspend fun getSummary(): AutomationSummary = withContext(Dispatchers.IO) {
        val all = automationDao.getAllAutomations()
        AutomationSummary(
            totalAutomations = all.size,
            enabledAutomations = all.count { it.enabled },
            manualAutomations = all.count { it.trigger == AutomationTrigger.MANUAL.name },
            scheduledAutomations = all.count { it.trigger == AutomationTrigger.SCHEDULED.name },
            eventAutomations = all.count { it.trigger == AutomationTrigger.EVENT.name },
            totalRuns = all.sumOf { it.runCount }
        )
    }

    override suspend fun validateAutomation(request: CreateAutomationRequest): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Check name format
        if (request.name.isBlank()) {
            errors.add("Automation name cannot be empty")
        }
        if (!request.name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            errors.add("Automation name must start with lowercase letter and contain only lowercase letters, numbers, and underscores")
        }

        // Check steps
        if (request.steps.isEmpty()) {
            errors.add("Automation must have at least one step")
        }

        // Validate each step references an existing tool
        for (step in request.steps) {
            val existingTool = toolRegistry.tools.find { it.descriptor.name == step.tool }
            if (existingTool == null) {
                errors.add("Step references unknown tool: ${step.tool}")
            }
        }

        // Validate trigger config for scheduled automations
        if (request.trigger == AutomationTrigger.SCHEDULED && request.triggerConfig.isNullOrBlank()) {
            warnings.add("Scheduled automation should have a trigger config (cron expression or interval)")
        }

        // Validate trigger config for event automations
        if (request.trigger == AutomationTrigger.EVENT && request.triggerConfig.isNullOrBlank()) {
            warnings.add("Event automation should have a trigger config (event type)")
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    override suspend fun isNameAvailable(name: String): Boolean = withContext(Dispatchers.IO) {
        automationDao.getAutomationByName(name) == null
    }

    // Helper methods

    private fun resolveStepParams(step: AutomationStep, context: Map<String, Any?>): Map<String, Any?> {
        return step.params.mapValues { (_, value) ->
            resolveVariables(value, context)
        }
    }

    private fun resolveVariables(template: String, context: Map<String, Any?>): Any? {
        val pattern = Regex("\\$\\{([^}]+)\\}")
        val matches = pattern.findAll(template).toList()

        if (matches.isEmpty()) {
            return template
        }

        if (matches.size == 1 && matches[0].value == template) {
            val varPath = matches[0].groupValues[1]
            return resolveVariablePath(varPath, context)
        }

        var result = template
        for (match in matches) {
            val varPath = match.groupValues[1]
            val value = resolveVariablePath(varPath, context)
            result = result.replace(match.value, value?.toString() ?: "")
        }
        return result
    }

    private fun resolveVariablePath(path: String, context: Map<String, Any?>): Any? {
        val parts = path.split(".")
        var current: Any? = context

        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current[part]
                else -> null
            }
            if (current == null) return null
        }

        return current
    }

    /**
     * Required parameters must be present AND coercible to their declared
     * type. Absent or uncoercible values raise here, with the offending
     * param named — bad arguments never vanish into a payload the trace
     * reads as success. Wrong-type values previously dropped silently at
     * parse; the honest error now surfaces in the step trace.
     */
    private fun validateRequiredParams(descriptor: com.unuslumen.app.data.tools.registry.ToolDefinition, params: Map<String, Any?>) {
        val problems = mutableListOf<String>()
        for (param in descriptor.parameters.whereRequired()) {
            val value = params[param.name]
            when {
                !params.containsKey(param.name) || value == null ->
                    problems.add("'${param.name}' is required")
                value.toString().isNullOrBlank() && param.type == ToolParameterType.String ->
                    problems.add("'${param.name}' is required and was empty")
                !valueCoercible(value, param.type) ->
                    problems.add("'${param.name}' was '${value}' which cannot be read as ${param.type}")
            }
        }
        if (problems.isNotEmpty()) {
            throw MissingParamException("${descriptor.name}: ${problems.joinToString("; ")}")
        }
    }

    private fun List<com.unuslumen.app.data.tools.registry.ToolParameter>.whereRequired() = filter { it.required }

    private fun valueCoercible(value: Any?, type: ToolParameterType): Boolean = when (type) {
        ToolParameterType.String, ToolParameterType.Code, ToolParameterType.ShellCommand, ToolParameterType.Script, ToolParameterType.Enum -> value is String
        ToolParameterType.Integer, ToolParameterType.Long -> (value as? Number)?.toIntOrNullSafe() != null || (value as? String)?.toIntOrNull() != null || (value as? String)?.toLongOrNull() != null
        ToolParameterType.Boolean -> value is Boolean || (value == "true" || value == "false")
        ToolParameterType.Float -> (value as? Number) != null || (value as? String)?.toDoubleOrNull() != null
    }

    private fun Number.toIntOrNullSafe(): Int? =
        if (this is Int) this else if (this is Long && this in Int.MIN_VALUE..Int.MAX_VALUE) this.toInt() else null

    class ToolPayloadErrorException(message: String) : RuntimeException(message)
    class MissingParamException(message: String) : RuntimeException(message)

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeStep(
        step: AutomationStep,
        params: Map<String, Any?>
    ): Any? {
        val tool = toolRegistry.tools.find { it.descriptor.name == step.tool }
            ?: throw IllegalArgumentException("Tool not found: ${step.tool}")

        validateRequiredParams(tool.definition, params)

        val args = tool.decodeArgs(paramsToJsonElement(params.filterValues { it != null }))
        val result = tool.execute(args)

        // A tool that reports an unsuccessful result (bad params, refused
        // action, payload error) is a step failure here: the executor must
        // never read payload error as step success. Raise, so the trace
        // catches, the chain stops, and the honest error surfaces in trace.
        if (!result.success) {
            throw ToolPayloadErrorException(result.error ?: "Tool ${step.tool} reported an error")
        }
        return tool.encodeResult(result)
    }

    private fun paramsToJsonElement(params: Map<String, Any?>): kotlinx.serialization.json.JsonObject {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((key, value) in params) {
            map[key] = when (value) {
                null -> kotlinx.serialization.json.JsonNull
                is String -> kotlinx.serialization.json.JsonPrimitive(value)
                is Number -> kotlinx.serialization.json.JsonPrimitive(value)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
                is Map<*, *> -> paramsToJsonElement(value as Map<String, Any?>)
                is List<*> -> kotlinx.serialization.json.JsonArray(
                    value.map { v ->
                        when (v) {
                            is String -> kotlinx.serialization.json.JsonPrimitive(v as String)
                            is Number -> kotlinx.serialization.json.JsonPrimitive(v as Number)
                            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v as Boolean)
                            else -> kotlinx.serialization.json.JsonPrimitive(v?.toString() ?: "")
                        }
                    }
                )
                else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
            }
        }
        return kotlinx.serialization.json.JsonObject(map)
    }

    private fun GuruAutomationEntity.toDomain(): GuruAutomation {
        val steps = try {
            json.decodeFromString<List<AutomationStep>>(steps)
        } catch (e: Exception) {
            emptyList()
        }

        return GuruAutomation(
            id = id,
            name = name,
            displayName = displayName,
            description = description,
            trigger = AutomationTrigger.valueOf(trigger),
            triggerConfig = triggerConfig,
            steps = steps,
            createdAt = createdAt,
            lastRunAt = lastRunAt,
            runCount = runCount,
            enabled = enabled
        )
    }
}
