// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.model.GuruAgent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An agent reads only the memory its scope permits, and a refusal is never reported as emptiness.
 *
 * THE DISTINCTION THIS FILE EXISTS FOR. An agent told "nothing was found" will answer from its prompt
 * as though that is all there is. An agent told "you may not look" can say so, and its owner can
 * change the setting. Collapsing the two is how a permission becomes a silent limitation.
 *
 * The brain is mocked here rather than driven, because the subject is the scope enforcement and the
 * brain's own behaviour has its own tests. What is asserted is that the filter is applied and that
 * the refusal path is reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentMemoryServiceTest {

    private fun agent(scope: AgentMemoryScope) = GuruAgent(
        id = "agent-1",
        slug = "researcher",
        name = "Researcher",
        description = "",
        systemPrompt = "you research",
        toolPermissions = emptyList(),
        source = GuruAgent.SOURCE_USER,
        createdAt = 0,
        updatedAt = 0,
        memoryScope = scope,
    )

    private fun fact(text: String, category: String = "memory", source: String = "cerebrum") = MemoryFact(
        id = "id-${text.hashCode()}",
        category = category,
        fact = text,
        embedding = emptyList(),
        confidence = 0.9f,
        sourceConversationIds = emptyList(),
        extractedDate = 0,
        lastRecalledDate = 0,
        layer = "BUFFER",
        strength = 0.9f,
        accessCount = 0,
        source = source,
        trigger = "",
        beforeContext = "",
        afterContext = "",
        emotionalValence = 0f,
        domain = "",
        topic = "",
        subtopic = "",
        signature = "",
    )

    private fun brainReturning(facts: List<BrainService.DomainSearchResult>): BrainService {
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.search(any(), any()) } returns facts
        return brain
    }

    private fun service(brain: BrainService): AgentMemoryService {
        val artefacts = mockk<AgentArtefactService>(relaxed = true)
        return AgentMemoryService(brain, artefacts)
    }

    @Test
    fun anAgentWithNoMemoryScopeIsRefusedWithAReasonRatherThanAnEmptyAnswer() = runTest {
        val brain = brainReturning(emptyList())
        val outcome = service(brain).search(
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
        /*
         * A REFUSAL SHOULD NOT COST A SEARCH. Asking the brain and discarding the answer would leak
         * timing about what exists, and it would be wasted work on every turn of a toolless agent.
         */
        val brain = brainReturning(listOf(BrainService.DomainSearchResult(fact("secret"), 0.9f, "CEREBRUM")))
        service(brain).search(agent(AgentMemoryScope.NONE), "run-1", "anything")
        coVerify(exactly = 0) { brain.search(any(), any()) }
    }

    @Test
    fun aBrainFailureIsNotReportedAsAnEmptyMemory() = runTest {
        /*
         * THE DISTINCTION THAT MATTERS MOST IN THIS FILE. A brain that will not answer is not a
         * memory that holds nothing, and telling an agent the second when the first is true makes it
         * answer from its prompt as though that were all there is.
         */
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
    fun anAgentWithOwnOnlyScopeSeesOnlyItsOwnFacts() = runTest {
        /*
         * OWN-ONLY IS THE DEFAULT AND IT IS A REAL FILTER. The brain returns everything relevant;
         * this keeps the agent's own facts and drops the rest, so an agent does not ingest GURU's
         * whole knowledge merely by existing.
         */
        val brain = brainReturning(
            listOf(
                BrainService.DomainSearchResult(fact("its own note", source = "agent=researcher"), 0.9f, "CEREBRUM"),
                BrainService.DomainSearchResult(fact("a shared fact", source = "cerebrum"), 0.9f, "CEREBRUM"),
            )
        )
        val outcome = service(brain).search(
            agent = agent(AgentMemoryScope(readShared = false, readOwn = true)),
            runId = "run-1",
            query = "note",
        )

        assertFalse(outcome.wasRefused)
        assertTrue(
            "its own fact survives the filter",
            outcome.facts.any { it.text == "its own note" },
        )
        assertFalse(
            "the shared one does not, because the scope says own-only",
            outcome.facts.any { it.text == "a shared fact" },
        )
    }

    @Test
    fun sharedReadWidensTheLane() = runTest {
        val brain = brainReturning(
            listOf(
                BrainService.DomainSearchResult(fact("its own note", source = "agent=researcher"), 0.9f, "CEREBRUM"),
                BrainService.DomainSearchResult(fact("a shared fact", source = "cerebrum"), 0.9f, "CEREBRUM"),
            )
        )
        val outcome = service(brain).search(
            agent = agent(AgentMemoryScope(readShared = true)),
            runId = "run-1",
            query = "fact",
        )

        assertTrue("both are visible when shared is granted", outcome.facts.size == 2)
    }

    @Test
    fun aCategoryScopeNarrowsFurther() = runTest {
        val brain = brainReturning(
            listOf(
                BrainService.DomainSearchResult(fact("a work fact", category = "work"), 0.9f, "CEREBRUM"),
                BrainService.DomainSearchResult(fact("a personal fact", category = "personal"), 0.9f, "CEREBRUM"),
            )
        )
        val outcome = service(brain).search(
            agent = agent(
                AgentMemoryScope(readShared = true, categories = setOf("work"))
            ),
            runId = "run-1",
            query = "fact",
        )

        assertTrue("the in-category fact survives", outcome.facts.any { it.text == "a work fact" })
        assertFalse("the out-of-category one does not", outcome.facts.any { it.text == "a personal fact" })
    }

    @Test
    fun anEmptyCategorySetMeansEveryCategoryInScope() = runTest {
        val brain = brainReturning(
            listOf(
                BrainService.DomainSearchResult(fact("one", category = "work"), 0.9f, "CEREBRUM"),
                BrainService.DomainSearchResult(fact("two", category = "personal"), 0.9f, "CEREBRUM"),
            )
        )
        val outcome = service(brain).search(
            agent = agent(AgentMemoryScope(readShared = true, categories = emptySet())),
            runId = "run-1",
            query = "x",
        )
        assertTrue("an empty set is not a filter", outcome.facts.size == 2)
    }

    @Test
    fun writingIsASeparatePermissionFromReading() = runTest {
        /*
         * AN AGENT THAT MAY CONSULT MEMORY IS NOT THEREBY AN AGENT THAT MAY CHANGE IT. A write is how
         * one agent's mistake becomes every agent's fact, so it is its own switch and off by default.
         */
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            fact("stored")

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
    fun aWriteIsAttributedToTheAgentRatherThanThePerson() = runTest {
        /*
         * PROVENANCE IS THE POINT OF THE WRITE. A fact an agent recorded must be distinguishable
         * from something the person said, or an agent's guess becomes the user's stated preference
         * the next time anybody looks.
         */
        val brain = mockk<BrainService>(relaxed = true)
        coEvery { brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            fact("stored")

        val writer = agent(AgentMemoryScope(writeOwn = true))
        assertTrue(service(brain).remember(writer, "the second source was more reliable"))

        coVerify {
            brain.storeRecord(
                content = "the second source was more reliable",
                domain = any(),
                topic = any(),
                subtopic = any(),
                provenance = "agent=researcher",
                rationale = any(),
                relatedTo = any(),
                confidence = any(),
            )
        }
    }

    @Test
    fun aBlankFactIsNotStored() = runTest {
        val brain = mockk<BrainService>(relaxed = true)
        val writer = agent(AgentMemoryScope(writeOwn = true))
        assertFalse(service(brain).remember(writer, "   "))
        coVerify(exactly = 0) {
            brain.storeRecord(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun anAgentsDomainIsDerivedFromItsIdentityNotItsDisplayName() = runTest {
        /*
         * THE SLUG IS THE STABLE KEY. A rename must not orphan everything the agent remembered, which
         * is exactly what a domain built from the display name would do.
         */
        val service = service(brainReturning(emptyList()))
        val bySlug = agent(AgentMemoryScope.NONE)
        assertTrue(service.domainFor(bySlug) == "agent.researcher")

        val renamed = bySlug.copy(name = "Principal Researcher")
        assertTrue(
            "a rename leaves its own memory where it was",
            service.domainFor(renamed) == "agent.researcher",
        )
    }

    @Test
    fun ownFactsAreRefusedWithoutTheOwnReadScope() = runTest {
        val brain = mockk<BrainService>(relaxed = true)
        val outcome = service(brain).ownFacts(agent(AgentMemoryScope(readOwn = false)))
        assertTrue("no own-read means no own facts", outcome.isEmpty())
        coVerify(exactly = 0) { brain.getFactsByDomain(any()) }
    }
}
