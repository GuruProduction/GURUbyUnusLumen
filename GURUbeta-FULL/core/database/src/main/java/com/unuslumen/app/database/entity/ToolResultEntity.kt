package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Entity(
    tableName = "tool_results",
    indices = [
        Index("tool_name"),
        Index("timestamp"),
        Index("conversation_id")
    ]
)
data class ToolResultEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    @ColumnInfo(name = "parameters")
    val parameters: String = "",
    @ColumnInfo(name = "result")
    val result: String = "",
    @ColumnInfo(name = "result_text")
    val resultText: String = "",
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "success")
    val success: Boolean = true,
    @ColumnInfo(name = "ttl_minutes")
    val ttlMinutes: Long? = null,
    @ColumnInfo(name = "result_signature")
    val resultSignature: String = ""
) {
    companion object {
        private const val MAX_RESULT_CHARS = 100_000
        private const val MAX_RESULT_TEXT_CHARS = 50_000
        private const val MAX_PARAMETERS_CHARS = 10_000

        private val json = Json { ignoreUnknownKeys = true }

        fun getTtlForTool(toolName: String): Long? = when (toolName) {
            "weatherCurrent", "weatherForecast" -> 30L
            "sendNotification", "vibrate", "playRingtone", "playSoundFile" -> 5L
            "spotifyStatus", "spotifyPlay", "spotifyPause" -> 10L
            "healthCheck", "deviceInfo" -> 60L
            "getVolume", "getAudioInfo", "getRingerMode" -> 15L
            else -> null
        }

        fun flattenJsonToText(input: String): String {
            if (input.isBlank()) return ""

            val parsed: JsonElement = try {
                json.parseToJsonElement(input)
            } catch (e: Exception) {
                return input.trim().take(MAX_RESULT_TEXT_CHARS)
            }

            val sb = StringBuilder()
            flattenElement(parsed, sb)
            val result = sb.toString().trim()
            return if (result.length > MAX_RESULT_TEXT_CHARS) {
                result.take(MAX_RESULT_TEXT_CHARS)
            } else {
                result
            }
        }

        private fun flattenElement(element: JsonElement, sb: StringBuilder) {
            when (element) {
                is JsonPrimitive -> {
                    val content = element.contentOrNull
                    if (content != null && content.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(content)
                    }
                }
                is JsonArray -> {
                    for (item in element) {
                        flattenElement(item, sb)
                    }
                }
                is JsonObject -> {
                    for ((_, value) in element) {
                        flattenElement(value, sb)
                    }
                }
                else -> {}
            }
        }

        fun truncateResult(result: String): String {
            if (result.length <= MAX_RESULT_CHARS) return result
            val omitted = result.length - MAX_RESULT_CHARS
            return result.take(MAX_RESULT_CHARS) + "\n[... result truncated, $omitted chars omitted ...]"
        }

        fun truncateParameters(parameters: String): String {
            if (parameters.length <= MAX_PARAMETERS_CHARS) return parameters
            val omitted = parameters.length - MAX_PARAMETERS_CHARS
            return parameters.take(MAX_PARAMETERS_CHARS) + "\n[... parameters truncated, $omitted chars omitted ...]"
        }
    }
}

data class ToolResultWithScore(
    val id: String,
    val toolName: String,
    val parameters: String,
    val result: String,
    val resultText: String,
    val timestamp: Long,
    val conversationId: String,
    val success: Boolean,
    val ttlMinutes: Long?,
    val resultSignature: String,
    val bm25Score: Double
)