// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.test.runTest
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
 * What GURU is told about its own agents, before it is asked anything.
 *
 * THE CLAIM THIS FILE EXISTS TO SETTLE is that operational awareness is a fact in the model's context
 * rather than a tool the model may or may not reach for. Every test here asserts on the block that is
 * injected into the system message, which is the only thing GURU actually reads.
 *
 * THE TEN-AGENT CASE IS THE ONE THAT MATTERS (A12). A block that names nine of ten agents is worse
 * than no block at all, because a model reading it will confidently report that nothing else is
 * running, and the person has no way to know a tenth existed. So the list is never truncated: the
 * per-line prose is clipped and the whole block has a ceiling, but every run is named or the block
 * says how many did not fit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentOperationsServiceTest {

    private object PlainText : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var operations: AgentOperationsService

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
        operations = AgentOperationsService(
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            messageDao = db.agentMessageDao(),
        )
        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeRun(
        runId: String,
        name: String,
        status: String = AgentRunStatuses.RUNNING,
        updatedAt: Long = 1_000,
    ) {
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = "conv-1",
                agentId = "agent-$name",
                agentSlug = name.lowercase(),
                agentName = name,
                assignment = "do the thing for $name",
                status = status,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "p",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        )
    }

    /** Nothing running is stated as a fact, not left as an absence the model has to interpret. */
    @Test
    fun anEmptyOperationsListSaysNothingIsRunning() = runTest {
        val block = operations.awarenessBlock(operations.snapshot())
        assertEquals("a fresh install gets no block at all", "", block)

        makeRun("run-old", "Writer", AgentRunStatuses.COMPLETED, updatedAt = 5_000)
        val withHistory = operations.awarenessBlock(operations.snapshot())
        assertTrue(
            "a finished run is mentioned as history rather than as work: $withHistory",
            withHistory.contains("nothing is running"),
        )
    }

    /**
     * Ten concurrent agents, every one named.
     *
     * THE DEMONSTRATION THE SPECIFICATION ASKS FOR. Ten runs alive at once, and the block GURU reads
     * must name all ten. This is the assertion that fails if anyone ever reintroduces a `take(n)`.
     */
    @Test
    fun tenConcurrentAgentsAreAllNamedInTheBlock() = runTest {
        val names = listOf(
            "Archivist", "Builder", "Critic", "Driver", "Emissary",
            "Foreman", "Glazier", "Herald", "Inspector", "Joiner",
        )
        names.forEachIndexed { index, name ->
            makeRun("run-${index + 1}", name, updatedAt = 1_000L + index)
        }

        val snapshot = operations.snapshot()
        assertEquals("all ten are live", 10, snapshot.live.size)

        val block = operations.awarenessBlock(snapshot)
        for ((index, name) in names.withIndex()) {
            assertTrue(
                "the block must name $name, and it did not: $block",
                block.contains(name),
            )
            assertTrue(
                "and it must carry the run id so GURU can act: run-${index + 1} missing",
                block.contains("run-${index + 1}"),
            )
        }
        assertTrue(
            "the header states the count so a partial read is obvious: $block",
            block.contains("10 run(s) alive"),
        )
    }

    /**
     * When the ceiling is reached the block says how many it could not fit.
     *
     * THE HONEST TRUNCATION. A hundred live runs will not fit in a page, and the acceptable failure is
     * a block that names some and states the count of the rest, with a pointer at the verb that lists
     * them all. The unacceptable failure is a block that names nine and reads as complete.
     */
    @Test
    fun anOverlongListStatesHowManyItCouldNotList() = runTest {
        repeat(80) { index ->
            makeRun("run-$index", "AgentWithALongNameNumber$index", updatedAt = 1_000L + index)
        }

        val snapshot = operations.snapshot()
        val block = operations.awarenessBlock(snapshot)

        assertEquals("the snapshot itself keeps every run", 80, snapshot.live.size)
        assertTrue("the block respects its ceiling: ${block.length}", block.length <= 2_000)
        assertTrue(
            "and it says how many it could not list rather than reading as complete: $block",
            block.contains("more run(s) are alive and not listed here"),
        )
        assertTrue(
            "and it points at the verb that has the complete set",
            block.contains("listAgentRuns"),
        )
    }

    /** A run waiting on a person is called out in its own line, above the list. */
    @Test
    fun aRunWaitingOnAPersonIsCalledOutSeparately() = runTest {
        makeRun("run-1", "Researcher", AgentRunStatuses.RUNNING)
        makeRun("run-2", "Planner", AgentRunStatuses.WAITING_FOR_GURU)

        val block = operations.awarenessBlock(operations.snapshot())

        assertTrue(
            "a blocked run is the most important fact in the block: $block",
            block.contains("WAITING ON A DECISION"),
        )
        assertTrue("and it names which one", block.contains("Planner"))
        assertTrue(
            "and says what it is waiting for",
            block.contains("waiting for you"),
        )
    }

    /** A run waiting on the owner is distinguished from one waiting on GURU. */
    @Test
    fun waitingOnTheOwnerIsNotReportedAsWaitingOnGuru() = runTest {
        makeRun("run-1", "Sender", AgentRunStatuses.WAITING_FOR_USER)

        val block = operations.awarenessBlock(operations.snapshot())

        assertTrue("the owner is named as the blocker: $block", block.contains("waiting for the person"))
        assertFalse(
            "and it is not addressed to GURU, who cannot answer it: $block",
            block.contains("waiting for you"),
        )
    }

    /** The newest thing a run did travels, so GURU knows what it is looking at. */
    @Test
    fun theNewestActivityTravelsWithEachRun() = runTest {
        makeRun("run-1", "Researcher")
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                payload = """{"invocationId":"inv-1","tool":"webSearch","arguments":"{}"}""",
            ),
        )

        val snapshot = operations.snapshot()
        val line = snapshot.line("run-1")!!

        assertEquals("the newest event is named", "started webSearch", line.newestEvent)
        assertTrue("a running run is expected to be executing", line.expectedToBeExecuting)
        assertFalse("and it is not waiting on a person", line.needsAPerson)

        val block = operations.awarenessBlock(snapshot)
        assertTrue("the activity reaches the block: $block", block.contains("webSearch"))
    }

    /** Terminal runs leave the live list, so the glow and the supervisor cannot see a dead run. */
    @Test
    fun aFinishedRunIsNotInTheLiveSet() = runTest {
        makeRun("run-1", "Writer", AgentRunStatuses.RUNNING)
        assertTrue(operations.snapshot().live.any { it.runId == "run-1" })

        ledger.terminate("run-1", AgentRunStatuses.COMPLETED, "finished")

        val after = operations.snapshot()
        assertFalse("a completed run is not live", after.live.any { it.runId == "run-1" })
        assertTrue("and it is kept as recent history", after.finished.any { it.runId == "run-1" })
    }
}
