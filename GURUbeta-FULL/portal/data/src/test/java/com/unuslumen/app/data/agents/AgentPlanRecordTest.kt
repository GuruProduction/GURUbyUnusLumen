// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.PlanningToolDefinitions
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.PlanStepView
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A plan the agent wrote is a recorded revision, and the room shows the latest one.
 *
 * THE GAP THIS CLOSES. `createPlan` and `updatePlanStep` write notes whose steps are checkbox lines,
 * and `updatePlanStep` overwrites a line. Nothing keeps the previous state, so the note is a document
 * that eats its own history and no revision can be reconstructed from it. The ledger is where a
 * revision history belongs, and this proves one arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentPlanRecordTest {

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
                agentName = "Planner", assignment = "plan the work", status = AgentRunEntity.STATUS_RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you plan", toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen", modelName = "m", createdAt = 1, updatedAt = 1,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun recordPlan(
        revision: Int,
        kind: String,
        steps: List<String>,
        title: String = "the work",
    ) {
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.domain.agents.AgentEventTypes.PLAN_REVISED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                payload = """{"revision":$revision,"kind":"$kind","title":"$title","steps":"${
                    steps.joinToString(" | ")
                }","stepCount":${steps.size}}""",
            ),
        )
    }

    @Test
    fun aPlanRevisionBecomesStepsTheRoomCanDraw() = runTest {
        recordPlan(1, "CREATED", listOf("read the file", "summarise it", "report back"))

        val steps = assemblerPlanSteps()
        assertEquals(3, steps.size)
        assertEquals("read the file", steps[0].description)
        assertEquals("report back", steps[2].description)
    }

    @Test
    fun proposedStepsAreNotDrawnAsDone() = runTest {
        /*
         * A PROPOSAL IS NOT AN ACTION. The specification is explicit that a plan item marked as
         * proposed does not mean work has happened, and the state is what stops the interface
         * implying it.
         */
        recordPlan(1, "CREATED", listOf("read the file"))
        assertEquals(PlanStepView.STATE_PROPOSED, assemblerPlanSteps().single().state)
    }

    @Test
    fun aStepUpdateIsRecordedAsItsOwnKindOfRevision() = runTest {
        recordPlan(1, "CREATED", listOf("read the file", "summarise"))
        recordPlan(2, "STEP_UPDATED", listOf("read the file"))

        val steps = assemblerPlanSteps()
        assertEquals(
            "the newest revision is what the room shows",
            1,
            steps.size,
        )
        assertEquals(PlanStepView.STATE_ACTIVE, steps.single().state)
    }

    @Test
    fun theLatestRevisionWinsSoHistoryCanBeKeptWithoutShowingItAll() = runTest {
        recordPlan(1, "CREATED", listOf("first idea", "second idea"))
        recordPlan(2, "CREATED", listOf("better idea"))

        val steps = assemblerPlanSteps()
        assertEquals("the current plan is the newest one", 1, steps.size)
        assertEquals("better idea", steps.single().description)
    }

    @Test
    fun everyRevisionStaysInTheLedgerSoTheHistoryIsIntact() = runTest {
        /*
         * THE REASON THE LEDGER IS THE RECORD. Three revisions are three appended facts, so a reader
         * can see the agent changed its mind rather than only what it settled on. A document that
         * overwrites a line cannot answer that.
         */
        recordPlan(1, "CREATED", listOf("first idea"))
        recordPlan(2, "CREATED", listOf("second idea"))
        recordPlan(3, "CREATED", listOf("third idea"))

        val all = ledger.events("run-1").count {
            it.eventType == com.unuslumen.app.domain.agents.AgentEventTypes.PLAN_REVISED
        }
        assertEquals("all three revisions survive", 3, all)
    }

    @Test
    fun aRunWithNoPlanShowsNoStepsRatherThanAnEmptyPlan() = runTest {
        /*
         * NO PLAN IS NOT AN EMPTY PLAN. The room's planProgress is null when there are no steps, so
         * it says nothing rather than "0 of 0 steps done", which reads as a plan that achieved
         * nothing.
         */
        assertTrue(assemblerPlanSteps().isEmpty())
    }

    @Test
    fun aPlanWithNoStepsDoesNotInventOne() = runTest {
        recordPlan(1, "CREATED", emptyList())
        assertTrue(
            "a plan that named no steps produces no steps",
            assemblerPlanSteps().isEmpty(),
        )
    }

    @Test
    fun theRuntimeRecognisesTheRealPlanningToolNames() {
        /*
         * THE CONSTANTS ARE DUPLICATED DELIBERATELY AND PINNED HERE. The runtime carries the two
         * names so it does not depend on the tool module; this asserts they still match, so a rename
         * on either side fails a build rather than silently stopping the record.
         */
        assertEquals("createPlan", AgentRuntime.PLANNING_CREATE)
        assertEquals("updatePlanStep", AgentRuntime.PLANNING_UPDATE)
        assertTrue(
            "and the tool module really declares them",
            PlanningToolDefinitions.definitions.map { it.name }
                .containsAll(listOf(AgentRuntime.PLANNING_CREATE, AgentRuntime.PLANNING_UPDATE)),
        )
    }

    @Test
    fun planStepsCarryStableIdentifiers() = runTest {
        /*
         * A STEP NEEDS AN IDENTITY, which is the whole point of recording revisions rather than lines
         * in a note. The ids are positional within a revision, which is honest: a revision's third
         * step is its third step, and no claim is made that it is the same step as the third of an
         * earlier revision.
         */
        recordPlan(1, "CREATED", listOf("one", "two"))
        val steps = assemblerPlanSteps()
        assertEquals("step-1", steps[0].stepId)
        assertEquals("step-2", steps[1].stepId)
    }

    @Test
    fun aMissingRevisionNumberIsIgnoredRatherThanShownAsZero() = runTest {
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.domain.agents.AgentEventTypes.PLAN_REVISED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                payload = """{"kind":"CREATED","steps":"no revision"}""",
            ),
        )
        assertTrue(
            "an event with no revision number cannot be ordered, so it is skipped",
            assemblerPlanSteps().isEmpty(),
        )
    }

    /**
     * The room's plan steps for the seeded run, read through the assembler.
     *
     * THROUGH THE REAL ASSEMBLER, NOT A COPY OF ITS LOGIC. A test that reimplemented the parsing
     * would pass while the room showed something else. Only the agent lookup is stubbed, because this
     * test is about the plan half rather than the registry.
     */
    private suspend fun assemblerPlanSteps(): List<PlanStepView> {
        val repo = io.mockk.mockk<com.unuslumen.app.domain.repository.GuruAgentRepository>(relaxed = true)
        io.mockk.coEvery { repo.getAgent("agent-1") } returns com.unuslumen.app.domain.model.GuruAgent(
            id = "agent-1", slug = "planner", name = "Planner", description = "",
            systemPrompt = "you plan", toolPermissions = emptyList(),
            source = com.unuslumen.app.domain.model.GuruAgent.SOURCE_USER,
            createdAt = 1, updatedAt = 1,
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
        return real.assemble("agent-1")?.run?.planSteps ?: emptyList()
    }
}
