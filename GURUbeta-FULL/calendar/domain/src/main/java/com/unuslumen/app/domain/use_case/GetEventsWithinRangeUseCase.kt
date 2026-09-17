package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.CalendarRepository
import org.koin.core.annotation.Factory

@Factory
class GetEventsWithinRangeUseCase(private val calendarRepository: CalendarRepository) {
    suspend operator fun invoke(
        startMillis: Long,
        endMillis: Long,
        excludedCalendars: List<Int> = emptyList()
    ) = calendarRepository.getEvents(startMillis, endMillis, excludedCalendars)
}
