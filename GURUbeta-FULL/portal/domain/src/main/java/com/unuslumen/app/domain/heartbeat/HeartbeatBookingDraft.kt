// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.heartbeat

import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.RepeatKind
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Turning what a model asks for into a booking the diary will accept (issue 488).
 *
 * THE GAP. Heartbeat had a complete data model, a run service, an alarm chain and a
 * diary screen, and no tool a model could call: no `HeartbeatToolDefinitions.kt` existed
 * anywhere, and not one of the 607 tool names managed a booking. Two agent prompts now
 * depend on it. The Self Improvement Engine is told to use "the actual Heartbeat
 * interface and capabilities available to you" to keep a development agenda, and
 * Archivist relies on scheduled sessions to do curation work. Neither could schedule
 * anything.
 *
 * WHY A BUILDER AND A VALIDATOR RATHER THAN A BARE CONSTRUCTOR. A booking is a rule with
 * real constraints: it fires on a half hour, it has a repeat kind, its chosen days only
 * matter for one of those kinds, and a slot can already be owned by another booking. The
 * repository refuses the last of those with the other booking's name, and everything else
 * fails as a stack trace from deeper in the app. Building and checking here means a model
 * gets a sentence it can act on.
 */
object HeartbeatBookingDraft {

    /** A booking, or the sentence saying why there is not one. */
    sealed interface Result {
        data class Ready(val booking: HeartbeatBooking) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * Build a booking from what a caller supplied.
     *
     * `startMillis` carries BOTH the first date and the time of day, because that is what
     * the entity's own field means. A caller that names only a date gets midnight, which
     * is a legitimate half hour and the one they asked for.
     */
    fun build(
        name: String?,
        instruction: String?,
        repeatKindName: String?,
        chosenDays: List<Int>?,
        startMillis: Long,
        endsAtMillis: Long,
        enabled: Boolean,
        combineName: String?,
        nowMillis: Long,
    ): Result {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isEmpty()) {
            return Result.Invalid("A booking needs a name: it is what the diary shows and how you find it again.")
        }
        val cleanInstruction = instruction?.trim().orEmpty()
        if (cleanInstruction.isEmpty()) {
            return Result.Invalid(
                "A booking needs an instruction: it is what GURU is told to do in that half hour, " +
                    "and a booking that says nothing wakes up with nothing to do.",
            )
        }

        val repeat = RepeatKind.entries.firstOrNull {
            it.name.equals(repeatKindName?.trim(), ignoreCase = true)
        } ?: if (repeatKindName.isNullOrBlank()) {
            RepeatKind.ONCE
        } else {
            return Result.Invalid(
                "\"$repeatKindName\" is not a repeat kind. The kinds are: " +
                    RepeatKind.entries.joinToString(", ") { it.name } + ".",
            )
        }

        // CHOSEN WITH NO DAYS BOOKS NOTHING. It is the one combination that produces a
        // rule that never fires, and it would look saved in the diary while doing nothing.
        val days = chosenDays.orEmpty().filter { it in 0..6 }.toSet()
        if (repeat == RepeatKind.CHOSEN && days.isEmpty()) {
            return Result.Invalid(
                "repeatKind CHOSEN needs at least one day, numbered Monday 0 through Sunday 6. " +
                    "With none the booking would never fire while looking saved.",
            )
        }

        if (startMillis <= 0L) {
            return Result.Invalid("A booking needs a start time: the first occurrence carries both the date and the half hour.")
        }
        if (endsAtMillis != 0L && endsAtMillis < startMillis) {
            return Result.Invalid("endsAt is before startsAt, so no occurrence could ever fall inside the rule.")
        }
        // A booking already past its end cannot fire, and the diary would show a rule the
        // beat will never act on.
        if (endsAtMillis != 0L && endsAtMillis < nowMillis) {
            return Result.Invalid(
                "endsAt is in the past, so this booking could never fire. Move the end forward or leave it out.",
            )
        }

        val combine = if (combineName.isNullOrBlank()) {
            com.unuslumen.app.domain.model.BookingCombine.ALONGSIDE
        } else {
            com.unuslumen.app.domain.model.BookingCombine.entries.firstOrNull {
                it.name.equals(combineName.trim(), ignoreCase = true)
            } ?: return Result.Invalid(
                "\"$combineName\" is not a combine mode. The modes are: " +
                    com.unuslumen.app.domain.model.BookingCombine.entries.joinToString(", ") { it.name } + ".",
            )
        }

        return Result.Ready(
            HeartbeatBooking(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                instruction = cleanInstruction,
                repeat = repeat,
                chosenDays = if (repeat == RepeatKind.CHOSEN) days else emptySet(),
                combine = combine,
                // A colour is derived from the id rather than asked for, so a booking keeps
                // one for its life and nothing has to be chosen by the caller.
                colourIndex = (cleanName.hashCode() and 0x7FFFFFFF) % 8,
                enabled = enabled,
                startsAt = startMillis,
                endsAt = endsAtMillis,
            )
        )
    }

    /**
     * The half-hour slot a wall-clock time falls in, in this device's zone.
     *
     * A booking fires on a half hour and nothing else, so a caller asking for 14:17 gets
     * 14:00 rather than a rule the alarm chain cannot express. The truncation is reported
     * rather than silent: the caller is told the slot it actually got.
     */
    fun snapToHalfHour(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val minute = calendar.get(Calendar.MINUTE)
        calendar.set(Calendar.MINUTE, if (minute < 30) 0 else 30)
        return calendar.timeInMillis
    }

    /**
     * A wall-clock description of a slot, for a reply a person can read.
     *
     * A timestamp in milliseconds is a machine's answer to "when does this run", and the
     * owner reading a diary wants the day and the time.
     */
    fun describeSlot(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = java.text.SimpleDateFormat("EEE d MMM yyyy 'at' HH:mm", java.util.Locale.UK)
        format.timeZone = zone
        return format.format(java.util.Date(millis))
    }
}
