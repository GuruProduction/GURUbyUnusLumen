// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentMemoryService
import com.unuslumen.app.data.agents.currentDispatchScope
import com.unuslumen.app.data.agents.withDispatchScope
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verb that keeps what an agent learned, and where its identity comes from.
 *
 * THE SUBJECT IS THE BOUNDARY, not the write. The write already exists and is tested through
 * `AgentMemoryService`. What is new is the door, and the two ways a door is dangerous: an agent naming
 * another agent in its arguments, and a tool reporting a save that did not happen.
 */
class MemoryWriteToolTest {

    private fun agent(writeOwn: Boolean) = GuruAgent(
        id = "agent-1",
        slug = "scribe",
        name = "Scribe",
        description = "",
        systemPrompt = "you write things down",
        toolPermissions = emptyList(),
        source = GuruAgent.SOURCE_USER,
        memoryScope = AgentMemoryScope(writeOwn = writeOwn),
        createdAt = 0,
        updatedAt = 0,
    )

    /**
     * A repository that resolves the agent by name and nothing else.
     *
     * ONE STUB WHOSE ANSWER DEPENDS ON THE ARGUMENT, rather than two registrations on the same call.
     * Two is ambiguous in MockK and the later one wins, which made an earlier version of this file
     * report "no agent called Scribe" while the fixture plainly contained one.
     *
     * THE ARGUMENT SENSITIVITY IS WHAT MAKES THE BOUNDARY TEST REAL. If this returned the agent for
     * any name, that test would pass even if the executor used the model's argument, which is the
     * defect it exists to catch.
     */
    private fun repository(agent: GuruAgent): GuruAgentRepository =
        io.mockk.mockk(relaxed = true) {
            io.mockk.coEvery { resolveAgent(any<String>()) } answers {
                if (firstArg<String>() == agent.name) agent else null
            }
        }

    /**
     * A memory service that answers the write as the scope would, and records who it was asked for.
     *
     * A MOCK RATHER THAN A SUBCLASS, because the real service is final and making production code
     * open to test it is the wrong trade. It returns exactly what the real service returns for a
     * given permission; the real service's own scope enforcement is proved in its own tests, and the
     * subject here is what the executor does with the answer.
     */
    private fun memory(allow: Boolean, recorded: MutableList<String>): AgentMemoryService =
        io.mockk.mockk(relaxed = true) {
            io.mockk.coEvery {
                remember(any(), any(), any(), any())
            } answers {
                recorded += firstArg<GuruAgent>().name
                allow
            }
        }

    private suspend fun run(
        executor: MemoryWriteToolExecutor,
        vararg args: Pair<String, Any?>,
    ): String {
        val result = executor.execute(MemoryWriteToolDefinitions.REMEMBER_THIS, args.toMap())
        return result.error ?: result.rawJson
    }

    @Test
    fun theAgentIsTakenFromTheCoroutineNotFromTheArguments() = runTest {
        /*
         * THE BOUNDARY. A model that could name an agent would write into that agent's memory with
         * that agent's provenance on the fact, which is how one agent's mistake becomes another's
         * remembered truth.
         */
        val scribe = agent(writeOwn = true)
        val asked = mutableListOf<String>()
        val executor = MemoryWriteToolExecutor(repository(scribe), memory(allow = true, recorded = asked))

        val out = withDispatchScope(conversationId = "c", agentName = "Scribe") {
            run(executor, "fact" to "the wifi password is on the router", "agent" to "Someone Else")
        }

        assertTrue("it saved: $out", out.contains("\"remembered\":true"))
        assertEquals("and it saved under the calling agent", listOf("Scribe"), asked)
    }

    @Test
    fun aCallWithNoAgentOnTheCoroutineIsRefusedRatherThanGuessed() = runTest {
        /*
         * THE MAIN CHAT HAS NO AGENT. Rather than inventing one or writing into whichever agent was
         * looked at last, the call is refused with the reason. That is the same rule the spawn path
         * follows when no conversation is bound.
         */
        val scribe = agent(writeOwn = true)
        val asked = mutableListOf<String>()
        val executor = MemoryWriteToolExecutor(repository(scribe), memory(allow = true, recorded = asked))

        val out = run(executor, "fact" to "something worth keeping")

        assertTrue("it was refused: $out", out.contains("\"remembered\":false"))
        assertTrue(out.contains("not attached to a running agent"))
        assertTrue("and nothing was attempted", asked.isEmpty())
    }

    @Test
    fun anAgentWithNoWritePermissionIsToldPlainlyAndNotToldItSaved() = runTest {
        /*
         * THE DEFECT THIS PREVENTS. An agent told "saved" when nothing was saved will not try again
         * and will believe the fact is kept, so the next job silently starts without it. The refusal
         * names the permission and says the work can continue regardless.
         */
        val reader = agent(writeOwn = false)
        val executor = MemoryWriteToolExecutor(
            repository(reader),
            memory(allow = false, recorded = mutableListOf()),
        )

        val out = withDispatchScope(conversationId = "c", agentName = "Scribe") {
            run(executor, "fact" to "something worth keeping")
        }

        assertTrue("it did not claim a save: $out", out.contains("\"remembered\":false"))
        assertTrue("and it names the permission: $out", out.contains("permission to write memory"))
    }

    @Test
    fun aMissingFactIsRefused() = runTest {
        val executor = MemoryWriteToolExecutor(
            repository(agent(true)),
            memory(allow = true, recorded = mutableListOf()),
        )
        val out = withDispatchScope(conversationId = "c", agentName = "Scribe") {
            run(executor)
        }
        assertTrue("it is refused: $out", out.contains("Missing 'fact'"))
    }

    @Test
    fun theVerbIsRegisteredAndCannotReachTheDevice() {
        val definitions = com.unuslumen.app.data.tools.registry.ToolRegistration.allDefinitions()
        val definition = definitions.firstOrNull {
            it.name == MemoryWriteToolDefinitions.REMEMBER_THIS
        }
        assertTrue("it is on the phone: ${definitions.size} tools", definition != null)
        assertTrue(
            "and it asks for no Android permission, because it writes to the brain rather than the device",
            definition!!.permissions.isEmpty(),
        )
    }

    @Test
    fun theScopeCarriesTheAgentSoTheExecutorCanReadIt() = runTest {
        /*
         * THE MECHANISM THE FIRST TEST DEPENDS ON, PROVED ON ITS OWN. If the scope did not carry the
         * agent, that test would pass by accident the day the executor found the name some other way.
         */
        withDispatchScope(conversationId = "c1", agentName = "Scribe") {
            assertEquals("Scribe", currentDispatchScope()?.agentName())
            assertEquals("c1", currentDispatchScope()?.conversationId())
        }
        withDispatchScope(conversationId = "c2") {
            assertNull("the main assistant has no agent, which is a value rather than an unknown", currentDispatchScope()?.agentName())
        }
        assertNull("and with no scope at all there is nothing to read", currentDispatchScope())
    }
}
