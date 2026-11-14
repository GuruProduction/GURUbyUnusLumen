// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolResultOutcome tests.
 *
 * Each of these is a case the old substring test got wrong. The two that matter
 * most are `an error field with no success flag is a failure` — the actual shape
 * of five real journal entries stored as successes in the owner's database — and
 * `a result that merely mentions the flag is not a failure`, which is the same
 * defect facing the other way.
 */
class ToolResultOutcomeTest {

    @Test
    fun `an explicit success false is a failure`() {
        assertTrue(
            ToolResultOutcome.isFailure("""{"success":false,"error":"no such app"}"""),
        )
    }

    @Test
    fun `an error field with no success flag is a failure`() {
        // This is the exact payload five createJournalEntry calls returned, and
        // all five were stored with success = 1 because the old test only looked
        // for an explicit "success":false.
        assertTrue(
            ToolResultOutcome.isFailure("""{"error":"Invalid mood: EXCITED"}"""),
        )
    }

    @Test
    fun `an ok flag set false is a failure`() {
        assertTrue(ToolResultOutcome.isFailure("""{"ok":false}"""))
    }

    @Test
    fun `a string success field saying false is a failure`() {
        assertTrue(ToolResultOutcome.isFailure("""{"success":"false"}"""))
    }

    @Test
    fun `an explicit success true is not a failure`() {
        assertFalse(ToolResultOutcome.isFailure("""{"success":true,"count":4}"""))
    }

    @Test
    fun `a result with no status field is not a failure`() {
        assertFalse(ToolResultOutcome.isFailure("""{"temperature":14.2,"condition":"cloudy"}"""))
    }

    @Test
    fun `a result that merely mentions the flag is not a failure`() {
        // Reading a config file, echoing a log, describing a schema: all real
        // results that quote the literal text. The old substring test called
        // every one of them a failed call.
        assertFalse(
            ToolResultOutcome.isFailure(
                """{"content":"the setting is \"success\":false in the file"}""",
            ),
        )
    }

    @Test
    fun `a null or empty error is not a failure`() {
        assertFalse(ToolResultOutcome.isFailure("""{"error":null}"""))
        assertFalse(ToolResultOutcome.isFailure("""{"error":""}"""))
        assertFalse(ToolResultOutcome.isFailure("""{"error":"none"}"""))
    }

    @Test
    fun `a non-boolean success beside an error string is decided by the flag`() {
        // A tool that reports success=true AND carries a stale error string has
        // said what it thinks. The explicit flag is the deliberate statement.
        assertFalse(
            ToolResultOutcome.isFailure("""{"success":true,"error":"previous attempt failed"}"""),
        )
    }

    @Test
    fun `a transport failure outranks a clean payload`() {
        assertTrue(
            ToolResultOutcome.isFailure("""{"anything":"at all"}""", transportFailed = true),
        )
    }

    @Test
    fun `a blank body from a call that did not throw is not a failure`() {
        assertFalse(ToolResultOutcome.isFailure(""))
    }

    @Test
    fun `a body that is not JSON is not claimed as a failure`() {
        // A plain-text result is a real answer from several tools. Reporting it
        // as a failure would be inventing a verdict the payload never gave.
        assertFalse(ToolResultOutcome.isFailure("Recording saved to /sdcard/rec.mp4"))
    }
}
