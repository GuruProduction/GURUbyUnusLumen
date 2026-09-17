package com.unuslumen.app.data.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo as AndroidShortcutInfo
import android.content.pm.ShortcutManager
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class WidgetToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        WidgetToolDefinitions.CREATE_SHORTCUT -> createShortcut(args)
        WidgetToolDefinitions.LIST_SHORTCUTS -> listShortcuts()
        WidgetToolDefinitions.REMOVE_SHORTCUT -> removeShortcut(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createShortcut(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val name = args["name"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'name'")
        val action = args["action"] as? String ?: "android.intent.action.VIEW"
        val dataUri = args["dataUri"] as? String ?: ""
        val description = args["description"] as? String ?: ""
        try {
            val sm = context.getSystemService(ShortcutManager::class.java)
            val intent = Intent(action).apply { if (dataUri.isNotBlank()) data = android.net.Uri.parse(dataUri) }
            val sc = AndroidShortcutInfo.Builder(context, "guru_shortcut_${System.currentTimeMillis()}").setShortLabel(name).setLongLabel(description.ifBlank { name }).setIntent(intent).build()
            val success = sm.requestPinShortcut(sc, null)
            val r = WidgetResult(success, if (success) null else "Shortcut creation may have been cancelled")
            ToolExecutionResult.success(r, json.encodeToString(WidgetResult.serializer(), r))
        } catch (e: Exception) { val r = WidgetResult(false, "Shortcut creation failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WidgetResult.serializer(), r)) }
    }

    private suspend fun listShortcuts(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val sm = context.getSystemService(ShortcutManager::class.java)
            val scs = sm.dynamicShortcuts.map { s -> GuruShortcutInfo(s.id, s.shortLabel.toString(), s.longLabel.toString(), s.isPinned) }
            val r = WidgetListResult(scs, null); ToolExecutionResult.success(r, json.encodeToString(WidgetListResult.serializer(), r))
        } catch (e: Exception) { val r = WidgetListResult(emptyList(), "List failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WidgetListResult.serializer(), r)) }
    }

    private suspend fun removeShortcut(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val id = args["shortcutId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'shortcutId'")
        try { val sm = context.getSystemService(ShortcutManager::class.java); sm.removeDynamicShortcuts(listOf(id)); val r = WidgetResult(true, null); ToolExecutionResult.success(r, json.encodeToString(WidgetResult.serializer(), r))
        } catch (e: Exception) { val r = WidgetResult(false, "Remove failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WidgetResult.serializer(), r)) }
    }
}