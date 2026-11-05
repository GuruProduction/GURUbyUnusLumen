// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import java.util.Calendar
import java.util.TimeZone

/**
 * Turning a rule into real dated half hours.
 *
 * WHY THIS IS A SEPARATE PURE OBJECT. A diary is a calendar: every booking is a real
 * entry on a real day that can be opened, edited alone, or reported as having run. That
 * means a repeat rule has to be expanded into dated rows ahead of the day it fires, and
 * the expansion is the one piece of this feature where an off-by-one is invisible until
 * somebody's Tuesday is missing. So it is Android-free, it takes the clock as an
 * argument, and it can be driven across a decade in a JVM test.
 *
 * THE HORIZON IS REAL AND IT IS LONG. Occurrences are materialised for a year ahead of
 * the day being looked at, and topped up whenever the diary is opened. A year of a daily
 * half-hour booking is 365 rows, which is nothing; a person can open next June and edit
 * a single Tuesday, and that is the point. Nothing expires: an old occurrence is kept as
 * history and a new one is generated when the horizon moves forward.
 */
object HeartbeatExpansion {

    /**
     * How far ahead occurrences are materialised.
     *
     * A full year, not ninety days. A calendar a person can only look ninety days into is
     * a calendar that cannot hold a birthday next spring, and there is no cost to the
     * longer horizon: the rows are a few dozen kilobytes for the densest possible diary.
     */
    const val HORIZON_DAYS = 365

    /**
     * Every slot a booking produces between [fromMillis] and [toMillis].
     *
     * ONE SLOT PER QUALIFYING DAY, at the booking's own time of day. The loop walks days
     * rather than slots because the rule is stated per day, and walking slots would mean
     * testing the same weekday forty-eight times.
     *
     * The start and end bounds are inclusive at the start and exclusive at the end, which
     * matches every other date range in this codebase. A booking whose own first
     * occurrence is later than the window simply produces nothing, and one that ended
     * before it produces nothing either.
     */
    fun slotsFor(
        booking: HeartbeatBooking,
        fromMillis: Long,
        toMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Long> {
        if (!booking.enabled) return emptyList()
        if (toMillis <= fromMillis) return emptyList()

        val produced = ArrayList<Long>()
        val day = Calendar.getInstance(zone).apply {
            timeInMillis = HeartbeatSlot.startOfDay(fromMillis, zone)
        }

        while (day.timeInMillis < toMillis) {
            // THE RULE ANSWERS THIS, NOT THIS LOOP. Asking slotOn means the "does it fire
            // today" question has one definition shared with the room's own drawing, so a
            // change to what ONCE means cannot apply in one place and not the other.
            val slot = booking.slotOn(day.timeInMillis, zone)
            if (slot != null && slot >= fromMillis && slot < toMillis) {
                produced.add(slot)
            }
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        return produced
    }

    /**
     * The next slot this booking produces strictly after an instant.
     *
     * USED BY THE SCHEDULER and by the room's "next booking" line. Strictly after, because
     * a beat that resolves the half hour it is already in must not be told its next
     * booking is the one it is running.
     *
     * Returns null when the rule has ended or covers no day that qualifies, which is the
     * honest answer rather than a fabricated timestamp a year out.
     */
    fun nextSlotAfter(
        booking: HeartbeatBooking,
        afterMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long? {
        if (!booking.enabled) return null
        if (booking.endsAt > 0L && afterMillis >= booking.endsAt) return null

        // One extra day of slack: the window starts at the next half hour, and the
        // booking's own slot on that day may be later than the window's first instant.
        val from = HeartbeatSlot.next(afterMillis, zone)
        val to = from + (HORIZON_DAYS + 2) * 24L * 60L * 60L * 1000L
        return slotsFor(booking, from, to, zone).firstOrNull()
    }

    /**
     * Every slot any booking produces in a window, deduplicated and sorted.
     *
     * THE DIARY MUST NEVER SHOW TWO BOOKINGS IN ONE HALF HOUR. That is refused at booking
     * time, but a rule edited later could still collide with another, and two cards drawn
     * on top of each other in a timeline is the visible symptom. This resolves it to one
     * slot per half hour, earliest booking first, so the room has nothing to disambiguate.
     */
    fun slotOwners(
        bookings: List<HeartbeatBooking>,
        fromMillis: Long,
        toMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Map<Long, HeartbeatBooking> {
        val owners = LinkedHashMap<Long, HeartbeatBooking>()
        /*
         * ORDERED BY START AND THEN BY ID, so the answer is the same on every run.
         *
         * Two bookings cannot legitimately share a half hour — booking refuses it — but a
         * rule edited later can still collide with one made earlier, and when that happens
         * the room must draw one card rather than two overlapping. Without a tie-break,
         * "who owns this slot" would depend on the order the database happened to return
         * rows in, which is a bug that appears on one phone and not another.
         */
        for (booking in bookings.sortedWith(compareBy({ it.startsAt }, { it.id }))) {
            for (slot in slotsFor(booking, fromMillis, toMillis, zone)) {
                owners.putIfAbsent(slot, booking)
            }
        }
        return owners
    }

    /**
     * True when two bookings claim the same half hour on any day in a window.
     *
     * THE REFUSAL AT BOOKING TIME. Two bookings in one half hour is not a thing to resolve
     * silently at fire time, because the owner asked for both and only one can run. This
     * checks a year ahead of the candidate's own start, which covers every repeat shape
     * this app can express.
     */
    fun collidesWith(
        candidate: HeartbeatBooking,
        existing: List<HeartbeatBooking>,
        zone: TimeZone = TimeZone.getDefault(),
    ): HeartbeatBooking? {
        val from = candidate.startsAt
        val to = from + (HORIZON_DAYS + 2) * 24L * 60L * 60L * 1000L
        val mine = slotsFor(candidate, from, to, zone).toSet()
        if (mine.isEmpty()) return null
        for (other in existing) {
            if (other.id == candidate.id) continue
            val theirs = slotsFor(other, from, to, zone)
            if (theirs.any { it in mine }) return other
        }
        return null
    }
}
