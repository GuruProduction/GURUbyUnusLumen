package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.repository.CalendarRepository
import com.unuslumen.app.widget.WidgetUpdater
import org.koin.core.annotation.Single

@Single
class UpdateCalendarEventUseCase(
    private val calendarRepository: CalendarRepository,
    private val widgetUpdater: WidgetUpdater
) {
    suspend operator fun invoke(event: CalendarEvent) {
        calendarRepository.updateEvent(event)
        widgetUpdater.updateAll(WidgetUpdater.WidgetType.Calendar)
    }
}