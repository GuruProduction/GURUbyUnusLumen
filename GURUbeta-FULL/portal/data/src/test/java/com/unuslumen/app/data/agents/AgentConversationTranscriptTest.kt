// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentMessageAuthors
import com.unuslumen.app.domain.agents.AgentMessageTypes
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
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
 * The room's chat is a conversation, not a run's transcript.
 *
 * THE DEFECT THIS PINS, IN THE PERSON'S WORDS. "Old messages clear from the agent chat": a run
 * finished, a new one began in the same conversation, and the transcript emptied because the room was
 * reading one run's messages. What a person was reading looked thrown away.
 *
 * THE THREE CLAIMS THAT MATTER, EACH OF WHICH CAN FAIL:
 *
 *  - a message stays after the run that carried it has finished;
 *  - a new run in the same conversation appends rather than replacing;
 *  - two overlapping runs appear in true chronological order, not grouped by run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentConversationTranscriptTest {

    private object PlainText : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var broker: AgentInteractionBroker
    private lateinit var assembler: AgentRoomAssembler
    private lateinit var repository: GuruAgentRepositoryImpl
    private lateinit var agentId: String

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
        broker = AgentInteractionBroker(
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
            ConversationEntity(id = "conv-1", title = "the thread", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
        agentId = repository.createAgent(
            SaveAgentRequest(
                name = "Writer",
                description = "writes",
                systemPrompt = "you write",
                toolPermissions = emptyList(),
            )
        ).id
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeRun(runId: String, status: String = AgentRunStatuses.RUNNING) {
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = "conv-1",
                agentId = agentId,
                agentSlug = "writer",
                agentName = "Writer",
                assignment = "write the report",
                status = status,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you write",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = 1_000,
                updatedAt = 1_000,
            )
        )
    }

    private suspend fun say(
        runId: String,
        who: String,
        text: String,
    ) {
        broker.send(
            runId,
            AgentInteractionBroker.Outgoing(
                authorType = who,
                recipientType = if (who == AgentMessageAuthors.USER) {
                    AgentMessageAuthors.AGENT
                } else {
                    AgentMessageAuthors.USER
                },
                messageType = AgentMessageTypes.FINDING,
                content = text,
            ),
        )
    }

    /**
     * A message survives the run that carried it finishing.
     *
     * THE EXACT REPORT. The run ends, the room re-assembles, and what was said must still be there.
     */
    @Test
    fun aMessageSurvivesItsRunFinishing() = runTest {
        makeRun("run-1")
        say("run-1", AgentMessageAuthors.USER, "start the survey")
        say("run-1", AgentMessageAuthors.AGENT, "the survey is under way")

        val before = assembler.assemble(agentId)!!.run!!
        assertEquals("both messages are in the room", 2, before.messages.size)

        ledger.terminate("run-1", AgentRunStatuses.COMPLETED, "done")

        val after = assembler.assemble(agentId)!!.run!!
        assertEquals(
            "the transcript must not empty when the run finishes",
            2,
            after.messages.size,
        )
        assertTrue(after.messages.any { it.content == "the survey is under way" })
    }

    /**
     * A new run in the same conversation appends to the transcript rather than replacing it.
     *
     * THE SECOND HALF OF THE REPORT. A person answered, the run finished, they sent something else,
     * and a new run began. Everything said before must still be readable.
     */
    @Test
    fun aSecondRunInTheSameConversationKeepsTheFirstOnesMessages() = runTest {
        makeRun("run-1")
        say("run-1", AgentMessageAuthors.USER, "first question")
        say("run-1", AgentMessageAuthors.AGENT, "first answer")
        ledger.terminate("run-1", AgentRunStatuses.COMPLETED, "done")

        makeRun("run-2")
        say("run-2", AgentMessageAuthors.USER, "second question")

        val room = assembler.assemble(agentId)!!.run!!
        assertEquals(
            "three messages across two runs, all in one conversation",
            3,
            room.messages.size,
        )
        assertEquals("and in the order they were said", "first question", room.messages[0].content)
        assertEquals("first answer", room.messages[1].content)
        assertEquals("second question", room.messages[2].content)
    }

    /**
     * Two runs whose messages interleave appear in time order, not grouped by run.
     *
     * THE ORDERING TRAP. Ordering by run then sequence would put every message of run one before
     * every message of run two, which reads as a conversation where one participant spoke twice in a
     * row and the other then answered both at once.
     */
    @Test
    fun overlappingRunsAppearInTrueChronologicalOrder() = runTest {
        makeRun("run-a")
        makeRun("run-b")

        say("run-a", AgentMessageAuthors.USER, "a says first")
        say("run-b", AgentMessageAuthors.USER, "b says second")
        say("run-a", AgentMessageAuthors.AGENT, "a says third")
        say("run-b", AgentMessageAuthors.AGENT, "b says fourth")

        val messages = assembler.assemble(agentId)!!.run!!.messages

        assertEquals(4, messages.size)
        assertEquals(
            "the order must be the order they were said, not the order of their runs",
            listOf("a says first", "b says second", "a says third", "b says fourth"),
            messages.map { it.content },
        )
    }

    /** A different conversation's messages never appear in this one. */
    @Test
    fun anotherConversationsMessagesDoNotLeakIn() = runTest {
        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-2", title = "elsewhere", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
        makeRun("run-1")
        say("run-1", AgentMessageAuthors.USER, "mine")

        ledger.createRun(
            AgentRunEntity(
                runId = "run-other",
                conversationId = "conv-2",
                agentId = agentId,
                agentSlug = "writer",
                agentName = "Writer",
                assignment = "something else",
                status = AgentRunStatuses.RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you write",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = 2_000,
                updatedAt = 2_000,
            )
        )
        say("run-other", AgentMessageAuthors.USER, "not mine")

        /*
         * ASKED OF THE TRANSCRIPT DIRECTLY, NOT OF THE ROOM.
         *
         * The room draws whichever run is newest, and run-other is newer, so asking the room here
         * would be asking about a different conversation. The claim under test is about the read:
         * one conversation's transcript must not contain another's messages.
         */
        val messages = broker.transcript("conv-1")
        assertTrue("my own thread is there", messages.any { it.content == "mine" })
        assertTrue(
            "and the other conversation's message is not: ${messages.map { it.content }}",
            messages.none { it.content == "not mine" },
        )

        val other = broker.transcript("conv-2")
        assertEquals("and the other side holds only its own", 1, other.size)
        assertEquals("not mine", other.single().content)
    }

    /** The transcript survives a process restart, because it is rows rather than memory. */
    @Test
    fun theTranscriptIsRebuiltFromRowsRatherThanHeldInMemory() = runTest {
        makeRun("run-1")
        say("run-1", AgentMessageAuthors.USER, "said before the restart")
        ledger.terminate("run-1", AgentRunStatuses.COMPLETED, "done")

        // A fresh assembler over the same database is exactly what a new process gets.
        val rebuilt = AgentRoomAssembler(
            agentRepository = repository,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            ledger = ledger,
            broker = broker,
        )

        val messages = rebuilt.assemble(agentId)!!.run!!.messages
        assertEquals("the transcript is still there", 1, messages.size)
        assertEquals("said before the restart", messages.single().content)
    }
}
