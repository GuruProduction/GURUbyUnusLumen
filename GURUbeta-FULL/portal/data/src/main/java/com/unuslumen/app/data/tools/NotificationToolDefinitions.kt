package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object NotificationToolDefinitions : ToolSetRegistration {
    const val LIST_NOTIFICATIONS = "getActiveNotifications"
    const val GET_NOTIFICATION = "getNotification"
    const val DISMISS_NOTIFICATION = "dismissNotification"
    const val DISMISS_ALL_NOTIFICATIONS = "dismissAllNotifications"

    override val definitions = listOf(
        ToolDefinition(name = LIST_NOTIFICATIONS, description = "List all active notifications currently visible in the notification shade. Returns app name, title, text, and timestamp. Use to see what's happening on the device.", category = "notification", parameters = listOf(ToolParameter("limit", ToolParameterType.Integer, false, "Maximum notifications to return, default 50")), permissions = emptyList()),
        ToolDefinition(name = GET_NOTIFICATION, description = "Get details of a specific notification by package name and ID.", category = "notification", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name of the app that sent the notification"), ToolParameter("notificationId", ToolParameterType.Integer, true, "Notification ID")), permissions = emptyList()),
        ToolDefinition(name = DISMISS_NOTIFICATION, description = "Dismiss a notification by package name and ID. Use to clean up notifications your human doesn't need to see.", category = "notification", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name of the app"), ToolParameter("notificationId", ToolParameterType.Integer, true, "Notification ID to dismiss")), permissions = emptyList()),
        ToolDefinition(name = DISMISS_ALL_NOTIFICATIONS, description = "Dismiss all active notifications. Use to clear the notification shade completely.", category = "notification", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = NotificationToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}