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
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What one assemble costs, measured in decryptions rather than asserted in prose.
 *
 * WHY THIS TEST EXISTS AND WHY IT COUNTS. The room used to re-assemble everything on a two-second
 * poll, and every event payload is sealed, so every assemble was one Keystore decryption per event.
 * On a device that measured 77 decrypts and 181,661 bytes per assemble and 2,310 decrypts a minute,
 * growing with the run because the whole history was re-read each tick. The app crawled while an
 * agent worked, and the cause was arithmetic rather than a mystery.
 *
 * A DEVICE BASELINE CANNOT BE TAKEN HERE. S0 asks for `dumpsys gfxinfo`, `dumpsys meminfo` and a
 * decrypt count from a real phone, and no device is attached to this machine. So the claim this test
 * makes is the one the JVM can actually settle: how many times the protector is asked to open a
 * value, per assemble, before and after. That number is the dominant cost by measurement, and it is
 * the number the fix moves.
 *
 * THE COUNTER IS THE POINT. Every assertion below is about a count that can fail. A test that only
 * checked the room still rendered correctly would pass against the poll just as happily.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentRoomFoldCostTest {

    /**
     * A protector that actually seals, and counts every open.
     *
     * IT SEALS RATHER THAN PASSING PLAINTEXT THROUGH ON PURPOSE. If it passed plaintext through, the
     * ledger would take a different branch and the test would be measuring a code path production
     * never walks. The envelope shape is the same one the real protector produces, so the ledger's
     * open path is exercised exactly as it is on a phone.
     */
    private class CountingProtector : DataProtector {
        val opens = AtomicInteger(0)
        val seals = AtomicInteger(0)

        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite {
            seals.incrementAndGet()
            return ProtectedWrite.Sealed("SEALED:$plaintext")
        }

        override fun open(context: ProtectionContext, stored: String?): ProtectedRead {
            opens.incrementAndGet()
            if (stored == null) return ProtectedRead.Absent("nothing stored")
            if (!stored.startsWith("SEALED:")) return ProtectedRead.Value(stored)
            return ProtectedRead.Value(stored.removePrefix("SEALED:"))
        }

        override fun isEnvelope(stored: String?): Boolean = stored?.startsWith("SEALED:") == true

        fun reset() {
            opens.set(0)
            seals.set(0)
        }
    }

    private lateinit var db: guruDatabase
    private lateinit var protector: CountingProtector
    private lateinit var ledger: AgentRunLedger
    private lateinit var assembler: AgentRoomAssembler
    private lateinit var repository: GuruAgentRepositoryImpl
    private lateinit var agentId: String

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()

        protector = CountingProtector()
        ledger = AgentRunLedger(
            database = db,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            checkpointDao = db.agentRunCheckpointDao(),
            protector = protector,
        )
        val broker = AgentInteractionBroker(
            database = db,
            messageDao = db.agentMessageDao(),
            approvalDao = db.agentApprovalDao(),
            runDao = db.agentRunDao(),
            ledger = ledger,
        )
        repository = GuruAgentRepositoryImpl(
            guruAgentDao = db.guruAgentDao(),
            definitionVersionDao = db.agentDefinitionVersionDao(),
            transactor = RoomAgentStoreTransactor(db),
            toolRegistry = com.unuslumen.app.data.tools.registry.ToolRegistry(),
        )
        assembler = AgentRoomAssembler(
            agentRepository = repository,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            ledger = ledger,
            broker = broker,
        )

        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
        agentId = repository.createAgent(
            SaveAgentRequest(
                name = "Writer",
                description = "writes things",
                systemPrompt = "you write",
                toolPermissions = emptyList(),
            )
        ).id

        ledger.createRun(
            AgentRunEntity(
                runId = "run-1",
                conversationId = "conv-1",
                agentId = agentId,
                agentName = "Writer",
                assignment = "write the report",
                status = AgentRunStatuses.RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you write",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = 1000,
                updatedAt = 1000,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Append a completed tool call, which is two events a reader has to decrypt. */
    private suspend fun appendToolCall(index: Int) {
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                payload = """{"invocationId":"inv-$index","tool":"captureScreen","arguments":"{}"}""",
            ),
        )
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_COMPLETED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_TOOL,
                payload = """{"invocationId":"inv-$index","tool":"captureScreen","result":"ok"}""",
            ),
        )
    }

    /**
     * The first assemble pays for the history; an unchanged second assemble pays nothing.
     *
     * THIS IS THE FIX, STATED AS A NUMBER. Against the old implementation the second assemble cost
     * exactly what the first did, and the third cost the same again, which is what the poll did every
     * two seconds for as long as a run was on screen.
     */
    @Test
    fun anUnchangedAssembleDecryptsNothing() = runTest {
        repeat(6) { appendToolCall(it) }
        protector.reset()

        val first = assembler.assemble(agentId)
        val afterFirst = protector.opens.get()

        assertTrue("the first assemble has to read the history", afterFirst > 0)
        assertEquals("the tool calls are all there", 6, first!!.run!!.toolActivity.size)

        protector.reset()
        val second = assembler.assemble(agentId)

        assertEquals(
            "an unchanged room must not decrypt anything at all, and it decrypted " +
                "${protector.opens.get()} values",
            0,
            protector.opens.get(),
        )
        assertEquals(
            "and it must still draw the same room",
            6,
            second!!.run!!.toolActivity.size,
        )
    }

    /** One new event costs one decryption, not one per event ever written. */
    @Test
    fun anAssembleAfterOneNewEventDecryptsOnlyThatEvent() = runTest {
        repeat(10) { appendToolCall(it) }
        assembler.assemble(agentId)

        protector.reset()
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                payload = """{"invocationId":"inv-new","tool":"captureScreen","arguments":"{}"}""",
            ),
        )
        val afterAppend = protector.opens.get()
        protector.reset()

        val room = assembler.assemble(agentId)

        assertEquals(
            "one new event is one decryption, and it took ${protector.opens.get()}",
            1,
            protector.opens.get(),
        )
        assertEquals(
            "the new call is visible alongside the old ones",
            11,
            room!!.run!!.toolActivity.size,
        )
        // The append path itself seals and opens nothing of the history, which is why this figure is
        // about the write rather than the read.
        assertTrue("appending does not decrypt the history", afterAppend < 5)
    }

    /**
     * A cache evicted under pressure rebuilds correctly, and only costs time.
     *
     * THE CACHE IS BOUNDED, SO THIS MUST BE SAFE. Nine runs read in turn evict the first from an
     * eight-entry cache; asking for it again must produce the same room, not a half-filled one. A
     * cache that silently dropped events would be worse than no cache at all, because it would look
     * like an agent that forgot what it did.
     */
    @Test
    fun anEvictedRunIsRebuiltIdentically() = runTest {
        repeat(3) { appendToolCall(it) }
        val before = assembler.assemble(agentId)!!.run!!

        // Nine other agents with their own runs push this one out of an eight-entry cache.
        repeat(9) { index ->
            val other = repository.createAgent(
                SaveAgentRequest(
                    name = "Other $index",
                    description = "d",
                    systemPrompt = "p",
                    toolPermissions = emptyList(),
                )
            )
            ledger.createRun(
                AgentRunEntity(
                    runId = "other-$index",
                    conversationId = "conv-1",
                    agentId = other.id,
                    agentName = other.name,
                    assignment = "job $index",
                    status = AgentRunStatuses.RUNNING,
                    agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                    resolvedSystemPrompt = "p",
                    toolManifestSnapshotJson = """{"schemaVersion":1}""",
                    modelProvider = "UnusLumen",
                    modelName = "a-model",
                    createdAt = 2000,
                    updatedAt = 2000,
                )
            )
            assembler.assemble(other.id)
        }

        val after = assembler.assemble(agentId)!!.run!!

        assertEquals("the same tool calls", before.toolActivity.size, after.toolActivity.size)
        assertEquals("the same assignment", before.assignment, after.assignment)
        assertEquals("the same status", before.status, after.status)
    }
}
