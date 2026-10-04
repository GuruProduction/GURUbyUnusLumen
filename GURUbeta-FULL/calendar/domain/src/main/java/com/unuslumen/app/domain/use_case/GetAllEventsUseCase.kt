// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.repository.CalendarRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class GetAllEventsUseCase(
    private val calendarRepository: CalendarRepository,
    @Named("defaultDispatcher") private val defaultDispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(
        excluded: List<Int>,
        until: Long? = null,
        fromWidget: Boolean = false,
        groupBySelector: (CalendarEvent) -> String
    ): Map<String, List<CalendarEvent>> {
        return withContext(defaultDispatcher) {
            try {
                calendarRepository.getEvents(excluded, until)
                    .run {
                        if (fromWidget) take(25).groupBy(groupBySelector)
                        else groupBy(groupBySelector)
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyMap()
            }
        }
    }
}