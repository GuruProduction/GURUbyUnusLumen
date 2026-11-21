// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/** One booking as the diary holds it, with the slot described in words as well as millis. */
@Serializable
data class HeartbeatBookingEntry(
    val id: String,
    val name: String,
    val instruction: String,
    val repeatKind: String,
    val chosenDays: List<Int>,
    val combine: String,
    val enabled: Boolean,
    /** The first occurrence, in words, so a reader does not decode a timestamp. */
    val startsAt: String,
    val startsAtMillis: Long,
) : ToolResultData

@Serializable
data class HeartbeatBookingListResult(
    val total: Int,
    val bookings: List<HeartbeatBookingEntry>,
) : ToolResultData

/**
 * The result of writing a booking.
 *
 * `slot` names the half hour the booking actually got, because a caller that asked for
 * 14:17 needs to know it became 14:00. `error` carries the refusal sentence when the half
 * hour is already owned, naming the booking that owns it.
 */
@Serializable
data class HeartbeatWriteResult(
    val success: Boolean,
    val id: String = "",
    val name: String = "",
    val slot: String = "",
    /**
     * What happened beyond the bare success.
     *
     * Carries the snap when a caller asked for a time that is not on a half hour, and
     * the reminder that a change applies from now forward. Both are facts a caller needs
     * and neither is an error.
     */
    val note: String = "",
    val error: String? = null,
) : ToolResultData

/**
 * The master prompt, and whether one has been written at all.
 *
 * `written` EXISTS BECAUSE AN EMPTY STRING IS NOT AN ANSWER. A fresh install has no
 * prompt, because the app ships no default and the owner writes it. A prompt that was
 * saved and then cleared also reads as empty. Both are honest states and they lead to
 * different things for a caller to do, so the boolean carries the distinction and the
 * message says which one this is.
 */
@Serializable
data class HeartbeatPromptResult(
    val success: Boolean,
    val written: Boolean,
    val content: String,
    val updatedAtMillis: Long? = null,
    val message: String,
) : ToolResultData

/**
 * The answer to a manual fire.
 *
 * `accepted` IS NOT "THE BEAT RAN". It says Android took the request to start the run
 * service. Whether the beat then reached a model, ended on an empty prompt, or was cut
 * short is recorded in the diary, and the message says to read the day rather than
 * implying this call proves anything about the work itself.
 */
@Serializable
data class HeartbeatFireResult(
    val success: Boolean,
    val accepted: Boolean,
    val message: String,
    val error: String? = null,
) : ToolResultData

/** One occurrence in a day, with what actually happened in it. */
@Serializable
data class HeartbeatDayEntry(
    val id: String,
    val bookingName: String,
    val slot: String,
    val state: String,
    val instruction: String,
    val outcome: String,
) : ToolResultData

@Serializable
data class HeartbeatDayResult(
    val date: String,
    val total: Int,
    val entries: List<HeartbeatDayEntry>,
) : ToolResultData
