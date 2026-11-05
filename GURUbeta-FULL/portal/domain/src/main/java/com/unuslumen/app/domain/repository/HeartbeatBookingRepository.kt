// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.HeartbeatEntry
import kotlinx.coroutines.flow.Flow

/**
 * The heartbeat diary: its rules, its dated occurrences, and what happened in each.
 *
 * WHY THE ROOM AND THE BEAT SHARE ONE CONTRACT. The room draws what is booked and the run
 * service resolves what to do in the half hour it woke in. If those two read different
 * sources, the diary slowly stops describing the thing that actually fires. One
 * repository, one set of rules, one answer.
 *
 * THE WRITES THAT MATTER. [book] materialises the occurrences; [reschedule] re-expands a
 * rule from a given instant forward while leaving everything before it alone, because an
 * edit should change the future and never rewrite history that really happened.
 */
interface HeartbeatBookingRepository {

    // ---------- rules ----------

    /** Every booking, disabled ones included, so the sheet can re-enable one. */
    fun observeBookings(): Flow<List<HeartbeatBooking>>

    suspend fun bookings(): List<HeartbeatBooking>

    suspend fun booking(id: String): HeartbeatBooking?

    /**
     * Save a new booking and materialise its occurrences.
     *
     * REFUSED WHEN ANOTHER BOOKING OWNS THE SAME HALF HOUR. Two instructions for one beat
     * is a question with no answer, so it is refused here with the other booking's name in
     * the failure, and the room can say which one is already there.
     */
    suspend fun book(booking: HeartbeatBooking): Result<Unit>

    /**
     * Change a rule from [fromMillis] forward.
     *
     * What is before that instant stays exactly as it was. That is the difference between
     * editing a calendar and rewriting a diary: Tuesday afternoon is not changed because
     * the rule changed on Thursday.
     */
    suspend fun reschedule(booking: HeartbeatBooking, fromMillis: Long): Result<Unit>

    /** Turn a booking off without deleting it, and drop what has not happened yet. */
    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit>

    /**
     * Remove a booking.
     *
     * The future goes. The past stays, because it happened, and a diary that forgets what
     * already ran is not a diary. Occurrences that already ran are kept as history.
     */
    suspend fun remove(id: String): Result<Unit>

    // ---------- occurrences ----------

    /** The occurrences for one day, ordered by slot, as a live flow. */
    fun observeDay(dayStart: Long, dayEnd: Long): Flow<List<HeartbeatEntry>>

    /**
     * The occurrences for one day, read once.
     *
     * WHY BOTH THIS AND THE FLOW. The flow is what keeps the timeline live. This is what the
     * room uses to redraw the day the instant a booking is saved, without waiting for the
     * database to notify a collector: a save must land on screen immediately, and a rebuild
     * that had to wait for an emission is the lag that made a booking look unsaved.
     */
    suspend fun day(dayStart: Long, dayEnd: Long): List<HeartbeatEntry>

    /** Which half hours in a window have anything on them, for the week strip. */
    fun observeSlotsBetween(fromMillis: Long, toMillis: Long): Flow<List<Long>>

    /** The occurrence that owns one exact half hour, or null when nothing is booked. */
    suspend fun entryAt(slotAt: Long): HeartbeatEntry?

    /** Rewrite one occurrence alone, leaving the rule and every other occurrence intact. */
    suspend fun overrideInstruction(entryId: String, instruction: String?): Result<Unit>

    /** Record what actually happened in an occurrence. */
    suspend fun recordOutcome(
        entryId: String,
        state: com.unuslumen.app.domain.model.EntryState,
        firedAt: Long,
        finishedAt: Long,
        outcome: String,
    )

    /**
     * Materialise occurrences from now to the horizon, and mark the past as missed.
     *
     * CALLED WHEN THE DIARY OPENS AND BY THE BEAT ITSELF. The top-up keeps the calendar a
     * year deep; the sweep is what turns a half hour that passed with no beat into a
     * MISSED entry, so the diary can say why rather than leaving a hopeful "booked" on a
     * time that has gone.
     */
    suspend fun reconcile(nowMillis: Long)
}
