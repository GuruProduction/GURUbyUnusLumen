// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent conversation: the question, the answer, and the outcomes a caller can read.
 *
 * WHY THIS EXISTS. `spawnAgent` has been through two shapes. First it was fire-and-forget: the
 * agent got a job, ran to completion and returned a report, so it could not ask what it did not
 * know. Then it blocked for the whole job, which meant a spawn that took four minutes held the
 * caller's turn open for four minutes and the phone read as frozen.
 *
 * The shape these tests pin is the third one: a spawn that returns a run id and STARTED, and a
 * separate set of verbs that read the outcome afterwards. That makes the outcome verbs load
 * bearing rather than decoration, because nothing else will ever tell the caller what happened.
 * They are written against the published descriptions and the result contract rather than the
 * internals, because the internals are free to change and the contract is what the model reads.
 */
class AgentDialogueTest {

    private fun json(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): JsonObject =
        Json.parseToJsonElement(result.rawJson) as? JsonObject
            ?: error("not a JSON object: ${result.rawJson.take(200)}")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.num(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun AgentRuntime.SpawnResult.asResult(): JsonObject {
        val result = SpawnAgentResult(
            success = success,
            agent = agentName,
            status = status.name,
            report = report,
            toolRounds = toolRounds,
            sessionId = sessionId,
            question = question,
            why = why,
            options = options,
            error = error,
        )
        return Json.parseToJsonElement(
            Json { encodeDefaults = true }.encodeToString(SpawnAgentResult.serializer(), result),
        ).jsonObject
    }

    // ------------------------------------------------------------------
    // The verbs exist
    // ------------------------------------------------------------------

    /** The conversation verbs are declared, because a tool the model cannot see is no tool. */
    @Test
    fun theConversationVerbsAreDeclared() {
        val names = AgentToolDefinitions.definitions.map { it.name }.toSet()
        assertTrue("resumeAgent must exist", names.contains(AgentToolDefinitions.RESUME_AGENT))
        assertTrue("askAssistant must exist", names.contains(AgentToolDefinitions.ASK_ASSISTANT))
    }

    /** Each one tells the model what it is for, because the model reasons from that text. */
    @Test
    fun theConversationVerbsAreDescribed() {
        for (name in listOf(AgentToolDefinitions.RESUME_AGENT, AgentToolDefinitions.ASK_ASSISTANT)) {
            val definition = AgentToolDefinitions.definitions.first { it.name == name }
            assertTrue("'$name' has no description", definition.description.isNotBlank())
            assertEquals("'$name' belongs in the agents category", "agents", definition.category)
        }
    }

    /**
     * spawnAgent must say that it returns before the work does, and where the outcome comes from.
     *
     * TWO SEPARATE FAILURES ARE PINNED HERE. The first is the old one: a description that promised
     * a report and nothing else gave a model no reason to expect a question, so it treated a
     * waiting agent as a finished one. The second is the newer and worse one: a description that
     * implies the call blocks means a model reads an immediate return as a finished job, reports
     * work it never saw, and never asks the run what actually happened.
     *
     * So the assertion is not "names some outcomes". It is that the text names STARTED as what this
     * call returns, and names the three verbs that carry the real answer afterwards. A description
     * missing any of those leaves the model with a call it cannot interpret correctly.
     */
    @Test
    fun spawnAgentSaysTheAnswerArrivesAfterItReturns() {
        val spawn = AgentToolDefinitions.definitions.first { it.name == AgentToolDefinitions.SPAWN_AGENT }
        assertTrue(
            "spawnAgent must say what it returns: ${spawn.description}",
            spawn.description.contains("STARTED"),
        )
        assertTrue(
            "and it must say the work is still going after it returned",
            spawn.description.contains("background"),
        )
        for (verb in listOf(
            AgentRunToolDefinitions.LIST_AGENT_RUNS,
            AgentRunToolDefinitions.GET_AGENT_RUN,
            AgentToolDefinitions.RESUME_AGENT,
        )) {
            assertTrue(
                "spawnAgent must point at $verb, or the outcome has no route back: ${spawn.description}",
                spawn.description.contains(verb),
            )
        }
        assertTrue(
            "and it must forbid assuming the job finished: ${spawn.description}",
            spawn.description.contains("NEVER ASSUME"),
        )
    }

    /** The verbs a spawn's outcome is read through have to exist, or the description lies. */
    @Test
    fun theRunVerbsTheSpawnDescriptionNamesAreAllDeclared() {
        val declared = AgentToolDefinitions.definitions.map { it.name } +
            AgentRunToolDefinitions.definitions.map { it.name }
        for (verb in listOf(
            AgentRunToolDefinitions.LIST_AGENT_RUNS,
            AgentRunToolDefinitions.GET_AGENT_RUN,
            AgentRunToolDefinitions.CANCEL_AGENT_RUN,
        )) {
            assertTrue("'$verb' is named but not declared", declared.contains(verb))
        }
    }

    /** A question tool that does not say when not to use it invites a stream of them. */
    @Test
    fun askAssistantSaysWhenNotToAsk() {
        val ask = AgentToolDefinitions.definitions.first { it.name == AgentToolDefinitions.ASK_ASSISTANT }
        assertTrue(
            "it must discourage asking permission for work already given: ${ask.description}",
            ask.description.contains("permission"),
        )
    }

    // ------------------------------------------------------------------
    // The result contract
    // ------------------------------------------------------------------

    /** A finished turn carries the report and no question. */
    @Test
    fun aFinishedTurnCarriesTheReportAndNoQuestion() {
        val body = AgentRuntime.SpawnResult(
            success = true,
            agentName = "Researcher",
            report = "The survey is done.",
            toolRounds = 4,
            sessionId = "session-1",
            status = AgentRuntime.SpawnResult.Status.FINISHED,
        ).asResult()

        assertEquals("FINISHED", body.str("status"))
        assertTrue(body.flag("success") == true)
        assertEquals("The survey is done.", body.str("report"))
        assertNull("a finished turn is not waiting on anything", body.str("question"))
    }

    /** A waiting turn carries the question, the session, and is still a success. */
    @Test
    fun aWaitingTurnCarriesTheQuestionAndIsNotAFailure() {
        val body = AgentRuntime.SpawnResult(
            success = true,
            agentName = "Researcher",
            report = "",
            toolRounds = 2,
            sessionId = "session-2",
            status = AgentRuntime.SpawnResult.Status.ASKING,
            question = "Do you mean the Bristol or the London office?",
            why = "Both exist in the records and the answer changes the survey.",
            options = listOf("Bristol", "London"),
        ).asResult()

        assertEquals(
            "ASKING is a working conversation, so it must not read as an error",
            true,
            body.flag("success"),
        )
        assertEquals("ASKING", body.str("status"))
        assertEquals("Do you mean the Bristol or the London office?", body.str("question"))
        assertEquals("session-2", body.str("sessionId"))
        assertEquals("the offered options travel with the question", 2, (body["options"] as? kotlinx.serialization.json.JsonArray)?.size)
    }

    /** A failure says what went wrong and carries no session. */
    @Test
    fun aFailedTurnSaysWhyAndCarriesNoSession() {
        val body = AgentRuntime.SpawnResult(
            success = false,
            agentName = "Researcher",
            report = "",
            toolRounds = 0,
            status = AgentRuntime.SpawnResult.Status.FAILED,
            error = "No model is connected right now, so 'Researcher' could not run.",
        ).asResult()

        assertEquals("FAILED", body.str("status"))
        assertFalse(body.flag("success") == true)
        assertNull(body.str("sessionId"))
        assertNotNull(body.str("error"))
    }

    /**
     * Every outcome is a distinct value, and there are now five.
     *
     * IF TWO OF THEM EVER COLLAPSED into one name, a caller reading the status could no longer tell
     * a waiting agent from a dead one, which is the whole reason the field exists rather than being
     * inferred from `success`.
     *
     * [PAUSED] WAS ADDED FOR THE SAME REASON THE FIELD EXISTS. A person can now stop a run part way
     * through with its state kept, and that is neither a finish nor a failure: reporting it as a
     * failure says the work was no good, and reporting it as finished hands back a partial report as
     * an answer. A caller that cannot tell the three apart cannot decide whether to carry on, ask a
     * question or start again.
     *
     * [STARTED] IS THE NEWEST AND IT IS NOT A SYNONYM FOR FINISHED. It means the job was accepted
     * and is running, which is the only thing a spawn can honestly report, because a spawn now
     * returns in under a second and a real job does not. Without it a caller has two choices on an
     * immediate return, success or failure, and both are lies about what happened.
     */
    @Test
    fun theOutcomesAreDistinct() {
        val statuses = AgentRuntime.SpawnResult.Status.values().map { it.name }.toSet()
        assertEquals(5, statuses.size)
        assertTrue(
            "a pause is its own outcome, not a failure and not a finish, and an accepted job is " +
                "neither of those either",
            statuses.containsAll(setOf("STARTED", "FINISHED", "ASKING", "PAUSED", "FAILED")),
        )
    }

    /**
     * A paused turn carries the session and a partial report, and does not claim success.
     *
     * THE SHAPE MATTERS AS MUCH AS THE NAME. A caller continues a paused run with the session id, so
     * the id has to travel. The report is what it had produced before stopping, so it must not be
     * empty when there was work. And `success` must be false, because the assignment was not
     * completed.
     */
    @Test
    fun aPausedTurnCarriesTheSessionAndWhatItHadSoFar() {
        val body = AgentRuntime.SpawnResult(
            success = false,
            agentName = "Researcher",
            report = "Half the survey is done.",
            toolRounds = 6,
            sessionId = "session-3",
            status = AgentRuntime.SpawnResult.Status.PAUSED,
            error = "Paused at round 6. Everything it had produced is above and its working state " +
                "is kept. Resume it to carry on.",
        ).asResult()

        assertEquals("PAUSED", body.str("status"))
        assertEquals("session-3", body.str("sessionId"))
        assertEquals("Half the survey is done.", body.str("report"))
        assertFalse("a paused run did not complete its assignment", body.flag("success") == true)
        assertNull("pausing is not a question, so there is nothing to answer", body.str("question"))
    }
}
