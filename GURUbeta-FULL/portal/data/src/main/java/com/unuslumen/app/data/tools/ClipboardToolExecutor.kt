package com.unuslumen.app.data.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ClipboardToolExecutor(
    private val context: Context
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ClipboardToolDefinitions.GET_CLIPBOARD -> getClipboard()
            ClipboardToolDefinitions.SET_CLIPBOARD -> setClipboard(args)
            ClipboardToolDefinitions.CLEAR_CLIPBOARD -> clearClipboard()
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun getClipboard(): ToolExecutionResult = withContext(Dispatchers.Main) {
        try {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val result = ClipboardResult(text = text, hasText = text.isNotBlank(), error = null)
            ToolExecutionResult.success(result, json.encodeToString(ClipboardResult.serializer(), result))
        } catch (e: Exception) {
            val result = ClipboardResult(text = "", hasText = false, error = "Clipboard read failed: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ClipboardResult.serializer(), result))
        }
    }

    private suspend fun setClipboard(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val text = args["text"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'text' parameter")
        val label = args["label"] as? String ?: "Guru"

        try {
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            val result = ClipboardResult(text = text, hasText = true, error = null)
            ToolExecutionResult.success(result, json.encodeToString(ClipboardResult.serializer(), result))
        } catch (e: Exception) {
            val result = ClipboardResult(text = "", hasText = false, error = "Clipboard write failed: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ClipboardResult.serializer(), result))
        }
    }

    private suspend fun clearClipboard(): ToolExecutionResult = withContext(Dispatchers.Main) {
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            val result = ClipboardResult(text = "", hasText = false, error = null)
            ToolExecutionResult.success(result, json.encodeToString(ClipboardResult.serializer(), result))
        } catch (e: Exception) {
            val result = ClipboardResult(text = "", hasText = false, error = "Clipboard clear failed: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ClipboardResult.serializer(), result))
        }
    }
}