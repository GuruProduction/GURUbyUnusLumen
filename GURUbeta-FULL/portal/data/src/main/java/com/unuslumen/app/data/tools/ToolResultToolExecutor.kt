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

/**
 * Reading back what tools returned.
 *
 * TWO SOURCES, AND THEY ARE NOT INTERCHANGEABLE.
 *
 * The main chat's calls live in `tool_results`, written by `AiRepositoryImpl` inside its own tool
 * loop. A spawned agent's calls never reach that table: they are recorded in `agent_run_events` as
 * lifecycle facts and in `tool_observations` as what the tool actually returned. So a request that
 * names a run must be answered from the run's own tables, and a request that does not is the chat's
 * history exactly as it was before.
 *
 * THAT SPLIT IS THE WHOLE FIX FOR "getToolResult returns empty for spawned agents". The tool searched
 * the chat's table for the agent's calls, found none, and reported the absence as an answer. It was
 * not wrong about the table; it was asked the wrong question.
 */
class ToolResultToolExecutor(
    private val toolResultDao: ToolResultDao,
    private val runDao: com.unuslumen.app.database.dao.AgentRunDao,
    private val observations: com.unuslumen.app.database.dao.ToolObservationDao,
    private val ledger: com.unuslumen.app.data.agents.AgentRunLedger,
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
        val runId = (args["runId"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val toolName = (args["toolName"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 5
        val conversationId = args["conversationId"] as? String

        /*
         * A RUN IS ANSWERED FROM THE RUN'S OWN RECORD, first and completely.
         *
         * No fallback to the chat table afterwards: a run that genuinely called nothing must say so
         * rather than being padded with an unrelated conversation's history, which is the confusion
         * this split exists to remove.
         */
        if (runId != null) {
            return runOwnedResult(runId, toolName, limit)
        }

        if (toolName == null) {
            return ToolExecutionResult.error(
                "Missing 'toolName'. Pass one to read the main chat's history, or pass a 'runId' " +
                    "to read a spawned agent's own tool calls.",
            )
        }

        val entities = if (conversationId != null) {
            toolResultDao.getRecentByToolNameAndConversation(toolName, conversationId, limit)
        } else {
            toolResultDao.getRecentByToolName(toolName, limit)
        }

        val result = buildResult(entities)
        return ToolExecutionResult.success(result, json.encodeToString(ToolResultRetrievalResult.serializer(), result))
    }

    /**
     * A run's tool calls, with the output each one actually returned.
     *
     * THE OBSERVATION IS THE OUTPUT AND THE EVENT IS THE HISTORY. A call with a start and no
     * observation is reported as having no recorded output rather than as a call that returned
     * nothing, because those are different facts and only one of them is true.
     */
    private suspend fun runOwnedResult(
        runId: String,
        toolName: String?,
        limit: Int,
    ): ToolExecutionResult {
        val run = runCatching { runDao.run(runId) }.getOrNull()
            ?: return ToolExecutionResult.error(
                "There is no run with the id '$runId'. Use listAgentRuns to find a real one.",
            )

        val events = runCatching { ledger.events(runId) }.getOrDefault(emptyList())
        val observed = runCatching { observations.forRun(runId) }
            .getOrDefault(emptyList())
            .associateBy { it.agentInvocationId ?: "" }

        val entries = events
            .filter { it.eventType == com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED }
            .mapNotNull { event ->
                val payload = runCatching {
                    json.parseToJsonElement(event.payloadJson) as kotlinx.serialization.json.JsonObject
                }.getOrNull() ?: return@mapNotNull null
                val invocationId = (payload["invocationId"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val tool = (payload["tool"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content.orEmpty()
                if (toolName != null && !tool.equals(toolName, ignoreCase = true)) return@mapNotNull null

                val record = observed[invocationId]
                ToolResultEntry(
                    toolName = tool,
                    parameters = (payload["arguments"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content.orEmpty(),
                    result = record?.resultSummary?.takeIf { it.isNotBlank() }
                        ?: "[no output was recorded for this call. It may have run and its result " +
                            "may have been lost, or it may never have finished; that is not the " +
                            "same as a call that returned nothing.]",
                    timestamp = event.occurredAt,
                    ageMinutes = (System.currentTimeMillis() - event.occurredAt) / 60_000,
                    isStale = false,
                )
            }
            .takeLast(limit)

        if (entries.isEmpty()) {
            return ToolExecutionResult.success(
                ToolResultRetrievalResult(results = emptyList()),
                json.encodeToString(
                    ToolResultRetrievalResult.serializer(),
                    ToolResultRetrievalResult(results = emptyList()),
                ),
            )
        }

        val result = ToolResultRetrievalResult(results = entries)
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