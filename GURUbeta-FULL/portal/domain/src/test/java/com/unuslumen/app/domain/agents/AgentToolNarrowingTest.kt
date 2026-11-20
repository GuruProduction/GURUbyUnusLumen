// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A local restriction on an imported agent, and the one direction it may move in.
 *
 * THE SPECIFICATION ASKS FOR ISOLATION OF EXTERNALLY SOURCED AGENTS. A catalogue agent arrives with
 * exactly what the publisher shipped, which is right, and there was no way to hold it to less. The
 * risk in adding one is that it becomes a way to grant more instead, so the tests pin the direction
 * harder than they pin the feature.
 */
class AgentToolNarrowingTest {

    private val published = listOf("captureScreen", "sendSms", "webSearch")

    @Test
    fun anEmptyRestrictionMeansEverythingRatherThanNothing() {
        /*
         * THE DEFAULT STATE OF EVERY AGENT IS AN EMPTY SET. Reading it as "may use nothing" would
         * break every catalogue agent on the phone the moment this shipped, so an empty set has to
         * mean no restriction.
         */
        assertEquals(published, AgentToolNarrowing.effectiveTools(published, emptySet()))
    }

    @Test
    fun aRestrictionTakesToolsAwayAndNothingElse() {
        assertEquals(
            listOf("captureScreen"),
            AgentToolNarrowing.effectiveTools(published, setOf("captureScreen")),
        )
    }

    @Test
    fun aRestrictionCannotAddAToolThePublisherNeverGranted() {
        /*
         * THIS IS THE GUARANTEE, NOT A VALIDATION. The catalogue review is the authority on what an
         * agent may touch, so a local restriction can only be a ceiling below that one. If it could
         * add, every imported agent would be a route around the review.
         */
        val verdict = AgentToolNarrowing.evaluate(published.toSet().toList(), setOf("captureScreen", "rootShell"))
        assertFalse("an extension is refused", verdict.permitted)
        assertTrue(
            "and the refusal names the tool: ${verdict.explanation}",
            verdict.explanation!!.contains("rootShell"),
        )
    }

    @Test
    fun aSubsetIsPermittedAndSaysWhatItDoes() {
        val verdict = AgentToolNarrowing.evaluate(published, setOf("captureScreen"))
        assertTrue(verdict.permitted)
        assertEquals(setOf("captureScreen"), verdict.tools)
        assertTrue(
            "it names the reduction: ${verdict.explanation}",
            verdict.explanation!!.contains("1 of the 3"),
        )
    }

    @Test
    fun namingTheWholePublishedSetChangesNothingRatherThanFailing() {
        /*
         * A SAVE BUTTON THAT REFUSES A NO-OP IS A BUG. A person submitting the form without changing
         * anything is the most common thing they will do, and the honest answer is that it changes
         * nothing rather than an error.
         */
        val verdict = AgentToolNarrowing.evaluate(published, published.toSet())
        assertTrue(verdict.permitted)
        assertTrue(
            "and it says so: ${verdict.explanation}",
            verdict.explanation!!.contains("changes nothing"),
        )
    }

    @Test
    fun anEmptyRequestIsPermittedAndRemovesTheRestriction() {
        val verdict = AgentToolNarrowing.evaluate(published, emptySet())
        assertTrue(verdict.permitted)
        assertTrue(verdict.tools.isEmpty())
        assertTrue(verdict.explanation!!.contains("everything it was published with"))
    }

    @Test
    fun anUpdateThatAddsAToolOutsideTheRestrictionSaysItWillNotLand() {
        /*
         * THE ONE PLACE AN UPDATE AND A RESTRICTION MEET. A publisher adding a tool is already gated
         * by the update review; if the person has narrowed the agent, that tool is outside their
         * restriction and does not take effect however the update decision goes. Saying so avoids an
         * update that was approved and then did nothing.
         */
        val note = AgentToolNarrowing.noteForUpdate(
            addedTools = setOf("sendSms", "phoneCall"),
            narrowedTo = setOf("captureScreen"),
        )
        assertTrue(note!!.contains("sendSms"))
        assertTrue(note.contains("phoneCall"))
        assertTrue(note.contains("will not gain"))
    }

    @Test
    fun anUpdateWithNoRestrictionNeedsNoNote() {
        assertNull(
            "with nothing restricted there is nothing to explain",
            AgentToolNarrowing.noteForUpdate(setOf("sendSms"), emptySet()),
        )
    }

    @Test
    fun anUpdateWhoseNewToolIsInsideTheRestrictionNeedsNoNote() {
        assertNull(
            AgentToolNarrowing.noteForUpdate(setOf("sendSms"), setOf("sendSms", "captureScreen")),
        )
    }
}
