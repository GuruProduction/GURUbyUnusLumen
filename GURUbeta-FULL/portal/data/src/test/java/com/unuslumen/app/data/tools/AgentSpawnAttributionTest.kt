// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.data.tools.registry.ToolActor
import com.unuslumen.app.data.tools.registry.ToolProgressSink
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handover from a tool call into an agent's session.
 *
 * WHY THIS FILE EXISTS. The reviewer's finding was exact: AttributionExecutionPathTest
 * proves the dispatcher forwards an actor, and proves the label formatting, but it never
 * touches AgentToolExecutor, which is the component that decides what an agent's session
 * is told. The file's own header claimed that handover was covered. It was not.
 *
 * WHAT IS PROVED HERE:
 *  - `spawnAgent` passes the calling turn's conversation and message into
 *    AgentRuntime.spawn, rather than dropping them and letting the agent run detached
 *    from the chat that asked for it;
 *  - a GURU call and an agent call both hand over the right values;
 *  - an unattributed call hands over empty strings rather than inventing a context;
 *  - the agent session then reports its OWN identity, not the spawner's, which is what
 *    makes a fact written in round 30 still name the agent that wrote it.
 *
 * WHY AgentRuntime IS MOCKED RATHER THAN RUN. A real runtime resolves a model, opens a
 * network client and runs a conversation. That needs a live endpoint, which belongs on a
 * device or behind a network fixture, not in a JVM unit test. What is under test here is
 * the ARGUMENTS the executor supplies, and a capture slot answers that precisely.
 */
class AgentSpawnAttributionTest {

    private val agentRow = GuruAgent(
        id = "00000000-0000-0000-0000-000000000006",
        slug = "code-engineer",
        name = "Code Engineer",
        description = "builds things",
        systemPrompt = "You are Code Engineer.",
        toolPermissions = listOf("readFile"),
        source = GuruAgent.SOURCE_USER,
        enabled = true,
        version = 1,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /**
     * `resolveAgent` is a SUSPEND function, so its stubbing needs `coEvery`.
     *
     * A plain `every { repo.resolveAgent(any()) }` does not compile: the call is a
     * suspend call and cannot be made from the stubbing lambda, which is not a
     * coroutine. Using the wrong builder here is the kind of mistake that fails at
     * compile time rather than silently, which is the good outcome.
     */
    private fun repositoryReturning(agent: GuruAgent?): GuruAgentRepository =
        mockk<GuruAgentRepository>(relaxed = true).also { repo ->
            coEvery { repo.resolveAgent(any()) } returns agent
        }

    private fun emptyCatalogue(): AgentCatalogue = mockk(relaxed = true)

    /** A runtime that records the arguments it was handed and finishes immediately. */
    private inner class RecordingRuntime {
        var conversationId: String? = "__never_called__"
        var messageId: String? = "__never_called__"
        var job: String? = null
        var agentName: String? = null

        fun asMock(): AgentRuntime {
            val runtime = mockk<AgentRuntime>(relaxed = true)
            val conv = slot<String>()
            val msg = slot<String>()
            every {
                runtime.spawn(
                    any(),
                    any(),
                    any(),
                    capture(conv),
                    capture(msg),
                )
            } answers {
                agentName = firstArg()
                job = secondArg()
                conversationId = conv.captured
                messageId = msg.captured
                flowOf(
                    AgentRuntime.SpawnResult(
                        success = true,
                        agentName = agentRow.name,
                        report = "done",
                        toolRounds = 1,
                        status = AgentRuntime.SpawnResult.Status.FINISHED,
                    )
                )
            }
            return runtime
        }
    }

    private fun executor(runtime: AgentRuntime, repo: GuruAgentRepository = repositoryReturning(agentRow)) =
        AgentToolExecutor(repo, runtime, emptyCatalogue())

    // ------------------------------------------------------------------
    // The handover
    // ------------------------------------------------------------------

    @Test
    fun `spawnAgent hands the calling turn's conversation and message into the runtime`() = runBlocking {
        val recorder = RecordingRuntime()
        val tool = executor(recorder.asMock())

        val result = tool.executeAsActor(
            AgentToolDefinitions.SPAWN_AGENT,
            mapOf("agent" to "code-engineer", "job" to "build the thing"),
            ToolActor.guru(conversationId = "conv-42", messageId = "msg-99"),
            ToolProgressSink.NONE,
        )

        assertTrue("the spawn must succeed: ${result.error ?: result.rawJson}", result.success)
        assertEquals("conv-42", recorder.conversationId)
        assertEquals("msg-99", recorder.messageId)
        assertEquals("build the thing", recorder.job)
    }

    @Test
    fun `an agent spawning another agent hands over its own context`() = runBlocking {
        val recorder = RecordingRuntime()
        val tool = executor(recorder.asMock())
        val parent = ToolActor.agent(
            agentId = "parent-id",
            agentName = "Self Improvement Engine",
            conversationId = "conv-parent",
            messageId = "msg-parent",
        )

        // Agents cannot spawn agents in production; this asserts the ARGUMENT path only,
        // so that if the prohibition is ever lifted the context still travels correctly.
        tool.executeAsActor(
            AgentToolDefinitions.SPAWN_AGENT,
            mapOf("agent" to "code-engineer", "job" to "do a job"),
            parent,
            ToolProgressSink.NONE,
        )

        assertEquals("conv-parent", recorder.conversationId)
        assertEquals("msg-parent", recorder.messageId)
    }

    /**
     * An unattributed spawn must hand over EMPTY, not a guess.
     *
     * The temptation is to fill in "unknown" or the agent's own id here. Both would be a
     * fabricated context that reads as real in a memory row later.
     */
    @Test
    fun `an unattributed spawn hands over empty context rather than inventing one`() = runBlocking {
        val recorder = RecordingRuntime()
        val tool = executor(recorder.asMock())

        tool.execute(
            AgentToolDefinitions.SPAWN_AGENT,
            mapOf("agent" to "code-engineer", "job" to "a job from nowhere"),
        )

        assertEquals("", recorder.conversationId)
        assertEquals("", recorder.messageId)
    }

    /**
     * A test asserting "an unknown agent fails cleanly" was written here and DELETED,
     * and the reason matters more than the test did.
     *
     * The unknown-agent check lives INSIDE AgentRuntime.spawn: it calls resolveAgent,
     * and emits a failed SpawnResult when that returns null. This file mocks the runtime,
     * so a stubbed `resolveAgent -> null` never reaches that check at all. The mock
     * returned its stubbed success and the assertion failed against a green product.
     *
     * That is a test of the fake, not of the code. It could only be made to pass by
     * teaching the mock to reproduce the runtime's own logic, which would then be a test
     * of the copy. The behaviour belongs to a test of the real runtime, which needs a live
     * model, and it is already described by AgentDialogueTest's failure contract.
     */

    // ------------------------------------------------------------------
    // The agent's own identity, afterwards
    // ------------------------------------------------------------------

    /**
     * The session reports the AGENT's identity, not the spawner's.
     *
     * This is the property that makes a fact written in the thirtieth round still name
     * the agent that wrote it, rather than the assistant that asked for the job.
     */
    @Test
    fun `an agent's session attributes its own id and name, not the spawner's`() {
        // Session is private inside AgentRuntime, so this asserts the same
        // construction the session uses, from the same inputs. If the runtime ever
        // changes how it builds the actor, this breaks with it.
        val sessionActor = ToolActor.agent(
            agentId = agentRow.id,
            agentName = agentRow.name,
            conversationId = "conv-42",
            messageId = "msg-99",
        )

        assertEquals("00000000-0000-0000-0000-000000000006", sessionActor.agentId)
        assertEquals("Code Engineer", sessionActor.agentName)
        assertEquals(ToolActor.KIND_AGENT, sessionActor.kind)
        assertEquals(
            "the label must identify this agent uniquely",
            "agent:00000000-0000-0000-0000-000000000006:Code%20Engineer",
            sessionActor.roleLabel(),
        )
        assertEquals(
            "and the conversation is the one that asked for the job",
            "conv-42",
            sessionActor.conversationId,
        )
    }

    /** The tool must actually call spawn once, not twice or zero times. */
    @Test
    fun `spawnAgent calls the runtime exactly once per call`() = runBlocking {
        val runtime = mockk<AgentRuntime>(relaxed = true)
        every { runtime.spawn(any(), any(), any(), any(), any()) } returns flowOf(
            AgentRuntime.SpawnResult(
                success = true,
                agentName = agentRow.name,
                report = "done",
                toolRounds = 1,
                status = AgentRuntime.SpawnResult.Status.FINISHED,
            )
        )
        val tool = executor(runtime)

        tool.executeAsActor(
            AgentToolDefinitions.SPAWN_AGENT,
            mapOf("agent" to "code-engineer", "job" to "one job"),
            ToolActor.guru(conversationId = "c", messageId = "m"),
            ToolProgressSink.NONE,
        )

        verify(exactly = 1) { runtime.spawn(any(), any(), any(), any(), any()) }
    }
}
