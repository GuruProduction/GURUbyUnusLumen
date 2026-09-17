package com.unuslumen.app.data.tools

import com.unuslumen.app.data.llmDateTimeFormatUnicode
import com.unuslumen.app.data.parseDateTimeFromLLM
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.CalendarEventFrequency
import com.unuslumen.app.domain.use_case.AddCalendarEventUseCase
import com.unuslumen.app.domain.use_case.DeleteCalendarEventUseCase
import com.unuslumen.app.domain.use_case.GetAllCalendarsUseCase
import com.unuslumen.app.domain.use_case.GetCalendarEventByIdUseCase
import com.unuslumen.app.domain.use_case.GetEventsWithinRangeUseCase
import com.unuslumen.app.domain.use_case.GetMonthEventsUseCase
import com.unuslumen.app.domain.use_case.SearchEventsByTitleWithinRangeUseCase
import com.unuslumen.app.domain.use_case.UpdateCalendarEventUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringSetPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.YearMonth
import kotlinx.serialization.json.Json
import java.util.Locale

class CalendarToolExecutor(
    private val getEventsWithinRangeUseCase: GetEventsWithinRangeUseCase,
    private val searchEventsByTitleWithinRangeUseCase: SearchEventsByTitleWithinRangeUseCase,
    private val addCalendarEvent: AddCalendarEventUseCase,
    private val getAllCalendarsUseCase: GetAllCalendarsUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val updateEventUseCase: UpdateCalendarEventUseCase,
    private val deleteEventUseCase: DeleteCalendarEventUseCase,
    private val getEventByIdUseCase: GetCalendarEventByIdUseCase,
    private val getMonthEventsUseCase: GetMonthEventsUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getExcludedCalendars(): List<Int> {
        return getPreference(stringSetPreferencesKey(PrefsConstants.EXCLUDED_CALENDARS_KEY), emptySet()).firstOrNull().orEmpty().mapNotNull { it.toIntOrNull() }
    }

    private fun String.toDayOfWeekOrNull(): DayOfWeek? = when (trim().uppercase(Locale.US)) {
        "MONDAY", "MON", "MO" -> DayOfWeek.MONDAY; "TUESDAY", "TUE", "TU" -> DayOfWeek.TUESDAY
        "WEDNESDAY", "WED", "WE" -> DayOfWeek.WEDNESDAY; "THURSDAY", "THU", "TH" -> DayOfWeek.THURSDAY
        "FRIDAY", "FRI", "FR" -> DayOfWeek.FRIDAY; "SATURDAY", "SAT", "SA" -> DayOfWeek.SATURDAY
        "SUNDAY", "SUN", "SU" -> DayOfWeek.SUNDAY; else -> null
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        CalendarToolDefinitions.GET_EVENTS_WITHIN_RANGE -> { val start = (args["startDateTime"] as? String)?.parseDateTimeFromLLM() ?: return ToolExecutionResult.error("Invalid start date"); val end = (args["endDateTime"] as? String)?.parseDateTimeFromLLM() ?: return ToolExecutionResult.error("Invalid end date"); val r = GetEventsResult(getEventsWithinRangeUseCase(start, end, getExcludedCalendars())); ToolExecutionResult.success(r, json.encodeToString(GetEventsResult.serializer(), r)) }
        CalendarToolDefinitions.SEARCH_EVENTS_BY_NAME -> { val name = args["eventName"] as? String ?: return ToolExecutionResult.error("Missing 'eventName'"); val start = (args["startDateTime"] as? String)?.parseDateTimeFromLLM() ?: return ToolExecutionResult.error("Invalid start"); val end = (args["endDateTime"] as? String)?.parseDateTimeFromLLM() ?: return ToolExecutionResult.error("Invalid end"); val r = SearchEventsResult(searchEventsByTitleWithinRangeUseCase(start, end, name.trim(), getExcludedCalendars())); ToolExecutionResult.success(r, json.encodeToString(SearchEventsResult.serializer(), r)) }
        CalendarToolDefinitions.CREATE_EVENT -> { val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'"); val start = (args["start"] as? String)?.parseDateTimeFromLLM() ?: return ToolExecutionResult.error("Invalid start"); val end = (args["end"] as? String)?.parseDateTimeFromLLM() ?: return ToolExecutionResult.error("Invalid end"); val calId = (args["calendarId"] as? Number)?.toLong() ?: return ToolExecutionResult.error("Missing 'calendarId'"); val event = CalendarEvent(id = 0, title = title, description = args["description"] as? String, start = start, end = end, location = args["location"] as? String, allDay = (args["allDay"] as? Boolean) ?: false, calendarId = calId, recurring = (args["recurring"] as? Boolean) ?: false, frequency = CalendarEventFrequency.NEVER, interval = 1, weekDays = emptySet()); val r = CalendarEventIdResult(addCalendarEvent(event)); ToolExecutionResult.success(r, json.encodeToString(CalendarEventIdResult.serializer(), r)) }
        CalendarToolDefinitions.CREATE_EVENTS -> { val eventsStr = args["events"] as? String ?: return ToolExecutionResult.error("Missing 'events'"); return ToolExecutionResult.error("Batch events not yet implemented in executor — use createEvent individually") }
        CalendarToolDefinitions.GET_ALL_CALENDARS -> { val r = GetCalendarsResult(getAllCalendarsUseCase(getExcludedCalendars())); ToolExecutionResult.success(r, json.encodeToString(GetCalendarsResult.serializer(), r)) }
        CalendarToolDefinitions.UPDATE_EVENT -> { val id = (args["id"] as? Number)?.toLong() ?: return ToolExecutionResult.error("Missing 'id'"); val existing = getEventByIdUseCase(id) ?: return ToolExecutionResult.error("Not found: $id"); val updated = existing.copy(title = (args["title"] as? String) ?: existing.title, start = (args["start"] as? String)?.parseDateTimeFromLLM() ?: existing.start, end = (args["end"] as? String)?.parseDateTimeFromLLM() ?: existing.end, description = args["description"] as? String ?: existing.description, location = args["location"] as? String ?: existing.location); updateEventUseCase(updated); val r = CalendarEventResult(updated); ToolExecutionResult.success(r, json.encodeToString(CalendarEventResult.serializer(), r)) }
        CalendarToolDefinitions.DELETE_EVENT -> { val id = (args["id"] as? Number)?.toLong() ?: return ToolExecutionResult.error("Missing 'id'"); val event = getEventByIdUseCase(id) ?: return ToolExecutionResult.error("Not found: $id"); deleteEventUseCase(event); val r = DeleteCalendarEventResult(id); ToolExecutionResult.success(r, json.encodeToString(DeleteCalendarEventResult.serializer(), r)) }
        CalendarToolDefinitions.GET_EVENT_BY_ID -> { val id = (args["id"] as? Number)?.toLong() ?: return ToolExecutionResult.error("Missing 'id'"); val event = getEventByIdUseCase(id) ?: return ToolExecutionResult.error("Not found: $id"); val r = CalendarEventResult(event); ToolExecutionResult.success(r, json.encodeToString(CalendarEventResult.serializer(), r)) }
        CalendarToolDefinitions.GET_MONTH_EVENTS -> { val month = (args["month"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'month'"); val year = (args["year"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'year'"); val days = getMonthEventsUseCase(YearMonth(year, month), getExcludedCalendars()); val events = days.flatMap { it.events }; val r = GetMonthEventsResult(events); ToolExecutionResult.success(r, json.encodeToString(GetMonthEventsResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }
}