package com.unuslumen.app.data.hooks

import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.database.dao.GuruHookDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.unuslumen.app.database.entity.GuruHookEntity
import com.unuslumen.app.domain.model.*
import com.unuslumen.app.domain.repository.HookRepository
import com.unuslumen.app.domain.repository.ValidationResult
import com.unuslumen.app.domain.repository.AutomationRepository
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
@Single(binds = [HookRepository::class])
class HookRepositoryImpl(
    private val hookDao: GuruHookDao,
    private val automationRepository: AutomationRepository
) : HookRepository, KoinComponent {

    private val toolRegistryHolder: ToolRegistryHolder by inject()
    private val toolRegistry get() = toolRegistryHolder.toolRegistry

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createHook(request: CreateHookRequest): GuruHook = withContext(Dispatchers.IO) {
        val hook = GuruHookEntity(
            id = Uuid.random().toString(),
            name = request.name,
            displayName = request.displayName,
            description = request.description,
            eventType = request.eventType.name,
            triggerTiming = request.triggerTiming.name,
            condition = request.condition?.let { json.encodeToString(HookCondition.serializer(), it) },
            action = json.encodeToString(HookAction.serializer(), request.action),
            priority = request.priority,
            enabled = true,
            createdAt = System.currentTimeMillis()
        )
        hookDao.insertHook(hook)
        hook.toDomain()
    }

    override suspend fun getAllHooks(): List<GuruHook> = withContext(Dispatchers.IO) {
        hookDao.getAllHooks().map { it.toDomain() }
    }

    override fun getAllHooksFlow(): Flow<List<GuruHook>> =
        hookDao.getAllHooksFlow().map { hooks -> hooks.map { it.toDomain() } }

    override suspend fun getEnabledHooks(): List<GuruHook> = withContext(Dispatchers.IO) {
        hookDao.getEnabledHooks().map { it.toDomain() }
    }

    override fun getEnabledHooksFlow(): Flow<List<GuruHook>> =
        hookDao.getEnabledHooksFlow().map { hooks -> hooks.map { it.toDomain() } }

    override suspend fun getHooksForEvent(eventType: HookEventType): List<GuruHook> = withContext(Dispatchers.IO) {
        hookDao.getHooksByEventType(eventType.name).map { it.toDomain() }
    }

    override suspend fun getHooksForEventAndTiming(
        eventType: HookEventType,
        timing: TriggerTiming
    ): List<GuruHook> = withContext(Dispatchers.IO) {
        hookDao.getHooksByEventTypeAndTiming(eventType.name, timing.name).map { it.toDomain() }
    }

    override suspend fun getHook(id: String): GuruHook? = withContext(Dispatchers.IO) {
        hookDao.getHookById(id)?.toDomain()
    }

    override suspend fun getHookByName(name: String): GuruHook? = withContext(Dispatchers.IO) {
        hookDao.getHookByName(name)?.toDomain()
    }

    override suspend fun updateHook(id: String, request: CreateHookRequest): GuruHook = withContext(Dispatchers.IO) {
        val existing = hookDao.getHookById(id) ?: throw IllegalArgumentException("Hook not found: $id")
        val updated = existing.copy(
            displayName = request.displayName,
            description = request.description,
            eventType = request.eventType.name,
            triggerTiming = request.triggerTiming.name,
            condition = request.condition?.let { json.encodeToString(HookCondition.serializer(), it) },
            action = json.encodeToString(HookAction.serializer(), request.action),
            priority = request.priority
        )
        hookDao.updateHook(updated)
        updated.toDomain()
    }

    override suspend fun enableHook(id: String): GuruHook = withContext(Dispatchers.IO) {
        hookDao.setEnabled(id, true)
        hookDao.getHookById(id)!!.toDomain()
    }

    override suspend fun disableHook(id: String): GuruHook = withContext(Dispatchers.IO) {
        hookDao.setEnabled(id, false)
        hookDao.getHookById(id)!!.toDomain()
    }

    override suspend fun deleteHook(id: String) = withContext(Dispatchers.IO) {
        hookDao.deleteHookById(id)
    }

    override suspend fun executeHooks(
        eventType: HookEventType,
        timing: TriggerTiming,
        eventData: Map<String, Any?>
    ): List<HookExecutionResult> {
        val hooks = getHooksForEventAndTiming(eventType, timing)
        val results = mutableListOf<HookExecutionResult>()

        for (hook in hooks) {
            if (!hook.enabled) continue

            // Check condition if present
            val condition = hook.condition
            if (condition != null && !evaluateCondition(condition, eventData)) {
                continue
            }

            val startTime = System.currentTimeMillis()
            try {
                val actionResult = executeAction(hook.action, eventData)
                val executionTime = System.currentTimeMillis() - startTime

                recordTrigger(hook.id)

                results.add(HookExecutionResult(
                    hookId = hook.id,
                    hookName = hook.name,
                    eventType = eventType.name,
                    triggeredAt = startTime,
                    success = true,
                    actionResult = actionResult,
                    error = null,
                    executionTimeMs = executionTime
                ))
            } catch (e: Exception) {
                val executionTime = System.currentTimeMillis() - startTime
                results.add(HookExecutionResult(
                    hookId = hook.id,
                    hookName = hook.name,
                    eventType = eventType.name,
                    triggeredAt = startTime,
                    success = false,
                    actionResult = null,
                    error = e.message,
                    executionTimeMs = executionTime
                ))
            }
        }

        return results
    }

    override suspend fun recordTrigger(id: String) = withContext(Dispatchers.IO) {
        hookDao.recordTrigger(id, System.currentTimeMillis())
    }

    override suspend fun getSummary(): HooksSummary = withContext(Dispatchers.IO) {
        val all = hookDao.getAllHooks()
        val byEventType = all.groupingBy { it.eventType }.eachCount()

        HooksSummary(
            totalHooks = all.size,
            enabledHooks = all.count { it.enabled },
            beforeHooks = all.count { it.triggerTiming == TriggerTiming.BEFORE.name },
            afterHooks = all.count { it.triggerTiming == TriggerTiming.AFTER.name },
            totalTriggers = all.sumOf { it.triggerCount },
            byEventType = byEventType
        )
    }

    override suspend fun validateHook(request: CreateHookRequest): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Validate name
        if (request.name.isBlank()) {
            errors.add("Hook name cannot be empty")
        }
        if (!request.name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            errors.add("Hook name must start with lowercase letter and contain only lowercase letters, numbers, and underscores")
        }

        // Validate action
        if (request.action.type !in listOf("skill", "tool", "notification")) {
            errors.add("Invalid action type: ${request.action.type}. Must be 'skill', 'tool', or 'notification'")
        }

        // Validate action target exists
        when (request.action.type) {
            "skill" -> {
                val skill = automationRepository.getAutomationByName(request.action.target)
                if (skill == null) {
                    warnings.add("Target skill '${request.action.target}' does not exist yet")
                }
            }
            "tool" -> {
                val tool = toolRegistry.tools.find { it.descriptor.name == request.action.target }
                if (tool == null) {
                    warnings.add("Target tool '${request.action.target}' does not exist")
                }
            }
        }

        // Validate condition if present
        val condition = request.condition
        if (condition != null) {
            if (condition.operator !in listOf("eq", "neq", "gt", "lt", "gte", "lte", "contains", "matches")) {
                errors.add("Invalid condition operator: ${condition.operator}")
            }
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    // Helper methods

    private fun evaluateCondition(condition: HookCondition, eventData: Map<String, Any?>): Boolean {
        val fieldValue = resolveFieldPath(condition.field, eventData) ?: return false
        val conditionValue = condition.value

        return when (condition.operator) {
            "eq" -> fieldValue.toString() == conditionValue
            "neq" -> fieldValue.toString() != conditionValue
            "gt" -> (fieldValue as? Number)?.toDouble()?.let { it > conditionValue.toDouble() } ?: false
            "lt" -> (fieldValue as? Number)?.toDouble()?.let { it < conditionValue.toDouble() } ?: false
            "gte" -> (fieldValue as? Number)?.toDouble()?.let { it >= conditionValue.toDouble() } ?: false
            "lte" -> (fieldValue as? Number)?.toDouble()?.let { it <= conditionValue.toDouble() } ?: false
            "contains" -> fieldValue.toString().contains(conditionValue, ignoreCase = true)
            "matches" -> fieldValue.toString().matches(Regex(conditionValue))
            else -> false
        }
    }

    private fun resolveFieldPath(path: String, data: Map<String, Any?>): Any? {
        val parts = path.split(".")
        var current: Any? = data

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
    private suspend fun executeAction(action: HookAction, eventData: Map<String, Any?>): String {
        return when (action.type) {
            "skill" -> {
                val params = action.params.mapValues { (_, value) ->
                    resolveVariables(value, eventData)
                }
                val result = automationRepository.executeAutomationByName(action.target, params)
                if (result.success) "Skill executed successfully" else "Skill execution failed"
            }
            "tool" -> {
                val tool = toolRegistry.tools.find { it.descriptor.name == action.target }
                    ?: throw IllegalArgumentException("Tool not found: ${action.target}")
                val args = tool.decodeArgs(
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        kotlinx.serialization.json.Json.encodeToString(
                            kotlinx.serialization.serializer<Map<String, Any?>>(),
                            action.params.mapValues { (_, value) -> resolveVariables(value, eventData) }.filterValues { it != null }
                        )
                    ).jsonObject
                )
                val result = tool.execute(args)
                tool.encodeResult(result).toString()
            }
            "notification" -> {
                "Notification sent: ${action.target}"
            }
            else -> throw IllegalArgumentException("Unknown action type: ${action.type}")
        }
    }

    private fun resolveVariables(template: String, context: Map<String, Any?>): Any? {
        val pattern = Regex("\\$\\{([^}]+)}")
        val matches = pattern.findAll(template).toList()

        if (matches.isEmpty()) {
            return template
        }

        if (matches.size == 1 && matches[0].value == template) {
            val varPath = matches[0].groupValues[1]
            return resolveFieldPath(varPath, context)
        }

        var result = template
        for (match in matches) {
            val varPath = match.groupValues[1]
            val value = resolveFieldPath(varPath, context)
            result = result.replace(match.value, value?.toString() ?: "")
        }
        return result
    }

    private fun GuruHookEntity.toDomain(): GuruHook {
        val condition = condition?.let {
            try {
                json.decodeFromString<HookCondition>(it)
            } catch (e: Exception) {
                null
            }
        }

        val action = try {
            json.decodeFromString<HookAction>(action)
        } catch (e: Exception) {
            HookAction(type = "notification", target = "unknown")
        }

        return GuruHook(
            id = id,
            name = name,
            displayName = displayName,
            description = description,
            eventType = HookEventType.valueOf(eventType),
            triggerTiming = TriggerTiming.valueOf(triggerTiming),
            condition = condition,
            action = action,
            priority = priority,
            enabled = enabled,
            createdAt = createdAt,
            lastTriggeredAt = lastTriggeredAt,
            triggerCount = triggerCount
        )
    }
}
