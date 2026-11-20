// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which run controls a room may draw, decided once rather than per screen.
 *
 * THE DEFECT THIS PREVENTS. The specification forbids a control drawn as operational because it
 * looks useful, and the way that defect actually arrives is a Compose screen comparing status
 * strings inline. Every screen then has its own copy of the rule, they drift, and one of them
 * offers a Pause button on a run that is sitting in a database waiting for an answer.
 *
 * SO THE RULE LIVES ON THE VALUE, where it is one function of one field and can be tested without a
 * device, a database or a frame of Compose.
 */
class AgentRunControlTest {

    private fun run(status: String) = RunView(
        runId = "run-1",
        assignment = "the job",
        status = status,
        startedAt = 1,
        finishedAt = null,
        failureMessage = null,
        resultSummary = null,
        planSteps = emptyList(),
        toolActivity = emptyList(),
        messages = emptyList(),
        reasoning = ReasoningView("", true, true),
        contextSupplied = 0,
        contextOffered = 0,
        artefacts = emptyList(),
        pendingApprovals = emptyList(),
        effectiveConfigSummary = "",
    )

    @Test
    fun aRunningRunCanBePaused() {
        assertTrue(run(AgentRunStatuses.RUNNING).canPause)
    }

    @Test
    fun aRunWaitingOnThePersonCannotBePausedBecauseNothingIsMoving() {
        /*
         * IT HAS ALREADY STOPPED. A run waiting on the person has released its lease and no loop is
         * executing, so a Pause control would do nothing while implying it does. What that run needs
         * is an answer, not a pause.
         */
        assertFalse(run(AgentRunStatuses.WAITING_FOR_USER).canPause)
    }

    @Test
    fun aRunWaitingOnGuruCannotBePaused() {
        assertFalse(run(AgentRunStatuses.WAITING_FOR_GURU).canPause)
    }

    @Test
    fun aRunWaitingOnAToolCannotBePaused() {
        assertFalse(run(AgentRunStatuses.WAITING_FOR_TOOL).canPause)
    }

    @Test
    fun anAlreadyPausedRunHasNothingToPause() {
        assertFalse(run(AgentRunStatuses.PAUSED).canPause)
    }

    @Test
    fun aFinishedRunHasNothingToPauseOrResume() {
        listOf(
            AgentRunStatuses.COMPLETED,
            AgentRunStatuses.LIMIT_REACHED,
            AgentRunStatuses.FAILED,
            AgentRunStatuses.CANCELLED,
        ).forEach { status ->
            assertFalse("$status must not offer pause", run(status).canPause)
            assertFalse("$status must not offer resume", run(status).canResume)
        }
    }

    @Test
    fun aPausedRunCanBeCarriedOn() {
        assertTrue(run(AgentRunStatuses.PAUSED).canResume)
    }

    @Test
    fun anInterruptedRunCanBeCarriedOnBecauseItsCheckpointHoldsTheState() {
        assertTrue(run(AgentRunStatuses.INTERRUPTED).canResume)
    }

    @Test
    fun aRunningRunIsNotOfferedAResumeBecauseItNeverStopped() {
        assertFalse(run(AgentRunStatuses.RUNNING).canResume)
    }

    @Test
    fun aRunWaitingOnAnAnswerIsNotOfferedAResumeBecauseItNeedsAnAnswer() {
        /*
         * THE TWO ARE DIFFERENT RESCUES. A waiting run is continued by answering its question,
         * which is a message rather than a resume. Offering a Carry on button there would be a
         * second route to the same place and the two would eventually disagree about which one
         * supplied the answer.
         */
        assertFalse(run(AgentRunStatuses.WAITING_FOR_GURU).canResume)
        assertFalse(run(AgentRunStatuses.WAITING_FOR_USER).canResume)
    }

    @Test
    fun pendingApprovalsMakeARunLookLikeItIsWaitingOnThePerson() {
        val withApproval = run(AgentRunStatuses.RUNNING).copy(
            pendingApprovals = listOf(
                ApprovalView(
                    approvalId = "a1",
                    action = "sendSms",
                    what = "send",
                    why = "needed",
                    affected = "one message",
                    reversible = false,
                    consequences = "cannot be recalled",
                    requestedPermission = "approval.external.sms",
                    requestedAt = 1,
                )
            )
        )
        assertTrue("an approval outranks a RUNNING status", withApproval.isWaitingOnUser)
    }

    @Test
    fun everyStatusInTheVocabularyIsClassifiable() {
        /*
         * NOTHING FALLS THROUGH. A status the vocabulary knows but this rule has never considered
         * would default to whatever the boolean expressions happen to produce, and that is how a
         * control appears on a state nobody thought about.
         *
         * THREE WAYS TO CONTROL A RUN, NOT TWO, AND THE SETS PARTITION. Pause and resume belong to
         * states where a loop exists or can be restarted. A run waiting on an answer has a third
         * control: the answer itself, which travels as a message rather than through either of
         * these. So each non-terminal status must offer at least one of the three, and the two
         * booleans must never both be true.
         */
        AgentRunStatuses.ALL.forEach { status ->
            val view = run(status)
            val terminal = status in AgentRunStatuses.TERMINAL

            assertEquals(
                "$status should be resumable exactly when it is paused or interrupted",
                status == AgentRunStatuses.PAUSED || status == AgentRunStatuses.INTERRUPTED,
                view.canResume,
            )
            assertFalse(
                "$status must not offer pause and resume at once",
                view.canPause && view.canResume,
            )

            if (!terminal) {
                /*
                 * THE STATES THAT NEED NO CONTROL FROM ANYONE. Some runs are not waiting to be
                 * driven, they are mid-move: WAITING is continued by an answer, WAITING_FOR_TOOL by
                 * the tool returning, and CREATED, STARTING and INTEGRATING are transitional states
                 * the runtime leaves within the same turn it enters them. Naming them here is what
                 * stops "no control" from being an oversight rather than a decision.
                 */
                val resolvesItself = status in AgentRunStatuses.WAITING ||
                    status == AgentRunStatuses.WAITING_FOR_TOOL ||
                    status == AgentRunStatuses.CREATED ||
                    status == AgentRunStatuses.STARTING ||
                    status == AgentRunStatuses.INTEGRATING
                assertTrue(
                    "$status is non-terminal and must offer pause, resume, an answer, or resolve itself",
                    view.canPause || view.canResume || resolvesItself,
                )
            }
        }
    }
}
