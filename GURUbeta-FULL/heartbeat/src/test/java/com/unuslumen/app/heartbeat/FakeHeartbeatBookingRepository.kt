// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import com.unuslumen.app.domain.model.EntryState
import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.HeartbeatEntry
import com.unuslumen.app.domain.model.HeartbeatExpansion
import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The diary, in memory, behaving like the real one.
 *
 * WHY A HAND-WRITTEN FAKE AND NOT A MOCK. The repository's write methods answer
 * `Result<Unit>`, and MockK special-cases Result: a stub declared as returning
 * `Result.success(Unit)` is unwrapped and handed back as `Unit`, which then fails the
 * `fold` inside the view model with a ClassCastException. That is a trap in the test
 * harness, not the app, and a fake avoids it entirely by answering exactly what the
 * interface says.
 *
 * It also does something a mock cannot: it holds state. A test can book, then assert that
 * the booking is really there, which is the difference between checking that a call
 * happened and checking that the diary changed.
 *
 * IT REFUSES WHAT THE REAL ONE REFUSES, using the same pure expansion object, so a test
 * that passes here is not passing against a friendlier stub than the phone.
 */
class FakeHeartbeatBookingRepository(

    /** Set to a failure to make the next write refuse, as the real repository does. */
    var refuseWith: String? = null,

    private val now: () -> Long = { System.currentTimeMillis() },
) : HeartbeatBookingRepository {

    val bookings = mutableListOf<HeartbeatBooking>()
    val entries = mutableListOf<HeartbeatEntry>()

    /** Every day window the view model asked for, so a test can assert what it observed. */
    val observedWindows = mutableListOf<Pair<Long, Long>>()

    var reconcileCount = 0
        private set

    override fun observeBookings(): Flow<List<HeartbeatBooking>> = flowOf(bookings.toList())

    override suspend fun bookings(): List<HeartbeatBooking> = bookings.toList()

    override suspend fun booking(id: String): HeartbeatBooking? = bookings.firstOrNull { it.id == id }

    override suspend fun book(booking: HeartbeatBooking): Result<Unit> {
        refuseWith?.let { reason -> return Result.failure(IllegalStateException(reason)) }
        if (booking.instruction.isBlank()) {
            return Result.failure(IllegalStateException("A booking needs to say what GURU should do."))
        }
        val owner = HeartbeatExpansion.collidesWith(booking, bookings)
        if (owner != null) {
            return Result.failure(
                IllegalStateException(
                    "That half hour is already booked as \"${owner.name}\". " +
                        "Two bookings cannot share one half hour."
                )
            )
        }
        bookings.add(booking)
        materialise(booking)
        return Result.success(Unit)
    }

    override suspend fun reschedule(booking: HeartbeatBooking, fromMillis: Long): Result<Unit> {
        refuseWith?.let { reason -> return Result.failure(IllegalStateException(reason)) }
        val index = bookings.indexOfFirst { it.id == booking.id }
        if (index < 0) bookings.add(booking) else bookings[index] = booking
        // Only what has not happened is re-derived, exactly as the real repository does.
        entries.removeAll { it.bookingId == booking.id && it.slotAt >= fromMillis }
        materialise(booking)
        return Result.success(Unit)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        val index = bookings.indexOfFirst { it.id == id }
        if (index < 0) return Result.failure(IllegalStateException("No such booking."))
        bookings[index] = bookings[index].copy(enabled = enabled)
        if (!enabled) {
            entries.removeAll { it.bookingId == id && it.state == EntryState.PENDING }
        } else {
            // Switching it back on rebuilds the future from the rule, which is the only
            // reason for keeping a rule rather than only its occurrences.
            materialise(bookings[index])
        }
        return Result.success(Unit)
    }

    override suspend fun remove(id: String): Result<Unit> {
        bookings.removeAll { it.id == id }
        entries.removeAll { it.bookingId == id && it.state == EntryState.PENDING }
        return Result.success(Unit)
    }

    override fun observeDay(dayStart: Long, dayEnd: Long): Flow<List<HeartbeatEntry>> {
        observedWindows.add(dayStart to dayEnd)
        return flowOf(entries.filter { it.slotAt >= dayStart && it.slotAt < dayEnd })
    }

    override suspend fun day(dayStart: Long, dayEnd: Long): List<HeartbeatEntry> =
        entries.filter { it.slotAt >= dayStart && it.slotAt < dayEnd }

    override fun observeSlotsBetween(fromMillis: Long, toMillis: Long): Flow<List<Long>> =
        flowOf(entries.map { it.slotAt }.filter { it in fromMillis until toMillis }.distinct())

    override suspend fun entryAt(slotAt: Long): HeartbeatEntry? =
        entries.firstOrNull { it.slotAt == slotAt && it.state != EntryState.CANCELLED }

    override suspend fun overrideInstruction(entryId: String, instruction: String?): Result<Unit> {
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return Result.failure(IllegalStateException("No such occurrence."))
        entries[index] = entries[index].copy(overrideInstruction = instruction)
        return Result.success(Unit)
    }

    override suspend fun recordOutcome(
        entryId: String,
        state: EntryState,
        firedAt: Long,
        finishedAt: Long,
        outcome: String,
    ) {
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return
        entries[index] = entries[index].copy(
            state = state,
            firedAt = firedAt,
            finishedAt = finishedAt,
            outcome = outcome,
        )
    }

    override suspend fun reconcile(nowMillis: Long) {
        reconcileCount++
        // The real one names a past half hour with no beat as missed. The fake does the
        // same, because a test that never sees a missed slot would miss that whole path.
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.state == EntryState.PENDING && entry.slotAt < nowMillis) {
                entries[index] = entry.copy(
                    state = EntryState.MISSED,
                    outcome = "No beat arrived in this half hour.",
                )
            }
        }
    }

    /**
     * Write the slots this rule produces that are not already recorded.
     *
     * ADDING ONLY THE MISSING ONES, rather than skipping the whole booking when any row
     * exists. The real repository relies on a unique index to ignore duplicates; the fake
     * has to be at least as careful, or a reschedule that legitimately re-derives the
     * future would find its own old rows and write nothing.
     */
    private fun materialise(booking: HeartbeatBooking) {
        val from = booking.startsAt
        val to = from + HeartbeatExpansion.HORIZON_DAYS * 24L * 60L * 60L * 1000L
        val existing = entries.filter { it.bookingId == booking.id }.map { it.slotAt }.toSet()
        HeartbeatExpansion.slotsFor(booking, from, to).forEach { slot ->
            if (slot in existing) return@forEach
            entries.add(
                HeartbeatEntry(
                    id = "entry-${booking.id}-$slot",
                    bookingId = booking.id,
                    slotAt = slot,
                    state = EntryState.PENDING,
                )
            )
        }
    }
}
