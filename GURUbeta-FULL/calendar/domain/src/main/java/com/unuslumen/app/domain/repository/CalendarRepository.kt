// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.Calendar
import com.unuslumen.app.domain.model.CalendarEvent

interface CalendarRepository {

    suspend fun getEvents(excludedCalendars: List<Int> = emptyList(), until: Long? = null): List<CalendarEvent>

    suspend fun getEvents(start: Long, end: Long, excludedCalendars: List<Int> = emptyList()): List<CalendarEvent>

    suspend fun searchEventsByTitleWithinRange(
        start: Long,
        end: Long,
        titleQuery: String,
        excludedCalendars: List<Int> = emptyList()
    ): List<CalendarEvent>

    suspend fun getCalendars(): List<Calendar>

    suspend fun getEventById(id: Long): CalendarEvent?

    suspend fun addEvent(event: CalendarEvent): Long?

    suspend fun deleteEvent(event: CalendarEvent)

    suspend fun updateEvent(event: CalendarEvent)

    suspend fun createCalendar()
}