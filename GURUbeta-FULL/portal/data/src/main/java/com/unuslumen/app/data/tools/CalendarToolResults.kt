package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.Calendar
import com.unuslumen.app.domain.model.CalendarEvent
import kotlinx.serialization.Serializable

@Serializable data class GetEventsResult(val events: List<CalendarEvent>) : ToolResultData
@Serializable data class SearchEventsResult(val events: List<CalendarEvent>) : ToolResultData
@Serializable data class GetCalendarsResult(val calendars: Map<String, List<Calendar>>) : ToolResultData
@Serializable data class CalendarEventIdResult(val createdEventId: Long?) : ToolResultData
@Serializable data class CalendarEventIdsResult(val createdEventIds: List<Long?>) : ToolResultData
@Serializable data class CalendarEventResult(val event: CalendarEvent) : ToolResultData
@Serializable data class DeleteCalendarEventResult(val deletedEventId: Long) : ToolResultData
@Serializable data class GetMonthEventsResult(val events: List<CalendarEvent>) : ToolResultData