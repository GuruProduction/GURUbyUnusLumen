package com.unuslumen.app.alarm.repository

import com.unuslumen.app.alarm.model.Alarm


interface AlarmScheduler {

    fun scheduleAlarm(alarm: Alarm)

    fun cancelAlarm(schedulerId: Int)

    fun canScheduleExactAlarms(): Boolean
}