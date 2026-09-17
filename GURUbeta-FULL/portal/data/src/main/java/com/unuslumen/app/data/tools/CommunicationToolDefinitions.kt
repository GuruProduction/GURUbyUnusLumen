package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.UtilToolDefinitions.FORMAT_DATE_TOOL
import kotlin.reflect.KClass

object CommunicationToolDefinitions : ToolSetRegistration {
    const val ASK_USER = "askUser"
    const val SEND_NOTIFICATION = "sendNotification"
    const val SET_REMINDER = "setReminder"

    override val definitions = listOf(
        ToolDefinition(
            name = ASK_USER,
            description = "Ask your human a question and wait for their response. Use this when you need clarification, confirmation, or input that you can't determine on your own. The conversation will pause until they respond.",
            category = "communication",
            parameters = listOf(
                ToolParameter("question", ToolParameterType.String, required = true, description = "The question to ask your human"),
                ToolParameter("options", ToolParameterType.String, required = false, description = "Optional list of choices for your human to pick from. Max 4 options. Pass as comma-separated string.")
            ),
            permissions = listOf("android.permission.POST_NOTIFICATIONS")
        ),
        ToolDefinition(
            name = SEND_NOTIFICATION,
            description = "Send a notification to your human's phone. Use this for important updates, reminders, or information they need to see even when they're not in the app.",
            category = "communication",
            parameters = listOf(
                ToolParameter("title", ToolParameterType.String, required = true, description = "The notification title"),
                ToolParameter("message", ToolParameterType.String, required = true, description = "The notification message")
            ),
            permissions = listOf("android.permission.POST_NOTIFICATIONS")
        ),
        ToolDefinition(
            name = SET_REMINDER,
            description = "Schedule a notification for a future time. Use this to remind your human about something at a specific time.",
            category = "communication",
            parameters = listOf(
                ToolParameter("title", ToolParameterType.String, required = true, description = "The reminder title"),
                ToolParameter("message", ToolParameterType.String, required = true, description = "The reminder message"),
                ToolParameter("triggerAtMillis", ToolParameterType.Integer, required = true, description = "When to trigger the reminder, in epoch milliseconds. Use $FORMAT_DATE_TOOL to convert if needed.")
            ),
            permissions = listOf("android.permission.POST_NOTIFICATIONS")
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = CommunicationToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}