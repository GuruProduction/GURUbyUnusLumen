// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable

@Serializable
data class CalendarEvent(
    val id: Long,
    val title: String,
    val description: String? = null,
    val start: Long,
    val end: Long,
    val location: String? = null,
    val allDay: Boolean = false,
    val color: Int = 0,
    val calendarId: Long,
    val recurring: Boolean = false,
    val frequency: CalendarEventFrequency = CalendarEventFrequency.NEVER,
    val interval: Int = 1,
    val weekDays: Set<DayOfWeek> = emptySet(),
    val instanceDay: Long? = null,
)

enum class CalendarEventFrequency {
    NEVER,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
