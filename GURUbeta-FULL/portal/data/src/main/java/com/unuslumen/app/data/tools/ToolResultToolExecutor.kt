package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.database.entity.ToolResultEntity
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

    private suspend fun searchToolResults(args: Map<String, Any?>): ToolExecutionResult {
        val query = args["query"] as? String
            ?: return ToolExecutionResult.error("Missing 'query' parameter")
        val limit = (args["limit"] as? Number)?.toInt() ?: 5

        val ftsQuery = query.trim().let {
            if (it.contains(" ")) "\"$it\" OR $it" else it
        }

        val entities = try {
            toolResultDao.searchToolResultsFts(ftsQuery, limit)
        } catch (e: Exception) {
            emptyList()
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