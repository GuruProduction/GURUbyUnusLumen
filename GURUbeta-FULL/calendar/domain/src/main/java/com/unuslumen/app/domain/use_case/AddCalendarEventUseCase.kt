package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.repository.CalendarRepository
import com.unuslumen.app.widget.WidgetUpdater
import org.koin.core.annotation.Single

@Single
class AddCalendarEventUseCase(
    private val calendarEventRepository: CalendarRepository,
    private val widgetUpdater: WidgetUpdater
) {
    suspend operator fun invoke(calendarEvent: CalendarEvent): Long? {
        val calendars = calendarEventRepository.getCalendars()
        val id = if (calendars.isNotEmpty()) {
            calendarEventRepository.addEvent(calendarEvent)
        } else {
            calendarEventRepository.createCalendar()
            val calendar = calendarEventRepository.getCalendars().first()
            calendarEventRepository.addEvent(calendarEvent.copy(calendarId = calendar.id))
        }
        widgetUpdater.updateAll(WidgetUpdater.WidgetType.Calendar)
        return id
    }

}