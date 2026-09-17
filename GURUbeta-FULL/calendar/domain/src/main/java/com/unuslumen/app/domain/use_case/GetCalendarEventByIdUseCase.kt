package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.repository.CalendarRepository
import org.koin.core.annotation.Single

@Single
class GetCalendarEventByIdUseCase(
    private val calendarRepository: CalendarRepository
) {
    suspend operator fun invoke(id: Long): CalendarEvent? {
        return calendarRepository.getEventById(id)
    }
}
