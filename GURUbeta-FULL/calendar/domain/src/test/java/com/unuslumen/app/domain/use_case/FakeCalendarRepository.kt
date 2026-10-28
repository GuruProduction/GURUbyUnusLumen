// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Calendar
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.repository.CalendarRepository

/**
 * Minimal in-memory CalendarRepository for use-case tests. Deliberately a real
 * fake rather than a MockK mock: the grid tests need honest values coming back,
 * and a fake that answers every call makes a failure unambiguous.
 */
class FakeCalendarRepository(
    private val events: List<CalendarEvent> = emptyList(),
    private val calendars: List<Calendar> = emptyList(),
) : CalendarRepository {

    override suspend fun getEvents(excludedCalendars: List<Int>, until: Long?): List<CalendarEvent> =
        events

    override suspend fun getEvents(
        start: Long,
        end: Long,
        excludedCalendars: List<Int>,
    ): List<CalendarEvent> = events.filter { it.start in start until end }

    override suspend fun searchEventsByTitleWithinRange(
        start: Long,
        end: Long,
        titleQuery: String,
        excludedCalendars: List<Int>,
    ): List<CalendarEvent> = events.filter {
        it.start in start until end && it.title.contains(titleQuery, ignoreCase = true)
    }

    override suspend fun getCalendars(): List<Calendar> = calendars

    override suspend fun getEventById(id: Long): CalendarEvent? = events.find { it.id == id }

    override suspend fun addEvent(event: CalendarEvent): Long? = event.id

    override suspend fun deleteEvent(event: CalendarEvent) = Unit

    override suspend fun updateEvent(event: CalendarEvent) = Unit

    override suspend fun createCalendar() = Unit
}
