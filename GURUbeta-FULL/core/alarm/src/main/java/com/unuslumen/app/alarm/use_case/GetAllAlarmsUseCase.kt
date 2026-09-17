package com.unuslumen.app.alarm.use_case

import com.unuslumen.app.alarm.repository.AlarmRepository
import org.koin.core.annotation.Single

@Single
class GetAllAlarmsUseCase(
    private val alarmRepository: AlarmRepository
) {
    suspend operator fun invoke() = alarmRepository.getAlarms()
}