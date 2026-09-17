package com.unuslumen.app.presentation.model

import com.unuslumen.app.domain.model.CalendarDay

data class CalendarMonth(
    val monthNumber: Int,
    val days: List<CalendarDay>
)

