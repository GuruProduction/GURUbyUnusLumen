// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.unuslumen.app.database.entity.HeartbeatBookingEntity
import com.unuslumen.app.database.entity.HeartbeatEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * The heartbeat diary: the rules and the dated half hours they produce.
 *
 * THE QUERIES ARE SHAPED BY WHAT THE ROOM DRAWS. It draws one day at a time, a week at
 * a time to know which days have anything on them, and the entries for a single half
 * hour when a beat wakes. Each of those is a query here rather than a filter over
 * everything, because a diary years deep is thousands of entries and reading all of
 * them to draw one day would make the room slow for no reason.
 */
@Dao
interface HeartbeatBookingDao {

    // ---------- rules ----------

    /** Every rule, including disabled ones, because the sheet must be able to edit them. */
    @Query("SELECT * FROM heartbeat_bookings ORDER BY starts_at ASC")
    fun observeBookings(): Flow<List<HeartbeatBookingEntity>>

    @Query("SELECT * FROM heartbeat_bookings ORDER BY starts_at ASC")
    suspend fun bookings(): List<HeartbeatBookingEntity>

    @Query("SELECT * FROM heartbeat_bookings WHERE id = :id")
    suspend fun booking(id: String): HeartbeatBookingEntity?

    /**
     * Rules that could still produce an occurrence at or after [fromMillis].
     *
     * A rule ending before that instant cannot, and neither can a disabled one. This is
     * the query the expander uses when topping up the horizon, so a diary with two
     * hundred finished bookings costs the same as one with two live ones.
     */
    @Query(
        """
        SELECT * FROM heartbeat_bookings
        WHERE enabled = 1 AND (ends_at = 0 OR ends_at >= :fromMillis)
        ORDER BY starts_at ASC
        """
    )
    suspend fun liveBookings(fromMillis: Long): List<HeartbeatBookingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBooking(booking: HeartbeatBookingEntity)

    @Update
    suspend fun updateBooking(booking: HeartbeatBookingEntity)

    @Query("DELETE FROM heartbeat_bookings WHERE id = :id")
    suspend fun deleteBooking(id: String)

    @Query("UPDATE heartbeat_bookings SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setBookingEnabled(id: String, enabled: Boolean, updatedAt: Long)

    // ---------- occurrences ----------

    /**
     * The entries for one day.
     *
     * FROM MIDNIGHT TO MIDNIGHT, because the timeline is a day. The bounds are computed
     * by the caller rather than in SQL, so the day boundary is one definition shared with
     * the rest of the app rather than a second one written in a query string.
     */
    @Query(
        """
        SELECT * FROM heartbeat_entries
        WHERE slot_at >= :dayStart AND slot_at < :dayEnd
        ORDER BY slot_at ASC
        """
    )
    fun observeDay(dayStart: Long, dayEnd: Long): Flow<List<HeartbeatEntryEntity>>

    @Query(
        """
        SELECT * FROM heartbeat_entries
        WHERE slot_at >= :dayStart AND slot_at < :dayEnd
        ORDER BY slot_at ASC
        """
    )
    suspend fun day(dayStart: Long, dayEnd: Long): List<HeartbeatEntryEntity>

    /**
     * Which days in a window have anything on them, for the week strip.
     *
     * The strip needs a dot per day that has bookings, not the bookings themselves, so
     * this returns only the distinct days rather than every row in the week.
     */
    @Query(
        """
        SELECT DISTINCT slot_at FROM heartbeat_entries
        WHERE slot_at >= :fromMillis AND slot_at < :toMillis
        ORDER BY slot_at ASC
        """
    )
    fun observeSlotsBetween(fromMillis: Long, toMillis: Long): Flow<List<Long>>

    /**
     * The entry that owns one exact half hour.
     *
     * THIS IS THE BEAT'S LOOKUP. A beat wakes on an absolute half hour, asks this
     * question, and either finds an instruction to run or finds nothing and runs the
     * master prompt alone. Two bookings claiming the same half hour cannot both be
     * returned, which is why the insert is refused at booking time and why this returns
     * a single row rather than a list.
     */
    @Query(
        """
        SELECT * FROM heartbeat_entries
        WHERE slot_at = :slotAt AND state != 'CANCELLED'
        ORDER BY created_at ASC
        LIMIT 1
        """
    )
    suspend fun entryAt(slotAt: Long): HeartbeatEntryEntity?

    @Query("SELECT * FROM heartbeat_entries WHERE booking_id = :bookingId ORDER BY slot_at ASC")
    suspend fun entriesFor(bookingId: String): List<HeartbeatEntryEntity>

    @Query("SELECT * FROM heartbeat_entries WHERE id = :id")
    suspend fun entry(id: String): HeartbeatEntryEntity?

    @Query("SELECT * FROM heartbeat_entries WHERE state = 'RUNNING' ORDER BY slot_at ASC")
    suspend fun runningEntries(): List<HeartbeatEntryEntity>

    @Query(
        """
        SELECT * FROM heartbeat_entries
        WHERE state = 'PENDING' AND slot_at < :beforeMillis
        ORDER BY slot_at ASC
        """
    )
    suspend fun pendingBefore(beforeMillis: Long): List<HeartbeatEntryEntity>

    @Query("SELECT MAX(slot_at) FROM heartbeat_entries")
    suspend fun newestEntrySlot(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<HeartbeatEntryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntry(entry: HeartbeatEntryEntity)

    @Query(
        """
        UPDATE heartbeat_entries
        SET state = :state, fired_at = :firedAt, finished_at = :finishedAt,
            outcome = :outcome, updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun setEntryOutcome(
        id: String,
        state: String,
        firedAt: Long,
        finishedAt: Long,
        outcome: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE heartbeat_entries
        SET override_instruction = :instruction, updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun setEntryInstruction(id: String, instruction: String?, updatedAt: Long)

    @Query("DELETE FROM heartbeat_entries WHERE booking_id = :bookingId AND state = 'PENDING'")
    suspend fun deletePendingFor(bookingId: String)

    @Query("DELETE FROM heartbeat_entries WHERE booking_id = :bookingId")
    suspend fun deleteAllFor(bookingId: String)

    /**
     * Cancel a booking: drop what has not happened, keep what has.
     *
     * A diary is a record as well as a plan. Deleting the rule removes the future, and
     * the past stays because it really happened.
     */
    @Transaction
    suspend fun retireBooking(bookingId: String, cancelledState: String, updatedAt: Long) {
        deletePendingFor(bookingId)
        setBookingEnabled(bookingId, false, updatedAt)
    }
}
