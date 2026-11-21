// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.agents.AgentCancellationService
import com.unuslumen.app.data.agents.AgentInteractionBroker
import com.unuslumen.app.data.agents.AgentRunHost
import com.unuslumen.app.data.agents.AgentRunLedger
import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * A run that stopped to ask a question must not be cancellable.
 *
 * WHY THIS TEST EXISTS. On a device on 2026-11-21 an agent ran four tool rounds, wrote a message and
 * went to WAITING_FOR_GURU to ask a question. The supervisor picked the failure up and its turn
 * chose to cancel the run, so the question was never answered and everything the agent had done was
 * thrown away. That is the worst possible outcome for the one state that exists to keep work alive.
 *
 * A PROMPT TELLING THE MODEL NOT TO DO IT IS NOT A GUARD. This is, which is why it is in code and why
 * these tests fail if it is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentCancelGuardTest {

    private object PlainText : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var executor: AgentRunToolExecutor

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
            protector = PlainText,
        )
        val broker = AgentInteractionBroker(
            database = db,
            messageDao = db.agentMessageDao(),
            approvalDao = db.agentApprovalDao(),
            runDao = db.agentRunDao(),
            ledger = ledger,
        )
        val host = AgentRunHost()
        executor = AgentRunToolExecutor(
            runDao = db.agentRunDao(),
            ledger = ledger,
            observations = db.toolObservationDao(),
            messages = db.agentMessageDao(),
            broker = broker,
            runtime = mockk<AgentRuntime>(relaxed = true),
            cancellation = AgentCancellationService(host, ledger, db.agentRunDao()),
        )
        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeRun(runId: String, status: String) {
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = "conv-1",
                agentId = "agent-1",
                agentSlug = "architect",
                agentName = "Architect",
                assignment = "say what you are for",
                status = status,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "p",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = 1_000,
                updatedAt = 1_000,
            )
        )
    }

    private fun json(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): JsonObject =
        Json.parseToJsonElement(result.rawJson).jsonObject

    /**
     * THE DEVICE FAILURE, AS A TEST. A run waiting on GURU is refused, and says why.
     */
    @Test
    fun aRunWaitingForGuruCannotBeCancelled() = runTest {
        makeRun("run-1", AgentRunStatuses.WAITING_FOR_GURU)

        val body = json(
            executor.execute(
                AgentRunToolDefinitions.CANCEL_AGENT_RUN,
                mapOf("runId" to "run-1"),
            )
        )

        assertFalse("the cancellation was refused", body["success"]!!.jsonPrimitive.booleanOrNull!!)
        assertEquals(
            "the run is still waiting, not cancelled",
            AgentRunStatuses.WAITING_FOR_GURU,
            db.agentRunDao().run("run-1")!!.status,
        )
        assertTrue(
            "and the refusal says what to do instead: ${body["sentence"]}",
            body["sentence"]!!.jsonPrimitive.content.contains("resumeAgent"),
        )
    }

    /** A run waiting on the person is refused the same way. */
    @Test
    fun aRunWaitingForThePersonCannotBeCancelled() = runTest {
        makeRun("run-2", AgentRunStatuses.WAITING_FOR_USER)

        val body = json(
            executor.execute(
                AgentRunToolDefinitions.CANCEL_AGENT_RUN,
                mapOf("runId" to "run-2"),
            )
        )

        assertFalse(body["success"]!!.jsonPrimitive.booleanOrNull!!)
        assertEquals(
            AgentRunStatuses.WAITING_FOR_USER,
            db.agentRunDao().run("run-2")!!.status,
        )
    }

    /**
     * A genuinely stuck run is still cancellable, which is the whole point of D12.
     *
     * THE GUARD MUST NOT BECOME AN EXCUSE. An interrupted run with nothing driving it is exactly the
     * class the specification says must be stoppable, and a guard that covered it would break the
     * feature it is protecting.
     */
    @Test
    fun anInterruptedRunIsStillCancellable() = runTest {
        makeRun("run-3", AgentRunStatuses.INTERRUPTED)

        val body = json(
            executor.execute(
                AgentRunToolDefinitions.CANCEL_AGENT_RUN,
                mapOf("runId" to "run-3"),
            )
        )

        assertTrue("an interrupted run can be stopped", body["success"]!!.jsonPrimitive.booleanOrNull!!)
        assertEquals(
            AgentRunStatuses.CANCELLED,
            db.agentRunDao().run("run-3")!!.status,
        )
    }

    /** A running run is still cancellable, because that is the ordinary case. */
    @Test
    fun aRunningRunIsStillCancellable() = runTest {
        makeRun("run-4", AgentRunStatuses.RUNNING)

        val body = json(
            executor.execute(
                AgentRunToolDefinitions.CANCEL_AGENT_RUN,
                mapOf("runId" to "run-4"),
            )
        )

        assertTrue(body["success"]!!.jsonPrimitive.booleanOrNull!!)
        assertEquals(AgentRunStatuses.CANCELLED, db.agentRunDao().run("run-4")!!.status)
    }

    /**
     * "Stop everything" does not route around the guard.
     *
     * A GUARD ON ONE DOOR AND NOT THE OTHER IS NOT A GUARD. This is the exact door a model would use
     * after being refused once.
     */
    @Test
    fun cancelAllLeavesWaitingRunsAloneAndSaysSo() = runTest {
        makeRun("run-waiting", AgentRunStatuses.WAITING_FOR_GURU)
        makeRun("run-running", AgentRunStatuses.RUNNING)

        val body = json(
            executor.execute(AgentRunToolDefinitions.CANCEL_ALL_AGENT_RUNS, emptyMap())
        )

        assertEquals(
            "the running one was stopped",
            AgentRunStatuses.CANCELLED,
            db.agentRunDao().run("run-running")!!.status,
        )
        assertEquals(
            "the waiting one was not, because cancelling it would discard its work",
            AgentRunStatuses.WAITING_FOR_GURU,
            db.agentRunDao().run("run-waiting")!!.status,
        )
        assertTrue(
            "and the sentence says how many were left alone: ${body["sentence"]}",
            body["sentence"]!!.jsonPrimitive.content.contains("waiting for an answer"),
        )
    }
}
