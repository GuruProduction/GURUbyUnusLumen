// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentEventTypes
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A plan carries more than its steps.
 *
 * THE SPECIFICATION ASKS FOR IT BY NAME. Section 4.1 says a plan should support objectives and
 * sub-objectives, steps, dependencies, current step status, expected outputs, verification
 * requirements, assigned tools, and the reasons for changing direction. The runtime recorded a title
 * and a list of step strings, which is three of those at best, so a reader could see what the agent
 * intended to do and not what it was for, what order constrained it, or how anyone would know it had
 * worked.
 *
 * WHAT THESE TESTS PIN. That the structured fields reach the ledger when the agent supplies them,
 * that a field it left out is absent rather than blank, and that the room reads the same revision the
 * steps came from so the two can never describe different versions of one plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentPlanStructureTest {

    private object PlainTextProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("none") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger

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

    /** Record a revision the way the runtime does, with only the fields that were supplied. */
    private suspend fun recordPlan(
        revision: Int,
        steps: List<String>,
        title: String = "the work",
        extra: Map<String, String> = emptyMap(),
    ) {
        val payload = buildJsonObject {
            put("revision", JsonPrimitive(revision))
            put("kind", JsonPrimitive("CREATED"))
            put("title", JsonPrimitive(title))
            put("steps", JsonPrimitive(steps.joinToString(" | ")))
            put("stepCount", JsonPrimitive(steps.size))
            extra.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypes.PLAN_REVISED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                actorId = "agent-1",
                payload = Json.encodeToString(JsonObject.serializer(), payload),
            )
        )
    }

    private suspend fun runView(): com.unuslumen.app.domain.agents.RunView? {
        val repo = io.mockk.mockk<com.unuslumen.app.domain.repository.GuruAgentRepository>(relaxed = true)
        io.mockk.coEvery { repo.getAgent("agent-1") } returns GuruAgent(
            id = "agent-1", slug = "planner", name = "Planner", description = "",
            systemPrompt = "you plan", toolPermissions = emptyList(),
            source = GuruAgent.SOURCE_USER, createdAt = 1, updatedAt = 1,
        )
        val assembler = AgentRoomAssembler(
            agentRepository = repo,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            ledger = ledger,
            broker = io.mockk.mockk(relaxed = true),
        )
        return assembler.assemble("agent-1")?.run
    }

    @Test
    fun everyStructuredFieldTheAgentSuppliedReachesTheRoom() = runTest {
        recordPlan(
            revision = 1,
            steps = listOf("read the file", "rewrite it", "verify"),
            extra = mapOf(
                "objective" to "get the parser to preserve order",
                "dependencies" to "1>2|2>3",
                "expectedOutputs" to "a diff | a passing test | green suite",
                "verification" to "the ordering test passes",
                "tools" to "fileEdit,shellExec",
                "reason" to "the old parser sorts, which loses order",
            ),
        )

        val plan = runView()?.plan
        assertNotNull("the plan's own fields were recorded", plan)
        assertEquals("get the parser to preserve order", plan!!.objective)
        assertEquals("1>2|2>3", plan.dependencies)
        assertEquals("a diff | a passing test | green suite", plan.expectedOutputs)
        assertEquals("the ordering test passes", plan.verification)
        assertEquals("fileEdit,shellExec", plan.tools)
        assertEquals("the old parser sorts, which loses order", plan.reason)
        assertTrue("it has detail beyond the steps", plan.hasDetail)
    }

    @Test
    fun aPlanGivenOnlyStepsSaysSoRatherThanShowingBlankRows() = runTest {
        /*
         * AN EMPTY LABELLED ROW READS AS A CLAIM. "EXPECTED: (nothing)" is a different fact from the
         * agent never having been asked, and the room draws nothing for a field that was not
         * supplied. The type says which case it is so the screen does not have to guess.
         */
        recordPlan(revision = 1, steps = listOf("do the thing"))
        val plan = runView()?.plan
        assertNotNull(plan)
        assertFalse("nothing was said beyond the steps", plan!!.hasDetail)
        assertTrue(plan.objective.isBlank())
    }

    @Test
    fun aRunWithNoPlanHasNoPlanRatherThanAnEmptyOne() = runTest {
        /*
         * NO PLAN IS NOT AN EMPTY PLAN. A run that never planned reads null, which the room draws as
         * nothing at all, rather than an object whose every field is blank and which would therefore
         * be indistinguishable from a plan the agent declined to describe.
         */
        assertNull("a run that never planned has no plan", runView()?.plan)
    }

    @Test
    fun thePlanAndTheStepsComeFromTheSameRevision() = runTest {
        /*
         * ONE REVISION, ONE TRUTH. If the steps were read from the newest revision and the plan's
         * fields from an older one, a reader would see the current steps beside a superseded
         * objective and have no way to tell.
         */
        recordPlan(
            revision = 1,
            steps = listOf("old step"),
            extra = mapOf("objective" to "the old objective"),
        )
        recordPlan(
            revision = 2,
            steps = listOf("new step one", "new step two"),
            extra = mapOf("objective" to "the new objective"),
        )

        val run = runView()!!
        assertEquals("the newest steps", 2, run.planSteps.size)
        assertEquals("and the newest objective beside them", "the new objective", run.plan?.objective)
        assertEquals(2, run.plan?.revision)
    }

    @Test
    fun aRevisionThatReplacesThePlanKeepsItsReason() = runTest {
        /*
         * THE REASON IS THE FIELD THAT MAKES A REVISION EXPLICABLE. Without it the history shows that
         * the plan changed and never why, which is the question a reader actually has.
         */
        recordPlan(revision = 1, steps = listOf("first idea"))
        recordPlan(
            revision = 2,
            steps = listOf("better idea"),
            extra = mapOf("reason" to "the first idea cannot be verified"),
        )
        assertEquals(
            "the reason survives into the room",
            "the first idea cannot be verified",
            runView()?.plan?.reason,
        )
    }

    @Test
    fun thePlanProgressCountsStepsAndNeverInventsAPercentage() = runTest {
        recordPlan(revision = 1, steps = listOf("a", "b", "c"))
        val run = runView()!!
        assertEquals(
            "a count of recorded steps, never a percentage",
            "0 of 3 steps done",
            run.planProgress,
        )
    }
}
