// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType as KoogParamType
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.domain.model.GuruDefinedTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GuruToolSchemaMapper — The only translator between the numen's stored
 * GURU-defined tool parameter schema and the two wire shapes the framework
 * needs it in:
 *
 *  - [ToolDefinition]        — registry-side, rides to `ToolSchemaSerializer`
 *                              and carries the Ollama-format wire (Lumen gateway,
 *                              local Ollama) where the numen executes.
 *  - [ToolDescriptor] koog   — rides to koog-brand BYO cloud executors, which
 *                              serialise tool schemas straight off required +
 *                              optional descriptor parameters.
 *
 * Accepted schema shapes for a stored tool's `parameters` JSON element:
 *  1. JSON Schema form:   {"type":"object","properties":{name:{type,description}},"required":[..]}
 *  2. Flat form:          {name:{type,description}, ...}      (no "properties" key)
 *  3. Map form:           {name:"string", ...}              (flat + primitive values)
 *
 * Required semantics (JSON Schema rules): when no `required` array exists every
 * top-level property is REQUIRED (safer default for executed tools); when a
 * `required` array exists it is honoured verbatim.
 *
 * Anything unresolvable degrades gracefully: one malformed tool can never
 * block every other GURU-created tool from being offered to the model.
 */
object GuruToolSchemaMapper {

    data class ParsedParam(
        val name: String,
        val type: ToolParameterType,
        val required: Boolean,
        val description: String,
        val enumValues: List<String>? = null
    )

    /** Best-effort parse of one tool's stored schema into ordered registry parameters. */
    fun parseParameters(parameters: JsonElement): List<ParsedParam> = runCatching {
        when (parameters) {
            is JsonObject -> {
                val hasProperties = parameters.containsKey("properties") &&
                    parameters["properties"] is JsonObject
                val requiredList = (parameters["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.toSet()
                if (hasProperties) {
                    val props = parameters["properties"]!!.jsonObject
                    props.map { (name, spec) ->
                        paramFromSpec(name, spec, requiredExplicit = requiredList?.contains(name))
                    }
                } else {
                    propertiesMap(parameters)
                }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** Flat map shapes where each entry is a param (object spec or a bare type name). */
    private fun propertiesMap(obj: JsonObject): List<ParsedParam> =
        obj.entries.filterNot { it.key == "type" && (it.value as? JsonPrimitive)?.contentOrNull == "object" }
            .map { (name, spec) -> paramFromSpec(name, spec, requiredExplicit = null) }

    /** The heart: spec → typed, described registry parameter.
    `requiredExplicit` = null means nothing specified → default required (safer). */
    private fun paramFromSpec(
        name: String,
        spec: JsonElement,
        requiredExplicit: Boolean?
    ): ParsedParam {
        val (type, enumVals) = typeOfSpec(spec)
        val desc = (spec as? JsonObject)?.get("description")?.let {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: ""
        return ParsedParam(
            name = name,
            type = type,
            required = requiredExplicit ?: true,
            description = desc.ifBlank { name },
            enumValues = enumVals
        )
    }

    /** (registry type, enum values) from a property's spec object or primitive. */
    private fun typeOfSpec(spec: JsonElement): Pair<ToolParameterType, List<String>?> {
        // Primitive spec = bare type name, e.g. "location": "string"
        if (spec is JsonPrimitive) {
            return primitiveType(spec.content) to null
        }
        if (spec !is JsonObject) return ToolParameterType.String to null
        val typeName = (spec["type"] as? JsonPrimitive)?.contentOrNull ?: "string"
        val enumVals = (spec["enum"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        return when (typeName.lowercase()) {
            "integer", "int" -> ToolParameterType.Integer to enumVals
            "long" -> ToolParameterType.Long to enumVals
            "float", "number", "double" -> ToolParameterType.Float to enumVals
            "boolean", "bool" -> ToolParameterType.Boolean to enumVals
            "enum", "enumeration" -> ToolParameterType.Enum to (enumVals ?: emptyList())
            "code" -> ToolParameterType.Code to enumVals
            "shell", "shellcommand", "command" -> ToolParameterType.ShellCommand to enumVals
            "script" -> ToolParameterType.Script to enumVals
            "" -> ToolParameterType.String to enumVals
            else -> ToolParameterType.String to enumVals
        }
    }

    private fun primitiveType(name: String): ToolParameterType = when (name.lowercase()) {
        "integer", "int", "long" -> ToolParameterType.Integer
        "float", "number", "double" -> ToolParameterType.Float
        "boolean", "bool" -> ToolParameterType.Boolean
        "enum", "enumeration" -> ToolParameterType.Enum
        else -> ToolParameterType.String
    }

    // ───────────────── WIRE SHAPES ─────────────────

    /** Registry ToolDefinition from one GURU-created tool. Null on unresolvable schemas. */
    fun toolToDefinition(tool: GuruDefinedTool): ToolDefinition? = runCatching {
        val params = parseParameters(tool.parameters)
        ToolDefinition(
            name = tool.name,
            description = tool.description,
            category = "guru_tool",
            parameters = params.map {
                ToolParameter(it.name, it.type, it.required, it.description, it.enumValues)
            }
        )
    }.getOrNull()

    /** Koog descriptor (full required/optional parameters) for BYO cloud executor paths. */
    fun toolToDescriptor(tool: GuruDefinedTool): ToolDescriptor? = runCatching {
        val params = parseParameters(tool.parameters)
        descriptorFromParams(tool.name, tool.description, params)
    }.getOrNull()

    /** The registry-to-koog path for BUILT-IN tools too — full parameter schemas
     *  for the descriptors every koog-path executor uses, rather than the bare
     *  name+description the model was left to guess inside cloud BYO requests. */
    fun definitionToDescriptor(def: ToolDefinition): ToolDescriptor = descriptorFromParams(
        name = def.name,
        description = def.description,
        params = def.parameters.map {
            ParsedParam(it.name, it.type, it.required, it.description, it.enumValues)
        }
    )

    private fun descriptorFromParams(
        name: String,
        description: String,
        params: List<ParsedParam>
    ): ToolDescriptor = ToolDescriptor(
        name = name,
        description = description,
        requiredParameters = params.filter { it.required }.map { it.toKoogDescriptor() },
        optionalParameters = params.filterNot { it.required }.map { it.toKoogDescriptor() }
    )

    private fun ParsedParam.toKoogDescriptor() = ToolParameterDescriptor(
        name = name,
        description = description,
        type = toKoogType()
    )

    private fun ParsedParam.toKoogType(): KoogParamType = when (type) {
        ToolParameterType.Integer, ToolParameterType.Long -> KoogParamType.Integer
        ToolParameterType.Float -> KoogParamType.Float
        ToolParameterType.Boolean -> KoogParamType.Boolean
        ToolParameterType.Enum -> KoogParamType.Enum(
            (enumValues ?: emptyList()).toTypedArray()
        )
        else -> KoogParamType.String
    }
}