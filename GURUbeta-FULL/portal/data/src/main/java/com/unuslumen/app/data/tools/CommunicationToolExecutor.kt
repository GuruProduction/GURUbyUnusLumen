package com.unuslumen.app.data.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.serialization.json.Json

class CommunicationToolExecutor(
    private val context: Context
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val channelId = "guru_communication"
    private val channelName = "Guru Communication"
    private var notificationId = 1000

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            CommunicationToolDefinitions.ASK_USER -> askUser(args)
            CommunicationToolDefinitions.SEND_NOTIFICATION -> sendNotification(args)
            CommunicationToolDefinitions.SET_REMINDER -> setReminder(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private fun askUser(args: Map<String, Any?>): ToolExecutionResult {
        val question = args["question"] as? String
            ?: return ToolExecutionResult.error("Missing 'question' parameter")
        val optionsStr = args["options"] as? String
        val options = optionsStr?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val optionsText = if (options.isNotEmpty()) "\nOptions: ${options.joinToString(", ")}" else ""
        val result = AskUserResult(question, options, "Waiting for your human to answer: $question$optionsText")
        return ToolExecutionResult.success(result, json.encodeToString(AskUserResult.serializer(), result))
    }

    private fun sendNotification(args: Map<String, Any?>): ToolExecutionResult {
        val title = args["title"] as? String
            ?: return ToolExecutionResult.error("Missing 'title' parameter")
        val message = args["message"] as? String
            ?: return ToolExecutionResult.error("Missing 'message' parameter")

        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).build()
            nm.notify(notificationId++, notif)
            val result = NotificationResult(true, null)
            ToolExecutionResult.success(result, json.encodeToString(NotificationResult.serializer(), result))
        } catch (e: Exception) {
            val result = NotificationResult(false, e.message)
            ToolExecutionResult.success(result, json.encodeToString(NotificationResult.serializer(), result))
        }
    }

    private fun setReminder(args: Map<String, Any?>): ToolExecutionResult {
        val title = args["title"] as? String
            ?: return ToolExecutionResult.error("Missing 'title' parameter")
        val message = args["message"] as? String
            ?: return ToolExecutionResult.error("Missing 'message' parameter")

        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Reminder: $title").setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
            nm.notify(notificationId++, notif)
            val result = NotificationResult(true, null)
            ToolExecutionResult.success(result, json.encodeToString(NotificationResult.serializer(), result))
        } catch (e: Exception) {
            val result = NotificationResult(false, e.message)
            ToolExecutionResult.success(result, json.encodeToString(NotificationResult.serializer(), result))
        }
    }
}