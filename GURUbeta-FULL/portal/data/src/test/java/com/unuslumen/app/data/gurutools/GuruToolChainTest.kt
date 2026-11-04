// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the composition-recursion guard, because issue 298's fix introduces the
 * one failure mode a tool framework must never produce: a stack overflow.
 *
 * THE DEFECT. A composition could only step on a BUILT-IN tool, because a
 * GURU-defined tool never enters `ToolRegistry`. Making the fall-through work is
 * what lets a tool chain its own tools, and it is also what lets two tools name
 * each other. Without this guard the answer to that is a StackOverflowError,
 * which names no tool, no chain and no cause, from a framework whose entire
 * promise is that a tool which cannot run says why.
 */
class GuruToolChainTest {

    @Test
    fun theFirstEntryIsAlwaysAdmitted() {
        val admission = GuruToolChain.admit(emptyList(), "bush_tv")
        assertTrue(admission is GuruToolChain.Admission.Allowed)
        assertEquals(listOf("bush_tv"), (admission as GuruToolChain.Admission.Allowed).next)
    }

    @Test
    fun aDistinctChainIsAdmittedAndExtended() {
        val admission = GuruToolChain.admit(listOf("outer", "middle"), "inner")
        assertEquals(
            listOf("outer", "middle", "inner"),
            (admission as GuruToolChain.Admission.Allowed).next,
        )
    }

    @Test
    fun aDirectSelfCallIsRefused() {
        val admission = GuruToolChain.admit(listOf("a"), "a")
        assertTrue(admission is GuruToolChain.Admission.Refused)
        val reason = (admission as GuruToolChain.Admission.Refused).reason
        assertTrue("The refusal must name the tool: $reason", reason.contains("a -> a"))
    }

    @Test
    fun anIndirectCycleNamesTheWholeLoop() {
        val admission = GuruToolChain.admit(listOf("a", "b"), "a")
        val reason = (admission as GuruToolChain.Admission.Refused).reason
        assertTrue(
            "An indirect cycle must show every hop, not just the repeat: $reason",
            reason.contains("a -> b -> a"),
        )
    }

    @Test
    fun aRepeatThatIsNotATailCycleStillNamesTheLoop() {
        // outer -> a -> b -> c -> a: the loop starts at the first 'a', not at the
        // head of the chain, and the message has to show the loop itself.
        val admission = GuruToolChain.admit(listOf("outer", "a", "b", "c"), "a")
        val reason = (admission as GuruToolChain.Admission.Refused).reason
        assertTrue(reason.contains("a -> b -> c -> a"))
        assertTrue("The message must not claim 'outer' is part of the cycle", !reason.contains("outer -> a -> b"))
    }

    @Test
    fun anAcyclicChainPastTheDepthLimitIsRefusedRatherThanOverflowing() {
        val deep = (1..GuruToolChain.MAX_DEPTH).map { "t$it" }
        val admission = GuruToolChain.admit(deep, "one_more")
        assertTrue(admission is GuruToolChain.Admission.Refused)
        assertTrue((admission as GuruToolChain.Admission.Refused).reason.contains("${GuruToolChain.MAX_DEPTH}"))
    }

    @Test
    fun theDepthLimitIsGenerousEnoughToBeUseful() {
        // A guard that trips on an ordinary composition would be its own defect.
        assertTrue("MAX_DEPTH must leave room for real compositions", GuruToolChain.MAX_DEPTH >= 8)
    }
}
