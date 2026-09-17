package com.unuslumen.app.data.tools.registry

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * ToolArgumentRepair — Fixes broken tool call arguments from the LLM.
 *
 * The dispatcher passes the resolved ToolDefinition so the repair component
 * has access to the parameter list for type coercion and fuzzy name matching.
 *
 * All logic ported from AiRepositoryImpl.repairToolCallArguments:
 * - Empty key detection and orphaned value assignment
 * - String-to-type conversion (int, bool, float) via fixValueType
 * - isTypeCompatible check for orphan assignment
 * - fuzzyMatchParamName for unknown keys (snake_case/camelCase, prefix matching)
 * - __wrapped_value__ key unwrapping
 * - Positional matching for remaining orphans
 */
class ToolArgumentRepair {

    fun repair(definition: ToolDefinition, rawArgs: JsonObject): Map<String, Any?> {
        val allParams = definition.parameters
        val paramNames = allParams.map { it.name }.toSet()
        val requiredParams = allParams.filter { it.required }.map { it.name }.toSet()
        val paramTypes = allParams.associate { it.name to it.type }

        val repaired = mutableMapOf<String, JsonElement>()
        val orphanedValues = mutableListOf<Pair<String, JsonElement>>()

        for ((key, value) in rawArgs) {
            if (key == "__wrapped_value__") {
                orphanedValues.add("" to value)
                continue
            }
            if (key.isBlank()) {
                orphanedValues.add("" to value)
            } else if (key !in paramNames) {
                val matched = fuzzyMatchParamName(key, paramNames)
                if (matched != null) {
                    repaired[matched] = fixValueType(matched, value, paramTypes)
                } else {
                    repaired[key] = value
                }
            } else {
                repaired[key] = fixValueType(key, value, paramTypes)
            }
        }

        if (orphanedValues.isNotEmpty()) {
            val missingRequired = requiredParams.filter { it !in repaired.keys }
            val missingOptional = paramNames.filter { it !in repaired.keys && it !in requiredParams }

            val usedOrphans = mutableSetOf<Int>()
            for (missingParam in missingRequired) {
                val paramType = paramTypes[missingParam]
                for ((i, pair) in orphanedValues.withIndex()) {
                    if (i in usedOrphans) continue
                    val value = pair.second
                    if (isTypeCompatible(value, paramType)) {
                        repaired[missingParam] = fixValueType(missingParam, value, paramTypes)
                        usedOrphans.add(i)
                        break
                    }
                }
            }

            for (orphanPair in orphanedValues.withIndex()) {
                if (orphanPair.index in usedOrphans) continue
                val value = orphanPair.value.second
                for (missingParam in missingOptional) {
                    if (missingParam in repaired) continue
                    val paramType = paramTypes[missingParam]
                    if (isTypeCompatible(value, paramType)) {
                        repaired[missingParam] = fixValueType(missingParam, value, paramTypes)
                        usedOrphans.add(orphanPair.index)
                        break
                    }
                }
            }

            val remainingOrphans = orphanedValues.filterIndexed { i, _ -> i !in usedOrphans }
            val remainingMissing = (requiredParams + paramNames.filter { it !in requiredParams })
                .filter { it !in repaired.keys }
            for ((i, pair) in remainingOrphans.withIndex()) {
                if (i < remainingMissing.size) {
                    val targetParam = remainingMissing[i]
                    repaired[targetParam] = fixValueType(targetParam, pair.second, paramTypes)
                }
            }
        }

        return repaired.mapValues { (_, element) ->
            when (element) {
                is JsonPrimitive -> {
                    when {
                        element.isString -> element.contentOrNull
                        element.contentOrNull == "true" -> true
                        element.contentOrNull == "false" -> false
                        element.contentOrNull?.contains('.') == true -> element.contentOrNull?.toDoubleOrNull()
                        else -> element.contentOrNull?.toLongOrNull() ?: element.contentOrNull
                    }
                }
                else -> element.toString()
            }
        }
    }

    private fun fuzzyMatchParamName(input: String, paramNames: Set<String>): String? {
        val caseMatch = paramNames.find { it.equals(input, ignoreCase = true) }
        if (caseMatch != null) return caseMatch

        val stripped = input.trim('"', '\'', ' ')
        if (stripped != input) {
            val strippedMatch = paramNames.find { it.equals(stripped, ignoreCase = true) }
            if (strippedMatch != null) return strippedMatch
        }

        if (input == "__wrapped_value__" || input == "value") return null

        val camelFromSnake = input.split("_").mapIndexed { i, part ->
            if (i == 0) part.lowercase() else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        val camelMatch = paramNames.find { it.equals(camelFromSnake, ignoreCase = true) }
        if (camelMatch != null) return camelMatch

        val snakeFromCamel = input.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }
        val snakeMatch = paramNames.find { it.equals(snakeFromCamel, ignoreCase = true) }
        if (snakeMatch != null) return snakeMatch

        for (name in paramNames) {
            if (name.contains(input, ignoreCase = true) || input.contains(name, ignoreCase = true)) {
                return name
            }
        }

        val lowerInput = input.lowercase()
        for (name in paramNames) {
            val lowerName = name.lowercase()
            if (lowerInput.length >= 3 && lowerName.length >= 3 && lowerInput.take(3) == lowerName.take(3)) {
                return name
            }
        }

        return null
    }

    private fun fixValueType(
        paramName: String,
        value: JsonElement,
        paramTypes: Map<String, ToolParameterType>
    ): JsonElement {
        val expectedType = paramTypes[paramName] ?: return value
        if (value !is JsonPrimitive) return value

        return when (expectedType) {
            ToolParameterType.Integer -> {
                if (value.isString) {
                    val str = value.content
                    str.toIntOrNull()?.let { JsonPrimitive(it) }
                        ?: str.toLongOrNull()?.let { JsonPrimitive(it) }
                        ?: value
                } else value
            }
            ToolParameterType.Long -> {
                if (value.isString) {
                    val str = value.content
                    str.toLongOrNull()?.let { JsonPrimitive(it) }
                        ?: str.toIntOrNull()?.let { JsonPrimitive(it) }
                        ?: value
                } else value
            }
            ToolParameterType.Boolean -> {
                if (value.isString) {
                    val str = value.content.lowercase()
                    if (str == "true") JsonPrimitive(true)
                    else if (str == "false") JsonPrimitive(false)
                    else value
                } else value
            }
            ToolParameterType.Float -> {
                if (value.isString) {
                    val str = value.content
                    str.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: value
                } else value
            }
            else -> value
        }
    }

    private fun isTypeCompatible(
        value: JsonElement,
        expectedType: ToolParameterType?
    ): Boolean {
        if (expectedType == null) return true
        if (value !is JsonPrimitive) return true

        return when (expectedType) {
            ToolParameterType.String -> value.isString || value.contentOrNull != null
            ToolParameterType.Integer -> {
                value.isString && (value.content.toIntOrNull() != null || value.content.toLongOrNull() != null)
                    || !value.isString
            }
            ToolParameterType.Long -> {
                value.isString && (value.content.toLongOrNull() != null || value.content.toIntOrNull() != null)
                    || !value.isString
            }
            ToolParameterType.Boolean -> {
                value.isString && (value.content.lowercase() == "true" || value.content.lowercase() == "false")
                    || !value.isString
            }
            ToolParameterType.Float -> {
                value.isString && value.content.toDoubleOrNull() != null
                    || !value.isString
            }
            else -> true
        }
    }
}