package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ScreenToolExecutor(
    private val context: Context
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ScreenToolDefinitions.TAKE_SCREENSHOT -> captureScreen()
            ScreenToolDefinitions.RECORD_SCREEN -> screenRecord(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun captureScreen(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val screenshotPath = "${context.cacheDir.absolutePath}/screenshot_${System.currentTimeMillis()}.png"
            // Route through ShellExecutor: root > ADB > Runtime fallback. A raw
            // Runtime.exec("screencap") runs as the app UID which is denied screencap
            // access on virtually every device, which is why takeScreenshot always
            // failed until capture permission was granted through the other tool.
            val result = shellExecutor.execute("screencap -p '$screenshotPath'")
            val file = File(screenshotPath)
            if (result.success && file.exists() && file.length() > 0) {
                val r = ScreenShotResult(true, screenshotPath, file.length(), null)
                ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
            } else {
                val detail = if (result.stderr.isNotBlank()) " (${result.stderr.take(150)})" else ""
                val r = ScreenShotResult(
                    false, "", 0,
                    "screencap failed even with root/ADB (exit ${result.exitCode})$detail"
                )
                ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
            }
        } catch (e: Exception) {
            val r = ScreenShotResult(false, "", 0, "Screenshot error: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
        }
    }

    private suspend fun screenRecord(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val action = args["action"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'action' parameter")
        val duration = (args["duration"] as? Number)?.toInt() ?: 30

        try {
            if (action.lowercase() == "start") {
                val path = "${context.cacheDir.absolutePath}/screenrecord_${System.currentTimeMillis()}.mp4"
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "screenrecord --time-limit $duration $path &"))
                val result = ScreenShotResult(true, path, 0, "Recording started for ${duration}s. Use 'stop' to finish.")
                ToolExecutionResult.success(result, json.encodeToString(ScreenShotResult.serializer(), result))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall screenrecord 2>/dev/null"))
                val dir = File(context.cacheDir.absolutePath)
                val recording = dir.listFiles()?.filter { it.name.startsWith("screenrecord_") }?.maxByOrNull { it.lastModified() }
                if (recording != null && recording.exists()) {
                    val result = ScreenShotResult(true, recording.absolutePath, recording.length(), null)
                    ToolExecutionResult.success(result, json.encodeToString(ScreenShotResult.serializer(), result))
                } else {
                    val result = ScreenShotResult(false, "", 0, "No recording found. Start one first.")
                    ToolExecutionResult.success(result, json.encodeToString(ScreenShotResult.serializer(), result))
                }
            }
        } catch (e: Exception) {
            val result = ScreenShotResult(false, "", 0, "Screen record error: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ScreenShotResult.serializer(), result))
        }
    }
}