package com.unuslumen.app.alarm.repository

import com.unuslumen.app.alarm.model.Alarm

interface AlarmRepository {

    suspend fun getAlarms(): List<Alarm>

    suspend fun upsertAlarm(alarm: Alarm): Long

    suspend fun deleteAlarm(alarm: Alarm)

    suspend fun deleteAlarm(id: Int)
}