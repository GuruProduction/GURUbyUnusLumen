package com.unuslumen.app.data.tools.registry

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * ToolSecurityPolicy — Security checks before any tool executes.
 *
 * Inspects by parameter type, not by hardcoded tool name. For each parameter
 * in the ToolDefinition whose type is ShellCommand, Code, or Script, the policy
 * inspects the argument value for dangerous patterns: sed commands, perl -i,
 * awk -i inplace, python open() with write mode.
 *
 * Any tool that has a code parameter gets the security check automatically.
 */
class ToolSecurityPolicy {

    data class SecurityCheckResult(val blocked: Boolean, val reason: String) {
        companion object {
            fun passed() = SecurityCheckResult(false, "")
            fun blocked(reason: String) = SecurityCheckResult(true, reason)
        }
    }

    fun check(definition: ToolDefinition, args: JsonObject): SecurityCheckResult {
        for (param in definition.parameters) {
            if (param.type !in setOf(ToolParameterType.ShellCommand, ToolParameterType.Code, ToolParameterType.Script)) continue
            val value = args[param.name]?.jsonPrimitive?.contentOrNull ?: continue
            if (value.isBlank()) continue

            val lower = value.lowercase().trim()

            // sed detection
            val hasSed = lower == "sed" || lower.startsWith("sed ") || lower.startsWith("sed\t") ||
                "| sed " in lower || "| sed\t" in lower || "|sed " in lower ||
                "&& sed " in lower || "&& sed\t" in lower || "&&sed " in lower ||
                "; sed " in lower || "; sed\t" in lower || ";sed " in lower

            if (hasSed) return SecurityCheckResult.blocked(
                "BLOCKED: sed is not allowed. Use the editFile tool to edit files. Read the file first with readFile, then use editFile to make changes."
            )

            // perl -i detection
            val hasPerlInPlace = "perl -i" in lower || "perl -ie" in lower || "perl -pe" in lower
            if (hasPerlInPlace) return SecurityCheckResult.blocked(
                "BLOCKED: perl -i is not allowed. Use the editFile tool to edit files."
            )

            // awk -i inplace detection
            val hasAwkInPlace = "awk -i inplace" in lower
            if (hasAwkInPlace) return SecurityCheckResult.blocked(
                "BLOCKED: awk -i inplace is not allowed. Use the editFile tool to edit files."
            )

            // python open() write mode detection
            val writeModePatterns = listOf("'w'", "\"w\"", "'w+'", "\"w+\"", "'wb'", "\"wb\"",
                "'wb+'", "\"wb+\"", "'a'", "\"a\"", "'a+'", "\"a+\"", "'ab'", "\"ab+'", "'ab+'")
            val hasPythonWrite = "python" in lower && "open(" in lower &&
                writeModePatterns.any { it in lower }
            if (hasPythonWrite) return SecurityCheckResult.blocked(
                "BLOCKED: python open() with write mode is not allowed. Use the editFile tool to edit files."
            )
        }
        return SecurityCheckResult.passed()
    }
}