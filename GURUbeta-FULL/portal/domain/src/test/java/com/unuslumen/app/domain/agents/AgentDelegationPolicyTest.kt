// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delegation, and the four ways it goes wrong.
 *
 * Each test here guards a real failure: a child that inherits more than its parent, a chain with no
 * ceiling, an agent spawning helpers its owner never authorised, and an agent delegating to itself
 * in a loop. The last one exists because the first draft of the policy compared a target agent id
 * against a parent run id, which could never match and would have let the check pass every time.
 */
class AgentDelegationPolicyTest {

    private fun request(
        parentRunId: String = "run-parent",
        parentAgentId: String = "agent-writer",
        parentDepth: Int = 0,
        parentChildrenSoFar: Int = 0,
        parentTools: Set<String> = setOf("captureScreen", "phoneCall"),
        parentMayDelegate: Boolean = true,
        parentMaxDelegations: Int? = null,
        parentMaxDepth: Int? = null,
        targetAgentId: String = "agent-researcher",
        targetExists: Boolean = true,
        targetEnabled: Boolean = true,
        targetTools: Set<String> = setOf("webSearch", "captureScreen"),
        requestedTools: Set<String>? = null,
    ) = AgentDelegationPolicy.Request(
        parentRunId = parentRunId,
        parentAgentId = parentAgentId,
        parentDepth = parentDepth,
        parentChildrenSoFar = parentChildrenSoFar,
        parentTools = parentTools,
        parentMayDelegate = parentMayDelegate,
        parentMaxDelegations = parentMaxDelegations,
        parentMaxDepth = parentMaxDepth,
        targetAgentId = targetAgentId,
        targetExists = targetExists,
        targetEnabled = targetEnabled,
        targetTools = targetTools,
        requestedTools = requestedTools,
    )

    @Test
    fun aPermittedDelegationGrantsOnlyTheIntersection() {
        /*
         * THE SECURITY RULE. The parent holds captureScreen and phoneCall; the child declares
         * webSearch and captureScreen. The child gets captureScreen and nothing else. Not the union,
         * because then delegation would be a route to a capability the parent was refused.
         */
        val verdict = AgentDelegationPolicy.evaluate(request())
        assertTrue(verdict is AgentDelegationPolicy.Verdict.Permitted)
        val permitted = verdict as AgentDelegationPolicy.Verdict.Permitted
        assertEquals(setOf("captureScreen"), permitted.grantedTools)
        assertEquals(1, permitted.depth)
        assertEquals("run-parent", permitted.parentRunId)
    }

    @Test
    fun aChildNeverGainsAToolItsParentDoesNotHold() {
        /*
         * THE DIRECTION THAT MATTERS. The parent holds only captureScreen; the child declares
         * webSearch among others. webSearch must not appear in the grant, or a toolless agent could
         * borrow any capability in the app by delegating to one that has it.
         */
        val verdict = AgentDelegationPolicy.evaluate(
            request(
                parentTools = setOf("captureScreen"),
                targetTools = setOf("webSearch", "clearAppData", "captureScreen"),
            )
        ) as AgentDelegationPolicy.Verdict.Permitted

        assertEquals(setOf("captureScreen"), verdict.grantedTools)
        assertFalse("a capability the parent lacks must never appear", "webSearch" in verdict.grantedTools)
        assertFalse("nor a destructive one", "clearAppData" in verdict.grantedTools)
    }

    @Test
    fun anAgentCannotDelegateToItself() {
        /*
         * THE BUG THIS TEST CAUGHT. The first draft compared the target agent id against the parent
         * RUN id, which never matches, so this check silently passed everything. Comparing against
         * the parent AGENT id is what makes it fire.
         */
        val verdict = AgentDelegationPolicy.evaluate(
            request(parentAgentId = "agent-writer", targetAgentId = "agent-writer")
        )
        assertTrue(verdict is AgentDelegationPolicy.Verdict.Refused)
        assertTrue(
            "the refusal names the recursion: ${(verdict as AgentDelegationPolicy.Verdict.Refused).reason}",
            verdict.reason.contains("cannot delegate to itself"),
        )
    }

    @Test
    fun anAgentWhoseAutonomyForbidsDelegationIsRefused() {
        val verdict = AgentDelegationPolicy.evaluate(request(parentMayDelegate = false))
        assertTrue(verdict is AgentDelegationPolicy.Verdict.Refused)
        assertTrue(
            "the refusal points at where to change it: ${(verdict as AgentDelegationPolicy.Verdict.Refused).reason}",
            verdict.reason.contains("settings"),
        )
    }

    @Test
    fun aTargetThatDoesNotExistIsRefused() {
        val verdict = AgentDelegationPolicy.evaluate(request(targetExists = false))
        assertTrue(verdict is AgentDelegationPolicy.Verdict.Refused)
        assertTrue((verdict as AgentDelegationPolicy.Verdict.Refused).reason.contains("No agent"))
    }

    @Test
    fun aSwitchedOffTargetIsRefused() {
        val verdict = AgentDelegationPolicy.evaluate(request(targetEnabled = false))
        assertTrue(verdict is AgentDelegationPolicy.Verdict.Refused)
        assertTrue(
            (verdict as AgentDelegationPolicy.Verdict.Refused).reason.contains("switched off"),
        )
    }

    @Test
    fun theDepthCeilingIsEnforced() {
        /*
         * A CHAIN THAT REACHES THE CEILING STOPS WITH A REASON. Allowing it to run and fail later
         * would spend real money before discovering the same fact.
         */
        val atCeiling = AgentDelegationPolicy.evaluate(
            request(parentDepth = AgentDelegationPolicy.SYSTEM_MAX_DEPTH)
        )
        assertTrue(atCeiling is AgentDelegationPolicy.Verdict.Refused)
        assertTrue(
            (atCeiling as AgentDelegationPolicy.Verdict.Refused).reason.contains("levels deep"),
        )
    }

    @Test
    fun theLastPermittedDepthIsStillPermitted() {
        val permitted = AgentDelegationPolicy.evaluate(
            request(parentDepth = AgentDelegationPolicy.SYSTEM_MAX_DEPTH - 1)
        )
        assertTrue(
            "the ceiling is inclusive of the deepest allowed level",
            permitted is AgentDelegationPolicy.Verdict.Permitted,
        )
        assertEquals(
            AgentDelegationPolicy.SYSTEM_MAX_DEPTH,
            (permitted as AgentDelegationPolicy.Verdict.Permitted).depth,
        )
    }

    @Test
    fun anAgentsOwnDepthCeilingTightensTheSystemOne() {
        /*
         * THE CEILING IS THE DEEPEST DEPTH ALLOWED, NOT THE LAST PARENT DEPTH. With a declared
         * ceiling of 2, a parent at depth 1 produces depth 2, which is allowed; a parent already at
         * depth 2 would produce 3 and be refused. The first version of this test asserted the wrong
         * one and the policy was right.
         */
        val allowed = AgentDelegationPolicy.evaluate(request(parentDepth = 1, parentMaxDepth = 2))
        assertTrue(
            "reaching the declared ceiling exactly is permitted",
            allowed is AgentDelegationPolicy.Verdict.Permitted,
        )

        val refused = AgentDelegationPolicy.evaluate(request(parentDepth = 2, parentMaxDepth = 2))
        assertTrue(refused is AgentDelegationPolicy.Verdict.Refused)
        assertTrue(
            "a declared ceiling well below the system one is what refused this, not the system depth",
            (refused as AgentDelegationPolicy.Verdict.Refused).reason.contains("ceiling is 2"),
        )
    }

    @Test
    fun anAgentsOwnCeilingCannotExceedTheSystemOne() {
        /*
         * AN AGENT MAY TIGHTEN, NEVER WIDEN. Declaring a depth of ninety-nine does not buy depth
         * ninety-nine; the system ceiling still governs.
         */
        val refused = AgentDelegationPolicy.evaluate(
            request(parentDepth = AgentDelegationPolicy.SYSTEM_MAX_DEPTH, parentMaxDepth = 99)
        )
        assertTrue(
            "a declared ceiling above the system one is clamped, so this is still refused",
            refused is AgentDelegationPolicy.Verdict.Refused,
        )
    }

    @Test
    fun theFanOutCeilingIsEnforced() {
        val refused = AgentDelegationPolicy.evaluate(
            request(parentChildrenSoFar = AgentDelegationPolicy.SYSTEM_MAX_CHILDREN_PER_RUN)
        )
        assertTrue(refused is AgentDelegationPolicy.Verdict.Refused)
        assertTrue(
            (refused as AgentDelegationPolicy.Verdict.Refused).reason.contains("already delegated"),
        )
    }

    @Test
    fun theLastPermittedChildIsStillPermitted() {
        val permitted = AgentDelegationPolicy.evaluate(
            request(parentChildrenSoFar = AgentDelegationPolicy.SYSTEM_MAX_CHILDREN_PER_RUN - 1)
        )
        assertTrue(permitted is AgentDelegationPolicy.Verdict.Permitted)
    }

    @Test
    fun anAgentsOwnChildCeilingTightensTheSystemOne() {
        val refused = AgentDelegationPolicy.evaluate(
            request(parentChildrenSoFar = 1, parentMaxDelegations = 1)
        )
        assertTrue(refused is AgentDelegationPolicy.Verdict.Refused)
    }

    @Test
    fun aRequestedToolTheParentLacksIsRefusedRatherThanDropped() {
        /*
         * SILENTLY NARROWING WOULD BE THE WORSE ANSWER. A parent that asked for a tool it does not
         * hold would believe it delegated that capability, and the child would fail at the point of
         * use naming the wrong component.
         */
        val verdict = AgentDelegationPolicy.evaluate(
            request(requestedTools = setOf("webSearch"))
        )
        assertTrue(verdict is AgentDelegationPolicy.Verdict.Refused)
        assertTrue(
            "the refusal names the tool and the rule: ${(verdict as AgentDelegationPolicy.Verdict.Refused).reason}",
            verdict.reason.contains("webSearch") && verdict.reason.contains("never more"),
        )
    }

    @Test
    fun aRequestedSubsetNarrowsFurther() {
        val permitted = AgentDelegationPolicy.evaluate(
            request(
                parentTools = setOf("captureScreen", "phoneCall"),
                targetTools = setOf("captureScreen", "phoneCall"),
                requestedTools = setOf("captureScreen"),
            )
        ) as AgentDelegationPolicy.Verdict.Permitted
        assertEquals(setOf("captureScreen"), permitted.grantedTools)
    }

    @Test
    fun aJobNeedingNoToolsGrantsNothingAndIsStillPermitted() {
        /*
         * AN EMPTY GRANT IS A REAL ANSWER. A child may be delegated work that needs no tools, and it
         * reasons and reports without touching anything. Refusing this would make a whole class of
         * delegated job impossible.
         */
        val permitted = AgentDelegationPolicy.evaluate(
            request(parentTools = setOf("captureScreen"), targetTools = setOf("phoneCall"))
        ) as AgentDelegationPolicy.Verdict.Permitted
        assertTrue(
            "disjoint tool sets produce an empty grant, not a refusal",
            permitted.grantedTools.isEmpty(),
        )
    }

    @Test
    fun theSystemCeilingsArePositiveAndOrdinary() {
        /*
         * A CEILING OF ZERO WOULD DISABLE DELEGATION ENTIRELY, which is a decision somebody made
         * once in a constant and nobody would notice. This pins that the feature exists.
         */
        assertTrue(AgentDelegationPolicy.SYSTEM_MAX_DEPTH > 0)
        assertTrue(AgentDelegationPolicy.SYSTEM_MAX_CHILDREN_PER_RUN > 0)
    }

    @Test
    fun concurrencyIsConfiguredSequentialTodayAndSaysSo() {
        /*
         * THE HONEST STATE OF CONCURRENCY. One run at a time, because one phone serving one model
         * connection cannot drive two cognitive loops without both degrading. The point of the
         * assertion is that raising it is this constant rather than a schema change.
         */
        assertEquals("sequential today", 1, AgentDelegationPolicy.MAX_CONCURRENT_RUNS)
        assertTrue(AgentDelegationPolicy.admitsAnother(0))
        assertFalse("a second run is refused while one is live", AgentDelegationPolicy.admitsAnother(1))
    }
}
