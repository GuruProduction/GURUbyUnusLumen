// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a beat actually sends, in every combination of master prompt and booking.
 *
 * WHY THE COMPOSITION IS TESTED SEPARATELY FROM THE SCREEN. "Alongside master" and
 * "instead of master" are two promises, and the difference between them is the whole
 * reason the control exists. If INSTEAD quietly appended the master prompt, the control
 * would be a lie and nothing would fail: the beat would run, a reply would arrive, and
 * the owner would never learn that the half hour they reserved for one job had his
 * standing instruction bolted onto the front of it.
 *
 * The preview in the room and the injection in the run service both call this function,
 * so these tests are the contract for both.
 */
class HeartbeatInstructionTest {

    private fun booking(
        name: String = "Inbox",
        instruction: String = "Read the inbox and report only what needs a reply.",
        combine: BookingCombine = BookingCombine.ALONGSIDE,
    ) = HeartbeatBooking(
        id = "b",
        name = name,
        instruction = instruction,
        repeat = RepeatKind.DAILY,
        combine = combine,
        enabled = true,
        startsAt = 0L,
    )

    private fun entry(override: String? = null) = HeartbeatEntry(
        id = "e",
        bookingId = "b",
        slotAt = 0L,
        overrideInstruction = override,
    )

    @Test
    fun `with nothing booked the master prompt is sent untouched`() {
        val out = composeBeatInstruction("Look at the day.", null, null)
        assertEquals("Look at the day.", out)
    }

    @Test
    fun `alongside sends both, the master prompt first and the booking named`() {
        val out = composeBeatInstruction("Look at the day.", booking(), entry())
        assertTrue("the master prompt must be there: $out", out.startsWith("Look at the day."))
        assertTrue("the booking must be named: $out", out.contains("\"Inbox\""))
        assertTrue(
            "the instruction must be there: $out",
            out.contains("Read the inbox and report only what needs a reply."),
        )
    }

    @Test
    fun `instead of sends the booking alone and never the master prompt`() {
        val out = composeBeatInstruction(
            masterPrompt = "Look at the day.",
            booking = booking(combine = BookingCombine.INSTEAD),
            entry = entry(),
        )
        assertEquals("Read the inbox and report only what needs a reply.", out)
        assertFalse("the master prompt must not leak in: $out", out.contains("Look at the day."))
    }

    @Test
    fun `a booking alone is still a valid beat when there is no master prompt`() {
        val out = composeBeatInstruction("", booking(), entry())
        assertTrue(out.contains("Read the inbox and report only what needs a reply."))
        // With nothing to sit alongside, the booking is sent as itself rather than with a
        // header about a master prompt that does not exist.
        assertFalse("no header when there is nothing to add to: $out", out.contains("as well"))
    }

    @Test
    fun `no prompt and no booking is empty, which is what refuses the beat`() {
        assertEquals("", composeBeatInstruction("", null, null))
        assertEquals("", composeBeatInstruction("   ", null, null))
    }

    @Test
    fun `a whitespace master prompt with a booking sends the booking`() {
        val out = composeBeatInstruction("   \n  ", booking(), entry())
        assertTrue(out.contains("Read the inbox"))
    }

    @Test
    fun `an occurrence override replaces that occurrence's instruction alone`() {
        val original = booking()
        val edited = entry(override = "Just tell me if anything arrived from the bank.")
        val out = composeBeatInstruction("Look at the day.", original, edited)
        assertTrue(out.contains("Just tell me if anything arrived from the bank."))
        assertFalse(
            "the rule's own text must not also be sent: $out",
            out.contains("Read the inbox and report only what needs a reply."),
        )
    }

    @Test
    fun `a blank override falls back to the rule rather than sending nothing`() {
        val out = composeBeatInstruction("Look at the day.", booking(), entry(override = "   "))
        assertTrue(out.contains("Read the inbox and report only what needs a reply."))
    }

    @Test
    fun `an entry with no booking sends the override alone`() {
        val out = composeBeatInstruction(
            masterPrompt = "Look at the day.",
            booking = null,
            entry = entry(override = "Check the doorbell camera."),
        )
        assertEquals("Look at the day.\n\nThis half hour is booked in your diary. Do this as well:\nCheck the doorbell camera.", out)
    }

    @Test
    fun `a blank booking name still produces a sentence rather than an empty quote`() {
        val out = composeBeatInstruction(
            "Look at the day.",
            booking(name = "  "),
            entry(),
        )
        assertFalse("an empty name must not print as \"\": $out", out.contains("\"\""))
        assertTrue(out.contains("Do this as well"))
    }
}
