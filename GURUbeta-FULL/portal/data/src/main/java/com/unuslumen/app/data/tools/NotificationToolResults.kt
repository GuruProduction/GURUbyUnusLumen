package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class NotificationInfo(val id: Int, val packageName: String, val appName: String, val title: String, val text: String, val subText: String, val postTime: Long, val isOngoing: Boolean, val isClearable: Boolean, val category: String) : ToolResultData
@Serializable data class NotificationAction(val title: String, val actionIntent: Boolean) : ToolResultData
@Serializable data class NotificationDetail(val id: Int, val packageName: String, val appName: String, val title: String, val text: String, val subText: String, val bigText: String, val summaryText: String, val postTime: Long, val isOngoing: Boolean, val isClearable: Boolean, val category: String, val actions: List<NotificationAction>) : ToolResultData
@Serializable data class NotificationListResult(val notifications: List<NotificationInfo>, val error: String?) : ToolResultData
@Serializable data class NotificationDetailResult(val notification: NotificationDetail?, val error: String?) : ToolResultData
@Serializable data class NotificationActionResult(val success: Boolean, val error: String?) : ToolResultData