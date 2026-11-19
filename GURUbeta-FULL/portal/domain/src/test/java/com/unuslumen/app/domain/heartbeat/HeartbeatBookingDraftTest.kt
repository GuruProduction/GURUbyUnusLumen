// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.heartbeat

import com.unuslumen.app.domain.model.RepeatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Turning what a model asks for into a booking the diary will accept (issue 488).
 *
 * THE GAP. Heartbeat had a complete data model, a run service, an alarm chain and a diary
 * screen, and no tool a model could call. Two agent prompts depended on it: the Self
 * Improvement Engine is told to use "the actual Heartbeat interface and capabilities
 * available to you" to keep a development agenda, and Archivist relies on scheduled
 * sessions to do its curation work.
 *
 * These tests hold the rules a booking must satisfy, which is the half that can be proven
 * without a device. The write path itself goes through the repository's own contract.
 */
class HeartbeatBookingDraftTest {

    private val nine = run {
        val c = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        c.timeInMillis
    }

    private fun build(
        name: String? = "Nightly curation",
        instruction: String? = "Read the day's conversations and file what matters.",
        repeatKind: String? = "DAILY",
        chosenDays: List<Int>? = null,
        startMillis: Long = nine,
        endsAtMillis: Long = 0L,
        combine: String? = null,
    ) = HeartbeatBookingDraft.build(
        name = name,
        instruction = instruction,
        repeatKindName = repeatKind,
        chosenDays = chosenDays,
        startMillis = startMillis,
        endsAtMillis = endsAtMillis,
        enabled = true,
        combineName = combine,
        // NOW IS THE REAL CLOCK, not a fixed instant. The fixture's own dates are real
        // dates in November 2026, and the end-in-the-past rule is only meaningful against a
        // now that is later than them.
        nowMillis = System.currentTimeMillis(),
    )

    @Test
    fun a_complete_booking_is_ready() {
        val ready = build() as HeartbeatBookingDraft.Result.Ready
        assertEquals("Nightly curation", ready.booking.name)
        assertEquals(RepeatKind.DAILY, ready.booking.repeat)
        assertTrue(ready.booking.id.isNotEmpty())
    }

    @Test
    fun a_booking_with_no_name_or_instruction_is_refused() {
        assertTrue(build(name = "") is HeartbeatBookingDraft.Result.Invalid)
        assertTrue(build(instruction = "   ") is HeartbeatBookingDraft.Result.Invalid)
    }

    @Test
    fun an_unknown_repeat_kind_is_refused_with_the_real_set() {
        // A refusal that names the set teaches a caller; one that says "invalid" makes it
        // guess, and a guess at a repeat rule is a booking that fires on the wrong days.
        val invalid = build(repeatKind = "FORTNIGHTLY") as HeartbeatBookingDraft.Result.Invalid
        assertTrue(invalid.reason.contains("FORTNIGHTLY"))
        assertTrue(invalid.reason.contains("DAILY"))
        assertTrue(invalid.reason.contains("CHOSEN"))
    }

    @Test
    fun an_absent_repeat_kind_defaults_to_once() {
        assertEquals(RepeatKind.ONCE, (build(repeatKind = null) as HeartbeatBookingDraft.Result.Ready).booking.repeat)
    }

    @Test
    fun chosen_with_no_days_is_refused_because_it_would_never_fire() {
        // THE COMBINATION THAT LOOKS SAVED AND DOES NOTHING. CHOSEN with an empty day set
        // is a rule with no occurrence, and in a diary it reads as booked.
        val invalid = build(repeatKind = "CHOSEN") as HeartbeatBookingDraft.Result.Invalid
        assertTrue("must say why: ${invalid.reason}", invalid.reason.contains("CHOSEN"))
        assertTrue(invalid.reason.contains("never fire"))
    }

    @Test
    fun chosen_with_days_keeps_them() {
        val ready = build(repeatKind = "CHOSEN", chosenDays = listOf(0, 2, 4)) as HeartbeatBookingDraft.Result.Ready
        assertEquals(setOf(0, 2, 4), ready.booking.chosenDays)
    }

    @Test
    fun days_outside_the_week_are_dropped_rather_than_stored() {
        // 9 is not a weekday. Storing it would put a day in the mask the alarm chain cannot
        // read, so it is filtered at the door.
        val ready = build(repeatKind = "CHOSEN", chosenDays = listOf(0, 9, -1)) as HeartbeatBookingDraft.Result.Ready
        assertEquals(setOf(0), ready.booking.chosenDays)
    }

    @Test
    fun days_are_ignored_for_a_kind_that_does_not_use_them() {
        // DAILY covers every day; a chosen set beside it is noise the diary would print.
        val ready = build(repeatKind = "DAILY", chosenDays = listOf(0, 1)) as HeartbeatBookingDraft.Result.Ready
        assertTrue(ready.booking.chosenDays.isEmpty())
    }

    @Test
    fun an_end_before_the_start_is_refused() {
        val invalid = build(endsAtMillis = nine - 86_400_000L) as HeartbeatBookingDraft.Result.Invalid
        assertTrue(invalid.reason.contains("before"))
    }

    @Test
    fun an_end_already_in_the_past_is_refused() {
        // A booking whose window has closed can never fire, and the diary would show a rule
        // the beat will never act on.
        //
        // BOTH THE START AND THE END ARE IN THE PAST HERE, and the end is after the start, so
        // the refusal that fires is the closed-window one rather than the end-before-start
        // one. A fixture with a future start and a past end would be refused for the other
        // reason and would prove nothing about this rule.
        val pastStart = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000
        val pastEnd = pastStart + 24L * 60 * 60 * 1000
        val invalid = build(startMillis = pastStart, endsAtMillis = pastEnd) as HeartbeatBookingDraft.Result.Invalid
        assertTrue("must name the closed window: ${invalid.reason}", invalid.reason.contains("past"))
    }

    @Test
    fun a_missing_start_is_refused() {
        assertTrue(build(startMillis = 0L) is HeartbeatBookingDraft.Result.Invalid)
    }

    @Test
    fun an_unknown_combine_mode_is_refused_with_the_real_set() {
        val invalid = build(combine = "OVERWRITE") as HeartbeatBookingDraft.Result.Invalid
        assertTrue(invalid.reason.contains("OVERWRITE"))
        assertTrue(invalid.reason.contains("ALONGSIDE"))
    }

    // ------------------------------------------------------------------
    // The half hour, which is the only granularity the alarm chain has.
    // ------------------------------------------------------------------

    @Test
    fun a_time_between_half_hours_snaps_down_to_the_half_hour() {
        // The alarm chain fires on half hours only, so 14:17 cannot be expressed. It snaps
        // to 14:00 and the reply says so, rather than silently becoming something else.
        val zone = TimeZone.getDefault()
        val c = Calendar.getInstance(zone).apply {
            set(2026, Calendar.NOVEMBER, 20, 14, 17, 33)
            set(Calendar.MILLISECOND, 0)
        }
        val snapped = HeartbeatBookingDraft.snapToHalfHour(c.timeInMillis, zone)
        val check = Calendar.getInstance(zone).apply { timeInMillis = snapped }
        assertEquals(14, check.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, check.get(Calendar.MINUTE))
        assertEquals(0, check.get(Calendar.SECOND))
    }

    @Test
    fun an_already_on_the_half_hour_time_is_left_alone() {
        val zone = TimeZone.getDefault()
        val c = Calendar.getInstance(zone).apply {
            set(2026, Calendar.NOVEMBER, 20, 9, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(c.timeInMillis, HeartbeatBookingDraft.snapToHalfHour(c.timeInMillis, zone))
    }

    @Test
    fun the_slot_is_described_in_words_not_millis() {
        // A timestamp is a machine's answer to "when does this run"; the owner reading a
        // diary wants the day and the time.
        val described = HeartbeatBookingDraft.describeSlot(nine)
        assertTrue("must name the day and time: $described", described.contains("20"))
        assertTrue(described.contains("09:00"))
    }
}
