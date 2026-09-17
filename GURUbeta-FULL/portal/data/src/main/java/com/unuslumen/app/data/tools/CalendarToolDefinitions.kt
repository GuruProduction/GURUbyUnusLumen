package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object CalendarToolDefinitions : ToolSetRegistration {
    const val GET_EVENTS_WITHIN_RANGE = "getEventsWithinRange"; const val SEARCH_EVENTS_BY_NAME = "searchEventsByNameWithinRange"
    const val CREATE_EVENT = "createEvent"; const val CREATE_EVENTS = "createEvents"; const val GET_ALL_CALENDARS = "getAllCalendars"
    const val UPDATE_EVENT = "updateEvent"; const val DELETE_EVENT = "deleteEvent"; const val GET_EVENT_BY_ID = "getEventById"; const val GET_MONTH_EVENTS = "getMonthEvents"

    /** Android runtime permission strings this tool set requires.
     *  Checked by ToolDispatcher before execution; a missing grant makes the
     *  dispatcher return PERMISSION_DENIED so the UI can prompt the user. */
    private const val READ_CALENDAR = "android.permission.READ_CALENDAR"
    private const val WRITE_CALENDAR = "android.permission.WRITE_CALENDAR"

    override val definitions = listOf(
        ToolDefinition(name = GET_EVENTS_WITHIN_RANGE, description = "Get events within date range.", category = "calendar", parameters = listOf(ToolParameter("startDateTime", ToolParameterType.String, true, "Format: HH:mm dd-MM-yyyy"), ToolParameter("endDateTime", ToolParameterType.String, true, "Format: HH:mm dd-MM-yyyy")), permissions = listOf(READ_CALENDAR)),
        ToolDefinition(name = SEARCH_EVENTS_BY_NAME, description = "Search for an event name within a date range.", category = "calendar", parameters = listOf(ToolParameter("eventName", ToolParameterType.String, true, "Event name or partial"), ToolParameter("startDateTime", ToolParameterType.String, true, "Format: HH:mm dd-MM-yyyy"), ToolParameter("endDateTime", ToolParameterType.String, true, "Format: HH:mm dd-MM-yyyy")), permissions = listOf(READ_CALENDAR)),
        ToolDefinition(name = CREATE_EVENT, description = "Create event. Returns ID.", category = "calendar", parameters = listOf(ToolParameter("title", ToolParameterType.String, true, "Event title"), ToolParameter("start", ToolParameterType.String, true, "Format: HH:mm dd-MM-yyyy"), ToolParameter("end", ToolParameterType.String, true, "Format: HH:mm dd-MM-yyyy"), ToolParameter("calendarId", ToolParameterType.Integer, true, "Use getAllCalendars to get ID"), ToolParameter("description", ToolParameterType.String, false, "Description"), ToolParameter("location", ToolParameterType.String, false, "Location"), ToolParameter("allDay", ToolParameterType.Boolean, false, "All day"), ToolParameter("recurring", ToolParameterType.Boolean, false, "Recurring")), permissions = listOf(WRITE_CALENDAR)),
        ToolDefinition(name = CREATE_EVENTS, description = "Create multiple events. Returns IDs.", category = "calendar", parameters = listOf(ToolParameter("events", ToolParameterType.String, true, "JSON array of event inputs")), permissions = listOf(WRITE_CALENDAR)),
        ToolDefinition(name = GET_ALL_CALENDARS, description = "Get all calendars (grouped by account).", category = "calendar", parameters = emptyList(), permissions = listOf(READ_CALENDAR)),
        ToolDefinition(name = UPDATE_EVENT, description = "Update an existing calendar event. Only the fields you provide will be changed.", category = "calendar", parameters = listOf(ToolParameter("id", ToolParameterType.Integer, true, "Event ID"), ToolParameter("title", ToolParameterType.String, false, "New title"), ToolParameter("start", ToolParameterType.String, false, "New start"), ToolParameter("end", ToolParameterType.String, false, "New end"), ToolParameter("description", ToolParameterType.String, false, "New description"), ToolParameter("location", ToolParameterType.String, false, "New location")), permissions = listOf(WRITE_CALENDAR, READ_CALENDAR)),
        ToolDefinition(name = DELETE_EVENT, description = "Delete a calendar event permanently.", category = "calendar", parameters = listOf(ToolParameter("id", ToolParameterType.Integer, true, "Event ID")), permissions = listOf(WRITE_CALENDAR, READ_CALENDAR)),
        ToolDefinition(name = GET_EVENT_BY_ID, description = "Get a calendar event by its ID.", category = "calendar", parameters = listOf(ToolParameter("id", ToolParameterType.Integer, true, "Event ID")), permissions = listOf(READ_CALENDAR)),
        ToolDefinition(name = GET_MONTH_EVENTS, description = "Get all events for a specific month.", category = "calendar", parameters = listOf(ToolParameter("month", ToolParameterType.Integer, true, "Month number 1-12"), ToolParameter("year", ToolParameterType.Integer, true, "Year")), permissions = listOf(READ_CALENDAR))
    )
    override fun executorClass(): KClass<out ToolExecutor> = CalendarToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}