// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.database.dao.ToolObservationDao
import com.unuslumen.app.database.entity.ToolObservationEntity
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ObservationToolExecutor — record and retrieve the action-and-observation log.
 *
 * Recording through the tool carries the same row shape the dispatcher writes,
 * so a manual observation and an automatic one are indistinguishable on read
 * and can be compared against each other.
 *
 * Retrieval filters in the database rather than in memory: applying both a
 * package and a tool filter to two single-filter reads would silently return
 * fewer rows than the limit allows and lose the tail of the history.
 */
@OptIn(ExperimentalUuidApi::class)
class ObservationToolExecutor(
    private val dao: ToolObservationDao
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ObservationToolDefinitions.LOG_OBSERVATION -> logObservation(args)
        ObservationToolDefinitions.GET_OBSERVATIONS -> getObservations(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun logObservation(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val packageName = (args["packageName"] as? String)?.trim().orEmpty()
        val toolName = (args["toolName"] as? String)?.trim().orEmpty()
        if (packageName.isBlank()) return@withContext ToolExecutionResult.error("Missing 'packageName'")
        if (toolName.isBlank()) return@withContext ToolExecutionResult.error("Missing 'toolName'")

        val argumentsJson = (args["argumentsJson"] as? String).orEmpty()
        val summary = (args["resultSummary"] as? String).orEmpty()
        val success = args["success"] as? Boolean ?: false
        val id = Uuid.random().toString()

        dao.insert(
            ToolObservationEntity(
                id = id,
                packageName = packageName,
                activityClass = (args["activityClass"] as? String)?.trim()?.takeIf { it.isNotBlank() },
                toolName = toolName,
                argumentsJson = ToolObservationEntity.truncateArguments(argumentsJson),
                resultSummary = ToolObservationEntity.truncateSummary(summary),
                success = if (success) 1 else 0,
                screenFingerprint = (args["screenFingerprint"] as? String)?.takeIf { it.isNotBlank() },
                accessibilityTreeJson = (args["accessibilityTreeJson"] as? String)?.takeIf { it.isNotBlank() },
                conversationId = (args["conversationId"] as? String)?.trim()?.takeIf { it.isNotBlank() },
                timestamp = System.currentTimeMillis(),
                durationMs = (args["durationMs"] as? Number)?.toLong() ?: 0L
            )
        )

        val message = if (success) "Recorded." else "Recorded as a failure."
        val r = ToolObservationRecordResult(id = id, recorded = true, message = message)
        ToolExecutionResult.success(r, json.encodeToString(ToolObservationRecordResult.serializer(), r))
    }

    private suspend fun getObservations(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val packageName = (args["packageName"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val toolName = (args["toolName"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 10
        val failuresOnly = args["failuresOnly"] as? Boolean ?: false

        if (packageName == null && toolName == null) {
            return@withContext ToolExecutionResult.error(
                "Give a packageName, a toolName, or both. Without one the log would return the whole device history."
            )
        }

        // Filtering stays in SQL so both filters narrow together and the limit
        // always returns the newest rows that actually match rather than the
        // newest rows before filtering.
        val entities = when {
            packageName != null && toolName != null && failuresOnly ->
                dao.getFailuresByPackageAndToolName(packageName, toolName, limit)
            packageName != null && toolName != null ->
                dao.getByPackageAndToolName(packageName, toolName, limit)
            packageName != null && failuresOnly ->
                dao.getFailuresByPackage(packageName, limit)
            packageName != null ->
                dao.getByPackage(packageName, limit)
            failuresOnly ->
                dao.getFailuresByToolName(toolName!!, limit)
            else ->
                dao.getByToolName(toolName!!, limit)
        }

        val now = System.currentTimeMillis()
        val result = ToolObservationLogResult(entities.map { it.toEntry(now) })
        ToolExecutionResult.success(result, json.encodeToString(ToolObservationLogResult.serializer(), result))
    }

    private fun ToolObservationEntity.toEntry(now: Long) = ToolObservationEntry(
        packageName = packageName,
        activityClass = activityClass,
        toolName = toolName,
        argumentsJson = argumentsJson,
        resultSummary = resultSummary,
        success = success != 0,
        screenFingerprint = screenFingerprint,
        accessibilityTreeJson = accessibilityTreeJson,
        conversationId = conversationId,
        timestamp = timestamp,
        durationMs = durationMs,
        ageMinutes = ((now - timestamp) / 60_000L).coerceAtLeast(0L)
    )
}
