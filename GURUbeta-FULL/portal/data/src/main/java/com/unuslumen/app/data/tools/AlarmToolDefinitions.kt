package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.UtilToolDefinitions.FORMAT_DATE_TOOL
import kotlin.reflect.KClass

object AlarmToolDefinitions : ToolSetRegistration {
    const val CREATE_ALARM = "createAlarm"
    const val DELETE_ALARM = "deleteAlarm"
    const val GET_ALL_ALARMS = "getAllAlarms"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_ALARM, description = "Create an alarm for a task. The alarm will notify the user at the specified time. Returns the alarm ID, or null if exact alarms are not permitted on this device.", category = "alarm", parameters = listOf(ToolParameter("currentAlarmId", ToolParameterType.Integer, false, "The existing alarm ID to update, or null to create a new alarm"), ToolParameter("dueDate", ToolParameterType.Integer, true, "The time for the alarm in epoch milliseconds. Use $FORMAT_DATE_TOOL to convert if needed.")), permissions = listOf("android.permission.SCHEDULE_EXACT_ALARM")),
        ToolDefinition(name = DELETE_ALARM, description = "Delete an alarm by its ID. Use this when a task's alarm is no longer needed.", category = "alarm", parameters = listOf(ToolParameter("alarmId", ToolParameterType.Integer, true, "The alarm ID to delete")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_ALARMS, description = "Get all scheduled alarms. Returns alarm IDs and their scheduled times.", category = "alarm", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = AlarmToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}