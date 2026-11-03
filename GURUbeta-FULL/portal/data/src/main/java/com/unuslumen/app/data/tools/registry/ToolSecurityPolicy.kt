// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
 * awk -i inplace.
 *
 * Any tool that has a code parameter gets the security check automatically.
 *
 * PYTHON IS DELIBERATELY NOT IN THIS LIST (issue 283/284).
 *
 * There used to be a block here that refused a script containing the word
 * "python" alongside an open() call with a write mode. It was removed because it
 * did not work in either direction:
 *
 *   - It blocked honest code. The match was a substring test on the whole source,
 *     so print("python") next to a legitimate write was refused, and so was any
 *     script mentioning PYTHONPATH or python3.
 *   - It did not prevent writes. pathlib.Path.write_text, os.open, shutil,
 *     sqlite3, zipfile and subprocess all wrote files freely while the guard
 *     refused the one route a careful script would take.
 *
 * A restriction that refuses good code and cannot stop bad code is worse than no
 * restriction: it teaches a caller to reach for os.open to get the job done. The
 * owner's decision is that Python is fully unlocked, which is the honest version
 * of what the guard was already pretending to be.
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

            // No Python block. See the class comment: the previous one was a
            // substring test that refused honest scripts and was trivially
            // bypassed, so it protected nothing and cost real capability.
        }
        return SecurityCheckResult.passed()
    }
}