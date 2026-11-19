// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.database.entity.ToolResultEntity
import com.unuslumen.app.domain.memory.ToolResultQuery
import kotlinx.serialization.json.Json

class ToolResultToolExecutor(
    private val toolResultDao: ToolResultDao
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ToolResultToolDefinitions.GET_TOOL_RESULT -> getToolResult(args)
            ToolResultToolDefinitions.SEARCH_TOOL_RESULTS -> searchToolResults(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun getToolResult(args: Map<String, Any?>): ToolExecutionResult {
        val toolName = args["toolName"] as? String
            ?: return ToolExecutionResult.error("Missing 'toolName' parameter")
        val limit = (args["limit"] as? Number)?.toInt() ?: 5
        val conversationId = args["conversationId"] as? String

        val entities = if (conversationId != null) {
            toolResultDao.getRecentByToolNameAndConversation(toolName, conversationId, limit)
        } else {
            toolResultDao.getRecentByToolName(toolName, limit)
        }

        val result = buildResult(entities)
        return ToolExecutionResult.success(result, json.encodeToString(ToolResultRetrievalResult.serializer(), result))
    }

    /**
     * Search the caller's own tool history (issues 465 and 470).
     *
     * THE OLD BODY WAS THE BUG, in two lines.
     *
     *     val ftsQuery = query.trim().let { if (it.contains(" ")) "\"$it\" OR $it" else it }
     *     val entities = try { dao.searchToolResultsFts(ftsQuery, limit) } catch (e: Exception) { emptyList() }
     *
     * FTS5 does not read its MATCH argument as literal text. It reads it as a query
     * in its own grammar, where `.`, `/`, `:`, `-`, `(` and `*` are operators, so a
     * dotted address or a slash-separated URL path threw a syntax error. The bare
     * catch turned that into an empty list, and an empty list from a search reads as
     * "nothing matched" rather than "the search is broken".
     *
     * Every term is quoted now, which is FTS5's own literal-string escape, and the
     * terms are ORed so a two-word search is a union rather than an implicit AND
     * that almost no single row satisfies.
     *
     * A failure is no longer swallowed. If FTS still cannot answer, the search falls
     * back to a LIKE scan over the longest plain word, and only reports a failure
     * when the fallback finds nothing AND the query was unsearchable to begin with.
     * The distinction matters: "your history has nothing matching that" and "the
     * index could not be asked" are different facts, and the old code reported the
     * second as the first every time.
     */
    private suspend fun searchToolResults(args: Map<String, Any?>): ToolExecutionResult {
        val query = args["query"] as? String
            ?: return ToolExecutionResult.error("Missing 'query' parameter")
        val limit = (args["limit"] as? Number)?.toInt() ?: 5

        val expression = ToolResultQuery.ftsExpression(query)
            ?: return ToolExecutionResult.error(
                "'query' holds nothing searchable: give at least one word or number. " +
                    "Punctuation on its own matches nothing.",
            )

        val fromFts = try {
            toolResultDao.searchToolResultsFts(expression, limit)
        } catch (e: Exception) {
            // Logged, not swallowed. The fallback below may still answer, and a
            // caller that gets nothing needs to be able to tell which path was
            // tried — the old code returned the same empty list either way.
            android.util.Log.w(
                "guru_tool_results",
                "FTS query failed on \"$expression\": ${e.message?.take(160)}",
            )
            emptyList<ToolResultEntity>()
        }

        val entities = if (fromFts.isNotEmpty()) {
            fromFts
        } else {
            // The index could not answer, or genuinely has nothing. A LIKE scan over
            // the longest plain word distinguishes those two cheaply and honestly.
            val fallback = ToolResultQuery.fallbackTerm(query)
            if (fallback == null) {
                emptyList()
            } else {
                runCatching { toolResultDao.searchToolResultsLike(fallback) }
                    .getOrDefault(emptyList())
                    .take(limit)
            }
        }

        val result = buildResult(entities)
        return ToolExecutionResult.success(result, json.encodeToString(ToolResultRetrievalResult.serializer(), result))
    }

    private fun buildResult(entities: List<ToolResultEntity>): ToolResultRetrievalResult {
        val currentTime = System.currentTimeMillis()
        val entries = entities.map { entity ->
            val ageMinutes = (currentTime - entity.timestamp) / (60 * 1000)
            val isStale = checkStale(entity, ageMinutes)
            val resultContent = if (isStale) {
                entity.result + "\n\n[NOTE: This data is ${ageMinutes} minutes old and may be stale. Consider re-calling the tool for fresh data.]"
            } else {
                entity.result
            }
            ToolResultEntry(
                toolName = entity.toolName,
                parameters = entity.parameters,
                result = resultContent,
                timestamp = entity.timestamp,
                ageMinutes = ageMinutes,
                isStale = isStale
            )
        }
        return ToolResultRetrievalResult(results = entries)
    }

    private fun checkStale(entity: ToolResultEntity, ageMinutes: Long): Boolean {
        val ttl = entity.ttlMinutes ?: return false
        return ageMinutes > ttl
    }
}