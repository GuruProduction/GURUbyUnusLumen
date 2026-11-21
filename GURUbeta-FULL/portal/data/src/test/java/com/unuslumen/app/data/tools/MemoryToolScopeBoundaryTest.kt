// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.withDispatchScope
import com.unuslumen.app.data.memory.persistedFactRoundTrip
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.memory.FactListing
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryOwnership
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.RetrievedContext
import com.unuslumen.app.domain.memory.ToolResultSearchHit
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GuruAgentRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An agent cannot read another agent's memory by calling a tool instead of the service.
 *
 * THE HOLE THIS CLOSES. The visibility scope was enforced inside `AgentMemoryService.search`
 * and nowhere else. Every other route into memory went straight to the repository, so an
 * agent with own-only scope could call `searchMemoryFacts`, `listMemoryFacts`,
 * `traverseMemoryGraph` or `searchAllMemory` and read the entire vault. The partition was a
 * property of one function, which is not a boundary.
 *
 * These tests drive the REAL executor, with the caller's identity arriving from the
 * coroutine exactly as the dispatcher installs it, and provenance produced by the same
 * writer the app uses. Nothing here hand-writes a provenance string or a scope.
 */
class MemoryToolScopeBoundaryTest {

    private val otherAgentId = "22222222-2222-2222-2222-222222222222"
    private val callerAgentId = "11111111-1111-1111-1111-111111111111"

    private fun fact(
        text: String,
        owner: String = "",
        shared: Boolean = false,
        category: String = "knowledge",
    ) = MemoryFact(
        id = "id-${text.hashCode()}",
        category = category,
        fact = text,
        confidence = 0.9f,
        extractedDate = 1L,
        source = MemoryOwnership.labelFor("agent run", agentId = owner, shared = shared),
        domain = category,
    )

    /**
     * A repository holding three facts written through the production provenance writer.
     *
     * THE CALLER'S OWN FACT MUST CARRY THE CALLER'S ID. The first version of this fixture wrote
     * it with no owner at all, so the boundary correctly treated it as the person's fact rather
     * than the agent's own, and own-only rejected it. That was the fixture being wrong about
     * what an agent's own fact looks like, not the boundary being wrong.
     */
    private fun repository(): MemoryRepository {
        val rows = listOf(
            fact("the caller's own note", owner = callerAgentId),
            fact("another agent's private note", owner = otherAgentId, shared = false),
            fact("another agent's offered note", owner = otherAgentId, shared = true),
        )
        val repo = mockk<MemoryRepository>(relaxed = true)
        coEvery { repo.getAllFacts() } returns rows
        coEvery { repo.searchFacts(any(), any()) } returns rows
        coEvery { repo.getFactsByCategory(any()) } returns rows
        coEvery { repo.listFacts(any()) } returns FactListing(rows, rows.size, rows.size, true)
        coEvery { repo.traverseGraph(any(), any(), any(), any()) } returns rows
        coEvery { repo.searchMessages(any()) } returns emptyList()
        coEvery { repo.searchToolResults(any()) } returns emptyList()
        coEvery { repo.getFact(any()) } returns rows[1]
        return repo
    }

    /** The calling agent, resolved by the repository from the name the dispatcher carried. */
    private fun agents(scope: AgentMemoryScope): GuruAgentRepository {
        val agent = GuruAgent(
            id = callerAgentId,
            slug = "researcher",
            name = "researcher",
            description = "",
            systemPrompt = "you research",
            toolPermissions = emptyList(),
            source = GuruAgent.SOURCE_USER,
            createdAt = 0,
            updatedAt = 0,
            memoryScope = scope,
        )
        val repo = mockk<GuruAgentRepository>(relaxed = true)
        coEvery { repo.resolveAgent(any()) } returns agent
        return repo
    }

    private fun executor(scope: AgentMemoryScope) =
        MemoryToolExecutor(repository(), agents(scope))


    private fun payloadOf(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): String =
        if (result.success) result.rawJson else (result.error ?: "")

    @Test
    fun `an own-only agent cannot read another agent's private fact through searchMemoryFacts`() = runTest {
        val boundary = executor(AgentMemoryScope(readOwn = true, readShared = false))
        val outcome = withDispatchScope(conversationId = "conv-1", agentName = "researcher") {
            boundary.execute(MemoryToolDefinitions.SEARCH_MEMORY_FACTS, mapOf("query" to "note"))
        }
        val payload = payloadOf(outcome)
        assertTrue("its own fact is there: $payload", payload.contains("the caller's own note"))
        assertFalse(
            "another agent's private fact must not be reachable by calling the tool instead of the service",
            payload.contains("another agent's private note"),
        )
    }

    @Test
    fun `an own-only agent cannot read an offered fact either`() = runTest {
        val boundary = executor(AgentMemoryScope(readOwn = true, readShared = false))
        val payload = payloadOf(
            withDispatchScope(conversationId = "conv-1", agentName = "researcher") {
                boundary.execute(MemoryToolDefinitions.SEARCH_MEMORY_FACTS, mapOf("query" to "note"))
            },
        )
        assertFalse(
            "offering is permission to the shared scope, and this agent did not join it",
            payload.contains("another agent's offered note"),
        )
    }

    @Test
    fun `a shared agent sees the offered fact and still not the private one`() = runTest {
        val boundary = executor(AgentMemoryScope(readOwn = true, readShared = true))
        val payload = payloadOf(
            withDispatchScope(conversationId = "conv-1", agentName = "researcher") {
                boundary.execute(MemoryToolDefinitions.SEARCH_MEMORY_FACTS, mapOf("query" to "note"))
            },
        )
        assertTrue(payload.contains("another agent's offered note"))
        assertFalse(
            "shared is not a bypass; the private fact stays private",
            payload.contains("another agent's private note"),
        )
    }

    @Test
    fun `the listing tool obeys the same boundary as the search tool`() = runTest {
        /*
         * A BOUNDARY ON ONE PATH IS NOT A BOUNDARY. `listMemoryFacts` reads through a
         * different repository call and would have leaked everything the search refused.
         */
        val boundary = executor(AgentMemoryScope(readOwn = true, readShared = false))
        val payload = payloadOf(
            withDispatchScope(conversationId = "conv-1", agentName = "researcher") {
                boundary.execute(MemoryToolDefinitions.LIST_MEMORY_FACTS, emptyMap())
            },
        )
        assertFalse("the private fact is not listed", payload.contains("another agent's private note"))
    }

    @Test
    fun `the graph walk and the unified search obey the same boundary`() = runTest {
        val boundary = executor(AgentMemoryScope(readOwn = true, readShared = false))
        val graphPayload = payloadOf(
            withDispatchScope(conversationId = "conv-1", agentName = "researcher") {
                boundary.execute(MemoryToolDefinitions.TRAVERSE_MEMORY_GRAPH, mapOf("subject" to "note"))
            },
        )
        assertFalse(graphPayload.contains("another agent's private note"))

        val unifiedPayload = payloadOf(
            withDispatchScope(conversationId = "conv-1", agentName = "researcher") {
                boundary.execute(MemoryToolDefinitions.SEARCH_ALL_MEMORY, mapOf("query" to "note"))
            },
        )
        assertFalse(unifiedPayload.contains("another agent's private note"))
    }

    @Test
    fun `a fact cannot be read by naming its id when the agent may not see it`() = runTest {
        /*
         * AN EXISTENCE CHECK IS A READ. Delete and update resolve a row by id and that
         * lookup returns its text, so without this an agent could read any fact in the vault
         * by naming its id, and the id is the hash of the content, so it is not a secret.
         */
        val boundary = executor(AgentMemoryScope(readOwn = true, readShared = false))
        val payload = payloadOf(
            withDispatchScope(conversationId = "conv-1", agentName = "researcher") {
                boundary.execute(
                    MemoryToolDefinitions.DELETE_MEMORY_FACT,
                    mapOf("factId" to "id-${"another agent's private note".hashCode()}"),
                )
            },
        )
        assertFalse(
            "a hidden fact must be reported absent, not returned as the delete's echo",
            payload.contains("another agent's private note"),
        )
    }

    @Test
    fun `the main assistant is not locked out of its own memory`() = runTest {
        /*
         * NO AGENT IN SCOPE MEANS THE OWNER IS CALLING. The partition exists to stop one agent
         * reading another's; it must not fence the person out of his own brain.
         */
        val boundary = executor(AgentMemoryScope(readOwn = true, readShared = false))
        val payload = payloadOf(
            boundary.execute(MemoryToolDefinitions.SEARCH_MEMORY_FACTS, mapOf("query" to "note")),
        )
        assertTrue("the owner sees everything", payload.contains("another agent's private note"))
    }
}
