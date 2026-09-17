package com.unuslumen.app.data.di

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolRegistryHolder(registry: ToolRegistry) {
    val toolRegistry = Wrapper(registry)

    class Wrapper(private val registry: ToolRegistry) {
        val tools: List<ToolRef>
            get() = registry.getAllDefinitions().map { def ->
                ToolRef(def, registry.getExecutor(def.name)!!)
            }
    }

    class ToolRef(
        private val definition: ToolDefinition,
        private val executor: ToolExecutor
    ) {
        val descriptor = Descriptor(definition.name, definition.description)

        class Descriptor(val name: String, val description: String)

        fun decodeArgs(args: JsonObject): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            for ((key, value) in args) {
                map[key] = when {
                    value is JsonPrimitive && value.isString -> value.content
                    value is JsonPrimitive && value.content == "true" -> true
                    value is JsonPrimitive && value.content == "false" -> false
                    value is JsonPrimitive -> value.content.toDoubleOrNull() ?: value.content
                    else -> value.toString()
                }
            }
            return map
        }

        suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            return executor.execute(definition.name, args)
        }

        fun encodeResult(result: ToolExecutionResult): JsonElement {
            if (!result.success) {
                return buildJsonObject { put("error", result.error ?: "Unknown error") }
            }
            return try {
                kotlinx.serialization.json.Json.parseToJsonElement(result.rawJson)
            } catch (e: Exception) {
                JsonPrimitive(result.rawJson)
            }
        }
    }
}
