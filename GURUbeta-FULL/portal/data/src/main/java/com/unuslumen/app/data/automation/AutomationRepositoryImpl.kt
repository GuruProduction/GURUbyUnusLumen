package com.unuslumen.app.data.automation

import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.database.dao.GuruAutomationDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.unuslumen.app.database.entity.GuruAutomationEntity
import com.unuslumen.app.domain.model.CreateAutomationRequest
import com.unuslumen.app.domain.model.GuruAutomation
import com.unuslumen.app.domain.model.AutomationExecutionResult
import com.unuslumen.app.domain.model.AutomationStep
import com.unuslumen.app.domain.model.AutomationTrigger
import com.unuslumen.app.domain.model.AutomationSummary
import com.unuslumen.app.domain.model.StepResult
import com.unuslumen.app.domain.repository.AutomationRepository
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

        val startTime = System.currentTimeMillis()
        val results = mutableListOf<StepResult>()
        val context = mutableMapOf<String, Any?>("params" to params)

        for ((index, step) in automation.steps.withIndex()) {
            try {
                val resolvedParams = resolveStepParams(step, context)
                val result = executeStep(step.tool, resolvedParams)
                context[step.output] = result
                results.add(StepResult(
                    step = index,
                    tool = step.tool,
                    success = true,
                    result = result.toString()
                ))
            } catch (e: Exception) {
                results.add(StepResult(
                    step = index,
                    tool = step.tool,
                    success = false,
                    result = null,
                    error = e.message
                ))
                // Stop execution on failure
                break
            }
        }

        val executionTime = System.currentTimeMillis() - startTime

        // Record execution
        recordExecution(id)

        return AutomationExecutionResult(
            success = results.all { it.success },
            results = results,
            executionTimeMs = executionTime
        )
    }

    override suspend fun executeAutomationByName(name: String, params: Map<String, Any?>): AutomationExecutionResult {
        val automation = getAutomationByName(name) ?: throw IllegalArgumentException("Automation not found: $name")
        return executeAutomation(automation.id, params)
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
        val pattern = Regex("\\$\\{([^}]+)}")
        val matches = pattern.findAll(template).toList()

        if (matches.isEmpty()) {
            return template
        }

        if (matches.size == 1 && matches[0].value == template) {
            // Entire string is a single variable
            val varPath = matches[0].groupValues[1]
            return resolveVariablePath(varPath, context)
        }

        // Multiple variables or mixed content
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

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeStep(toolName: String, params: Map<String, Any?>): Any? {
        val tool = toolRegistry.tools.find { it.descriptor.name == toolName }
            ?: throw IllegalArgumentException("Tool not found: $toolName")

        val args = tool.decodeArgs(
            kotlinx.serialization.json.Json.parseToJsonElement(
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, Any?>>(),
                    params.filterValues { it != null }
                )
            ).jsonObject
        )
        val result = tool.execute(args)
        return tool.encodeResult(result)
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
