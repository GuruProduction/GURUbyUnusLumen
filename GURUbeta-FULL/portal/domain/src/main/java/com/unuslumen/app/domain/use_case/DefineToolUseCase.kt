package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.DefineToolRequest
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.repository.GuruToolRepository
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Factory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Define a new Guru tool.
 */
@Factory
class DefineToolUseCase(
    private val guruToolRepository: GuruToolRepository
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        name: String,
        displayName: String,
        description: String,
        parametersJson: String,
        implementationJson: String,
        rationale: String? = null
    ): Result<GuruDefinedTool> = runCatching {
        // Validate name format
        val sanitizedName = name.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("^_+|_+$"), "")
        
        if (sanitizedName.isBlank()) {
            throw IllegalArgumentException("Tool name cannot be empty")
        }
        
        // Check if name is available
        if (!guruToolRepository.isNameAvailable(sanitizedName)) {
            throw IllegalArgumentException("Tool name '$sanitizedName' is already taken")
        }
        
        // Parse parameters JSON
        val parameters = kotlinx.serialization.json.Json.parseToJsonElement(parametersJson)
        
        // Parse implementation JSON
        val implementation = parseImplementation(implementationJson)
        
        val request = DefineToolRequest(
            name = sanitizedName,
            displayName = displayName,
            description = description,
            parameters = parameters,
            implementation = implementation,
            rationale = rationale
        )
        
        // Validate
        val validation = guruToolRepository.validateToolDefinition(request)
        if (!validation.valid) {
            throw IllegalArgumentException(validation.errors.joinToString("; "))
        }
        
        guruToolRepository.defineTool(request)
    }
    
    private fun parseImplementation(json: String): com.unuslumen.app.domain.model.ToolImplementation {
        // Parse the implementation JSON into the appropriate type
        val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val obj = jsonElement.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        
        return when (type) {
            "composition" -> {
                val steps = obj["steps"]?.jsonArray?.map { stepElement ->
                    val stepObj = stepElement.jsonObject
                    com.unuslumen.app.domain.model.ToolStep(
                        tool = stepObj["tool"]?.jsonPrimitive?.content ?: "",
                        params = stepObj["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                        output = stepObj["output"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()
                com.unuslumen.app.domain.model.ToolImplementation.Composition(steps)
            }
            "shell" -> {
                com.unuslumen.app.domain.model.ToolImplementation.ShellCommand(
                    command = obj["command"]?.jsonPrimitive?.content ?: "",
                    params = obj["params"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                )
            }
            "webhook" -> {
                com.unuslumen.app.domain.model.ToolImplementation.Webhook(
                    url = obj["url"]?.jsonPrimitive?.content ?: "",
                    method = obj["method"]?.jsonPrimitive?.content ?: "POST",
                    headers = obj["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                )
            }
            else -> throw IllegalArgumentException("Unknown implementation type: $type")
        }
    }
}