package com.unuslumen.app.data.tools.registry

/**
 * ToolExecutor — Interface for tool execution.
 *
 * Every tool set's executor implements this. The dispatcher calls execute()
 * with the resolved tool name and parsed arguments. The executor runs the
 * actual Kotlin code that touches the device/OS and returns a result.
 *
 * The executor stays as Kotlin forever because it interfaces with Android.
 */
interface ToolExecutor {
    suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult
}