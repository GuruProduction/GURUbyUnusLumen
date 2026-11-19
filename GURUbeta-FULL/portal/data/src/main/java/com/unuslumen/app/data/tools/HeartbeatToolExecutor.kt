// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.heartbeat.HeartbeatBookingDraft
import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.TimeZone

/**
 * The heartbeat diary tools, over the repository that already exists (issue 488).
 *
 * EVERY WRITE GOES THROUGH THE REPOSITORY'S OWN CONTRACT rather than around it. `book`
 * already refuses a half hour that another booking owns and names the owner, `reschedule`
 * already keeps the past intact, `setEnabled` already drops only the future, and `remove`
 * already keeps what ran. Reimplementing any of that here would be a second contract, and
 * the first time a booking was edited the room and the beat would disagree.
 */
class HeartbeatToolExecutor(
    private val repository: HeartbeatBookingRepository,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            HeartbeatToolDefinitions.LIST_BOOKINGS -> listBookings()
            HeartbeatToolDefinitions.BOOK_HEARTBEAT -> book(args)
            HeartbeatToolDefinitions.UPDATE_HEARTBEAT -> update(args)
            HeartbeatToolDefinitions.SET_HEARTBEAT_ENABLED -> setEnabled(args)
            HeartbeatToolDefinitions.READ_HEARTBEAT_DAY -> readDay(args)
            HeartbeatToolDefinitions.REMOVE_HEARTBEAT -> remove(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun listBookings(): ToolExecutionResult {
        val bookings = repository.bookings()
        val result = HeartbeatBookingListResult(
            total = bookings.size,
            bookings = bookings.map { it.toEntry() },
        )
        return ToolExecutionResult.success(result, json.encodeToString(HeartbeatBookingListResult.serializer(), result))
    }

    /**
     * Book a half hour.
     *
     * The slot snaps to the half hour the requested time falls in, because the alarm
     * chain fires on half hours only and a rule at 14:17 cannot be expressed. The reply
     * names the slot that was actually created rather than the one that was asked for.
     */
    private suspend fun book(args: Map<String, Any?>): ToolExecutionResult {
        val startRaw = (args["startAt"] as? String)?.trim().orEmpty()
        if (startRaw.isEmpty()) {
            return failWrite("Missing 'startAt': give the first occurrence as an ISO date and time.")
        }
        val startMillis = parseIso(startRaw)
            ?: return failWrite(
                "\"$startRaw\" is not a date and time I can read. Use ISO, such as 2026-11-20T09:00.",
            )
        val snapped = HeartbeatBookingDraft.snapToHalfHour(startMillis)
        val endsRaw = (args["endsAt"] as? String)?.trim().orEmpty()
        val endsMillis = if (endsRaw.isEmpty()) 0L else parseIso(endsRaw)
            ?: return failWrite("\"$endsRaw\" is not a date I can read. Use ISO, such as 2026-12-31.")

        val chosenDays = (args["chosenDays"] as? String)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }

        val draft = HeartbeatBookingDraft.build(
            name = args["name"] as? String,
            instruction = args["instruction"] as? String,
            repeatKindName = args["repeatKind"] as? String,
            chosenDays = chosenDays,
            startMillis = snapped,
            endsAtMillis = endsMillis,
            enabled = true,
            combineName = args["combine"] as? String,
            nowMillis = System.currentTimeMillis(),
        )

        return when (draft) {
            is HeartbeatBookingDraft.Result.Invalid -> failWrite(draft.reason)
            is HeartbeatBookingDraft.Result.Ready -> {
                // The repository refuses a half hour another booking owns and names the
                // owner in the failure, so that sentence goes straight back to the caller
                // rather than being replaced with something vaguer.
                val booked = repository.book(draft.booking)
                if (booked.isFailure) {
                    val reason = booked.exceptionOrNull()?.message ?: "the diary refused the booking"
                    return failWrite(reason)
                }
                val result = HeartbeatWriteResult(
                    success = true,
                    id = draft.booking.id,
                    name = draft.booking.name,
                    slot = HeartbeatBookingDraft.describeSlot(snapped),
                    note = if (snapped != startMillis) {
                        "The half hour is ${HeartbeatBookingDraft.describeSlot(snapped)}, because the " +
                            "alarm chain fires on half hours only and the time you gave was snapped to one."
                    } else {
                        ""
                    },
                )
                ToolExecutionResult.success(result, json.encodeToString(HeartbeatWriteResult.serializer(), result))
            }
        }
    }

    /**
     * Change a booking from now forward.
     *
     * `reschedule` takes the instant the change starts from, and every write from this
     * tool passes now: what already happened stays exactly as it was.
     */
    private suspend fun update(args: Map<String, Any?>): ToolExecutionResult {
        val id = (args["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) return failWrite("Missing 'id'.")
        val existing = repository.booking(id)
            ?: return failWrite("No booking with id \"$id\". Call listHeartbeatBookings for the ids this diary holds.")

        val chosenDays = (args["chosenDays"] as? String)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }

        // Every field is optional and an absent one keeps what the booking already holds,
        // so a caller changing the instruction does not have to restate the rule.
        val repeatName = (args["repeatKind"] as? String)?.takeIf { it.isNotBlank() } ?: existing.repeat.name
        val endsRaw = (args["endsAt"] as? String)?.trim()
        val endsMillis = when {
            endsRaw.isNullOrEmpty() -> existing.endsAt
            else -> parseIso(endsRaw) ?: return failWrite("\"$endsRaw\" is not a date I can read.")
        }

        val draft = HeartbeatBookingDraft.build(
            name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: existing.name,
            instruction = (args["instruction"] as? String)?.takeIf { it.isNotBlank() } ?: existing.instruction,
            repeatKindName = repeatName,
            chosenDays = chosenDays ?: existing.chosenDays.toList(),
            startMillis = existing.startsAt,
            endsAtMillis = endsMillis,
            enabled = existing.enabled,
            combineName = existing.combine.name,
            nowMillis = System.currentTimeMillis(),
        )
        return when (draft) {
            is HeartbeatBookingDraft.Result.Invalid -> failWrite(draft.reason)
            is HeartbeatBookingDraft.Result.Ready -> {
                // The id is kept, because a rescheduled booking is the same booking.
                val updated = draft.booking.copy(id = existing.id, colourIndex = existing.colourIndex)
                val applied = repository.reschedule(updated, System.currentTimeMillis())
                if (applied.isFailure) {
                    return failWrite(applied.exceptionOrNull()?.message ?: "the diary refused the change")
                }
                val result = HeartbeatWriteResult(
                    success = true,
                    id = updated.id,
                    name = updated.name,
                    slot = HeartbeatBookingDraft.describeSlot(updated.startsAt),
                    note = "Changed from now forward. Everything that already happened is untouched.",
                )
                ToolExecutionResult.success(result, json.encodeToString(HeartbeatWriteResult.serializer(), result))
            }
        }
    }

    private suspend fun setEnabled(args: Map<String, Any?>): ToolExecutionResult {
        val id = (args["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) return failWrite("Missing 'id'.")
        val enabled = args["enabled"] as? Boolean
            ?: return failWrite("Missing 'enabled': true to turn it on, false to turn it off.")
        val booking = repository.booking(id)
            ?: return failWrite("No booking with id \"$id\".")
        val applied = repository.setEnabled(id, enabled)
        if (applied.isFailure) {
            return failWrite(applied.exceptionOrNull()?.message ?: "the diary refused the change")
        }
        val result = HeartbeatWriteResult(
            success = true,
            id = id,
            name = booking.name,
            slot = HeartbeatBookingDraft.describeSlot(booking.startsAt),
            note = if (enabled) "Turned on." else "Turned off. What has not happened yet is dropped; what did is kept.",
        )
        return ToolExecutionResult.success(result, json.encodeToString(HeartbeatWriteResult.serializer(), result))
    }

    private suspend fun readDay(args: Map<String, Any?>): ToolExecutionResult {
        val dateRaw = (args["date"] as? String)?.trim().orEmpty()
        if (dateRaw.isEmpty()) return ToolExecutionResult.error("Missing 'date'.")
        val dayStart = parseIso(dateRaw) ?: return ToolExecutionResult.error(
            "\"$dateRaw\" is not a date. Use ISO, such as 2026-11-20.",
        )
        val zone = TimeZone.getDefault()
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val endOfDay = calendar.timeInMillis

        val entries = repository.day(startOfDay, endOfDay)
        val bookingsById = repository.bookings().associateBy { it.id }
        val result = HeartbeatDayResult(
            date = dateRaw,
            total = entries.size,
            entries = entries.map { entry ->
                HeartbeatDayEntry(
                    id = entry.id,
                    bookingName = bookingsById[entry.bookingId]?.name ?: "(removed booking)",
                    slot = HeartbeatBookingDraft.describeSlot(entry.slotAt),
                    state = entry.state.name,
                    instruction = entry.instructionFor(bookingsById[entry.bookingId]),
                    outcome = entry.outcome,
                )
            },
        )
        return ToolExecutionResult.success(result, json.encodeToString(HeartbeatDayResult.serializer(), result))
    }

    private suspend fun remove(args: Map<String, Any?>): ToolExecutionResult {
        val id = (args["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) return failWrite("Missing 'id'.")
        val booking = repository.booking(id)
            ?: return failWrite("No booking with id \"$id\".")
        val applied = repository.remove(id)
        if (applied.isFailure) {
            return failWrite(applied.exceptionOrNull()?.message ?: "the diary refused the removal")
        }
        val result = HeartbeatWriteResult(
            success = true,
            id = id,
            name = booking.name,
            note = "Removed. The future goes and everything that already ran stays in the diary.",
        )
        return ToolExecutionResult.success(result, json.encodeToString(HeartbeatWriteResult.serializer(), result))
    }

    private fun failWrite(reason: String): ToolExecutionResult {
        val result = HeartbeatWriteResult(success = false, error = reason)
        return ToolExecutionResult.success(result, json.encodeToString(HeartbeatWriteResult.serializer(), result))
    }

    private fun HeartbeatBooking.toEntry(): HeartbeatBookingEntry = HeartbeatBookingEntry(
        id = id,
        name = name,
        instruction = instruction,
        repeatKind = repeat.name,
        chosenDays = chosenDays.sorted(),
        combine = combine.name,
        enabled = enabled,
        startsAt = HeartbeatBookingDraft.describeSlot(startsAt),
        startsAtMillis = startsAt,
    )

    /**
     * Parse an ISO date or date-time.
     *
     * Three shapes are accepted because a caller reaching for "tomorrow at nine" will type
     * one of them: a full date-time, a date with a space instead of the T, and a bare date.
     * Anything else returns null so the caller is told, rather than being given now().
     */
    private fun parseIso(raw: String): Long? {
        val normalised = raw.trim().replace(' ', 'T')
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            val format = java.text.SimpleDateFormat(pattern, java.util.Locale.UK).apply {
                isLenient = false
            }
            try {
                return format.parse(normalised)?.time
            } catch (_: java.text.ParseException) {
                // try the next shape
            }
        }
        return null
    }
}
