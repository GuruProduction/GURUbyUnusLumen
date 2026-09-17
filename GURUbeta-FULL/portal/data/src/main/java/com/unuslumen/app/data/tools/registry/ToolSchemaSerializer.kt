package com.unuslumen.app.data.tools.registry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ToolSchemaSerializer — Turns ToolDefinition data classes into the
 * OpenAI-compatible JSON schema the LLM sees.
 *
 * Replaces UnusLumenToolSchemaGenerator which wrapped koog's
 * OllamaToolDescriptorSchemaGenerator. Works from pure ToolDefinition
 * data, no koog dependency, no reflection.
 */
class ToolSchemaSerializer {

    fun serialize(definition: ToolDefinition): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(definition.name))
                put("description", JsonPrimitive("Tool name: ${definition.name}. ${definition.description}"))
                put("parameters", buildParametersSchema(definition.parameters))
            })
        }
    }

    fun serializeAll(definitions: List<ToolDefinition>): JsonArray {
        return buildJsonArray {
            definitions.forEach { add(serialize(it)) }
        }
    }

    /**
     * Serialize only the parameters object for a tool — {type:object, properties:{...}, required:[...]}.
     *
     * Added for Fix 1: the Ollama wire format wants function.parameters to BE that flat
     * parameters object. Using serialize()'s full envelope there would nest the schema
     * two levels deep, leaving the model with unusable parameter metadata. Callers use
     * serialize() when they want the whole function object, serializeParameters() when
     * they are filling function.parameters directly.
     */
    fun serializeParameters(definition: ToolDefinition): JsonObject {
        return buildParametersSchema(definition.parameters)
    }

    private fun buildParametersSchema(params: List<ToolParameter>): JsonObject {
        val properties = buildJsonObject {
            for (param in params) {
                put(param.name, buildParamType(param))
            }
        }

        val requiredList = params.filter { it.required }.map { it.name }

        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", properties)
            if (requiredList.isNotEmpty()) {
                put("required", buildJsonArray {
                    requiredList.forEach { add(JsonPrimitive(it)) }
                })
            }
        }
    }

    private fun buildParamType(param: ToolParameter): JsonObject {
        return buildJsonObject {
            when (param.type) {
                ToolParameterType.String,
                ToolParameterType.Code,
                ToolParameterType.ShellCommand,
                ToolParameterType.Script -> {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(param.description))
                    param.enumValues?.let { enumVals ->
                        put("enum", buildJsonArray { enumVals.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                ToolParameterType.Integer -> {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive(param.description))
                }
                ToolParameterType.Long -> {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive(param.description))
                }
                ToolParameterType.Boolean -> {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive(param.description))
                }
                ToolParameterType.Float -> {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive(param.description))
                }
                ToolParameterType.Enum -> {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(param.description))
                    param.enumValues?.let { enumVals ->
                        put("enum", buildJsonArray { enumVals.forEach { add(JsonPrimitive(it)) } })
                    }
                }
            }
        }
    }
}