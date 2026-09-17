package com.unuslumen.app.data.tools.registry

import kotlinx.serialization.json.JsonObject

/**
 * ToolNameResolver — Fuzzy tool name matching at runtime.
 *
 * Queries the registry at runtime. No static lookup table.
 *
 * Three-tier resolution:
 * 1. Exact case-insensitive match against registered names.
 * 2. Parameter-to-tool reverse lookup with disambiguation:
 *    If the requested name matches a parameter name on exactly one tool, resolve to that tool.
 *    If multiple tools share that parameter name, disambiguate by checking which candidate
 *    tool has the most other parameter names matching keys in rawArgs.
 *    If disambiguation fails (no additional keys match, or no args provided), return null
 *    (deliberate failure rather than arbitrary pick).
 * 3. Fail with null.
 *
 * The dispatcher passes rawArgs so disambiguation has access to the full argument set.
 */
class ToolNameResolver {

    fun resolve(requested: String, registry: ToolRegistry, rawArgs: JsonObject? = null): String? {
        val allNames = registry.getAllToolNames()

        // 1. Exact case-insensitive match
        allNames.find { it.equals(requested, ignoreCase = true) }?.let { return it }

        // 2. Parameter reverse lookup with disambiguation
        val paramIndex = registry.getParameterIndex()
        val candidates = paramIndex[requested.lowercase()].orEmpty()

        return when {
            // Single candidate — safe to resolve directly
            candidates.size == 1 -> candidates.first()

            // Multiple candidates with args — disambiguate by matching other argument keys
            candidates.size > 1 && rawArgs != null -> {
                val argKeys = rawArgs.keys.map { it.lowercase() }.toSet() - requested.lowercase()
                if (argKeys.isEmpty()) return null  // no context to disambiguate, fail safely

                val bestMatch = candidates.maxByOrNull { toolName ->
                    val def = registry.getDefinition(toolName)
                    def?.parameters?.count { p -> p.name.lowercase() in argKeys } ?: 0
                }
                val matchCount = bestMatch?.let {
                    registry.getDefinition(it)?.parameters?.count { p -> p.name.lowercase() in argKeys } ?: 0
                } ?: 0

                // Only return if the best match has at least 1 additional matching parameter
                if (matchCount > 0) bestMatch else null
            }

            // Multiple candidates without args — cannot disambiguate, fail safely
            candidates.size > 1 && rawArgs == null -> null

            // No candidates — fail
            else -> null
        }
    }
}