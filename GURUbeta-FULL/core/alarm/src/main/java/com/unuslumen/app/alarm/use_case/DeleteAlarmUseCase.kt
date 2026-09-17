package com.unuslumen.app.alarm.use_case

import com.unuslumen.app.alarm.repository.AlarmRepository
import com.unuslumen.app.alarm.repository.AlarmScheduler
import org.koin.core.annotation.Single

@Single
class DeleteAlarmUseCase(
    private val alarmRepository: AlarmRepository,
    private val alarmScheduler: AlarmScheduler
) {
    suspend operator fun invoke(alarmId: Int) {
        alarmScheduler.cancelAlarm(alarmId)
        alarmRepository.deleteAlarm(alarmId)
    }
}