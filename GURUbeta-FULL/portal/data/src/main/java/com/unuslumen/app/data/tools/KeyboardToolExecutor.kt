package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class KeyboardToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        KeyboardToolDefinitions.TYPE_TEXT -> typeText(args)
        KeyboardToolDefinitions.PRESS_KEY -> pressKey(args)
        KeyboardToolDefinitions.PRESS_KEY_COMBO -> pressKeyCombo(args)
        KeyboardToolDefinitions.HIDE_KEYBOARD -> hideKeyboard()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun typeText(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val text = args["text"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'text'")
        try {
            val escaped = text.replace("'", "'\\''").replace("\"", "\\\"")
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text '$escaped'")); p.waitFor()
            val r = KeyboardResult(p.exitValue() == 0, if (p.exitValue() != 0) "May have failed" else null)
            ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r))
        } catch (e: Exception) { val r = KeyboardResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r)) }
    }

    private suspend fun pressKey(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val keyCode = (args["keyCode"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'keyCode'")
        try { val p = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString())); p.waitFor()
            val r = KeyboardResult(p.exitValue() == 0, null); ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r))
        } catch (e: Exception) { val r = KeyboardResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r)) }
    }

    private suspend fun pressKeyCombo(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val keyCodes = (args["keyCodes"] as? String) ?: return@withContext ToolExecutionResult.error("Missing 'keyCodes'")
        val codes = keyCodes.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (codes.isEmpty()) return@withContext ToolExecutionResult.error("No valid key codes")
        try { val a = mutableListOf("input", "keyevent"); codes.forEach { a.add(it.toString()) }
            val p = Runtime.getRuntime().exec(a.toTypedArray()); p.waitFor()
            val r = KeyboardResult(p.exitValue() == 0, null); ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r))
        } catch (e: Exception) { val r = KeyboardResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r)) }
    }

    private suspend fun hideKeyboard(): ToolExecutionResult = withContext(Dispatchers.Main) {
        try { val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ime set com.android.internal.inputmethod/.FallbackInputMethod 2>/dev/null; sleep 0.1; ime reset 2>/dev/null")); p.waitFor()
            val r = KeyboardResult(true, null); ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r))
        } catch (e: Exception) { val r = KeyboardResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(KeyboardResult.serializer(), r)) }
    }
}