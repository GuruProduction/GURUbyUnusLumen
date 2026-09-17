package com.unuslumen.app.domain.use_case

import com.unuslumen.app.alarm.repository.AlarmScheduler
import org.koin.core.annotation.Single

@Single
class CanScheduleAlarmsUseCase(
    private val alarmScheduler: AlarmScheduler
) {
    operator fun invoke(): Boolean {
        return alarmScheduler.canScheduleExactAlarms()
    }
}
