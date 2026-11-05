// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One dated occurrence of a booking: a real half hour on a real day.
 *
 * WHY THIS ROW EXISTS AT ALL. Each one is a half hour that has either happened or not,
 * and the diary has to be able to say which. A rule alone cannot answer "did my nine
 * o'clock run" or "did I miss it because the phone was asleep", and those two questions
 * are the whole reason a person opens a diary rather than trusting a schedule.
 *
 * [slotAt] is the absolute half hour this occurrence belongs to, in local wall clock.
 * The beat resolves the half hour it woke in against this column, so a booking means a
 * time of day rather than "thirty minutes after the last one finished".
 *
 * [overrideInstruction] is the per-instance edit. Null means "use the booking's
 * instruction"; a string means this occurrence alone was rewritten, and editing one
 * occurrence must never touch the rule or any other occurrence.
 *
 * [state] is what ACTUALLY happened, never what was asked for. The distinction between
 * MISSED (the phone was asleep or the chain did not deliver) and SKIPPED (the beacon
 * arrived but a previous beat was still running) is the difference between a phone
 * problem and a GURU problem, and collapsing them would hide a real fault.
 */
@Entity(
    tableName = "heartbeat_entries",
    indices = [
        Index(value = ["slot_at"], name = "index_heartbeat_entries_slot_at"),
        Index(value = ["booking_id"], name = "index_heartbeat_entries_booking"),
        Index(value = ["state"], name = "index_heartbeat_entries_state"),
        Index(
            value = ["booking_id", "slot_at"],
            name = "index_heartbeat_entries_booking_slot",
            unique = true,
        ),
    ],
)
data class HeartbeatEntryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "booking_id")
    val bookingId: String,
    /** The absolute half hour this occurrence is, in local wall clock, as epoch millis. */
    @ColumnInfo(name = "slot_at")
    val slotAt: Long,
    /** A per-instance rewrite of the instruction, or null to use the booking's own. */
    @ColumnInfo(name = "override_instruction")
    val overrideInstruction: String? = null,
    /** PENDING, RUNNING, RAN, MISSED, SKIPPED or CANCELLED. */
    val state: String,
    /** When the beat actually started on this occurrence, or 0. */
    @ColumnInfo(name = "fired_at", defaultValue = "0")
    val firedAt: Long = 0L,
    /** When it finished, or 0 while it is still running. */
    @ColumnInfo(name = "finished_at", defaultValue = "0")
    val finishedAt: Long = 0L,
    /** One sentence saying what happened, or why it did not. */
    @ColumnInfo(defaultValue = "")
    val outcome: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_RAN = "RAN"
        const val STATE_MISSED = "MISSED"
        const val STATE_SKIPPED = "SKIPPED"
        const val STATE_CANCELLED = "CANCELLED"

        /** The states a beat may still pick up. */
        val OPEN_STATES = listOf(STATE_PENDING)
    }
}
