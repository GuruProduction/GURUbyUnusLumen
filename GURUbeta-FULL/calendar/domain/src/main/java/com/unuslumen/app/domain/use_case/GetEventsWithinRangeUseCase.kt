// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
