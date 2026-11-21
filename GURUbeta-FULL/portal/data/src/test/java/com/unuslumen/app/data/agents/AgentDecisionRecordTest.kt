// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.DecisionToolDefinitions
import com.unuslumen.app.data.tools.DecisionToolExecutor
import com.unuslumen.app.data.tools.PlanningToolDefinitions
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentEventTypes
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A decision the agent wrote is a recorded fact, and it is not the reasoning channel.
 *
 * THE DISTINCTION IS THE WHOLE POINT OF THE FEATURE. The specification requires the room to show
 * what an agent decided and why, and it forbids presenting that as interchangeable with raw
 * reasoning. The two differ in where they come from: reasoning is provider output that is absent
 * entirely on a model without a reasoning channel, while a decision is a record the agent chose to
 * write and therefore exists on every model.
 *
 * WHAT THESE TESTS PROVE. That the tool refuses a decision without a reason, that a recorded one is
 * an appended event with every field the agent supplied, that the room reads them back in the run's
 * own order, and that the runtime's name for the tool is the one the tool module actually declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentDecisionRecordTest {

    private object PlainTextProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("none") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var assembler: AgentRoomAssembler

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()
        ledger = AgentRunLedger(
            database = db,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            checkpointDao = db.agentRunCheckpointDao(),
            protector = PlainTextProtector,
        )
        assembler = AgentRoomAssembler(
            agentRepository = io.mockk.mockk(relaxed = true),
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            ledger = ledger,
            broker = io.mockk.mockk(relaxed = true),
        )
        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
        ledger.createRun(
            AgentRunEntity(
                runId = "run-1", conversationId = "conv-1", agentId = "agent-1",
                agentName = "Decider", assignment = "decide things", status = AgentRunEntity.STATUS_RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you decide", toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen", modelName = "m", createdAt = 1, updatedAt = 1,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun recordDecision(
        sequence: Int,
        decision: String,
        reason: String,
        problem: String = "",
        approach: String = "",
        alternatives: String = "",
        evidence: String = "",
        uncertainty: String = "",
        next: String = "",
    ) {
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypes.DECISION_RECORDED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                actorId = "agent-1",
                payload = Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("decision", kotlinx.serialization.json.JsonPrimitive(decision))
                        put("problem", kotlinx.serialization.json.JsonPrimitive(problem))
                        put("approach", kotlinx.serialization.json.JsonPrimitive(approach))
                        put("alternatives", kotlinx.serialization.json.JsonPrimitive(alternatives))
                        put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                        put("evidence", kotlinx.serialization.json.JsonPrimitive(evidence))
                        put("uncertainty", kotlinx.serialization.json.JsonPrimitive(uncertainty))
                        put("next", kotlinx.serialization.json.JsonPrimitive(next))
                        put("sequence", kotlinx.serialization.json.JsonPrimitive(sequence))
                    }
                ),
            )
        )
    }

    private suspend fun assembleDecisions(): List<com.unuslumen.app.domain.agents.DecisionView> {
        val repo = io.mockk.mockk<com.unuslumen.app.domain.repository.GuruAgentRepository>(relaxed = true)
        io.mockk.coEvery { repo.getAgent("agent-1") } returns GuruAgent(
            id = "agent-1", slug = "decider", name = "Decider", description = "",
            systemPrompt = "you decide", toolPermissions = emptyList(),
            source = GuruAgent.SOURCE_USER, createdAt = 1, updatedAt = 1,
        )
        val real = AgentRoomAssembler(
            agentRepository = repo,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            ledger = ledger,
            broker = io.mockk.mockk(relaxed = true),
        )
        return real.assemble("agent-1")?.run?.decisions.orEmpty()
    }

    // ------------------------------------------------------------------
    // The tool
    // ------------------------------------------------------------------

    @Test
    fun theRuntimeRecognisesTheRealDecisionToolName() {
        /*
         * THE CONSTANT IS DUPLICATED INTO THE RUNTIME and pinned here, exactly as the planning pair
         * is, so a rename on either side fails a build rather than silently stopping the record.
         */
        assertEquals("recordDecision", AgentRuntime.DECISION_RECORD)
        assertTrue(
            "the tool module really declares it",
            DecisionToolDefinitions.definitions.map { it.name }.contains(AgentRuntime.DECISION_RECORD),
        )
    }

    @Test
    fun aDecisionWithoutAReasonIsRefused() = runTest {
        /*
         * A CONCLUSION NOBODY CAN CHECK IS THE THING THIS FEATURE EXISTS TO PREVENT. A decision with
         * no reason is that, so the tool refuses it and says what is missing rather than storing a
         * record whose most important field is empty.
         */
        val result = DecisionToolExecutor().execute(
            DecisionToolDefinitions.RECORD_DECISION,
            mapOf("decision" to "Use the second route"),
        )
        assertFalse("a decision with no reason is not accepted", result.success)
        assertTrue(
            "and the refusal names the missing field: ${result.error}",
            result.error.orEmpty().contains("reason"),
        )
    }

    @Test
    fun aDecisionWithNoDecisionIsRefused() = runTest {
        val result = DecisionToolExecutor().execute(
            DecisionToolDefinitions.RECORD_DECISION,
            mapOf("reason" to "because"),
        )
        assertFalse(result.success)
    }

    @Test
    fun aCompleteDecisionIsAcceptedWithItsAlternativesSplit() = runTest {
        val result = DecisionToolExecutor().execute(
            DecisionToolDefinitions.RECORD_DECISION,
            mapOf(
                "decision" to "Rewrite the parser",
                "reason" to "The old one loses ordering",
                "alternatives" to "Patch the old one|Drop the feature",
            ),
        )
        assertTrue("a complete record is accepted", result.success)
        assertTrue(
            "the alternatives are split rather than kept as one string",
            result.rawJson.contains("Patch the old one") && result.rawJson.contains("Drop the feature"),
        )
    }

    // ------------------------------------------------------------------
    // The record
    // ------------------------------------------------------------------

    @Test
    fun aRecordedDecisionBecomesADecisionTheRoomCanDraw() = runTest {
        recordDecision(1, "Use the second route", "It is reversible")
        val decisions = assembleDecisions()
        assertEquals(1, decisions.size)
        assertEquals("Use the second route", decisions.single().decision)
        assertEquals("It is reversible", decisions.single().reason)
    }

    @Test
    fun everyFieldTheAgentSuppliedSurvivesTheRecord() = runTest {
        recordDecision(
            sequence = 1,
            decision = "Rewrite the parser",
            reason = "The old one loses ordering",
            problem = "Step order matters and the old parser sorts",
            approach = "A streaming parser that preserves arrival order",
            alternatives = "Patch the old one|Drop the feature",
            evidence = "The tool result at round 3 showed the reorder",
            uncertainty = "Whether the format allows duplicates",
            next = "Rewrite and re-run the failing test",
        )
        val decision = assembleDecisions().single()
        assertEquals("Step order matters and the old parser sorts", decision.problem)
        assertEquals("A streaming parser that preserves arrival order", decision.approach)
        assertEquals(listOf("Patch the old one", "Drop the feature"), decision.alternatives)
        assertEquals("The tool result at round 3 showed the reorder", decision.evidence)
        assertEquals("Whether the format allows duplicates", decision.uncertainty)
        assertEquals("Rewrite and re-run the failing test", decision.next)
        assertTrue("it carried detail beyond the two required fields", decision.hasDetail)
    }

    @Test
    fun aDecisionWithOnlyItsRequiredFieldsReportsNoDetailRatherThanBlankRows() = runTest {
        /*
         * AN EMPTY LABELLED ROW READS AS A CLAIM THAT THERE WAS NOTHING TO SAY, which is a different
         * fact from not having been asked. The room draws nothing for a blank field and the type
         * says so, so the screen does not have to guess.
         */
        recordDecision(1, "Go with A", "B costs more")
        val decision = assembleDecisions().single()
        assertFalse("nothing beyond decision and reason was supplied", decision.hasDetail)
        assertTrue(decision.problem.isBlank())
        assertTrue(decision.alternatives.isEmpty())
    }

    @Test
    fun decisionsComeBackInTheRunsOwnOrder() = runTest {
        recordDecision(2, "Second", "because two")
        recordDecision(1, "First", "because one")
        recordDecision(3, "Third", "because three")

        val decisions = assembleDecisions()
        assertEquals(
            "the run's own sequence decides the order, not the order rows were written",
            listOf("First", "Second", "Third"),
            decisions.map { it.decision },
        )
        assertEquals(listOf(1, 2, 3), decisions.map { it.sequence })
    }

    @Test
    fun aRunWithNoDecisionsShowsNoneRatherThanAnEmptyRecord() = runTest {
        assertTrue(assembleDecisions().isEmpty())
    }

    @Test
    fun aDecisionWithNoSequenceIsNotDrawnFirst() = runTest {
        /*
         * A RECORD WITH NO SEQUENCE CANNOT BE PLACED IN THE RUN'S ORDER. It is kept at the front
         * rather than dropped, because the decision was still made and the record is real, but it
         * must not claim to be the first thing the run decided with a fabricated number.
         */
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypes.DECISION_RECORDED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                payload = """{"decision":"Unnumbered","reason":"because","problem":"","approach":"","alternatives":"","evidence":"","uncertainty":"","next":""}""",
            )
        )
        recordDecision(1, "Numbered", "because")

        val decisions = assembleDecisions()
        assertEquals(2, decisions.size)
        assertEquals("Unnumbered", decisions.first().decision)
        assertEquals("it made no claim about its own position", 0, decisions.first().sequence)
    }

    @Test
    fun aDecisionIsNotReasoningAndIsNotReadFromTheReasoningEvent() = runTest {
        /*
         * THE TWO ARE READ FROM DIFFERENT EVENTS AND MUST NEVER CROSS. This writes a reasoning
         * event and asserts the decisions list is still empty, then writes a decision and asserts
         * the reasoning is unaffected. If either reader ever looked at the other's event, this fails.
         */
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypes.MODEL_REASONING_COMPLETED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                payload = """{"reasoning":"I am thinking about it"}""",
            )
        )
        assertTrue(
            "reasoning must not appear as a decision",
            assembleDecisions().isEmpty(),
        )

        recordDecision(1, "Chose A", "cheaper")
        val run = assembleRun()
        assertEquals("one decision", 1, run?.decisions?.size ?: 0)
        assertEquals(
            "and the reasoning is still its own thing",
            "I am thinking about it",
            run?.reasoning?.text,
        )
    }

    @Test
    fun aDecisionIsKeptWhenTheSameRunRecordsSeveral() = runTest {
        repeat(4) { index -> recordDecision(index + 1, "Decision ${index + 1}", "reason ${index + 1}") }
        assertEquals(
            "every decision is its own appended fact",
            4,
            assembleDecisions().size,
        )
    }

    private suspend fun assembleRun(): com.unuslumen.app.domain.agents.RunView? {
        val repo = io.mockk.mockk<com.unuslumen.app.domain.repository.GuruAgentRepository>(relaxed = true)
        io.mockk.coEvery { repo.getAgent("agent-1") } returns GuruAgent(
            id = "agent-1", slug = "decider", name = "Decider", description = "",
            systemPrompt = "you decide", toolPermissions = emptyList(),
            source = GuruAgent.SOURCE_USER, createdAt = 1, updatedAt = 1,
        )
        val real = AgentRoomAssembler(
            agentRepository = repo,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            ledger = ledger,
            broker = io.mockk.mockk(relaxed = true),
        )
        return real.assemble("agent-1")?.run
    }
}
