// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One booking in the heartbeat diary: the rule, not the occurrences.
 *
 * WHAT THIS IS. The heartbeat fires on every absolute half hour of the day. A booking
 * claims one of those half hours and says what GURU should do in it, in its owner's
 * words. The master prompt still fires alongside it, unless the booking is marked
 * [Combine.INSTEAD].
 *
 * WHY THE RULE AND THE OCCURRENCES ARE TWO TABLES. A booking that repeats is not one
 * record; it is a rule plus every dated half hour that rule produces. A calendar that
 * only held rules could not answer "did my nine o'clock run", could not let its owner
 * edit next Tuesday alone, and could not show a history. A calendar that only held
 * occurrences would have to re-derive the rule from the rows every time it drew the
 * sheet, and the first skipped week would erase the rule. Both are needed, so both
 * exist: this table holds the rule, HeartbeatEntryEntity holds the dated entries.
 *
 * [daysMask] is a bit per weekday, bit 0 Monday through bit 6 Sunday. It is only read
 * for [RepeatKind.CHOSEN]; the other kinds derive their days from the kind itself so a
 * stale mask can never quietly change what a "weekdays" booking does.
 *
 * [startsAt] carries BOTH the first date and the time of day. A booking is a half hour,
 * so the minute component is always 0 or 30 and everything below that is zeroed. The
 * time of day is taken from here rather than stored separately, because two fields
 * describing one instant is how a calendar ends up with a booking at 09:00 that fires
 * at 09:30.
 */
@Entity(
    tableName = "heartbeat_bookings",
    indices = [
        Index(value = ["enabled"], name = "index_heartbeat_bookings_enabled"),
        Index(value = ["starts_at"], name = "index_heartbeat_bookings_starts_at"),
    ],
)
data class HeartbeatBookingEntity(
    @PrimaryKey
    val id: String,
    /** What the booking is called, in its owner's words. Shown on the timeline. */
    val name: String,
    /** What GURU does in this half hour. Injected as the beat's instruction. */
    val instruction: String,
    /** ONCE, DAILY, WEEKDAYS or CHOSEN. Stored as text so a new kind needs no migration. */
    @ColumnInfo(name = "repeat_kind")
    val repeatKind: String,
    /** Bit 0 Monday through bit 6 Sunday. Read only when repeatKind is CHOSEN. */
    @ColumnInfo(name = "days_mask", defaultValue = "0")
    val daysMask: Int = 0,
    /** ALONGSIDE (master prompt plus this) or INSTEAD (this alone). */
    val combine: String,
    /**
     * Which Memphis colour the booking wears on the timeline.
     *
     * An index into the palette rotation rather than a stored hex value, because the
     * palette is the app's and a stored colour would survive a change to it and end up
     * the one off-brand object on the page.
     */
    @ColumnInfo(name = "colour_index", defaultValue = "0")
    val colourIndex: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** The first occurrence, and the time of day every occurrence uses. */
    @ColumnInfo(name = "starts_at")
    val startsAt: Long,
    /** Last date this may occur on, or 0 for "no end". */
    @ColumnInfo(name = "ends_at", defaultValue = "0")
    val endsAt: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        /** Stored spellings. Text, not an ordinal, so reordering an enum cannot rewrite history. */
        const val REPEAT_ONCE = "ONCE"
        const val REPEAT_DAILY = "DAILY"
        const val REPEAT_WEEKDAYS = "WEEKDAYS"
        const val REPEAT_CHOSEN = "CHOSEN"

        const val COMBINE_ALONGSIDE = "ALONGSIDE"
        const val COMBINE_INSTEAD = "INSTEAD"
    }
}
