// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryOwnership
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.model.GuruAgent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An agent reads exactly the memory its scope permits, and a refusal is never reported as emptiness.
 *
 * THE FIXTURES IN THIS FILE WERE THE BUG. Every case that exercised ownership used to write its own
 * provenance string:
 *
 *     fact("its own note", source = "agent=researcher")
 *
 * and the production write path never emitted that. It wrote `cerebrum`, the storage backend, so the
 * ownership check was always false. The tests passed because the fixture supplied the exact value the
 * code was looking for, which means they proved the fixture and not the code.
 *
 * So no provenance string is typed anywhere below. Every label comes from
 * [MemoryOwnership.labelFor], the function the live write path calls, and every agent identity is
 * [GuruAgent.id], the field a permission decision keys on. A fixture cannot fabricate an owner the
 * writer would never emit.
 *
 * The reading agent's id also matters. The old fixture used `slug = "researcher"` and the code keyed on
 * the slug, so a rename would have moved every permission decision with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentMemoryServiceTest {

    private fun agent(
        scope: AgentMemoryScope,
        id: String = "11111111-1111-1111-1111-111111111111",
        slug: String = "researcher",
        name: String = "Researcher",
    ) = GuruAgent(
        id = id,
        slug = slug,
        name = name,
        description = "",
        systemPrompt = "you research",
        toolPermissions = emptyList(),
        source = GuruAgent.SOURCE_USER,
        createdAt = 0,
        updatedAt = 0,
        memoryScope = scope,
    )

    /**
     * A fact whose provenance comes from the production writer rather than from this file.
     *
     * If `labelFor` and `parse` ever disagree, these tests stop compiling the truth they assert and
     * fail, which is the point.
     */
    private fun factWrittenBy(
        text: String,
        category: String = "knowledge",
        agentId: String = "",
        shared: Boolean = false,
    ) = MemoryFact(
        id = "id-${text.hashCode()}",
        category = category,
        fact = text,
        confidence = 0.9f,
        extractedDate = 0,
        layer = "SEMANTIC",
        strength = 0.9f,
        source = MemoryOwnership.labelFor("agent run", agentId = agentId, shared = shared),
        domain = category,
    )

    private fun result(hitFact: MemoryFact) =
        BrainService.DomainSearchResult(fact = hitFact, score = 0.9f, source = "CEREBRUM")

    private fun brainReturning(hits: List<BrainService.DomainSearchResult>): BrainService {
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.search(any(), any()) } returns hits
        return brain
    }

    private fun service(brain: BrainService): AgentMemoryService {
        val artefacts = mockk<AgentArtefactService>(relaxed = true)
        return AgentMemoryService(brain, artefacts)
    }

    @Test
    fun anAgentWithNoMemoryScopeIsRefusedWithAReasonRatherThanAnEmptyAnswer() = runTest {
        val outcome = service(brainReturning(emptyList())).search(
            agent = agent(AgentMemoryScope.NONE),
            runId = "run-1",
            query = "anything",
        )
        assertTrue("the refusal is stated", outcome.wasRefused)
        assertTrue(
            "and names where its owner can change it: ${outcome.refusedBecause}",
            outcome.refusedBecause!!.contains("settings"),
        )
        assertTrue(outcome.facts.isEmpty())
    }

    @Test
    fun anAgentWithNoScopeDoesNotEvenAskTheBrain() = runTest {
        val brain = brainReturning(listOf(result(factWrittenBy("secret"))))
        service(brain).search(agent(AgentMemoryScope.NONE), "run-1", "anything")
        coVerify(exactly = 0) { brain.search(any(), any()) }
    }

    @Test
    fun aBrainFailureIsNotReportedAsAnEmptyMemory() = runTest {
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.search(any(), any()) } throws IllegalStateException("brain offline")
        val outcome = service(brain).search(
            agent = agent(AgentMemoryScope(readShared = true)),
            runId = "run-1",
            query = "anything",
        )
        assertTrue("a failure is stated as a failure", outcome.wasRefused)
        assertTrue(
            "and says explicitly that it is not the same as nothing remembered",
            outcome.refusedBecause!!.contains("not the same as there being nothing remembered"),
        )
    }

    @Test
    fun anOwnOnlyAgentSeesItsOwnFactsAndAnotherAgentsPrivateFactIsInvisible() = runTest {
        /*
         * THE CASE THE OLD FIXTURE COULD NOT TEST. Two agents with real ids, both writing privately
         * through the production label function. This reader must see its own and not the other's.
         */
        val me = agent(AgentMemoryScope(readOwn = true, readShared = false))
        val otherId = "22222222-2222-2222-2222-222222222222"
        val brain = brainReturning(
            listOf(
                result(factWrittenBy("my own note", agentId = me.id)),
                result(factWrittenBy("another agent's private note", agentId = otherId, shared = false)),
            )
        )

        val outcome = service(brain).search(me, "run-1", "note")

        assertFalse(outcome.wasRefused)
        assertEquals(
            "own-only must return exactly its own fact, not its own plus a stranger's",
            listOf("my own note"),
            outcome.facts.map { it.text },
        )
    }

    @Test
    fun aSharedAgentSeesAnOfferedFactWithoutSeeingEveryPrivateFact() = runTest {
        /*
         * SHARED IS NOT "EVERYTHING". The old filter treated `readShared` as a bypass, so a shared
         * reader was handed the whole vault. Sharing is opt-in by the writer, so another agent's
         * private fact stays private however wide the scope.
         */
        val me = agent(AgentMemoryScope(readOwn = true, readShared = true))
        val otherId = "22222222-2222-2222-2222-222222222222"
        val brain = brainReturning(
            listOf(
                result(factWrittenBy("my own note", agentId = me.id)),
                result(factWrittenBy("an offered note", agentId = otherId, shared = true)),
                result(factWrittenBy("a private note", agentId = otherId, shared = false)),
                result(factWrittenBy("something the person said")),
            )
        )

        val outcome = service(brain).search(me, "run-1", "note")

        assertEquals(
            "own, the explicitly offered one, and the unattributed one: never the private one",
            listOf("my own note", "an offered note", "something the person said"),
            outcome.facts.map { it.text },
        )
    }

    @Test
    fun anOwnOnlyAgentDoesNotSeeAnotherAgentsOfferedFactEither() = runTest {
        /*
         * THE MIRROR CASE, and the one that catches a filter leaking whenever `readShared` is on.
         * Offering is permission to the shared scope; an own-only agent did not join it.
         */
        val me = agent(AgentMemoryScope(readOwn = true, readShared = false))
        val otherId = "22222222-2222-2222-2222-222222222222"
        val brain = brainReturning(
            listOf(result(factWrittenBy("an offered note", agentId = otherId, shared = true)))
        )
        val outcome = service(brain).search(me, "run-1", "note")
        assertTrue("own-only reads nothing another agent wrote", outcome.facts.isEmpty())
    }

    @Test
    fun aCategoryScopeNarrowsFurtherThanOwnership() = runTest {
        val me = agent(AgentMemoryScope(readOwn = true, readShared = true, categories = setOf("work")))
        val brain = brainReturning(
            listOf(
                result(factWrittenBy("a work fact", category = "work", agentId = me.id)),
                result(factWrittenBy("a personal fact", category = "personal_info", agentId = me.id)),
            )
        )
        val outcome = service(brain).search(me, "run-1", "fact")
        assertEquals(listOf("a work fact"), outcome.facts.map { it.text })
    }

    @Test
    fun anEmptyCategorySetMeansEveryCategoryInScope() = runTest {
        val me = agent(AgentMemoryScope(readOwn = true, readShared = true, categories = emptySet()))
        val brain = brainReturning(
            listOf(
                result(factWrittenBy("one", category = "work", agentId = me.id)),
                result(factWrittenBy("two", category = "personal_info", agentId = me.id)),
            )
        )
        assertEquals(2, service(brain).search(me, "run-1", "x").facts.size)
    }

    @Test
    fun writingIsASeparatePermissionFromReading() = runTest {
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            factWrittenBy("stored")
        val readOnly = agent(AgentMemoryScope(readShared = true, writeOwn = false))
        assertFalse(
            "reading does not imply writing",
            service(brain).remember(readOnly, "something learned"),
        )
        coVerify(exactly = 0) {
            brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun aWriteCarriesTheAgentsStableIdInTheProductionProvenanceGrammar() = runTest {
        /*
         * THE WRITE MUST EMIT WHAT THE READ PARSES. Asserting the literal string would re-create the
         * original fault in reverse, so the assertion is the round trip: the label, when parsed, names
         * this agent and is private by default.
         */
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            factWrittenBy("stored")
        val writer = agent(AgentMemoryScope(writeOwn = true))
        assertTrue(service(brain).remember(writer, "the second source was more reliable"))

        val captured = mutableListOf<String>()
        coVerify {
            brain.storeRecord(
                content = "the second source was more reliable",
                // The category is a real category. The agent's identity is NOT the category, which is
                // what filing it under `agent.<id>` got wrong.
                domain = "knowledge",
                topic = any(),
                subtopic = any(),
                provenance = capture(captured),
                rationale = any(),
                relatedTo = any(),
                confidence = any(),
            )
        }

        val parsed = MemoryOwnership.parse(captured.single())
        assertEquals("the write must name the stable id", writer.id, parsed.owner)
        assertFalse("a write is private unless the caller asks otherwise", parsed.shared)
    }

    @Test
    fun aSharedWriteMarksTheFactAsOffered() = runTest {
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            factWrittenBy("stored")
        val writer = agent(AgentMemoryScope(writeOwn = true))
        assertTrue(service(brain).remember(writer, "a shared lesson", shareWithOtherAgents = true))

        val captured = mutableListOf<String>()
        coVerify { brain.storeRecord(any(), any(), any(), any(), capture(captured), any(), any(), any()) }
        val parsed = MemoryOwnership.parse(captured.single())
        assertEquals(writer.id, parsed.owner)
        assertTrue("an explicit share is recorded as one", parsed.shared)
    }

    @Test
    fun aBlankFactIsNotStored() = runTest {
        val brain = mockk<BrainService>(relaxed = true)
        assertFalse(service(brain).remember(agent(AgentMemoryScope(writeOwn = true)), "   "))
        coVerify(exactly = 0) {
            brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun theAgentsIdentityKeyIsItsIdNotItsDisplayNameOrSlug() = runTest {
        /*
         * A RENAME MUST NOT ORPHAN ANYTHING. The key is the row id, so changing the name or the slug
         * moves nothing. The old code keyed on the slug, so a slug edit would have moved every
         * permission decision with it.
         */
        val service = service(brainReturning(emptyList()))
        val original = agent(AgentMemoryScope.NONE)
        assertEquals(original.id, service.agentIdOf(original))

        val renamed = original.copy(name = "Principal Researcher", slug = "principal-researcher")
        assertEquals(
            "a rename leaves the identity, and therefore its memory, where it was",
            original.id,
            service.agentIdOf(renamed),
        )
    }

    @Test
    fun ownFactsAreRefusedWithoutTheOwnReadScope() = runTest {
        val brain = mockk<BrainService>(relaxed = true)
        val outcome = service(brain).ownFacts(agent(AgentMemoryScope(readOwn = false)))
        assertTrue("no own-read means no own facts", outcome.isEmpty())
        coVerify(exactly = 0) { brain.getAllFacts() }
    }

    @Test
    fun ownFactsReturnsOnlyTheFactsThisAgentWrote() = runTest {
        val me = agent(AgentMemoryScope(readOwn = true))
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.getAllFacts() } returns listOf(
            factWrittenBy("mine", agentId = me.id),
            factWrittenBy("a stranger's", agentId = "22222222-2222-2222-2222-222222222222"),
            factWrittenBy("the person's"),
        )
        val own = service(brain).ownFacts(me)
        assertEquals(listOf("mine"), own.map { it.text })
    }
}
