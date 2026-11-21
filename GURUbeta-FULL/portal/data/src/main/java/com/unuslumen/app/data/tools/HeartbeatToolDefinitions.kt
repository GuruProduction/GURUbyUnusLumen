// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * The heartbeat diary tools (issue 488).
 *
 * THE GAP. Heartbeat had a complete data model, a run service, an alarm chain and a
 * diary screen, and no tool a model could call: no `HeartbeatToolDefinitions.kt` existed
 * anywhere, and none of the 607 tool names managed a booking. Two agent prompts now
 * depend on it. The Self Improvement Engine is told to use "the actual Heartbeat
 * interface and capabilities available to you" to keep a development agenda, and
 * Archivist relies on scheduled sessions to do curation work. Neither could schedule
 * anything.
 *
 * This is a tool layer over a working subsystem, not a feature build: `book`,
 * `reschedule`, `setEnabled`, `remove`, `day` and `entryAt` all exist and work.
 */
object HeartbeatToolDefinitions : ToolSetRegistration {

    const val LIST_BOOKINGS = "listHeartbeatBookings"
    const val BOOK_HEARTBEAT = "bookHeartbeat"
    const val UPDATE_HEARTBEAT = "updateHeartbeatBooking"
    const val SET_HEARTBEAT_ENABLED = "setHeartbeatEnabled"
    const val READ_HEARTBEAT_DAY = "readHeartbeatDay"
    const val REMOVE_HEARTBEAT = "removeHeartbeatBooking"
    const val GET_HEARTBEAT_PROMPT = "getHeartbeatPrompt"
    const val SAVE_HEARTBEAT_PROMPT = "saveHeartbeatPrompt"
    const val FIRE_HEARTBEAT_NOW = "fireHeartbeatNow"

    override val definitions = listOf(
        /*
         * THE MASTER PROMPT, AND WHY ITS ABSENCE IS ITS OWN ANSWER.
         *
         * Every half hour that has no booking sends the master prompt, and a fresh
         * install has none: the owner writes it. Reporting that as an empty string
         * would be indistinguishable from a prompt that was saved and then cleared,
         * and a caller reading "" cannot tell whether to ask the owner for one or to
         * accept that the diary is deliberately silent. So the reply carries
         * `written`, and the sentence differs in each case.
         */
        ToolDefinition(
            name = GET_HEARTBEAT_PROMPT,
            description = "Read the master prompt: the standing instruction GURU gives itself in every " +
                "half hour that no booking claims. A fresh install has none, because the owner writes " +
                "it and the app ships no default, so 'written' is false and the reply says a prompt " +
                "still needs writing rather than reporting an empty string as though it were the answer.",
            category = "heartbeat",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SAVE_HEARTBEAT_PROMPT,
            description = "Write the master prompt, replacing whatever was there. This is the text " +
                "injected on every half hour that no booking claims, so it is the owner's standing " +
                "instruction to GURU and never a message to a person. An empty prompt is refused " +
                "rather than stored, because a beat with an empty prompt would inject nothing and " +
                "look exactly like a beat that failed.",
            category = "heartbeat",
            parameters = listOf(
                ToolParameter(
                    "content", ToolParameterType.String, required = true,
                    "The full prompt text. This replaces the previous prompt rather than appending to it.",
                ),
            ),
            permissions = emptyList(),
        ),
        /*
         * A MANUAL FIRE IS A DIAGNOSTIC, NOT SCHEDULING.
         *
         * The alarm chain can be healthy and the beat still broken, or the other way
         * round, and from the outside both look like silence. Firing by hand answers
         * which: it starts the beat directly rather than waiting for an alarm, and the
         * outcome is recorded either way, including a refusal by Android.
         *
         * IT DOES NOT FIRE ONE BOOKING. A beat resolves whichever booking owns the half
         * hour it wakes in; this wakes the same chain at the current half hour. Saying
         * so is the difference between a tool that tells the truth and one whose name
         * promises something the mechanism does not do.
         */
        ToolDefinition(
            name = FIRE_HEARTBEAT_NOW,
            description = "Run a heartbeat beat immediately instead of waiting for the next half-hour " +
                "alarm. It resolves whatever the diary says about the half hour it fires in, so this " +
                "tests the beat itself rather than one booking. It answers whether Android accepted " +
                "the request; the beat's own result is recorded in the diary, so read the day " +
                "afterwards to see what it actually did.",
            category = "heartbeat",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = LIST_BOOKINGS,
            description = "List the heartbeat bookings that govern when GURU wakes on its own: each one's name, instruction, repeat rule, half-hour slot and whether it is enabled. Read this before booking anything, so you extend the diary rather than colliding with a half hour that is already owned.",
            category = "heartbeat",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = BOOK_HEARTBEAT,
            description = "Book a half hour in GURU's own diary: a name, the instruction GURU is told to carry out when that half hour arrives, and a repeat rule. The slot snaps to the half hour it falls in, because the alarm chain fires on half hours only, and the reply says which slot it actually got. Refused when another booking already owns that half hour, with that booking named.",
            category = "heartbeat",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, required = true, description = "A short name for the booking, as the diary shows it"),
                ToolParameter("instruction", ToolParameterType.String, required = true, description = "What GURU is told to do in that half hour"),
                ToolParameter("startAt", ToolParameterType.String, required = true, description = "The first occurrence as an ISO date and time, such as 2026-11-20T09:00. The time of day repeats from here"),
                ToolParameter("repeatKind", ToolParameterType.String, required = false, description = "ONCE, DAILY, WEEKDAYS, or CHOSEN. Default ONCE"),
                ToolParameter("chosenDays", ToolParameterType.String, required = false, description = "For CHOSEN only: comma-separated day numbers, Monday 0 through Sunday 6"),
                ToolParameter("endsAt", ToolParameterType.String, required = false, description = "Last date an occurrence may fall on, as an ISO date. Omit for no end"),
                ToolParameter("combine", ToolParameterType.String, required = false, description = "ALONGSIDE to add to that half hour's work, or INSTEAD to replace it. Default ALONGSIDE"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = UPDATE_HEARTBEAT,
            description = "Change a booking from now forward: its name, instruction, repeat rule or end date. What already happened stays exactly as it was, because a diary that rewrites its own past is not a diary.",
            category = "heartbeat",
            parameters = listOf(
                ToolParameter("id", ToolParameterType.String, required = true, description = "The booking's id from listHeartbeatBookings"),
                ToolParameter("name", ToolParameterType.String, required = false, description = "New name"),
                ToolParameter("instruction", ToolParameterType.String, required = false, description = "New instruction"),
                ToolParameter("repeatKind", ToolParameterType.String, required = false, description = "New repeat kind"),
                ToolParameter("chosenDays", ToolParameterType.String, required = false, description = "New chosen days for CHOSEN"),
                ToolParameter("endsAt", ToolParameterType.String, required = false, description = "New end date"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SET_HEARTBEAT_ENABLED,
            description = "Turn a booking on or off without removing it. Turning one off drops the occurrences that have not happened and keeps everything that did.",
            category = "heartbeat",
            parameters = listOf(
                ToolParameter("id", ToolParameterType.String, required = true, description = "The booking's id"),
                ToolParameter("enabled", ToolParameterType.Boolean, required = true, description = "true to turn it on, false to turn it off"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = READ_HEARTBEAT_DAY,
            description = "Read one day of the diary: every occurrence in that day with its slot, state, instruction and the outcome it recorded. Use it to see what GURU actually did rather than what was booked.",
            category = "heartbeat",
            parameters = listOf(
                ToolParameter("date", ToolParameterType.String, required = true, description = "The day to read, as an ISO date such as 2026-11-20"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = REMOVE_HEARTBEAT,
            description = "Remove a booking. The future goes and the past stays, because it happened and a diary that forgets what already ran is not a diary.",
            category = "heartbeat",
            parameters = listOf(
                ToolParameter("id", ToolParameterType.String, required = true, description = "The booking's id"),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = HeartbeatToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
