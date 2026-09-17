package com.unuslumen.app.data.tools

import com.unuslumen.app.alarm.use_case.DeleteAlarmUseCase
import com.unuslumen.app.alarm.use_case.GetAllAlarmsUseCase
import com.unuslumen.app.alarm.use_case.UpsertAlarmUseCase
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.AlarmInfo
import kotlinx.serialization.json.Json

class AlarmToolExecutor(
    private val upsertAlarm: UpsertAlarmUseCase,
    private val deleteAlarmUseCase: DeleteAlarmUseCase,
    private val getAllAlarmsUseCase: GetAllAlarmsUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        AlarmToolDefinitions.CREATE_ALARM -> createAlarm(args)
        AlarmToolDefinitions.DELETE_ALARM -> deleteAlarm(args)
        AlarmToolDefinitions.GET_ALL_ALARMS -> getAllAlarms()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createAlarm(args: Map<String, Any?>): ToolExecutionResult {
        val currentAlarmId = (args["currentAlarmId"] as? Number)?.toInt()
        val dueDate = (args["dueDate"] as? Number)?.toLong() ?: return ToolExecutionResult.error("Missing 'dueDate'")
        val alarmId = upsertAlarm(currentAlarmId, dueDate)
        val r = AlarmResult(alarmId, dueDate, alarmId != null)
        return ToolExecutionResult.success(r, json.encodeToString(AlarmResult.serializer(), r))
    }

    private suspend fun deleteAlarm(args: Map<String, Any?>): ToolExecutionResult {
        val alarmId = (args["alarmId"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'alarmId'")
        deleteAlarmUseCase(alarmId)
        val r = DeleteAlarmResult(alarmId)
        return ToolExecutionResult.success(r, json.encodeToString(DeleteAlarmResult.serializer(), r))
    }

    private suspend fun getAllAlarms(): ToolExecutionResult {
        val alarms = getAllAlarmsUseCase()
        val r = AlarmsResult(alarms.map { AlarmInfo(id = it.id, time = it.time) })
        return ToolExecutionResult.success(r, json.encodeToString(AlarmsResult.serializer(), r))
    }
}