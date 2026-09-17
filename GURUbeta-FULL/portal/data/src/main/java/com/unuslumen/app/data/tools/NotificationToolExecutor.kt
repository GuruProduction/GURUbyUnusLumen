package com.unuslumen.app.data.tools

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class NotificationToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        NotificationToolDefinitions.LIST_NOTIFICATIONS -> getActiveNotifications(args)
        NotificationToolDefinitions.GET_NOTIFICATION -> getNotification(args)
        NotificationToolDefinitions.DISMISS_NOTIFICATION -> dismissNotification(args)
        NotificationToolDefinitions.DISMISS_ALL_NOTIFICATIONS -> dismissAll()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun getActiveNotifications(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val limit = (args["limit"] as? Number)?.toInt() ?: 50
        try {
            val service = GuruNotificationListener.instance ?: return@withContext run { val r = NotificationListResult(emptyList(), "Notification listener not active."); ToolExecutionResult.success(r, json.encodeToString(NotificationListResult.serializer(), r)) }
            val notifs = service.activeNotifications.take(limit).map { it.toInfo() }
            val r = NotificationListResult(notifs, null); ToolExecutionResult.success(r, json.encodeToString(NotificationListResult.serializer(), r))
        } catch (e: Exception) { val r = NotificationListResult(emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(NotificationListResult.serializer(), r)) }
    }

    private suspend fun getNotification(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val packageName = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        val notificationId = (args["notificationId"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'notificationId'")
        try {
            val service = GuruNotificationListener.instance ?: return@withContext run { val r = NotificationDetailResult(null, "Notification listener not active."); ToolExecutionResult.success(r, json.encodeToString(NotificationDetailResult.serializer(), r)) }
            val sbn = service.activeNotifications.find { it.packageName == packageName && it.id == notificationId } ?: return@withContext run { val r = NotificationDetailResult(null, "Not found: $packageName/$notificationId"); ToolExecutionResult.success(r, json.encodeToString(NotificationDetailResult.serializer(), r)) }
            val r = NotificationDetailResult(sbn.toDetail(), null); ToolExecutionResult.success(r, json.encodeToString(NotificationDetailResult.serializer(), r))
        } catch (e: Exception) { val r = NotificationDetailResult(null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(NotificationDetailResult.serializer(), r)) }
    }

    private suspend fun dismissNotification(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val packageName = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        val notificationId = (args["notificationId"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'notificationId'")
        try {
            val service = GuruNotificationListener.instance ?: return@withContext run { val r = NotificationActionResult(false, "Listener not active."); ToolExecutionResult.success(r, json.encodeToString(NotificationActionResult.serializer(), r)) }
            service.cancelNotification("$packageName:$notificationId")
            val r = NotificationActionResult(true, null); ToolExecutionResult.success(r, json.encodeToString(NotificationActionResult.serializer(), r))
        } catch (e: Exception) { val r = NotificationActionResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(NotificationActionResult.serializer(), r)) }
    }

    private suspend fun dismissAll(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val service = GuruNotificationListener.instance ?: return@withContext run { val r = NotificationActionResult(false, "Listener not active."); ToolExecutionResult.success(r, json.encodeToString(NotificationActionResult.serializer(), r)) }
            service.cancelAllNotifications()
            val r = NotificationActionResult(true, null); ToolExecutionResult.success(r, json.encodeToString(NotificationActionResult.serializer(), r))
        } catch (e: Exception) { val r = NotificationActionResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(NotificationActionResult.serializer(), r)) }
    }

    private fun StatusBarNotification.toInfo(): NotificationInfo {
        val e = notification.extras
        return NotificationInfo(id, packageName, e.getString(Notification.EXTRA_TITLE) ?: packageName, e.getString(Notification.EXTRA_TITLE) ?: "", e.getString(Notification.EXTRA_TEXT) ?: "", e.getString(Notification.EXTRA_SUB_TEXT) ?: "", postTime, isOngoing, isClearable, notification.category ?: "")
    }

    private fun StatusBarNotification.toDetail(): NotificationDetail {
        val e = notification.extras
        val actions = notification.actions?.map { NotificationAction(it.title?.toString() ?: "", it.actionIntent != null) } ?: emptyList()
        return NotificationDetail(id, packageName, e.getString(Notification.EXTRA_TITLE) ?: packageName, e.getString(Notification.EXTRA_TITLE) ?: "", e.getString(Notification.EXTRA_TEXT) ?: "", e.getString(Notification.EXTRA_SUB_TEXT) ?: "", e.getString(Notification.EXTRA_BIG_TEXT) ?: "", e.getString(Notification.EXTRA_SUMMARY_TEXT) ?: "", postTime, isOngoing, isClearable, notification.category ?: "", actions)
    }
}