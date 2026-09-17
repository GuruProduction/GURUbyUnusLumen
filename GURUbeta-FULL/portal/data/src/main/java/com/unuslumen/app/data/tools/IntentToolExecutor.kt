package com.unuslumen.app.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class IntentToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        IntentToolDefinitions.SEND_INTENT -> sendIntent(args)
        IntentToolDefinitions.SEND_BROADCAST -> sendBroadcast(args)
        IntentToolDefinitions.QUERY_INTENT_ACTIVITIES -> queryIntentActivities(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun sendIntent(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val action = args["action"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'action'")
        val dataUri = args["dataUri"] as? String ?: ""
        val mimeType = args["mimeType"] as? String ?: ""
        val packageName = args["packageName"] as? String ?: ""
        val extras = args["extras"] as? String ?: "{}"
        try {
            val intent = Intent(action)
            if (dataUri.isNotBlank()) intent.data = Uri.parse(dataUri)
            if (mimeType.isNotBlank()) intent.type = mimeType
            if (packageName.isNotBlank()) intent.setPackage(packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (extras != "{}" && extras.isNotBlank()) { try { val map = json.decodeFromString<Map<String, String>>(extras); map.forEach { (k, v) -> intent.putExtra(k, v) } } catch (_: Exception) {} }
            context.startActivity(intent)
            val r = IntentResult(true, null); ToolExecutionResult.success(r, json.encodeToString(IntentResult.serializer(), r))
        } catch (e: Exception) { val r = IntentResult(false, "Intent failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(IntentResult.serializer(), r)) }
    }

    private suspend fun sendBroadcast(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val action = args["action"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'action'")
        val extras = args["extras"] as? String ?: "{}"
        try {
            val intent = Intent(action)
            if (extras != "{}" && extras.isNotBlank()) { try { val map = json.decodeFromString<Map<String, String>>(extras); map.forEach { (k, v) -> intent.putExtra(k, v) } } catch (_: Exception) {} }
            context.sendBroadcast(intent)
            val r = IntentResult(true, null); ToolExecutionResult.success(r, json.encodeToString(IntentResult.serializer(), r))
        } catch (e: Exception) { val r = IntentResult(false, "Broadcast failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(IntentResult.serializer(), r)) }
    }

    private suspend fun queryIntentActivities(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val action = args["action"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'action'")
        val dataUri = args["dataUri"] as? String ?: ""
        try {
            val intent = Intent(action); if (dataUri.isNotBlank()) intent.data = Uri.parse(dataUri)
            val activities = context.packageManager.queryIntentActivities(intent, 0)
            val r = IntentQueryResult(activities.map { it.activityInfo.packageName }, null); ToolExecutionResult.success(r, json.encodeToString(IntentQueryResult.serializer(), r))
        } catch (e: Exception) { val r = IntentQueryResult(emptyList(), "Query failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(IntentQueryResult.serializer(), r)) }
    }
}