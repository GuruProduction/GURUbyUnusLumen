package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ProcessToolExecutor : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ProcessToolDefinitions.LIST_PROCESSES -> listProcesses()
            ProcessToolDefinitions.KILL_PROCESS -> killProcess(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun listProcesses(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A -o PID,NAME,STAT 2>/dev/null || ps 2>/dev/null"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val lines = output.lines().drop(1).filter { it.isNotBlank() }
            val processes = lines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size >= 2) {
                    ProcessInfo(pid = parts[0], name = parts[1], state = parts.getOrNull(2) ?: "")
                } else null
            }
            val result = ProcessListResult(processes = processes, error = null)
            ToolExecutionResult.success(result, json.encodeToString(ProcessListResult.serializer(), result))
        } catch (e: Exception) {
            val result = ProcessListResult(processes = emptyList(), error = "Process list failed: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ProcessListResult.serializer(), result))
        }
    }

    private suspend fun killProcess(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pid = args["pid"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'pid' parameter")
        val signal = (args["signal"] as? Number)?.toInt() ?: 15

        try {
            val process = Runtime.getRuntime().exec(arrayOf("kill", "-$signal", pid))
            process.waitFor()
            val result = ProcessActionResult(success = process.exitValue() == 0, error = if (process.exitValue() != 0) "Kill may have failed" else null)
            ToolExecutionResult.success(result, json.encodeToString(ProcessActionResult.serializer(), result))
        } catch (e: Exception) {
            val result = ProcessActionResult(success = false, error = "Kill failed: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ProcessActionResult.serializer(), result))
        }
    }
}