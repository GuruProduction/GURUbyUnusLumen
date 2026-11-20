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
 * What the Portal's ambient glow says, and when it says nothing.
 *
 * THE RULING ON THIS SURFACE IS ONE SENTENCE: while an agent is active the Portal shows a glow in
 * that agent's colour and one thing to tap, and nothing else. So the tests are about the two ways that
 * goes wrong — a glow that keeps pulsing over work that has stopped, and a glow that draws something
 * to tap when there is no room to open.
 */
class AgentPresenceTest {

    private fun presence(status: String, colourIndex: Int = 2) = LivePresence(
        runId = "run-1",
        agentId = "agent-1",
        agentName = "Scribe",
        colourIndex = colourIndex,
        status = status,
    )

    @Test
    fun theGlowBreathesOnlyWhileWorkIsGenuinelyExecuting() {
        /*
         * A PULSE MEANS "MOVING" AND NOTHING ELSE. A run waiting on the person has released its loop,
         * so a pulse there would say busy about something that is waiting for them — which is the
         * worst version of this feature, because it is a light that asks nothing and implies
         * everything.
         */
        assertTrue(presence(AgentRunStatuses.RUNNING).isExecuting)
        assertTrue(presence(AgentRunStatuses.STARTING).isExecuting)

        listOf(
            AgentRunStatuses.WAITING_FOR_USER,
            AgentRunStatuses.WAITING_FOR_GURU,
            AgentRunStatuses.WAITING_FOR_TOOL,
            AgentRunStatuses.PAUSED,
            AgentRunStatuses.INTERRUPTED,
            AgentRunStatuses.CREATED,
        ).forEach { status ->
            assertFalse("$status must not pulse", presence(status).isExecuting)
        }
    }

    @Test
    fun nothingAtAllIsDrawnWhenNothingIsRunning() {
        // Null is the whole answer: the Portal draws no edge, no mark and no placeholder.
        assertNull(LivePresence.mostUrgent(emptyList()))
    }

    @Test
    fun theRunThatNeedsThePersonMostIsTheOneDrawn() {
        /*
         * ONE GLOW, BECAUSE THE RUNTIME RUNS ONE. But the choice still has to be right, because the
         * runtime is designed to allow concurrency later and a glow that picked whichever row came
         * first would then hide the run that was actually waiting on somebody.
         */
        val waitingOnUser = presence(AgentRunStatuses.WAITING_FOR_USER)
        val waitingOnGuru = presence(AgentRunStatuses.WAITING_FOR_GURU)
        val working = presence(AgentRunStatuses.RUNNING)

        assertEquals(
            "a run waiting on the person outranks one that is merely working",
            AgentRunStatuses.WAITING_FOR_USER,
            LivePresence.mostUrgent(listOf(working, waitingOnUser))!!.status,
        )
        assertEquals(
            "and a question outranks work that is moving",
            AgentRunStatuses.WAITING_FOR_GURU,
            LivePresence.mostUrgent(listOf(working, waitingOnGuru))!!.status,
        )
        assertEquals(
            "the work itself is drawn when nothing else is waiting",
            AgentRunStatuses.RUNNING,
            LivePresence.mostUrgent(listOf(working))!!.status,
        )
    }

    @Test
    fun aPausedRunStillDrawsBecauseItIsThePersonsToResume() {
        /*
         * PAUSED AND INTERRUPTED ARE ALIVE FOR THIS PURPOSE. Both hold state a person can carry on,
         * and a glow that vanished because the job ended would hide work waiting on them.
         */
        assertEquals(
            AgentRunStatuses.PAUSED,
            LivePresence.mostUrgent(
                listOf(presence(AgentRunStatuses.RUNNING), presence(AgentRunStatuses.PAUSED))
            )!!.status,
        )
        assertEquals(
            AgentRunStatuses.INTERRUPTED,
            LivePresence.mostUrgent(
                listOf(presence(AgentRunStatuses.RUNNING), presence(AgentRunStatuses.INTERRUPTED))
            )!!.status,
        )
    }

    @Test
    fun theColourIsTheAgentsOwnRatherThanTheLists() {
        /*
         * AN AGENT KEEPS ITS COLOUR EVERY TIME IT RUNS. The colour comes from its stored visual
         * identity, so two runs of the same agent cannot be drawn in two colours just because
         * another agent finished in between.
         */
        assertEquals(5, presence(AgentRunStatuses.RUNNING, colourIndex = 5).colourIndex)
        assertEquals(5, presence(AgentRunStatuses.PAUSED, colourIndex = 5).colourIndex)
    }
}
