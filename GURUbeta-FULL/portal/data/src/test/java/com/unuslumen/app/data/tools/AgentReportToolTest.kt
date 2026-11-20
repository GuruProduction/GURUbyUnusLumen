// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.domain.agents.AgentInitiatedMessages
import com.unuslumen.app.domain.agents.AgentMessageTypes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verb an agent speaks through without stopping.
 *
 * THE SUBJECT IS THE SPLIT, NOT THE PLUMBING. Section 5.3 asks an agent to initiate communication for
 * findings, blockage, milestones, risks and review as well as clarification. The distinction that
 * matters is which of those halts the work, so most of this test is about the one refusal that keeps
 * the two apart: a question asked through the verb that does not wait.
 */
class AgentReportToolTest {

    private val executor = AgentReportToolExecutor()

    /**
     * Run the verb and render what a caller would read.
     *
     * THE ERROR AND THE PAYLOAD ARE THE SAME FIELD TO A CALLER, and the assertions care about which
     * of the two arrived rather than about the type that carried it, so this joins them.
     */
    private suspend fun run(vararg args: Pair<String, Any?>): String {
        val result = executor.execute(
            AgentReportToolDefinitions.REPORT_TO_PERSON,
            args.toMap(),
        )
        return result.error ?: result.rawJson
    }

    @Test
    fun aFindingIsAcceptedAndCarriesItsStoredType() = runTest {
        val out = run("kind" to "finding", "message" to "the invoices do not add up")
        assertTrue("it succeeded: $out", out.contains("\"kind\":\"${AgentMessageTypes.FINDING}\""))
        assertTrue(out.contains("the invoices do not add up"))
    }

    @Test
    fun aClarificationIsRefusedAndToldToUseTheVerbThatWaits() = runTest {
        /*
         * THE REFUSAL THAT KEEPS THE TWO VERBS APART. An agent that says it cannot continue without
         * an answer and then picks the verb which carries on regardless would carry on without the
         * answer it said it needed. Redirecting it costs one round; letting it through costs the job.
         */
        val out = run("kind" to "clarification", "message" to "which account")
        assertFalse("it is refused: $out", out.contains("\"kind\""))
        assertTrue("and the refusal names the right verb: $out", out.contains("askAssistant"))
        assertTrue("and says why this one is wrong: $out", out.contains("deliberately carries on"))
    }

    @Test
    fun anUnknownKindIsRefusedAndListsWhatExists() = runTest {
        val out = run("kind" to "complaint", "message" to "this is taking too long")
        assertTrue("it is refused: $out", out.contains("not a kind of message"))
        AgentInitiatedMessages.values().forEach { value ->
            assertTrue("the refusal lists '$value': $out", out.contains(value))
        }
    }

    @Test
    fun aMissingMessageIsRefused() = runTest {
        val out = run("kind" to "finding")
        assertTrue("it is refused: $out", out.contains("Missing 'message'"))
    }

    @Test
    fun aMissingKindIsRefused() = runTest {
        val out = run("message" to "something happened")
        assertTrue("it is refused: $out", out.contains("Missing 'kind'"))
    }

    @Test
    fun urgencyIsNormalisedAndUnknownValuesBecomeNormal() = runTest {
        /*
         * THE AGENT SAYS HOW URGENT IT THINKS THIS IS; it does not get to invent a level. A typo must
         * not become a level that no notification rule recognises, so anything outside the two it may
         * choose resolves to normal.
         */
        assertTrue(run("kind" to "risk", "message" to "m", "urgency" to "high").contains("\"urgency\":\"HIGH\""))
        assertTrue(run("kind" to "risk", "message" to "m", "urgency" to "LOW").contains("\"urgency\":\"LOW\""))
        assertTrue(run("kind" to "risk", "message" to "m", "urgency" to "urgent").contains("\"urgency\":\"NORMAL\""))
        assertTrue(run("kind" to "risk", "message" to "m").contains("\"urgency\":\"NORMAL\""))
    }

    @Test
    fun everyOfferedKindExceptClarificationIsAccepted() = runTest {
        /*
         * ALL FIVE OF THE REPORTING KINDS, so a model that picks any of them gets the message out.
         * This is the breadth half of the split: before this verb existed none of them had a door.
         */
        AgentInitiatedMessages.OFFERED.filter { !it.pauses }.forEach { option ->
            val out = run("kind" to option.value, "message" to "a message")
            assertTrue(
                "'${option.value}' is accepted: $out",
                out.contains("\"kind\":\"${option.type}\""),
            )
        }
    }

    @Test
    fun theVerbIsRegisteredInThePhoneToolList() {
        /*
         * A DEFINITION NOBODY REGISTERS IS A TOOL NOBODY CAN CALL, and the failure is silent: the
         * model never sees the verb and simply does not use it. This asserts the registration list,
         * which is the actual surface a phone builds its registry from.
         */
        val registered = com.unuslumen.app.data.tools.registry.ToolRegistration.allDefinitions()
            .map { it.name }
        assertTrue(
            "reportToPerson is on the phone: ${registered.size} tools",
            AgentReportToolDefinitions.REPORT_TO_PERSON in registered,
        )
    }
}
