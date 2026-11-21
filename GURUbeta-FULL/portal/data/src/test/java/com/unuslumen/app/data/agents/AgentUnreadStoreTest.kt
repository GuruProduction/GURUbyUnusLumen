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
 * The unread dot: per conversation, cleared only by reading, and honest about what it counts.
 *
 * THE FAILURES THIS PINS ARE ALL ONES A PERSON WOULD REPORT AS "the dot lied":
 *
 *  - a dot that clears because the run ended, having never been read;
 *  - a dot that clears for one conversation because another was read;
 *  - a dot shown for a message that failed to arrive, which cannot be read at all;
 *  - a dot that never appears because the message was for GURU rather than for the person.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentUnreadStoreTest {

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
    private lateinit var unread: AgentUnreadStore

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
        unread = AgentUnreadStore(
            context = ApplicationProvider.getApplicationContext(),
            runDao = db.agentRunDao(),
            messageDao = db.agentMessageDao(),
        )

        listOf("conv-a", "conv-b").forEach { id ->
            db.conversationDao().upsertConversation(
                ConversationEntity(id = id, title = id, createdDate = 1, updatedDate = 1, messageCount = 0)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeRun(runId: String, conversationId: String = "conv-a") {
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = conversationId,
                agentId = "agent-1",
                agentSlug = "writer",
                agentName = "Writer",
                assignment = "job",
                status = AgentRunStatuses.RUNNING,
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

    private suspend fun agentSaysToUser(runId: String, text: String) {
        broker.send(
            runId,
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageAuthors.AGENT,
                recipientType = AgentMessageAuthors.USER,
                messageType = AgentMessageTypes.FINDING,
                content = text,
            ),
        )
    }

    /** A message the agent addressed to the person is unread until they read it. */
    @Test
    fun aMessageToThePersonIsUnreadUntilRead() = runTest {
        makeRun("run-1")
        agentSaysToUser("run-1", "here is what I found")

        assertTrue("the agent said something to them", unread.hasUnread("conv-a"))
        assertEquals(1, unread.unreadCount("conv-a"))

        unread.markRead("conv-a")

        assertFalse("and reading it clears the mark", unread.hasUnread("conv-a"))
        assertEquals(0, unread.unreadCount("conv-a"))
    }

    /**
     * Reading one conversation does not clear another's.
     *
     * THE PER-AGENT BUG, EXACTLY. An agent-keyed watermark clears both when one is read, and the
     * person never sees the second.
     */
    @Test
    fun readingOneConversationDoesNotClearAnother() = runTest {
        makeRun("run-a", "conv-a")
        makeRun("run-b", "conv-b")
        agentSaysToUser("run-a", "a's news")
        agentSaysToUser("run-b", "b's news")

        unread.markRead("conv-a")

        assertFalse("the one that was read is clear", unread.hasUnread("conv-a"))
        assertTrue("and the one that was not is still marked", unread.hasUnread("conv-b"))
    }

    /**
     * A run ending does not clear the mark.
     *
     * THE DOT AND THE GLOW ARE DIFFERENT FACTS. A message that arrived one second before the run
     * finished is still unread after the run is gone; a dot tied to the run status vanishes under a
     * person's eyes having never been read.
     */
    @Test
    fun finishingTheRunDoesNotClearTheMark() = runTest {
        makeRun("run-1")
        agentSaysToUser("run-1", "done, here is the report")
        ledger.terminate("run-1", AgentRunStatuses.COMPLETED, "finished")

        assertTrue(
            "the run is over and the message is still unread",
            unread.hasUnread("conv-a"),
        )
    }

    /** A message the person wrote is not unread. */
    @Test
    fun thePersonsOwnMessageIsNotUnread() = runTest {
        makeRun("run-1")
        broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageAuthors.USER,
                recipientType = AgentMessageAuthors.AGENT,
                messageType = AgentMessageTypes.INSTRUCTION,
                content = "do the thing",
            ),
        )

        assertFalse("they wrote it, so it is read", unread.hasUnread("conv-a"))
    }

    /**
     * A message addressed to GURU is not unread for the person.
     *
     * AN AGENT ASKING GURU SOMETHING IS NOT THE PERSON'S BUSINESS YET. Counting it would put a dot in
     * front of them for a question they cannot answer.
     */
    @Test
    fun aMessageToGuruIsNotUnreadForThePerson() = runTest {
        makeRun("run-1")
        broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageAuthors.AGENT,
                recipientType = AgentMessageAuthors.GURU,
                messageType = AgentMessageTypes.QUESTION,
                content = "which office?",
            ),
        )

        assertFalse("it is addressed to GURU, not to them", unread.hasUnread("conv-a"))
    }

    /** The mark survives a restart, because it is written to disk rather than held in memory. */
    @Test
    fun theWatermarkSurvivesARestart() = runTest {
        makeRun("run-1")
        agentSaysToUser("run-1", "unread news")
        unread.markRead("conv-a")

        // A fresh store is exactly what a new process gets.
        val rebuilt = AgentUnreadStore(
            context = ApplicationProvider.getApplicationContext(),
            runDao = db.agentRunDao(),
            messageDao = db.agentMessageDao(),
        )

        assertFalse(
            "a read stays read across a restart, rather than the dot coming back",
            rebuilt.hasUnread("conv-a"),
        )
    }
}
