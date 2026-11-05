// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.util.Log
import com.unuslumen.app.database.dao.HeartbeatBookingDao
import com.unuslumen.app.database.entity.HeartbeatBookingEntity
import com.unuslumen.app.database.entity.HeartbeatEntryEntity
import com.unuslumen.app.domain.model.BookingCombine
import com.unuslumen.app.domain.model.EntryState
import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.HeartbeatEntry
import com.unuslumen.app.domain.model.HeartbeatExpansion
import com.unuslumen.app.domain.model.HeartbeatSlot
import com.unuslumen.app.domain.model.RepeatKind
import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

/**
 * The diary, stored: rules in one table, dated half hours in another.
 *
 * THE EXPANSION IS DELEGATED TO A PURE OBJECT. Every rule about which day produces which
 * slot lives in HeartbeatExpansion, which is Android-free and tested on the JVM. This
 * class only moves rows. That split is deliberate: the arithmetic is the part that can be
 * wrong in a way nobody notices until a Tuesday is missing, so it is the part that must
 * be provable without a device.
 *
 * A YEAR IS MATERIALISED AHEAD, NOT THREE MONTHS. A calendar a person cannot look at next
 * spring is not a calendar. The cost is bounded by the number of live bookings and the
 * density of their rules, and a daily booking is 365 rows.
 *
 * COLLISIONS ARE REFUSED HERE, WITH A NAME. Two bookings in one half hour is a question
 * with no answer at fire time, so it is refused when it is booked and the failure names
 * the booking already holding that slot.
 */
@Single(binds = [HeartbeatBookingRepository::class])
class HeartbeatBookingRepositoryImpl(
    private val dao: HeartbeatBookingDao,
) : HeartbeatBookingRepository {

    private companion object {
        const val TAG = "guru_heartbeat"
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }

    // ---------- rules ----------

    override fun observeBookings(): Flow<List<HeartbeatBooking>> =
        dao.observeBookings().map { rows -> rows.map { it.toModel() } }

    override suspend fun bookings(): List<HeartbeatBooking> = withContext(Dispatchers.IO) {
        runCatching { dao.bookings().map { it.toModel() } }
            .onFailure { Log.w(TAG, "Booking list read failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    override suspend fun booking(id: String): HeartbeatBooking? = withContext(Dispatchers.IO) {
        runCatching { dao.booking(id)?.toModel() }.getOrNull()
    }

    override suspend fun book(booking: HeartbeatBooking): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(booking.name.isNotBlank()) { "A booking needs a name." }
                require(booking.instruction.isNotBlank()) {
                    "A booking needs to say what GURU should do."
                }
                require(HeartbeatSlot.isOnSlot(booking.startsAt)) {
                    "A booking has to start on a half hour."
                }
                val others = dao.bookings().map { it.toModel() }
                val owner = HeartbeatExpansion.collidesWith(booking, others)
                require(owner == null) {
                    "That half hour is already booked as \"${owner?.name}\". " +
                        "Two bookings cannot share one half hour."
                }

                val now = System.currentTimeMillis()
                dao.putBooking(booking.toEntity(createdAt = now, updatedAt = now))
                materialise(booking, fromMillis = booking.startsAt)
            }.onFailure { Log.w(TAG, "Booking refused: ${it.message}") }
        }

    override suspend fun reschedule(booking: HeartbeatBooking, fromMillis: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(booking.instruction.isNotBlank()) {
                    "A booking needs to say what GURU should do."
                }
                val others = dao.bookings().map { it.toModel() }
                val owner = HeartbeatExpansion.collidesWith(booking, others)
                require(owner == null) {
                    "That half hour is already booked as \"${owner?.name}\". " +
                        "Two bookings cannot share one half hour."
                }

                val existing = dao.booking(booking.id)
                val now = System.currentTimeMillis()
                dao.putBooking(
                    booking.toEntity(createdAt = existing?.createdAt ?: now, updatedAt = now)
                )
                // THE PAST IS LEFT ALONE. Only what has not happened is re-derived, so a
                // rule changed today does not rewrite what already ran last week.
                dao.deletePendingFor(booking.id)
                materialise(booking, fromMillis = maxOf(fromMillis, booking.startsAt))
            }.onFailure { Log.w(TAG, "Reschedule refused: ${it.message}") }
        }

    override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                dao.setBookingEnabled(id, enabled, now)
                // Disabling removes what has not happened. Enabling regenerates it from
                // the rule, which is the whole point of keeping the rule rather than only
                // its occurrences.
                if (!enabled) {
                    dao.deletePendingFor(id)
                } else {
                    dao.booking(id)?.toModel()?.let { booking ->
                        materialise(booking, fromMillis = maxOf(now, booking.startsAt))
                    }
                }
                // An explicit Unit, because the `?.let` above answers Unit? when the row is
                // gone, and a Result<Unit?> is not a Result<Unit>.
                Unit
            }.onFailure { Log.w(TAG, "Booking enable/disable failed: ${it.message}") }
        }

    override suspend fun remove(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            dao.retireBooking(id, EntryState.CANCELLED.name, now)
            dao.deleteBooking(id)
        }.onFailure { Log.w(TAG, "Booking removal failed: ${it.message}") }
    }

    // ---------- occurrences ----------

    override fun observeDay(dayStart: Long, dayEnd: Long): Flow<List<HeartbeatEntry>> =
        dao.observeDay(dayStart, dayEnd).map { rows -> rows.map { it.toModel() } }

    override fun observeSlotsBetween(fromMillis: Long, toMillis: Long): Flow<List<Long>> =
        dao.observeSlotsBetween(fromMillis, toMillis)

    override suspend fun entryAt(slotAt: Long): HeartbeatEntry? = withContext(Dispatchers.IO) {
        runCatching { dao.entryAt(slotAt)?.toModel() }.getOrNull()
    }

    override suspend fun overrideInstruction(entryId: String, instruction: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                dao.setEntryInstruction(
                    entryId,
                    instruction?.takeIf { it.isNotBlank() },
                    System.currentTimeMillis(),
                )
            }.onFailure { Log.w(TAG, "Occurrence edit failed: ${it.message}") }
        }

    override suspend fun recordOutcome(
        entryId: String,
        state: EntryState,
        firedAt: Long,
        finishedAt: Long,
        outcome: String,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                dao.setEntryOutcome(
                    id = entryId,
                    state = state.name,
                    firedAt = firedAt,
                    finishedAt = finishedAt,
                    outcome = outcome,
                    updatedAt = System.currentTimeMillis(),
                )
            }.onFailure { Log.w(TAG, "Occurrence outcome write failed: ${it.message}") }
        }
    }

    /**
     * Top the calendar up, and turn a past half hour with no beat into a MISSED entry.
     *
     * THE SWEEP IS THE HONEST PART. An entry that stays PENDING after its time has gone
     * would tell a person their nine o'clock is still coming at half past ten. This marks
     * it missed with the reason, and never deletes it, because a diary that forgets is
     * worse than one that reports a gap.
     *
     * A RUNNING entry is left alone: a beat that started in that half hour may still be
     * working, and the run service writes its own outcome when it ends.
     */
    override suspend fun reconcile(nowMillis: Long): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val slotNow = HeartbeatSlot.floor(nowMillis)
            val horizonEnd = slotNow + HeartbeatExpansion.HORIZON_DAYS * DAY_MILLIS

            // 1. Top up every live rule from the newest thing it has produced.
            val live = dao.liveBookings(slotNow).map { it.toModel() }
            for (booking in live) {
                val newest = dao.entriesFor(booking.id).maxOfOrNull { it.slotAt } ?: 0L
                val from = maxOf(booking.startsAt, newest + HeartbeatSlot.SLOT_MILLIS)
                if (from < horizonEnd) materialise(booking, fromMillis = from)
            }

            // 2. Anything whose half hour has gone without a beat is missed, with a reason.
            val stale = dao.pendingBefore(slotNow)
            val updatedAt = System.currentTimeMillis()
            for (entry in stale) {
                dao.setEntryOutcome(
                    id = entry.id,
                    state = EntryState.MISSED.name,
                    firedAt = 0L,
                    finishedAt = 0L,
                    outcome = "No beat arrived in this half hour. The phone was asleep, " +
                        "or Android dropped the alarm.",
                    updatedAt = updatedAt,
                )
            }
        }.onFailure { Log.w(TAG, "Diary reconcile failed: ${it.message}") }
        // Reconcile is best effort by design: it is called from the booking path, where an
        // alarm has already been written, and a diary that could not top itself up must
        // never cost the owner a beat. The failure is logged above rather than returned,
        // because nothing upstream can act on it.
        Unit
    }

    // ---------- internals ----------

    /**
     * Write every slot this rule produces from [fromMillis] to the horizon.
     *
     * INSERT-OR-IGNORE at the database, so topping up twice cannot duplicate a day. The
     * unique index on (booking_id, slot_at) is what makes that true rather than a check
     * made here that a second writer could race.
     */
    private suspend fun materialise(booking: HeartbeatBooking, fromMillis: Long) {
        val now = System.currentTimeMillis()
        val to = HeartbeatSlot.floor(now) + HeartbeatExpansion.HORIZON_DAYS * DAY_MILLIS
        val slots = HeartbeatExpansion.slotsFor(booking, fromMillis, to)
        if (slots.isEmpty()) return

        val rows = slots.map { slot ->
            HeartbeatEntryEntity(
                id = Uuid.random().toString(),
                bookingId = booking.id,
                slotAt = slot,
                overrideInstruction = null,
                state = EntryState.PENDING.name,
                firedAt = 0L,
                finishedAt = 0L,
                outcome = "",
                createdAt = now,
                updatedAt = now,
            )
        }
        dao.insertEntries(rows)
        Log.d(TAG, "Materialised ${rows.size} occurrences for \"${booking.name}\"")
    }

    private fun HeartbeatBookingEntity.toModel(): HeartbeatBooking = HeartbeatBooking(
        id = id,
        name = name,
        instruction = instruction,
        repeat = RepeatKind.fromStored(repeatKind),
        chosenDays = decodeDays(daysMask),
        combine = BookingCombine.fromStored(combine),
        colourIndex = colourIndex,
        enabled = enabled,
        startsAt = startsAt,
        endsAt = endsAt,
    )

    private fun HeartbeatEntryEntity.toModel(): HeartbeatEntry = HeartbeatEntry(
        id = id,
        bookingId = bookingId,
        slotAt = slotAt,
        overrideInstruction = overrideInstruction,
        state = EntryState.fromStored(state),
        firedAt = firedAt,
        finishedAt = finishedAt,
        outcome = outcome,
    )

    private fun HeartbeatBooking.toEntity(createdAt: Long, updatedAt: Long) =
        HeartbeatBookingEntity(
            id = id,
            name = name,
            instruction = instruction,
            repeatKind = repeat.name,
            daysMask = encodeDays(chosenDays),
            combine = combine.name,
            colourIndex = colourIndex,
            enabled = enabled,
            startsAt = startsAt,
            endsAt = endsAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun encodeDays(days: Set<Int>): Int =
        days.filter { it in 0..6 }.fold(0) { mask, day -> mask or (1 shl day) }

    private fun decodeDays(mask: Int): Set<Int> =
        (0..6).filter { mask and (1 shl it) != 0 }.toSet()
}
