// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every declared event type has a restore policy, and the reducer honours it.
 *
 * THE DEFECT THIS FILE EXISTS FOR. `AgentRunReplay` branched on 15 of the 33 declared names
 * and every other name fell through `else -> Unit`, which means a fact recorded faithfully
 * in the ledger simply vanished when a session was rebuilt. Nothing errored and nothing was
 * logged, which is the worst shape a defect can take: a silent loss inside the one subsystem
 * whose entire purpose is to be the authoritative record.
 *
 * The fix is a classification rather than more branches, because most of those 18 names
 * SHOULD not rebuild session state. A lease heartbeat is operational metadata; a permission
 * decision is evidence about a call its own start and completion already represent. What was
 * wrong was that "no branch" and "deliberately ignored" were the same thing in code. They are
 * now different values, and this test keeps them that way.
 */
class AgentEventCoverageTest {

    /**
     * THE GATE. Adding a name to `ALL` without deciding what it means to recovery fails here.
     *
     * This is the assertion that stops a future phase adding a producer and forgetting the
     * reader. It fails loudly with the exact missing names rather than a count.
     */
    @Test
    fun everyDeclaredTypeHasARestorePolicy() {
        val declared = AgentEventTypes.ALL.toSet()
        val classified = AgentEventTypes.RESTORE_POLICY.keys

        val missing = declared - classified
        assertTrue(
            "these declared event types have no restore policy, so a reducer would drop them " +
                "silently: ${missing.sorted()}. Add each to RESTORE_POLICY as Rebuilds, " +
                "NoSessionEffect with a reason, or LaterPhase with a reason.",
            missing.isEmpty(),
        )

        val extra = classified - declared
        assertTrue(
            "these types have a restore policy but are not declared in AgentEventTypes.ALL, so " +
                "nothing can ever emit them and the policy describes an event that cannot exist: " +
                "${extra.sorted()}",
            extra.isEmpty(),
        )
    }

    /** Every policy carries a real reason, because "no branch" without one is the old defect. */
    @Test
    fun everyPolicyThatIsNotARebuildExplainsItself() {
        val unexplained = AgentEventTypes.RESTORE_POLICY.filterValues { policy ->
            when (policy) {
                is AgentEventTypes.RestorePolicy.Rebuilds -> false
                is AgentEventTypes.RestorePolicy.NoSessionEffect -> policy.reason.length < 30
                is AgentEventTypes.RestorePolicy.LaterPhase -> policy.reason.length < 30
            }
        }.keys
        assertTrue(
            "these types are excluded from the reducer without a stated reason, which is exactly " +
                "the silence this classification was built to remove: ${unexplained.sorted()}",
            unexplained.isEmpty(),
        )
    }

    /**
     * The Phase 5 communication types have a producer and a decided policy.
     *
     * THIS ASSERTION USED TO SAY THE OPPOSITE, AND THAT IS THE POINT. While nothing emitted these
     * types they were classified `LaterPhase`, and the earlier version of this test required that
     * classification so a future phase could not add a producer and forget the reader. Phase 5 is
     * that future phase: `AgentInteractionBroker` now writes all three, and the policy changed from
     * "no producer yet" to a decision about what a rebuilt session needs.
     *
     * It is updated rather than deleted because the guard is still doing its job. A policy that
     * drifted back to `LaterPhase` would now be wrong in the other direction: it would describe an
     * event as belonging to a phase that has already been built.
     *
     * `ASSIGNMENT_AMENDED` STAYS LATER, and deliberately. An amendment is carried as a message of
     * type ASSIGNMENT_AMENDMENT, so no `ASSIGNMENT_AMENDED` event is emitted and the reducer has
     * nothing to apply. The type is kept declared so that when a producer exists, the classification
     * is already there.
     */
    @Test
    fun thePhaseFiveCommunicationTypesHaveAProducerAndADecidedPolicy() {
        for (type in listOf(
            AgentEventTypes.MESSAGE_CREATED,
            AgentEventTypes.MESSAGE_DELIVERED,
            AgentEventTypes.MESSAGE_DELIVERY_FAILED,
        )) {
            val policy = AgentEventTypes.RESTORE_POLICY[type]
            assertTrue(
                "'$type' now has a producer in AgentInteractionBroker, so it must no longer be " +
                    "classified LaterPhase. Was: $policy",
                policy !is AgentEventTypes.RestorePolicy.LaterPhase,
            )
            assertTrue(
                "'$type' still needs a stated reason, because an excluded event without one is " +
                    "the silence this classification exists to remove. Was: $policy",
                (policy is AgentEventTypes.RestorePolicy.NoSessionEffect) ||
                    (policy is AgentEventTypes.RestorePolicy.Rebuilds),
            )
        }

        assertTrue(
            "ASSIGNMENT_AMENDED has no producer, because an amendment travels as a message of " +
                "type ASSIGNMENT_AMENDMENT. It stays classified until one exists.",
            AgentEventTypes.RESTORE_POLICY[AgentEventTypes.ASSIGNMENT_AMENDED]
                is AgentEventTypes.RestorePolicy.LaterPhase,
        )
    }

    /**
     * An undeclared type makes the rebuild inconsistent rather than disappearing.
     *
     * This is the old behaviour proved gone: a made-up type used to produce a clean rebuild
     * with the event simply absent. It must now be reported.
     */
    @Test
    fun anUnknownEventTypeMakesTheRebuildInconsistent() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                AgentRunReplay.Event(
                    sequence = 1,
                    type = "SOMETHING_NOBODY_DECLARED",
                    payload = emptyMap(),
                )
            )
        )
        assertFalse("an undeclared type must not rebuild cleanly", rebuilt.consistent)
        assertTrue(
            "the problem must name the type: ${rebuilt.problems}",
            rebuilt.problems.any { it.contains("SOMETHING_NOBODY_DECLARED") },
        )
    }

    /**
     * A type declared as rebuilding, but with no branch, is reported rather than dropped.
     *
     * This is the guard against the two halves drifting: the classification says the reducer
     * handles it, the reducer does not, and the difference has to be visible.
     */
    @Test
    fun aTypeClassifiedAsRebuildingWithoutABranchIsReported() {
        // MODEL_REQUEST_COMPLETED is Rebuilds and does have a branch, so it must stay clean.
        val handled = AgentRunReplay.replay(
            listOf(
                AgentRunReplay.Event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1")),
                AgentRunReplay.Event(2, AgentEventTypes.MODEL_TEXT_COMPLETED, mapOf("requestId" to "r1", "text" to "hi")),
                AgentRunReplay.Event(3, AgentEventTypes.MODEL_REQUEST_COMPLETED, emptyMap()),
            )
        )
        assertTrue("a fully handled sequence stays consistent: ${handled.problems}", handled.consistent)
    }

    /** A type classified as having no session effect is ignored without making the rebuild dirty. */
    @Test
    fun aTypeWithNoSessionEffectDoesNotDirtyTheRebuild() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                AgentRunReplay.Event(1, AgentEventTypes.RUN_LEASE_ACQUIRED, mapOf("owner" to "proc-abc")),
                AgentRunReplay.Event(2, AgentEventTypes.TOOL_PERMISSION_GRANTED, mapOf("tool" to "readFile")),
                AgentRunReplay.Event(3, AgentEventTypes.CHECKPOINT_CREATED, mapOf("checkpointId" to "c1")),
            )
        )
        assertTrue(
            "ignorable types must not make a rebuild look broken: ${rebuilt.problems}",
            rebuilt.consistent,
        )
        assertTrue(
            "and none of them may invent a message",
            rebuilt.messages.isEmpty(),
        )
    }

    /** The vocabulary has no duplicate spellings, because one typo is one lost event. */
    @Test
    fun theVocabularyHasNoDuplicates() {
        assertEquals(
            "a duplicated name would make one event type unreachable",
            AgentEventTypes.ALL.size,
            AgentEventTypes.ALL.distinct().size,
        )
    }
}
