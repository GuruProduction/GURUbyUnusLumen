// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.alarm.use_case

import com.unuslumen.app.alarm.model.Alarm
import com.unuslumen.app.alarm.repository.AlarmRepository
import com.unuslumen.app.alarm.repository.AlarmScheduler
import org.koin.core.annotation.Single

@Single
class UpsertAlarmUseCase(
    private val alarmRepository: AlarmRepository,
    private val alarmScheduler: AlarmScheduler
) {
    suspend operator fun invoke(currentAlarmId: Int?, dueDate: Long): Int? {
        if (!alarmScheduler.canScheduleExactAlarms()) return null
        val alarmId = alarmRepository.upsertAlarm(Alarm(currentAlarmId ?: 0, dueDate)).let {
            // -1 indicates alarm is updated so we return the original id
            if (it == -1L) currentAlarmId ?: 0 else it.toInt()
        }
        alarmScheduler.scheduleAlarm(Alarm(id = alarmId, dueDate))
        return alarmId
    }
}