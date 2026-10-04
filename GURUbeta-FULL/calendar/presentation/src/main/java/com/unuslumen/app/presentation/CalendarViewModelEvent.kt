// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.Calendar
import kotlinx.datetime.LocalDate

sealed class CalendarViewModelEvent {
    data class IncludeCalendar(val calendar: Calendar) : CalendarViewModelEvent()
    data class ReadPermissionChanged(val hasPermission: Boolean) : CalendarViewModelEvent()
    data class ViewModeChanged(val isMonthView: Boolean) : CalendarViewModelEvent()
    data class MonthChanged(val newMonth: LocalDate) : CalendarViewModelEvent()
    data class SelectedDateChanged(val newDate: LocalDate) : CalendarViewModelEvent()
}
