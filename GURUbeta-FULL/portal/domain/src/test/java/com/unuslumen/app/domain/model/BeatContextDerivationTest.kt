// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Issue 327's three facts, computed rather than vibed.
 *
 * Every fixture uses a FIXED timezone and absolute instants built from a calendar, because the
 * rules are slot arithmetic and a test that ran against the default zone would pass or fail
 * depending on where the build machine is.
 */
class BeatContextDerivationTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Europe/London")

    /** A fixed instant: 14:00 on a Tuesday, well away from any midnight or DST edge. */
    private fun at(hour: Int, minute: Int, day: Int = 10): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        }.timeInMillis

    private val noon = at(14, 0)

    @Test
    fun aHumanWhoSpokeInThisSlotLeavesZeroIdleBeats() {
        // BOTH INSIDE THE SAME HALF HOUR. 13:45 and 14:00 are in different slots (13:30 and
        // 14:00), which is correct arithmetic and not what this test is about; the question here
        // is whether a message inside the slot the beat woke in leaves a gap at all.
        assertEquals(
            "the beat woke in the same half hour they spoke, so nothing has gone unanswered",
            0,
            BeatContextDerivation.idleBeats(at(14, 20), at(14, 5), zone),
        )
    }

    @Test
    fun eachHalfHourOfSilenceIsOneIdleBeat() {
        // 14:00 now, spoke at 13:00: the 13:00, 13:30 and 14:00 slots have passed.
        assertEquals(2, BeatContextDerivation.idleBeats(noon, at(13, 0), zone))
        // Spoke at 11:00: 11:00, 11:30, 12:00, 12:30, 13:00, 13:30, 14:00 minus the shared slot.
        assertEquals(6, BeatContextDerivation.idleBeats(noon, at(11, 0), zone))
    }

    @Test
    fun aWholeDayOfSilenceCountsFortyEight() {
        assertEquals(
            "a day of GURU talking to itself is 48 half hours",
            48,
            BeatContextDerivation.idleBeats(noon, at(14, 0, day = 9), zone),
        )
    }

    @Test
    fun noHumanMessageEverMeansZeroRatherThanAnInventedNumber() {
        assertEquals(
            "there is no gap to measure, so the prompt gets 0 and can still ask why nobody spoke",
            0,
            BeatContextDerivation.idleBeats(noon, null, zone),
        )
        assertEquals(0, BeatContextDerivation.idleBeats(noon, 0L, zone))
    }

    @Test
    fun aFutureTimestampIsZeroNotNegative() {
        assertEquals(
            "a clock change must never produce a negative count in a prompt",
            0,
            BeatContextDerivation.idleBeats(noon, at(15, 0), zone),
        )
    }

    @Test
    fun aGapReadsAsPlainEnglish() {
        // 13:59 to 14:00 is exactly one minute. The phrase is built from whole minutes, so the
        // sub-minute case needs a gap of seconds rather than a minute boundary.
        assertEquals("1 minute", BeatContextDerivation.silentFor(noon, at(13, 59)))
        assertEquals(
            "less than a minute",
            BeatContextDerivation.silentFor(at(14, 0) + 30_000L, at(14, 0)),
        )
        assertEquals("5 minutes", BeatContextDerivation.silentFor(noon, at(13, 55)))
        assertEquals("1 hour", BeatContextDerivation.silentFor(noon, at(13, 0)))
        assertEquals("3 hours 20 minutes", BeatContextDerivation.silentFor(noon, at(10, 40)))
        assertEquals("1 day", BeatContextDerivation.silentFor(noon, at(14, 0, day = 9)))
        assertEquals(
            "2 days 3 hours",
            BeatContextDerivation.silentFor(noon, at(11, 0, day = 8)),
        )
    }

    @Test
    fun minutesAreDroppedOnceThereAreDays() {
        // "1 day 0 minutes" is noise, and a prompt repeating it reads as a machine.
        val sentence = BeatContextDerivation.silentFor(noon, at(14, 0, day = 9))
        assertTrue(
            "a whole-day gap must not carry a minutes clause: $sentence",
            !sentence.contains("minute"),
        )
    }

    @Test
    fun aSingularUnitIsNotPluralised() {
        assertEquals("1 minute", BeatContextDerivation.silentFor(at(14, 1), at(14, 0)))
        assertEquals("1 hour", BeatContextDerivation.silentFor(noon, at(13, 0)))
        assertEquals("1 day", BeatContextDerivation.silentFor(noon, at(14, 0, day = 9)))
    }

    @Test
    fun noGapIsAnEmptyStringRatherThanZeroMinutes() {
        assertEquals("", BeatContextDerivation.silentFor(noon, null))
        assertEquals("", BeatContextDerivation.silentFor(noon, 0L))
    }

    @Test
    fun aPreviousBeatNoteIsTrimmedToSomethingAPromptCanHold() {
        val long = "x".repeat(900)
        val note = BeatContextDerivation.lastBeatNote(long)
        assertTrue("a long note must be bounded: ${note.length}", note.length <= 400)
        assertTrue("and must show it was cut: ${note.takeLast(1)}", note.endsWith("\u2026"))
    }

    @Test
    fun anEmptyPreviousNoteStaysEmpty() {
        assertEquals(
            "the token substituting to nothing is the honest answer when the last beat did nothing",
            "",
            BeatContextDerivation.lastBeatNote(null),
        )
        assertEquals("", BeatContextDerivation.lastBeatNote("   "))
        assertEquals("already fine", BeatContextDerivation.lastBeatNote(" already fine "))
    }

    @Test
    fun aShortNoteIsUntouched() {
        val note = "sent a notification about the failing build"
        assertEquals(note, BeatContextDerivation.lastBeatNote(note))
    }
}
