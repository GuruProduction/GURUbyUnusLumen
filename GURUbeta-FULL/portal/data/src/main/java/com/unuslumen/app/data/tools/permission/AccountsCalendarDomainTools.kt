// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The calendar and accounts domain: the device's own calendars, the events in them, and the
 * accounts registered on the phone.
 *
 * WHY THE CALENDAR IS A SEPARATE STORE AND SAYS SO. GURU keeps its own event list, and so
 * does Android. They are different databases with different ids, and conflating them is a
 * real bug this project has already had: a tool that writes to its own list and reports the
 * event as though the person's Google Calendar now holds it. Every result below names the
 * device store explicitly.
 *
 * THE WRITE RULE. After any insert or update the row is read back and the values the store
 * actually holds are reported, not the values that were sent. A calendar write can be
 * silently adjusted by the provider, and a report of the intention would hide that.
 *
 * WHAT IS NOT HERE, deliberately. There is no tool that prints an auth token. AccountManager
 * can return one, and a token in a conversation is a credential escaping the device, so
 * anything touching a token reports only whether one came back.
 */

@Serializable
data class CalendarEntry(
    val id: Long,
    val displayName: String? = null,
    val accountName: String? = null,
    val ownerAccount: String? = null,
    val colour: Int? = null,
    val visible: Boolean = false,
)

@Serializable
data class CalendarEventEntry(
    val id: Long,
    val calendarId: Long,
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val beginMillis: Long? = null,
    val endMillis: Long? = null,
    val allDay: Boolean = false,
)

@Serializable
data class CalendarReadResult(
    val success: Boolean,
    val calendars: List<CalendarEntry> = emptyList(),
    val events: List<CalendarEventEntry> = emptyList(),
    val calendarCount: Int = 0,
    val eventCount: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class CalendarWriteResult(
    val success: Boolean,
    val action: String,
    val eventId: Long? = null,
    /** The row as the store holds it afterwards, read back rather than echoed. */
    val stored: CalendarEventEntry? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class AccountActionResult(
    val success: Boolean,
    val action: String,
    val accountName: String? = null,
    val accountType: String? = null,
    /** Whether a sign-in flow was handed to the platform rather than completed here. */
    val handedToSystemFlow: Boolean? = null,
    /** Whether the account list confirms the change afterwards. */
    val verifiedGone: Boolean? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object AccountsCalendarDomainDefinitions : ToolSetRegistration {
    const val CALENDAR_READ = "calendarRead"
    const val CALENDAR_WRITE = "calendarWrite"
    const val ACCOUNTS_MANAGE = "accountsManage"

    override val definitions = listOf(
        ToolDefinition(
            name = CALENDAR_READ,
            description = "Read the phone's own calendars and the events in a date range. This is " +
                "the DEVICE calendar store, which is a different database from GURU's own event " +
                "list: the ids here are Android's, and an event read here is one your other apps " +
                "can also see. Returns the calendar list first with each calendar's account and " +
                "colour, then the events inside the window you asked for.",
            category = "calendar",
            parameters = listOf(
                ToolParameter(
                    "calendarId", ToolParameterType.Long, false,
                    "One calendar's id to read. Omit or pass 0 for every calendar."
                ),
                ToolParameter(
                    "fromMillis", ToolParameterType.Long, false,
                    "Start of the window, epoch milliseconds. Default now."
                ),
                ToolParameter(
                    "toMillis", ToolParameterType.Long, false,
                    "End of the window, epoch milliseconds. Default thirty days after the start."
                ),
                ToolParameter("limit", ToolParameterType.Integer, false, "1 to 1000. Default 100."),
                ToolParameter(
                    "query", ToolParameterType.String, false,
                    "Case-insensitive partial match on the event title."
                ),
            ),
            permissions = listOf(TeamThreePermissions.READ_CALENDAR.permission),
        ),
        ToolDefinition(
            name = CALENDAR_WRITE,
            description = "Create, change or delete an event in the phone's own calendar store. It " +
                "reads the row back afterwards and reports what the store holds, because a provider " +
                "can adjust a value silently and a report of what was sent would hide that. Deleting " +
                "refuses without confirm=true, since a removed event is not recoverable from the " +
                "phone. This writes to the calendar your other apps read, not to GURU's own list.",
            category = "calendar",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true, "What to do.",
                    listOf("CREATE", "UPDATE", "DELETE"),
                ),
                ToolParameter("eventId", ToolParameterType.Long, false, "Required for UPDATE and DELETE."),
                ToolParameter("calendarId", ToolParameterType.Long, false, "Required for CREATE."),
                ToolParameter("title", ToolParameterType.String, false, "Required for CREATE."),
                ToolParameter("description", ToolParameterType.String, false, "The event body."),
                ToolParameter("location", ToolParameterType.String, false, "Where the event happens."),
                ToolParameter("beginMillis", ToolParameterType.Long, false, "Required for CREATE."),
                ToolParameter("endMillis", ToolParameterType.Long, false, "Required for CREATE."),
                ToolParameter("allDay", ToolParameterType.Boolean, false, "Default false."),
                ToolParameter(
                    "confirm", ToolParameterType.Boolean, false,
                    "Must be true to DELETE. Anything else refuses without removing anything."
                ),
            ),
            permissions = listOf(
                TeamThreePermissions.READ_CALENDAR.permission,
                TeamThreePermissions.WRITE_CALENDAR.permission,
            ),
        ),
        ToolDefinition(
            name = ACCOUNTS_MANAGE,
            description = "Add or remove an account on the phone. Adding hands the provider's own " +
                "sign-in screen to the person, because GURU holds no authenticator for that provider " +
                "and cannot complete a sign-in on its behalf: the result says so rather than implying " +
                "it did. Removing verifies afterwards that the account is genuinely gone by re-reading " +
                "the account list. It never returns an auth token, because a credential does not " +
                "belong in a conversation.",
            category = "accounts",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true, "Add an account or remove one.",
                    listOf("ADD", "REMOVE"),
                ),
                ToolParameter(
                    "accountType", ToolParameterType.String, false,
                    "The account type such as com.google, required for ADD."
                ),
                ToolParameter("accountName", ToolParameterType.String, true, "The account name."),
            ),
            permissions = listOf(
                TeamThreePermissions.MANAGE_ACCOUNTS.permission,
                TeamThreePermissions.GET_ACCOUNTS.permission,
            ),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = AccountsCalendarDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class AccountsCalendarDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            AccountsCalendarDomainDefinitions.CALENDAR_READ -> calendarRead(args)
            AccountsCalendarDomainDefinitions.CALENDAR_WRITE -> calendarWrite(args)
            AccountsCalendarDomainDefinitions.ACCOUNTS_MANAGE -> accountsManage(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Calendar
    // ------------------------------------------------------------------

    private suspend fun calendarRead(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CALENDAR)?.let { return it }

        val calendarId = (args["calendarId"] as? Number)?.toLong()?.takeIf { it > 0L }
        val fromMillis = (args["fromMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val toMillis = (args["toMillis"] as? Number)?.toLong() ?: (fromMillis + 30L * 24L * 60L * 60L * 1000L)
        val limit = ((args["limit"] as? Number)?.toInt() ?: 100).coerceIn(1, 1000)
        val query = (args["query"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        // The event window is checked before any query, because a window that runs backwards
        // returns nothing and an empty list would be indistinguishable from an empty calendar.
        if (toMillis <= fromMillis) {
            return failure(
                CalendarReadResult.serializer(),
                CalendarReadResult(
                    success = false,
                    error = "The window ends before it starts: fromMillis $fromMillis is not before " +
                        "toMillis $toMillis. Nothing was queried, which is different from finding " +
                        "no events.",
                ),
            )
        }

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val calendars = mutableListOf<CalendarEntry>()
                val calendarCursor = context.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Calendars.ACCOUNT_NAME,
                        CalendarContract.Calendars.OWNER_ACCOUNT,
                        CalendarContract.Calendars.CALENDAR_COLOR,
                        CalendarContract.Calendars.VISIBLE,
                    ),
                    if (calendarId != null) "${CalendarContract.Calendars._ID} = ?" else null,
                    calendarId?.let { arrayOf(it.toString()) },
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
                )

                calendarCursor?.use { c ->
                    while (c.moveToNext()) {
                        calendars.add(
                            CalendarEntry(
                                id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)),
                                displayName = c.getString(
                                    c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                                ),
                                accountName = c.getString(
                                    c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                                ),
                                ownerAccount = c.getString(
                                    c.getColumnIndexOrThrow(CalendarContract.Calendars.OWNER_ACCOUNT)
                                ),
                                colour = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)),
                                visible = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)) == 1,
                            )
                        )
                    }
                }

                val selectionParts = mutableListOf(
                    "${CalendarContract.Events.DTSTART} >= ?",
                    "${CalendarContract.Events.DTSTART} <= ?",
                )
                val selectionArgs = mutableListOf(fromMillis.toString(), toMillis.toString())
                calendarId?.let {
                    selectionParts.add("${CalendarContract.Events.CALENDAR_ID} = ?")
                    selectionArgs.add(it.toString())
                }
                query?.let {
                    selectionParts.add("(${CalendarContract.Events.TITLE} LIKE ?)")
                    selectionArgs.add("%$it%")
                }

                val events = mutableListOf<CalendarEventEntry>()
                val eventCursor = context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events.CALENDAR_ID,
                        CalendarContract.Events.TITLE,
                        CalendarContract.Events.DESCRIPTION,
                        CalendarContract.Events.EVENT_LOCATION,
                        CalendarContract.Events.DTSTART,
                        CalendarContract.Events.DTEND,
                        CalendarContract.Events.ALL_DAY,
                    ),
                    selectionParts.joinToString(" AND "),
                    selectionArgs.toTypedArray(),
                    "${CalendarContract.Events.DTSTART} ASC",
                )

                eventCursor?.use { c ->
                    while (c.moveToNext() && events.size < limit) {
                        events.add(
                            CalendarEventEntry(
                                id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events._ID)),
                                calendarId = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)),
                                title = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)),
                                description = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)),
                                location = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)),
                                beginMillis = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)),
                                endMillis = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.DTEND)),
                                allDay = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) == 1,
                            )
                        )
                    }
                }

                calendars to events
            }.getOrNull()
        }

        if (outcome == null) {
            return failure(
                CalendarReadResult.serializer(),
                CalendarReadResult(
                    success = false,
                    error = "The calendar provider did not answer the query at all, which is a provider " +
                        "that refused rather than an empty calendar.",
                ),
            )
        }

        val (calendars, events) = outcome
        val r = CalendarReadResult(
            success = true,
            calendars = calendars,
            events = events,
            calendarCount = calendars.size,
            eventCount = events.size,
            note = buildString {
                append("Read from the DEVICE calendar store, which is a different database from GURU's ")
                append("own event list: these ids are Android's, and other apps can see the same ")
                append("events. ")
                append("The window was $fromMillis to $toMillis. ")
                if (events.isEmpty()) {
                    append("No event starts inside that window, which is the window you asked for ")
                    append("rather than an empty calendar.")
                }
            }.trim(),
        )
        return emit(json, CalendarReadResult.serializer(), r)
    }

    private suspend fun calendarWrite(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CALENDAR)?.let { return it }
        PermissionGrants.guard(context, TeamThreePermissions.WRITE_CALENDAR)?.let { return it }

        val action = ((args["action"] as? String) ?: "").uppercase()

        return when (action) {
            "CREATE" -> {
                val calendarId = (args["calendarId"] as? Number)?.toLong()
                    ?: return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            error = "CREATE needs 'calendarId' as a number. Read the calendar list " +
                                "first to find one.",
                        ),
                    )
                val title = (args["title"] as? String)?.trim()
                if (title.isNullOrBlank()) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            error = "CREATE needs a non-blank 'title'.",
                        ),
                    )
                }
                val begin = (args["beginMillis"] as? Number)?.toLong()
                    ?: return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            error = "CREATE needs 'beginMillis' as a number.",
                        ),
                    )
                val end = (args["endMillis"] as? Number)?.toLong() ?: begin
                val allDay = args["allDay"] as? Boolean ?: false

                if (end < begin) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            error = "The event ends before it starts, which no calendar will store. " +
                                "Nothing was created.",
                        ),
                    )
                }

                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, begin)
                    put(CalendarContract.Events.DTEND, end)
                    put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                    (args["description"] as? String)?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                    (args["location"] as? String)?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    // A time zone is required by the calendar schema for a non-all-day event, and
                    // an absent one is a silent insert failure rather than an error.
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                }

                val inserted = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    }.getOrNull()
                }

                if (inserted == null) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            error = "The calendar provider refused the insert, so nothing was created.",
                        ),
                    )
                }

                val newId = runCatching { ContentUris.parseId(inserted) }.getOrNull()
                val stored = withContext(Dispatchers.IO) { readEvent(newId) }

                val r = CalendarWriteResult(
                    success = true,
                    action = action,
                    eventId = newId,
                    stored = stored,
                    note = if (stored == null) {
                        "The provider accepted the insert but the row could not be read back, so the " +
                            "event's stored values are unverified."
                    } else {
                        "Created in the device calendar store and read back. The values above are what " +
                            "the store holds, which a provider may have adjusted."
                    },
                )
                emit(json, CalendarWriteResult.serializer(), r)
            }

            "UPDATE" -> {
                val eventId = (args["eventId"] as? Number)?.toLong()
                    ?: return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            error = "UPDATE needs 'eventId' as a number.",
                        ),
                    )

                val values = ContentValues()
                (args["title"] as? String)?.let { values.put(CalendarContract.Events.TITLE, it) }
                (args["description"] as? String)?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
                (args["location"] as? String)?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
                (args["beginMillis"] as? Number)?.let { values.put(CalendarContract.Events.DTSTART, it.toLong()) }
                (args["endMillis"] as? Number)?.let { values.put(CalendarContract.Events.DTEND, it.toLong()) }
                (args["allDay"] as? Boolean)?.let { values.put(CalendarContract.Events.ALL_DAY, if (it) 1 else 0) }

                if (values.size() == 0) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            eventId = eventId,
                            error = "UPDATE needs at least one field to change. Nothing was written.",
                        ),
                    )
                }

                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val updated = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.update(uri, values, null, null) }.getOrNull()
                }

                if (updated == null) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            eventId = eventId,
                            error = "The calendar provider refused the update.",
                        ),
                    )
                }
                if (updated == 0) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            eventId = eventId,
                            error = "No event with id $eventId was changed, so either it does not " +
                                "exist or the values already matched.",
                        ),
                    )
                }

                val stored = withContext(Dispatchers.IO) { readEvent(eventId) }
                val r = CalendarWriteResult(
                    success = true,
                    action = action,
                    eventId = eventId,
                    stored = stored,
                    note = "Changed $updated row(s) and read the event back. The values above are what " +
                        "the store holds, not what was sent.",
                )
                emit(json, CalendarWriteResult.serializer(), r)
            }

            "DELETE" -> {
                val eventId = (args["eventId"] as? Number)?.toLong()
                    ?: return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            error = "DELETE needs 'eventId' as a number.",
                        ),
                    )
                if (args["confirm"] != true) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            eventId = eventId,
                            error = "Nothing was deleted. A removed event cannot be recovered from the " +
                                "phone, so this needs confirm=true.",
                        ),
                    )
                }

                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val deleted = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.delete(uri, null, null) }.getOrNull()
                }

                if (deleted == null || deleted == 0) {
                    return failure(
                        CalendarWriteResult.serializer(),
                        CalendarWriteResult(
                            success = false,
                            action = action,
                            eventId = eventId,
                            error = "No event with id $eventId was deleted, so either it does not exist " +
                                "or the provider refused.",
                        ),
                    )
                }

                val r = CalendarWriteResult(
                    success = true,
                    action = action,
                    eventId = eventId,
                    note = "Deleted $deleted row(s) from the device calendar store. It is not " +
                        "recoverable from the phone.",
                )
                emit(json, CalendarWriteResult.serializer(), r)
            }

            else -> failure(
                CalendarWriteResult.serializer(),
                CalendarWriteResult(
                    success = false,
                    action = action,
                    error = "The action must be CREATE, UPDATE or DELETE, and '$action' is none of them.",
                ),
            )
        }
    }

    private fun readEvent(eventId: Long?): CalendarEventEntry? {
        if (eventId == null) return null
        val cursor = runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                ),
                null, null, null,
            )
        }.getOrNull() ?: return null

        cursor.use { c ->
            if (c.moveToFirst()) {
                return CalendarEventEntry(
                    id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events._ID)),
                    calendarId = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)),
                    title = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)),
                    description = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)),
                    location = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)),
                    beginMillis = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)),
                    endMillis = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.DTEND)),
                    allDay = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) == 1,
                )
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    private suspend fun accountsManage(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.MANAGE_ACCOUNTS)?.let { return it }
        PermissionGrants.guard(context, TeamThreePermissions.GET_ACCOUNTS)?.let { return it }

        val action = ((args["action"] as? String) ?: "").uppercase()
        val accountName = (args["accountName"] as? String)?.trim()
        val accountType = (args["accountType"] as? String)?.trim()

        if (accountName.isNullOrBlank()) {
            return failure(
                AccountActionResult.serializer(),
                AccountActionResult(
                    success = false,
                    action = action,
                    error = "This call needs 'accountName'.",
                ),
            )
        }

        val manager = AccountManager.get(context)

        return when (action) {
            "ADD" -> {
                if (accountType.isNullOrBlank()) {
                    return failure(
                        AccountActionResult.serializer(),
                        AccountActionResult(
                            success = false,
                            action = action,
                            accountName = accountName,
                            error = "ADD needs 'accountType', for example com.google.",
                        ),
                    )
                }

                // addAccount launches the provider's own sign-in screen. GURU holds no
                // authenticator for another provider, so it cannot complete that sign-in and
                // must not imply it did.
                val started = runCatching {
                    manager.addAccount(accountType, null, null, null, null, null, null)
                    true
                }.getOrDefault(false)

                if (!started) {
                    failure(
                        AccountActionResult.serializer(),
                        AccountActionResult(
                            success = false,
                            action = action,
                            accountName = accountName,
                            accountType = accountType,
                            handedToSystemFlow = false,
                            error = "No account manager on this device handles the type $accountType, " +
                                "so no sign-in screen could be opened.",
                        ),
                    )
                } else {
                    emit(
                        json,
                        AccountActionResult.serializer(),
                        AccountActionResult(
                            success = true,
                            action = action,
                            accountName = accountName,
                            accountType = accountType,
                            handedToSystemFlow = true,
                            note = "The provider's own sign-in screen has been handed to the person. " +
                                "GURU cannot complete a sign-in for another provider because it holds " +
                                "no authenticator for one, so this reports the handover and not a " +
                                "completed add. Re-read the account list to see whether it landed.",
                        ),
                    )
                }
            }

            "REMOVE" -> {
                val account = Account(accountName, accountType.orEmpty())
                val known = runCatching { manager.accounts.any { it.name == accountName } }
                    .getOrDefault(false)

                if (!known) {
                    return failure(
                        AccountActionResult.serializer(),
                        AccountActionResult(
                            success = false,
                            action = action,
                            accountName = accountName,
                            accountType = accountType,
                            verifiedGone = null,
                            error = "No account named $accountName is registered on this device.",
                        ),
                    )
                }

                val removed = withContext(Dispatchers.IO) {
                    runCatching { manager.removeAccountExplicitly(account) }.getOrDefault(false)
                }

                // Re-list rather than trusting the return value: the platform can accept the
                // call and still leave the account behind.
                val gone = withContext(Dispatchers.IO) {
                    runCatching { manager.accounts.none { it.name == accountName } }.getOrNull()
                }

                val r = AccountActionResult(
                    success = removed && gone == true,
                    action = action,
                    accountName = accountName,
                    accountType = accountType,
                    verifiedGone = gone,
                    note = when {
                        gone == true ->
                            "The account was removed and the account list confirms it is gone."
                        gone == false ->
                            "The removal was accepted but the account is still listed, so it did not " +
                                "take effect. Some accounts can only be removed by their own app."
                        else ->
                            "The account list could not be re-read, so whether it is gone is unverified " +
                                "rather than confirmed."
                    },
                    error = if (removed && gone == true) null else {
                        "The account could not be confirmed as removed."
                    },
                )
                emit(json, AccountActionResult.serializer(), r)
            }

            else -> failure(
                AccountActionResult.serializer(),
                AccountActionResult(
                    success = false,
                    action = action,
                    accountName = accountName,
                    error = "The action must be ADD or REMOVE, and '$action' is neither.",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Emit helpers
    // ------------------------------------------------------------------

    private fun <T : ToolResultData> emit(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = when (payload) {
            is CalendarReadResult -> payload.success
            is CalendarWriteResult -> payload.success
            is AccountActionResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is CalendarReadResult -> payload.error
            is CalendarWriteResult -> payload.error
            is AccountActionResult -> payload.error
            else -> null
        },
    )

    private fun <T : ToolResultData> failure(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = when (payload) {
            is CalendarReadResult -> payload.error
            is CalendarWriteResult -> payload.error
            is AccountActionResult -> payload.error
            else -> null
        },
    )
}

object AccountsCalendarDomainModule : DomainToolModule {
    override val id: String = "accounts_calendar"

    override val registrations: List<ToolSetRegistration> = listOf(AccountsCalendarDomainDefinitions)

    override fun bindings(): Module = module {
        factory { AccountsCalendarDomainExecutor(get()) }
    }
}
