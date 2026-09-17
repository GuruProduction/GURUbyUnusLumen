package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.CalendarRepository
import org.koin.core.annotation.Factory

@Factory
class SearchEventsByTitleWithinRangeUseCase(private val calendarRepository: CalendarRepository) {
    suspend operator fun invoke(
        startMillis: Long,
        endMillis: Long,
        titleQuery: String,
        excludedCalendars: List<Int> = emptyList()
    ) = calendarRepository.searchEventsByTitleWithinRange(
        start = startMillis,
        end = endMillis,
        titleQuery = titleQuery,
        excludedCalendars = excludedCalendars
    )
}
