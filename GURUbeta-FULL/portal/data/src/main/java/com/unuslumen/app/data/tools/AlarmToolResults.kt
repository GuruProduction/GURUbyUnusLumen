package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.AlarmInfo
import kotlinx.serialization.Serializable

@Serializable
data class AlarmResult(val alarmId: Int?, val dueDate: Long, val success: Boolean) : ToolResultData
@Serializable
data class DeleteAlarmResult(val deletedAlarmId: Int) : ToolResultData
@Serializable
data class AlarmsResult(val alarms: List<AlarmInfo>) : ToolResultData