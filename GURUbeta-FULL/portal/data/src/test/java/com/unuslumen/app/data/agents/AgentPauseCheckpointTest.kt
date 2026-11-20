// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunCheckpointEntity
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentEventTypes
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A paused run keeps the terms it agreed to, and says so in its own vocabulary.
 *
 * THE TWO DEFECTS THIS CLOSES ARE BOTH INVISIBLE FROM THE LOOP. A pause is recorded as its own
 * reason so a reader can tell why a checkpoint exists in the middle of a run. And the budget travels
 * inside the checkpoint, so a resume puts the run back under the same limits rather than under
 * whatever the agent's row happens to say minutes later.
 *
 * NEITHER CAN BE PROVED WITHOUT A REAL DATABASE. The state is a sealed document written and read by
 * the ledger, and the whole question is whether what comes back is what went in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentPauseCheckpointTest {

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
                runId = "run-1",
                conversationId = "conv-1",
                agentId = "agent-1",
                agentName = "Worker",
                assignment = "do the work",
                status = AgentRunEntity.STATUS_RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you work",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "m",
                createdAt = 1,
                updatedAt = 1,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * The checkpoint document, built as a map rather than concatenated.
     *
     * HAND-CONCATENATING JSON IS HOW A TEST ENDS UP ASSERTING ON ITS OWN TYPO. Building it from
     * entries means a malformed document is impossible here, so a failure is about the ledger rather
     * than about the fixture.
     */
    private fun stateJson(
        exchanges: Int? = 12,
        toolRounds: Int? = Int.MAX_VALUE,
        toolCalls: Int? = null,
        durationMillis: Long? = null,
    ): String {
        /*
         * A NULL IS OMITTED, NOT WRITTEN AS null, BECAUSE THAT IS WHAT THE RUNTIME DOES. The
         * runtime's own builder skips a null field entirely, so a fixture that wrote `null` would be
         * testing a document the product never produces and would pass while the real restore path
         * read something else.
         */
        val map = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "runId" to "run-1",
            "rounds" to 4,
            "exchangeCount" to 1,
            "notebook" to "notes",
            "compactionCount" to 0,
            "systemPrompt" to "you work",
            "messageCount" to 1,
            "messages" to "user:the job",
            "maxExchanges" to exchanges,
            "maxToolRounds" to toolRounds,
            "maxToolCalls" to toolCalls,
            "maxDurationMillis" to durationMillis,
        ).filterValues { it != null }

        return map.entries.joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
            val value = if (v is Number) v.toString() else "\"" + v.toString().replace("\"", "\\\"") + "\""
            "\"$k\":$value"
        }
    }

    @Test
    fun aPauseCheckpointIsRecordedUnderItsOwnReason() = runTest {
        /*
         * WHY THE REASON MATTERS. A resume reads the newest checkpoint whatever reason it carries,
         * so the reason is not for the runtime. It is for the person reading the history later and
         * asking why a run has a checkpoint in the middle of nowhere.
         */
        val checkpoint = ledger.commitCheckpoint(
            runId = "run-1",
            reason = AgentRunCheckpointEntity.REASON_DELIBERATE_PAUSE,
            stateJson = stateJson(),
        )
        assertEquals(AgentRunCheckpointEntity.REASON_DELIBERATE_PAUSE, checkpoint.checkpointReason)

        val latest = ledger.latestCheckpoint("run-1")
        assertNotNull(latest)
        assertEquals(AgentRunCheckpointEntity.REASON_DELIBERATE_PAUSE, latest!!.checkpointReason)
    }

    @Test
    fun aPauseRequestIsNotThePauseItself() = runTest {
        /*
         * TWO FACTS, DELIBERATELY. The request says somebody asked; the state change says the loop
         * stopped. Between them is a real window in which an agent is finishing its current round,
         * and a surface that reported the first as the second would be describing something that
         * has not happened.
         */
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypes.RUN_PAUSE_REQUESTED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_USER,
                payload = """{"roundIndex":4}""",
            )
        )
        assertEquals(
            "asking to pause does not change the recorded state",
            AgentRunEntity.STATUS_RUNNING,
            ledger.run("run-1")!!.status,
        )

        ledger.appendStateChange(
            "run-1",
            AgentRunEntity.STATUS_RUNNING,
            AgentRunEntity.STATUS_PAUSED,
            "paused by a person",
        )
        assertEquals(
            "the loop stopping is what changes it",
            AgentRunEntity.STATUS_PAUSED,
            ledger.run("run-1")!!.status,
        )
    }

    @Test
    fun theBudgetSurvivesTheCheckpointRoundTrip() = runTest {
        /*
         * THE LIMITS ARE PART OF WHAT THE RUN AGREED TO. Re-deriving them at resume from the
         * agent's current row would let an edit made while the run was stopped change how much work
         * the continued run may do, which is the same offence as rewriting a snapshot.
         */
        val checkpoint = ledger.commitCheckpoint(
            runId = "run-1",
            reason = AgentRunCheckpointEntity.REASON_DELIBERATE_PAUSE,
            stateJson = stateJson(exchanges = 12, toolRounds = 500, toolCalls = 40, durationMillis = 90_000L),
        )

        val opened = ledger.openCheckpointState(checkpoint)
        val document = (opened as ProtectedRead.Value).plaintext
        val obj = Json.parseToJsonElement(document) as JsonObject

        assertEquals("12", (obj["maxExchanges"] as JsonPrimitive).content)
        assertEquals("500", (obj["maxToolRounds"] as JsonPrimitive).content)
        assertEquals("40", (obj["maxToolCalls"] as JsonPrimitive).content)
        assertEquals("90000", (obj["maxDurationMillis"] as JsonPrimitive).content)
    }

    @Test
    fun anUnbudgetedRunRecordsTheExchangeCeilingAndNeitherOptionalLimit() = runTest {
        /*
         * A NULL BUDGET IS NOT A MISSING FIELD. An agent with no wall-clock or tool-call limit
         * records neither, and the reader must be able to tell that from a checkpoint written by an
         * older build that carried no budget at all. That is why the presence check keys on
         * maxExchanges, which is always written.
         */
        val checkpoint = ledger.commitCheckpoint(
            runId = "run-1",
            reason = AgentRunCheckpointEntity.REASON_DELIBERATE_PAUSE,
            stateJson = stateJson(exchanges = 24, toolRounds = Int.MAX_VALUE, toolCalls = null, durationMillis = null),
        )
        val obj = Json.parseToJsonElement(
            (ledger.openCheckpointState(checkpoint) as ProtectedRead.Value).plaintext
        ) as JsonObject

        assertEquals("24", (obj["maxExchanges"] as JsonPrimitive).content)
        assertTrue("no tool-call ceiling was agreed", obj["maxToolCalls"] == null || obj["maxToolCalls"] is kotlinx.serialization.json.JsonNull)
        assertTrue("no wall-clock ceiling was agreed", obj["maxDurationMillis"] == null || obj["maxDurationMillis"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun aPausedRunStaysResumableBecausePausedIsNotTerminal() = runTest {
        /*
         * THE CLASSIFICATION THAT MAKES RESUME POSSIBLE AT ALL. If PAUSED were terminal the restore
         * path would refuse it, and the whole control would be decoration. This is the one line that
         * decides it.
         */
        ledger.appendStateChange(
            "run-1",
            AgentRunEntity.STATUS_RUNNING,
            AgentRunEntity.STATUS_PAUSED,
            "paused",
        )
        val run = ledger.run("run-1")!!
        assertTrue(run.status in AgentRunEntity.NON_TERMINAL_STATUSES)
        assertTrue(run.status !in AgentRunEntity.TERMINAL_STATUSES)
        assertNull("pausing does not finish the run", run.finishedAt)
    }

    @Test
    fun aPausedRunIsNotTreatedAsWaitingSoTheRecoveryScanLeavesItAlone() = runTest {
        /*
         * THE SCAN MUST NOT INTERRUPT WORK A PERSON STOPPED. Paused is neither waiting nor a dead
         * lease: the pause releases the lease deliberately, exactly as a waiting state does, so the
         * suspect query must not pick it up. This asserts the query's own behaviour rather than
         * trusting the comment above it.
         */
        ledger.appendStateChange("run-1", AgentRunEntity.STATUS_RUNNING, AgentRunEntity.STATUS_PAUSED, "paused")
        ledger.releaseLease("run-1", AgentRuntime.leaseOwner(), "paused")

        val suspects = db.agentRunDao().leaseSuspects(System.currentTimeMillis())
        assertTrue(
            "a released paused run has no lease owner, so the scan has nothing to suspect",
            suspects.none { it.runId == "run-1" },
        )
    }
}
