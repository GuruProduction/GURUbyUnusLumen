// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import com.unuslumen.app.domain.model.ApprovalTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stands between an agent and an action its owner wanted to be asked about.
 *
 * BEFORE THIS THE APPROVAL SYSTEM WAS DECORATION. An approval could be requested, stored, decided
 * and recorded as an event, and every tool call ran regardless. Each test here guards one way the
 * gate could fail to be in the way: a floor that a setting can lower, a general permission that
 * erases a specific trigger, a grant that never gets consumed, or the same question asked twice.
 */
class AgentApprovalGateTest {

    private fun request(
        toolName: String = "captureScreen",
        requiredFor: Set<ApprovalTrigger> = emptySet(),
        useToolsWithoutAsking: Boolean = true,
        alreadyGranted: Boolean = false,
        alreadyPending: Boolean = false,
        alwaysAsk: Set<String> = AgentApprovalGate.DEFAULT_ALWAYS_ASK,
    ) = AgentApprovalGate.Request(
        toolName = toolName,
        requiredFor = requiredFor,
        useToolsWithoutAsking = useToolsWithoutAsking,
        alreadyGranted = alreadyGranted,
        alreadyPending = alreadyPending,
        alwaysAsk = alwaysAsk,
    )

    @Test
    fun anOrdinaryToolWithNothingConfiguredJustRuns() {
        /*
         * THE GATE MUST NOT BE SO NOISY THAT PEOPLE TURN IT OFF. A screenshot with no triggers set
         * runs, because an agent that asked for everything would be useless and its owner would
         * learn to tap yes without reading.
         */
        val decision = AgentApprovalGate.evaluate(request(toolName = "captureScreen"))
        assertEquals(AgentApprovalGate.Verdict.PROCEED, decision.verdict)
        assertNull(decision.matchedTrigger)
    }

    @Test
    fun anIrreversibleActionAlwaysAsksWhateverTheSettingsSay() {
        /*
         * THE FLOOR UNDER THE POLICY. Sending a message cannot be recalled, so no configuration
         * lowers it. This is the one place the gate overrides the agent's own choice, and it is
         * deliberate: a default that let an SMS leave the phone without a yes would be wrong
         * whatever the agent's settings were.
         */
        val decision = AgentApprovalGate.evaluate(
            request(toolName = "sendSms", useToolsWithoutAsking = true)
        )
        assertEquals(AgentApprovalGate.Verdict.ASK, decision.verdict)
        assertTrue(
            "the reason says it cannot be undone: ${decision.reason}",
            decision.reason.contains("cannot be undone"),
        )
    }

    @Test
    fun everyDefaultAlwaysAskToolAsks() {
        for (tool in AgentApprovalGate.DEFAULT_ALWAYS_ASK) {
            val decision = AgentApprovalGate.evaluate(request(toolName = tool))
            assertEquals("'$tool' must always ask", AgentApprovalGate.Verdict.ASK, decision.verdict)
        }
    }

    @Test
    fun aConfiguredTriggerAsksAndNamesItself() {
        val decision = AgentApprovalGate.evaluate(
            request(
                toolName = "captureScreen",
                requiredFor = setOf(ApprovalTrigger.TOOL_EXECUTION),
            )
        )
        assertEquals(AgentApprovalGate.Verdict.ASK, decision.verdict)
        assertEquals(ApprovalTrigger.TOOL_EXECUTION, decision.matchedTrigger)
    }

    @Test
    fun aSpecificTriggerBeatsTheBlanketPermission() {
        /*
         * THE GENERAL NEVER ERASES THE SPECIFIC. `useToolsWithoutAsking` is the agent's blanket
         * setting, and it must not override a trigger its owner set deliberately. A person who said
         * "it may use its tools, but ask before writing memory" meant the second half.
         */
        val decision = AgentApprovalGate.evaluate(
            request(
                toolName = "memoryWrite",
                requiredFor = setOf(ApprovalTrigger.MEMORY_WRITE),
                useToolsWithoutAsking = true,
            )
        )
        assertEquals(AgentApprovalGate.Verdict.ASK, decision.verdict)
        assertEquals(ApprovalTrigger.MEMORY_WRITE, decision.matchedTrigger)
    }

    @Test
    fun anAgentThatMayNotUseToolsWithoutAskingAsksForEverything() {
        val decision = AgentApprovalGate.evaluate(
            request(toolName = "captureScreen", useToolsWithoutAsking = false)
        )
        assertEquals(AgentApprovalGate.Verdict.ASK, decision.verdict)
        assertTrue(decision.reason.contains("without asking"))
    }

    @Test
    fun aGrantedApprovalLetsTheCallProceed() {
        val decision = AgentApprovalGate.evaluate(
            request(toolName = "sendSms", alreadyGranted = true)
        )
        assertEquals(
            "a person has answered, so the gate stands aside",
            AgentApprovalGate.Verdict.PROCEED,
            decision.verdict,
        )
    }

    @Test
    fun aGrantIsCheckedBeforeTheAlwaysAskFloor() {
        /*
         * THE ORDER THAT MAKES A YES MEAN ANYTHING. If the floor were checked first, an always-ask
         * tool could never proceed even with a grant, and the approval system would be a wall with
         * no door.
         */
        val decision = AgentApprovalGate.evaluate(
            request(toolName = "sendSms", alreadyGranted = true, alwaysAsk = setOf("sendSms"))
        )
        assertEquals(AgentApprovalGate.Verdict.PROCEED, decision.verdict)
    }

    @Test
    fun aPendingRequestBlocksRatherThanAskingTwice() {
        /*
         * ASKING THE SAME QUESTION TWICE IS A FAILURE TO REMEMBER, NOT A NEW DECISION. An agent that
         * requested an approval and then requested it again would bury the person in duplicates and
         * spend the run's exchanges on repeats.
         */
        val decision = AgentApprovalGate.evaluate(
            request(toolName = "sendSms", alreadyPending = true)
        )
        assertEquals(AgentApprovalGate.Verdict.BLOCKED, decision.verdict)
        assertTrue(
            "the reason says the question is already out: ${decision.reason}",
            decision.reason.contains("already waiting"),
        )
    }

    @Test
    fun aPendingRequestIsCheckedBeforeTheGrant() {
        /*
         * A PENDING AND A GRANTED CANNOT BOTH BE TRUE for one action, and if they somehow are, the
         * safe reading is to wait rather than to spend something that might be a different approval.
         * This pins the order so a future change cannot quietly invert it.
         */
        val decision = AgentApprovalGate.evaluate(
            request(toolName = "sendSms", alreadyPending = true, alreadyGranted = false)
        )
        assertEquals(AgentApprovalGate.Verdict.BLOCKED, decision.verdict)
    }

    @Test
    fun theDelegationTriggerAsksOnlyForTheAgentVerbs() {
        val spawn = AgentApprovalGate.evaluate(
            request(toolName = "spawnAgent", requiredFor = setOf(ApprovalTrigger.DELEGATION))
        )
        assertEquals("delegating needs a yes when configured", AgentApprovalGate.Verdict.ASK, spawn.verdict)
        assertEquals(ApprovalTrigger.DELEGATION, spawn.matchedTrigger)

        val screenshot = AgentApprovalGate.evaluate(
            request(toolName = "captureScreen", requiredFor = setOf(ApprovalTrigger.DELEGATION))
        )
        assertEquals(
            "a screenshot is not a delegation",
            AgentApprovalGate.Verdict.PROCEED,
            screenshot.verdict,
        )
    }

    @Test
    fun theMemoryTriggerAsksOnlyForMemoryTools() {
        val write = AgentApprovalGate.evaluate(
            request(toolName = "memoryWrite", requiredFor = setOf(ApprovalTrigger.MEMORY_WRITE))
        )
        assertEquals(AgentApprovalGate.Verdict.ASK, write.verdict)

        val read = AgentApprovalGate.evaluate(
            request(toolName = "captureScreen", requiredFor = setOf(ApprovalTrigger.MEMORY_WRITE))
        )
        assertEquals(AgentApprovalGate.Verdict.PROCEED, read.verdict)
    }

    @Test
    fun aPublicationTriggerAsksOnlyForPublishing() {
        val publish = AgentApprovalGate.evaluate(
            request(toolName = "publishAgent", requiredFor = setOf(ApprovalTrigger.ARTEFACT_PUBLICATION))
        )
        assertEquals(AgentApprovalGate.Verdict.ASK, publish.verdict)

        val read = AgentApprovalGate.evaluate(
            request(toolName = "readFile", requiredFor = setOf(ApprovalTrigger.ARTEFACT_PUBLICATION))
        )
        assertEquals(AgentApprovalGate.Verdict.PROCEED, read.verdict)
    }

    @Test
    fun theToolExecutionTriggerAsksForEveryTool() {
        for (tool in listOf("captureScreen", "readFile", "webSearch", "shellExec")) {
            val decision = AgentApprovalGate.evaluate(
                request(toolName = tool, requiredFor = setOf(ApprovalTrigger.TOOL_EXECUTION))
            )
            assertEquals(
                "'$tool' must ask when every tool execution is gated",
                AgentApprovalGate.Verdict.ASK,
                decision.verdict,
            )
        }
    }

    @Test
    fun theDefaultFloorIsShortAndAboutIrreversibility() {
        /*
         * A FLOOR THAT COVERED EVERYTHING WOULD BE A USELESS AGENT. This pins that the default set
         * stays small, because the temptation when adding a safeguard is to add everything to it.
         */
        assertTrue(
            "the always-ask floor must stay narrow: ${AgentApprovalGate.DEFAULT_ALWAYS_ASK}",
            AgentApprovalGate.DEFAULT_ALWAYS_ASK.size <= 8,
        )
        assertTrue(
            "and it must include the irreversible external actions",
            AgentApprovalGate.DEFAULT_ALWAYS_ASK.containsAll(setOf("sendSms", "phoneCall")),
        )
    }

    @Test
    fun aCallerMayWidenTheFloorButTheDefaultRemainsTheDefault() {
        /*
         * A CALLER SUPPLYING ITS OWN SET DOES NOT WEAKEN THE GUARANTEE, because the default is what
         * an agent gets when nobody says otherwise. This test documents that the parameter exists for
         * a stricter caller, never a looser one.
         */
        val stricter = AgentApprovalGate.evaluate(
            request(toolName = "captureScreen", alwaysAsk = setOf("captureScreen", "sendSms"))
        )
        assertEquals(AgentApprovalGate.Verdict.ASK, stricter.verdict)
    }
}
