// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentArtefactEntity
import com.unuslumen.app.database.entity.AgentContextReferenceEntity
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.test.runTest
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
 * Artefact provenance and context recording, proved against a real database.
 *
 * The claims here are the ones a mock cannot check: that an artefact carries the producing agent and
 * run, that an oversized inline body is refused rather than truncated, and that a candidate which
 * never reached the model stays marked as a candidate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentArtefactServiceTest {

    /** A pass-through protector. The sealing machinery has its own tests; this one is about links. */
    private object PlainTextProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing stored") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var service: AgentArtefactService
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
        service = AgentArtefactService(
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            runDao = db.agentRunDao(),
            ledger = ledger,
        )
        seedRun()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedRun(runId: String = "run-1") {
        db.conversationDao().upsertConversation(
            ConversationEntity(
                id = "conv-1",
                title = "a conversation",
                createdDate = 1,
                updatedDate = 1,
                messageCount = 0,
            )
        )
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = "conv-1",
                agentId = "agent-writer",
                agentSlug = "writer",
                agentName = "Writer",
                agentVersion = 4,
                assignment = "write the report",
                status = AgentRunEntity.STATUS_RUNNING,
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

    @Test
    fun anArtefactCarriesTheAgentRunAndVersionThatProducedIt() = runTest {
        /*
         * THE FIVE PROVENANCE FACTS THE SPECIFICATION NAMES. Producing agent, run, version, creation
         * time and sources. An artefact without them is an orphan file whose origin nobody can
         * establish later, which is exactly what a shared documents folder produces.
         */
        val artefact = service.recordArtefact(
            runId = "run-1",
            title = "Quarterly findings",
            kind = AgentArtefactEntity.KIND_REPORT,
            storageKind = AgentArtefactEntity.STORAGE_INLINE,
            storageRef = "inline-1",
            contentText = "the report body",
            sources = listOf(
                AgentArtefactService.SourceRef(
                    kind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    id = "mem-abc",
                    label = "a memory about the quarter",
                )
            ),
        )!!

        assertEquals("agent-writer", artefact.agentId)
        assertEquals("Writer", artefact.agentName)
        assertEquals("the run's agent version is carried, not the current one", 4, artefact.agentVersion)
        assertEquals("run-1", artefact.runId)
        assertTrue("a creation time is always set", artefact.createdAt > 0)
        assertTrue(
            "the source it was built from travels with it: ${artefact.sourcesJson}",
            artefact.sourcesJson.contains("mem-abc"),
        )
    }

    @Test
    fun anInlineArtefactOverTheCeilingIsRefusedRatherThanTruncated() = runTest {
        /*
         * A TRUNCATED REPORT READS AS A COMPLETE ONE. Sixteen kilobytes is the line between a row
         * and a filing cabinet, and anything past it belongs in a store. Refusing is the only
         * outcome that cannot be mistaken for success.
         */
        val tooBig = "x".repeat(AgentArtefactEntity.MAX_INLINE_BYTES + 1)

        var refused = false
        try {
            service.recordArtefact(
                runId = "run-1",
                title = "too much",
                kind = AgentArtefactEntity.KIND_REPORT,
                storageKind = AgentArtefactEntity.STORAGE_INLINE,
                storageRef = "inline",
                contentText = tooBig,
            )
        } catch (e: AgentArtefactService.TooLargeForInline) {
            refused = true
            assertTrue(
                "the refusal names the actual size and the limit: ${e.message}",
                e.message.contains(AgentArtefactEntity.MAX_INLINE_BYTES.toString()),
            )
        }
        assertTrue("an oversized inline artefact must be refused", refused)
        assertEquals("nothing was stored", 0, service.artefactsFor("run-1").size)
    }

    @Test
    fun anArtefactHeldByAStoreCarriesNoInlineBody() = runTest {
        val artefact = service.recordArtefact(
            runId = "run-1",
            title = "Rendered video",
            kind = AgentArtefactEntity.KIND_VIDEO,
            storageKind = AgentArtefactEntity.STORAGE_MEDIA_ITEM,
            storageRef = "media-xyz",
            mimeType = "video/mp4",
            sizeBytes = 400_000_000,
        )!!

        assertEquals(AgentArtefactEntity.STORAGE_MEDIA_ITEM, artefact.storageKind)
        assertEquals("media-xyz", artefact.storageRef)
        assertNull(
            "a 400MB video is not copied into a provenance row",
            artefact.contentText,
        )
        assertEquals(400_000_000L, artefact.sizeBytes)
    }

    @Test
    fun anArtefactForAnUnknownRunIsNotRecorded() = runTest {
        val result = service.recordArtefact(
            runId = "no-such-run",
            title = "orphan",
            kind = AgentArtefactEntity.KIND_REPORT,
            storageKind = AgentArtefactEntity.STORAGE_INLINE,
            storageRef = "x",
            contentText = "body",
        )
        assertNull("an artefact with no run is evidence of nothing", result)
    }

    @Test
    fun artefactsAreListedByRunAndByAgent() = runTest {
        service.recordArtefact(
            "run-1", "first", AgentArtefactEntity.KIND_REPORT,
            AgentArtefactEntity.STORAGE_INLINE, "a", contentText = "one",
        )
        service.recordArtefact(
            "run-1", "second", AgentArtefactEntity.KIND_DATASET,
            AgentArtefactEntity.STORAGE_INLINE, "b", contentText = "two",
        )

        assertEquals(2, service.artefactsFor("run-1").size)
        assertEquals(2, service.artefactsBy("agent-writer").size)
        assertEquals(0, service.artefactsFor("another-run").size)
    }

    @Test
    fun recordingAnArtefactAddsAnEventToTheRunLedger() = runTest {
        service.recordArtefact(
            "run-1", "logged", AgentArtefactEntity.KIND_CODE,
            AgentArtefactEntity.STORAGE_INLINE, "c", contentText = "val x = 1",
        )

        val events = ledger.events("run-1")
        val artefactEvent = events.firstOrNull {
            it.eventType == com.unuslumen.app.domain.agents.AgentEventTypes.ARTEFACT_CREATED
        }
        assertNotNull(
            "producing an output is a moment in the run's history, not only a row afterwards",
            artefactEvent,
        )
        assertTrue(
            "the event names which artefact: ${artefactEvent!!.payloadJson}",
            artefactEvent.payloadJson.contains("artefactId"),
        )
    }

    // ------------------------------------------------------------------
    // Context provenance
    // ------------------------------------------------------------------

    @Test
    fun aCandidateThatNeverReachedTheModelIsNotClaimedAsAnInfluence() = runTest {
        /*
         * THE FIELD THAT STOPS A LIE. A retrieval that found twelve things and delivered two is a
         * fact worth having, and the ten that did not fit must never be presented as though the
         * agent read them.
         */
        val recorded = service.recordContextCandidates(
            "run-1",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    sourceRef = "mem-1",
                    excerpt = "a relevant memory",
                    rankInLane = 1,
                    score = 0.9f,
                ),
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    sourceRef = "mem-2",
                    excerpt = "another memory that did not fit",
                    rankInLane = 2,
                    score = 0.7f,
                ),
            ),
        )
        assertEquals(2, recorded.size)
        assertTrue(
            "nothing is supplied until the caller says so",
            recorded.none { it.suppliedToModel },
        )

        service.markSupplied("run-1", listOf(recorded.first().referenceId))

        val supplied = service.suppliedContextFor("run-1")
        assertEquals("only one reached the model", 1, supplied.size)
        assertEquals("mem-1", supplied.single().sourceRef)

        val all = service.contextFor("run-1")
        assertEquals("both remain in the record", 2, all.size)
    }

    @Test
    fun aCandidateCarriesItsSourceScopeAndExcerpt() = runTest {
        val recorded = service.recordContextCandidates(
            "run-1",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_PROJECT_DOCUMENT,
                    sourceRef = "doc-7",
                    sourceLabel = "the requirements document",
                    retrievalQuery = "what are the acceptance criteria",
                    withinScope = true,
                    scopeLabel = "projects.read",
                    excerpt = "the acceptance criteria are",
                    rankInLane = 3,
                    score = 0.55f,
                )
            ),
        ).single()

        assertEquals(AgentContextReferenceEntity.SOURCE_PROJECT_DOCUMENT, recorded.sourceKind)
        assertEquals("doc-7", recorded.sourceRef)
        assertEquals("the requirements document", recorded.sourceLabel)
        assertEquals("what are the acceptance criteria", recorded.retrievalQuery)
        assertEquals("projects.read", recorded.scopeLabel)
        assertTrue(recorded.withinScope)
        assertEquals(3, recorded.rankInLane)
        assertEquals(0.55f, recorded.score, 0.0001f)
    }

    @Test
    fun anExcerptIsBoundedSoARowStaysARow() = runTest {
        val huge = "y".repeat(AgentContextReferenceEntity.MAX_EXCERPT_CHARS * 3)
        val recorded = service.recordContextCandidates(
            "run-1",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_FILE,
                    sourceRef = "file-1",
                    excerpt = huge,
                )
            ),
        ).single()

        assertEquals(
            "an excerpt is a pointer, not the document",
            AgentContextReferenceEntity.MAX_EXCERPT_CHARS,
            recorded.excerpt.length,
        )
    }

    @Test
    fun aCandidateOutsideTheAgentsScopeIsRecordedAsSuch() = runTest {
        /*
         * A RETRIEVAL THAT SHOULD NOT HAVE HAPPENED IS EVIDENCE TOO. If a source was considered
         * outside the agent's scope, recording it as within scope would hide a scope bug rather than
         * expose it.
         */
        val recorded = service.recordContextCandidates(
            "run-1",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    sourceRef = "mem-secret",
                    withinScope = false,
                    scopeLabel = "shared.read",
                )
            ),
        ).single()

        assertFalse("an out-of-scope source must say so", recorded.withinScope)
    }

    @Test
    fun aMemoryCanBeTracedToTheRunsThatUsedIt() = runTest {
        seedRun("run-2")
        service.recordContextCandidates(
            "run-1",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    sourceRef = "mem-shared",
                )
            ),
        )
        service.recordContextCandidates(
            "run-2",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    sourceRef = "mem-shared",
                )
            ),
        )

        val citations = service.citationsOf(
            AgentContextReferenceEntity.SOURCE_MEMORY,
            "mem-shared",
        )
        assertEquals(
            "one memory, used by two runs, is traceable in both directions",
            2,
            citations.size,
        )
        assertEquals(setOf("run-1", "run-2"), citations.map { it.runId }.toSet())
    }

    @Test
    fun candidatesForAnUnknownRunAreNotRecorded() = runTest {
        val recorded = service.recordContextCandidates(
            "no-such-run",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    sourceRef = "mem-1",
                )
            ),
        )
        assertTrue("nothing to attach it to", recorded.isEmpty())
    }

    @Test
    fun markingSuppliedCannotReachAnotherRunsRow() = runTest {
        seedRun("run-2")
        val other = service.recordContextCandidates(
            "run-2",
            listOf(
                AgentArtefactService.ContextCandidate(
                    sourceKind = AgentContextReferenceEntity.SOURCE_MEMORY,
                    sourceRef = "mem-other",
                )
            ),
        ).single()

        // Try to mark run-2's reference as supplied while naming run-1.
        service.markSupplied("run-1", listOf(other.referenceId))

        assertFalse(
            "the update is scoped to the run, so a reference id alone cannot flip someone else's row",
            service.contextFor("run-2").single().suppliedToModel,
        )
    }
}
