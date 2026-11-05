// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The calendar arithmetic, proved without a device.
 *
 * WHY THIS FILE MATTERS MORE THAN THE SCREEN. Everything a person sees in the diary is a
 * consequence of these functions. An off-by-one here does not crash anything: it puts a
 * booking on the wrong day, or silently drops its last week, or gives two bookings the
 * same half hour. None of that is visible until somebody's Tuesday is missing, and by
 * then it is history rather than a bug report.
 *
 * THE ZONE IS PINNED. Every case runs in a fixed zone so a laptop in another time zone
 * produces the same answers, and so a daylight-saving boundary can be tested on purpose
 * rather than discovered in production.
 */
class HeartbeatExpansionTest {

    private val london = TimeZone.getTimeZone("Europe/London")

    /** A local wall-clock instant, so the expectations read like a diary rather than numbers. */
    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: TimeZone = london,
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun clockOf(millis: Long, zone: TimeZone = london): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return "%02d:%02d".format(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    // ---------- the half hour itself ----------

    @Test
    fun `an instant floors to the half hour it is in`() {
        assertEquals(at(2026, 11, 5, 9, 0), HeartbeatSlot.floor(at(2026, 11, 5, 9, 0), london))
        assertEquals(at(2026, 11, 5, 9, 0), HeartbeatSlot.floor(at(2026, 11, 5, 9, 14), london))
        assertEquals(at(2026, 11, 5, 9, 0), HeartbeatSlot.floor(at(2026, 11, 5, 9, 29), london))
        assertEquals(at(2026, 11, 5, 9, 30), HeartbeatSlot.floor(at(2026, 11, 5, 9, 30), london))
        assertEquals(at(2026, 11, 5, 9, 30), HeartbeatSlot.floor(at(2026, 11, 5, 9, 59), london))
    }

    @Test
    fun `the next slot is the next half hour, never the current one`() {
        val nineFourteen = at(2026, 11, 5, 9, 14)
        assertEquals(at(2026, 11, 5, 9, 30), HeartbeatSlot.next(nineFourteen, london))
        // Exactly on a boundary still moves forward, so a beat at 09:30 books 10:00 and
        // cannot rebook the half hour it is already running in.
        assertEquals(at(2026, 11, 5, 10, 0), HeartbeatSlot.next(at(2026, 11, 5, 9, 30), london))
    }

    @Test
    fun `a booking only ever sits on a half hour`() {
        assertTrue(HeartbeatSlot.isOnSlot(at(2026, 11, 5, 9, 30), london))
        assertFalse(HeartbeatSlot.isOnSlot(at(2026, 11, 5, 9, 31), london))
        assertFalse(HeartbeatSlot.isOnSlot(at(2026, 11, 5, 9, 0, london) + 1, london))
    }

    @Test
    fun `weekday index is monday based and wraps sunday to six`() {
        // 5 November 2026 is a Thursday, which is index 3.
        assertEquals(3, HeartbeatSlot.weekdayIndex(at(2026, 11, 5, 9, 0), london))
        // 8 November 2026 is a Sunday, which must be 6 rather than -1.
        assertEquals(6, HeartbeatSlot.weekdayIndex(at(2026, 11, 8, 9, 0), london))
        // 2 November 2026 is a Monday, the zero.
        assertEquals(0, HeartbeatSlot.weekdayIndex(at(2026, 11, 2, 9, 0), london))
    }

    // ---------- a rule and its days ----------

    @Test
    fun `a weekday booking produces monday to friday and nothing at the weekend`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Commute",
            instruction = "Check the roads.",
            repeat = RepeatKind.WEEKDAYS,
            enabled = true,
            startsAt = at(2026, 11, 2, 8, 30),
        )
        assertTrue(booking.occursOn(at(2026, 11, 5, 0, 0), london))
        assertTrue(booking.occursOn(at(2026, 11, 6, 0, 0), london))
        assertFalse(booking.occursOn(at(2026, 11, 7, 0, 0), london))
        assertFalse(booking.occursOn(at(2026, 11, 8, 0, 0), london))
    }

    @Test
    fun `a once booking covers only the day it starts on`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "One off",
            instruction = "Do the thing.",
            repeat = RepeatKind.ONCE,
            enabled = true,
            startsAt = at(2026, 11, 5, 14, 0),
        )
        assertTrue(booking.occursOn(at(2026, 11, 5, 0, 0), london))
        assertFalse(booking.occursOn(at(2026, 11, 6, 0, 0), london))
        // The following Thursday is the same weekday, and must still not fire.
        assertFalse(booking.occursOn(at(2026, 11, 12, 0, 0), london))
    }

    @Test
    fun `a chosen-days booking fires only on its own days`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Money",
            instruction = "Check the account.",
            repeat = RepeatKind.CHOSEN,
            chosenDays = setOf(1, 3),
            enabled = true,
            startsAt = at(2026, 11, 3, 10, 30),
        )
        // Tuesday the 3rd and Thursday the 5th.
        assertTrue(booking.occursOn(at(2026, 11, 3, 0, 0), london))
        assertTrue(booking.occursOn(at(2026, 11, 5, 0, 0), london))
        assertFalse(booking.occursOn(at(2026, 11, 4, 0, 0), london))
        assertFalse(booking.occursOn(at(2026, 11, 6, 0, 0), london))
    }

    @Test
    fun `a booking never fires before its own first occurrence`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Later",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 11, 5, 9, 0),
        )
        assertFalse(booking.occursOn(at(2026, 11, 4, 0, 0), london))
        assertTrue(booking.occursOn(at(2026, 11, 5, 0, 0), london))
    }

    @Test
    fun `a disabled booking produces nothing at all`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Off",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = false,
            startsAt = at(2026, 11, 5, 9, 0),
        )
        assertFalse(booking.occursOn(at(2026, 11, 5, 0, 0), london))
        assertTrue(
            HeartbeatExpansion
                .slotsFor(booking, at(2026, 11, 5, 0, 0), at(2026, 11, 12, 0, 0), london)
                .isEmpty()
        )
    }

    // ---------- expansion across a range ----------

    @Test
    fun `a week of a daily booking is exactly seven slots at the same time of day`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Daily",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 11, 2, 9, 0),
        )
        val slots = HeartbeatExpansion.slotsFor(
            booking,
            at(2026, 11, 2, 0, 0),
            at(2026, 11, 9, 0, 0),
            london,
        )
        assertEquals(7, slots.size)
        assertTrue("every slot must be at 09:00", slots.all { clockOf(it) == "09:00" })
    }

    @Test
    fun `a year of a daily booking is 365 slots, not 364 and not 366`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Daily",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 1, 1, 9, 0),
        )
        val slots = HeartbeatExpansion.slotsFor(
            booking,
            at(2026, 1, 1, 0, 0),
            at(2027, 1, 1, 0, 0),
            london,
        )
        // 2026 is not a leap year, so a whole calendar year is 365 days.
        assertEquals(365, slots.size)
        assertEquals(slots.size, slots.distinct().size)
    }

    @Test
    fun `a booking whose end has passed produces nothing beyond it`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Ends",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 11, 2, 9, 0),
            endsAt = at(2026, 11, 4, 9, 0),
        )
        val slots = HeartbeatExpansion.slotsFor(
            booking,
            at(2026, 11, 2, 0, 0),
            at(2026, 11, 9, 0, 0),
            london,
        )
        assertEquals(3, slots.size)
        assertTrue(slots.all { it <= at(2026, 11, 4, 9, 0) })
    }

    @Test
    fun `the first slot of a week is the one on the booking's own day`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Thursday",
            instruction = "Something.",
            repeat = RepeatKind.CHOSEN,
            chosenDays = setOf(3),
            enabled = true,
            startsAt = at(2026, 11, 5, 9, 30),
        )
        val slots = HeartbeatExpansion.slotsFor(
            booking,
            at(2026, 11, 2, 0, 0),
            at(2026, 11, 9, 0, 0),
            london,
        )
        assertEquals(1, slots.size)
        assertEquals("09:30", clockOf(slots.first()))
        assertEquals(3, HeartbeatSlot.weekdayIndex(slots.first(), london))
    }

    // ---------- next slot ----------

    @Test
    fun `the next slot after an instant is strictly later, even on the same day`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Daily",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 11, 2, 9, 0),
        )
        val next = HeartbeatExpansion.nextSlotAfter(booking, at(2026, 11, 5, 8, 0), london)
        assertEquals(at(2026, 11, 5, 9, 0), next)

        // At exactly its own time, the next one is tomorrow rather than now.
        val after = HeartbeatExpansion.nextSlotAfter(booking, at(2026, 11, 5, 9, 0), london)
        assertEquals(at(2026, 11, 6, 9, 0), after)
    }

    @Test
    fun `a booking with no qualifying day answers null rather than inventing a time`() {
        val booking = HeartbeatBooking(
            id = "b",
            name = "Never",
            instruction = "Something.",
            repeat = RepeatKind.CHOSEN,
            chosenDays = emptySet(),
            enabled = true,
            startsAt = at(2026, 11, 5, 9, 0),
        )
        assertNull(HeartbeatExpansion.nextSlotAfter(booking, at(2026, 11, 5, 8, 0), london))
    }

    // ---------- collisions ----------

    @Test
    fun `two bookings in the same half hour are refused and the other one is named`() {
        val first = HeartbeatBooking(
            id = "a",
            name = "Inbox",
            instruction = "Read mail.",
            repeat = RepeatKind.WEEKDAYS,
            enabled = true,
            startsAt = at(2026, 11, 5, 9, 0),
        )
        val second = first.copy(id = "b", name = "Calendar")

        val owner = HeartbeatExpansion.collidesWith(second, listOf(first), london)
        assertEquals("Inbox", owner?.name)
    }

    @Test
    fun `the same half hour on different days is not a collision`() {
        val monday = HeartbeatBooking(
            id = "a",
            name = "Monday",
            instruction = "Something.",
            repeat = RepeatKind.CHOSEN,
            chosenDays = setOf(0),
            enabled = true,
            startsAt = at(2026, 11, 2, 9, 0),
        )
        val tuesday = HeartbeatBooking(
            id = "b",
            name = "Tuesday",
            instruction = "Something else.",
            repeat = RepeatKind.CHOSEN,
            chosenDays = setOf(1),
            enabled = true,
            startsAt = at(2026, 11, 3, 9, 0),
        )
        assertNull(HeartbeatExpansion.collidesWith(tuesday, listOf(monday), london))
    }

    @Test
    fun `a booking never collides with itself`() {
        val booking = HeartbeatBooking(
            id = "a",
            name = "Inbox",
            instruction = "Read mail.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 11, 5, 9, 0),
        )
        assertNull(HeartbeatExpansion.collidesWith(booking, listOf(booking), london))
    }

    @Test
    fun `slot ownership resolves one booking per half hour and the earliest wins`() {
        val early = HeartbeatBooking(
            id = "a",
            name = "Early",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 11, 5, 9, 0),
        )
        // Deliberately overlapping, even though booking refuses it: this proves the
        // resolver has an answer rather than drawing two cards on one row.
        val late = early.copy(id = "b", name = "Late")

        val owners = HeartbeatExpansion.slotOwners(
            listOf(late, early),
            at(2026, 11, 5, 0, 0),
            at(2026, 11, 6, 0, 0),
            london,
        )
        assertEquals(1, owners.size)
        assertEquals("Early", owners.values.first().name)
    }

    // ---------- daylight saving ----------

    @Test
    fun `a daily booking keeps its wall clock time across a daylight saving change`() {
        // The UK moves the clocks on 25 October 2026, so 09:00 on the 24th and 09:00 on
        // the 26th are twenty-five hours apart in real time and must still both be 09:00.
        val booking = HeartbeatBooking(
            id = "b",
            name = "Across the change",
            instruction = "Something.",
            repeat = RepeatKind.DAILY,
            enabled = true,
            startsAt = at(2026, 10, 20, 9, 0),
        )
        val slots = HeartbeatExpansion.slotsFor(
            booking,
            at(2026, 10, 20, 0, 0),
            at(2026, 10, 28, 0, 0),
            london,
        )
        assertEquals(8, slots.size)
        assertTrue(
            "every occurrence must be at 09:00 wall clock: ${slots.map { clockOf(it) }}",
            slots.all { clockOf(it) == "09:00" },
        )
    }
}
