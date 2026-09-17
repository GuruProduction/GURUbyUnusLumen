package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.llmDateTimeWithDayNameFormat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Instant

class UtilToolExecutor : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            UtilToolDefinitions.FORMAT_DATE -> {
                val millis = (args["millis"] as? Number)?.toLong()
                    ?: args["millis"]?.toString()?.toLongOrNull()
                    ?: return ToolExecutionResult.error("Missing or invalid 'millis' parameter")

                val instant = Instant.fromEpochMilliseconds(millis)
                val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
                val formattedDate = localDateTime.format(llmDateTimeWithDayNameFormat)
                val result = FormattedDateResult(formattedDate)
                ToolExecutionResult.success(result, json.encodeToString(FormattedDateResult.serializer(), result))
            }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}