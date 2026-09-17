package com.unuslumen.app.data.gurutools

import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.database.dao.GuruDefinedToolDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.unuslumen.app.database.entity.GuruDefinedToolEntity
import com.unuslumen.app.domain.model.DefineToolRequest
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.GuruToolsSummary
import com.unuslumen.app.domain.model.ToolExecutionResult
import com.unuslumen.app.domain.model.ToolImplementation
import com.unuslumen.app.domain.model.ToolStatus
import com.unuslumen.app.domain.model.ToolStep
import com.unuslumen.app.domain.repository.GuruToolRepository
import com.unuslumen.app.domain.repository.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [GuruToolRepository::class])
class GuruToolRepositoryImpl(
    private val toolDao: GuruDefinedToolDao,
    private val dynamicToolExecutor: DynamicToolExecutor
) : GuruToolRepository, KoinComponent {

    private val toolRegistryHolder: ToolRegistryHolder by inject()
    private val toolRegistry get() = toolRegistryHolder.toolRegistry

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun defineTool(request: DefineToolRequest): GuruDefinedTool = withContext(Dispatchers.IO) {
        val tool = GuruDefinedToolEntity(
            id = Uuid.random().toString(),
            name = request.name,
            displayName = request.displayName,
            description = request.description,
            parameters = json.encodeToString(JsonElement.serializer(), request.parameters),
            implementation = serializeImplementation(request.implementation),
            status = ToolStatus.PENDING.name,
            createdAt = System.currentTimeMillis(),
            createdBy = "guru",
            rationale = request.rationale
        )
        toolDao.insertTool(tool)
        tool.toDomain()
    }

    override suspend fun getAllTools(): List<GuruDefinedTool> = withContext(Dispatchers.IO) {
        toolDao.getAllTools().map { it.toDomain() }
    }

    override fun getAllToolsFlow(): Flow<List<GuruDefinedTool>> =
        toolDao.getAllToolsFlow().map { tools -> tools.map { it.toDomain() } }

    override suspend fun getToolsByStatus(status: ToolStatus): List<GuruDefinedTool> = withContext(Dispatchers.IO) {
        toolDao.getToolsByStatus(status.name).map { it.toDomain() }
    }

    override suspend fun getApprovedTools(): List<GuruDefinedTool> = withContext(Dispatchers.IO) {
        toolDao.getApprovedTools().map { it.toDomain() }
    }

    override fun getApprovedToolsFlow(): Flow<List<GuruDefinedTool>> =
        toolDao.getApprovedToolsFlow().map { tools -> tools.map { it.toDomain() } }

    override suspend fun getPendingTools(): List<GuruDefinedTool> = withContext(Dispatchers.IO) {
        toolDao.getPendingTools().map { it.toDomain() }
    }

    override suspend fun getTool(id: String): GuruDefinedTool? = withContext(Dispatchers.IO) {
        toolDao.getToolById(id)?.toDomain()
    }

    override suspend fun getToolByName(name: String): GuruDefinedTool? = withContext(Dispatchers.IO) {
        toolDao.getToolByName(name)?.toDomain()
    }

    override suspend fun approveTool(id: String): GuruDefinedTool = withContext(Dispatchers.IO) {
        toolDao.approveTool(id, System.currentTimeMillis())
        toolDao.getToolById(id)!!.toDomain()
    }

    override suspend fun disableTool(id: String): GuruDefinedTool = withContext(Dispatchers.IO) {
        toolDao.disableTool(id)
        toolDao.getToolById(id)!!.toDomain()
    }

    override suspend fun enableTool(id: String): GuruDefinedTool = withContext(Dispatchers.IO) {
        toolDao.setToolPending(id)
        toolDao.getToolById(id)!!.toDomain()
    }

    override suspend fun deleteTool(id: String) = withContext(Dispatchers.IO) {
        toolDao.deleteTool(id)
    }

    override suspend fun updateTool(id: String, request: DefineToolRequest): GuruDefinedTool = withContext(Dispatchers.IO) {
        val existing = toolDao.getToolById(id) ?: throw IllegalArgumentException("Tool not found: $id")
        val updated = existing.copy(
            displayName = request.displayName,
            description = request.description,
            parameters = json.encodeToString(JsonElement.serializer(), request.parameters),
            implementation = serializeImplementation(request.implementation),
            status = ToolStatus.PENDING.name // Reset to pending after update
        )
        toolDao.updateTool(updated)
        updated.toDomain()
    }

    override suspend fun executeTool(id: String, params: Map<String, Any?>): ToolExecutionResult {
        val tool = getTool(id) ?: throw IllegalArgumentException("Tool not found: $id")
        
        if (tool.status != ToolStatus.APPROVED) {
            throw IllegalStateException("Tool '${tool.name}' is not approved for execution")
        }
        
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = dynamicToolExecutor.execute(tool, params)
            val executionTime = System.currentTimeMillis() - startTime
            
            // Record usage
            recordUsage(id)
            
            ToolExecutionResult(
                success = true,
                result = result,
                executionTimeMs = executionTime
            )
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            ToolExecutionResult(
                success = false,
                result = null,
                error = e.message ?: "Unknown error",
                executionTimeMs = executionTime
            )
        }
    }

    override suspend fun executeToolByName(name: String, params: Map<String, Any?>): ToolExecutionResult {
        val tool = getToolByName(name) ?: throw IllegalArgumentException("Tool not found: $name")
        return executeTool(tool.id, params)
    }

    override suspend fun recordUsage(id: String) = withContext(Dispatchers.IO) {
        toolDao.recordToolUsage(id, System.currentTimeMillis())
    }

    override suspend fun getSummary(): GuruToolsSummary = withContext(Dispatchers.IO) {
        val all = toolDao.getAllTools()
        GuruToolsSummary(
            totalTools = all.size,
            approvedTools = all.count { it.status == ToolStatus.APPROVED.name },
            pendingTools = all.count { it.status == ToolStatus.PENDING.name },
            disabledTools = all.count { it.status == ToolStatus.DISABLED.name },
            totalUses = all.sumOf { it.useCount }
        )
    }

    override suspend fun validateToolDefinition(request: DefineToolRequest): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Check name format
        if (request.name.isBlank()) {
            errors.add("Tool name cannot be empty")
        }
        if (!request.name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            errors.add("Tool name must start with lowercase letter and contain only lowercase letters, numbers, and underscores")
        }
        
        // Check for reserved names
        val reservedNames = listOf("create", "update", "delete", "get", "list", "search")
        if (request.name in reservedNames) {
            errors.add("Tool name '$${request.name}' is reserved")
        }
        
        // Validate implementation
        when (val impl = request.implementation) {
            is ToolImplementation.Composition -> {
                if (impl.steps.isEmpty()) {
                    errors.add("Composition must have at least one step")
                }
                // Validate each step references an existing tool
                for (step in impl.steps) {
                    val existingTool = toolRegistry.tools.find { it.descriptor.name == step.tool }
                    if (existingTool == null) {
                        // Check if it's a Guru-defined tool
                        val guruTool = getToolByName(step.tool)
                        if (guruTool == null || guruTool.status != ToolStatus.APPROVED) {
                            errors.add("Step references unknown tool: ${step.tool}")
                        }
                    }
                }
            }
            is ToolImplementation.ShellCommand -> {
                if (impl.command.isBlank()) {
                    errors.add("Shell command cannot be empty")
                }
                warnings.add("Shell commands have elevated permissions - review carefully before approving")
            }
            is ToolImplementation.Webhook -> {
                if (impl.url.isBlank()) {
                    errors.add("Webhook URL cannot be empty")
                }
                if (!impl.url.startsWith("https://") && !impl.url.startsWith("http://")) {
                    warnings.add("Webhook URL should use HTTPS for security")
                }
            }
        }
        
        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    override suspend fun isNameAvailable(name: String): Boolean = withContext(Dispatchers.IO) {
        // Check against existing Guru tools
        val existingGuruTool = toolDao.getToolByName(name)
        if (existingGuruTool != null) return@withContext false
        
        // Check against built-in tools
        val existingTool = toolRegistry.tools.find { it.descriptor.name == name }
        if (existingTool != null) return@withContext false
        
        true
    }

    // Helper methods

    private fun serializeImplementation(impl: ToolImplementation): String {
        return when (impl) {
            is ToolImplementation.Composition -> {
                json.encodeToString(
                    mapOf(
                        "type" to "composition",
                        "steps" to impl.steps.map { step ->
                            mapOf(
                                "tool" to step.tool,
                                "params" to step.params,
                                "output" to step.output
                            )
                        }
                    )
                )
            }
            is ToolImplementation.ShellCommand -> {
                json.encodeToString(
                    mapOf(
                        "type" to "shell",
                        "command" to impl.command,
                        "params" to impl.params
                    )
                )
            }
            is ToolImplementation.Webhook -> {
                json.encodeToString(
                    mapOf(
                        "type" to "webhook",
                        "url" to impl.url,
                        "method" to impl.method,
                        "headers" to impl.headers
                    )
                )
            }
        }
    }

    private fun deserializeImplementation(jsonString: String): ToolImplementation {
        val obj = json.parseToJsonElement(jsonString).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        
        return when (type) {
            "composition" -> {
                val steps = obj["steps"]?.jsonArray?.map { stepElement ->
                    val stepObj = stepElement.jsonObject
                    ToolStep(
                        tool = stepObj["tool"]?.jsonPrimitive?.content ?: "",
                        params = stepObj["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                        output = stepObj["output"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()
                ToolImplementation.Composition(steps)
            }
            "shell" -> {
                ToolImplementation.ShellCommand(
                    command = obj["command"]?.jsonPrimitive?.content ?: "",
                    params = obj["params"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                )
            }
            "webhook" -> {
                ToolImplementation.Webhook(
                    url = obj["url"]?.jsonPrimitive?.content ?: "",
                    method = obj["method"]?.jsonPrimitive?.content ?: "POST",
                    headers = obj["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                )
            }
            else -> throw IllegalArgumentException("Unknown implementation type: $type")
        }
    }

    private fun GuruDefinedToolEntity.toDomain(): GuruDefinedTool {
        return GuruDefinedTool(
            id = id,
            name = name,
            displayName = displayName,
            description = description,
            parameters = json.parseToJsonElement(parameters),
            implementation = deserializeImplementation(implementation),
            status = ToolStatus.valueOf(status),
            createdAt = createdAt,
            approvedAt = approvedAt,
            createdBy = createdBy,
            lastUsedAt = lastUsedAt,
            useCount = useCount,
            rationale = rationale
        )
    }
}