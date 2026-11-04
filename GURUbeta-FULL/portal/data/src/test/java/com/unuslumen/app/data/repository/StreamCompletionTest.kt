// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the rule that ended issue 307: a turn may only be reported as finished
 * when the protocol said it was finished.
 *
 * THE DEFECT. A Kotlin `flow { }` builder cannot distinguish two endings that look
 * identical from outside it: the producer saw `done: true`, or the producer's
 * input simply ran out. Both are normal completion. So a dropped connection read
 * as a finished turn, GURU stopped mid-task, and nobody was told.
 *
 * These tests pin the distinction, so nobody can quietly collapse it again.
 */
class StreamCompletionTest {

    @Test
    fun onlyTheProtocolTerminalEventCountsAsFinished() {
        assertTrue(
            "done: true is the only ending that may be reported as a finished turn",
            StreamCompletion.isTerminalSuccess(StreamCompletion.Ending.COMPLETE),
        )
        for (ending in StreamCompletion.Ending.values()) {
            if (ending == StreamCompletion.Ending.COMPLETE) continue
            assertFalse(
                "$ending must never be reported as a finished turn. Treating an " +
                    "incomplete ending as complete is the whole of issue 307.",
                StreamCompletion.isTerminalSuccess(ending),
            )
        }
    }

    @Test
    fun aTruncatedStreamSaysWhatWasLost() {
        val withTools = StreamCompletion.message(
            StreamCompletion.Ending.TRUNCATED,
            linesRead = 14,
            pendingToolCalls = 2,
            textLength = 300,
        )
        assertTrue(
            "The sentence must name the lost tool calls, or a reader cannot tell that " +
                "GURU intended to do something: $withTools",
            withTools.contains("2 tool call(s) that were never run"),
        )
        assertTrue("It must state the line count it did read: $withTools", withTools.contains("14 line(s)"))

        val withText = StreamCompletion.message(
            StreamCompletion.Ending.TRUNCATED,
            linesRead = 9,
            pendingToolCalls = 0,
            textLength = 512,
        )
        assertTrue(
            "With text and no tool calls, the sentence must say how much arrived: $withText",
            withText.contains("512 character(s)"),
        )
    }

    @Test
    fun aTruncatedStreamNeverClaimsTheModelChoseToStop() {
        val message = StreamCompletion.message(
            StreamCompletion.Ending.TRUNCATED,
            linesRead = 3,
            pendingToolCalls = 1,
            textLength = 10,
        )
        assertTrue(
            "The sentence must say plainly that this is not the model deciding to finish, " +
                "because that misreading is exactly what the owner reported: $message",
            message.contains("not the model choosing to stop"),
        )
    }

    @Test
    fun aTruncatedStreamIsRetryableAndACancellationIsNot() {
        assertTrue(
            "A truncated stream began from a valid request, so the same request can succeed",
            StreamCompletion.isRetryable(StreamCompletion.Ending.TRUNCATED),
        )
        assertFalse(
            "A cancellation was asked for; retrying it would undo the user's stop",
            StreamCompletion.isRetryable(StreamCompletion.Ending.CANCELLED),
        )
    }

    @Test
    fun aMalformedLineIsNotRetriedBecauseTheSameRequestRepeatsIt() {
        assertFalse(
            "The same request produces the same bad line, so a retry is a loop",
            StreamCompletion.isRetryable(StreamCompletion.Ending.MALFORMED),
        )
    }

    @Test
    fun everyEndingHasASentence() {
        for (ending in StreamCompletion.Ending.values()) {
            val message = StreamCompletion.message(ending, linesRead = 1, pendingToolCalls = 0, textLength = 0)
            assertTrue("$ending has no sentence a user could read", message.isNotBlank())
        }
    }

    @Test
    fun theCompleteEndingClaimsNothingWentWrong() {
        val message = StreamCompletion.message(
            StreamCompletion.Ending.COMPLETE,
            linesRead = 20,
            pendingToolCalls = 0,
            textLength = 900,
        )
        assertEquals("The stream finished normally after 20 line(s).", message)
    }
}
