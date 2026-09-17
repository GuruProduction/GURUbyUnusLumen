package com.unuslumen.app.domain.model

import kotlinx.datetime.LocalDate

data class CalendarDay(
    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val events: List<CalendarEvent>
)

