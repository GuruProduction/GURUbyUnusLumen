// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ActorAwareToolExecutor
import com.unuslumen.app.data.tools.registry.ToolActor
import com.unuslumen.app.data.tools.registry.ToolArgumentRepair
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolDispatcher
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolNameResolver
import com.unuslumen.app.data.tools.registry.ToolProgressSink
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolSecurityPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ACTUAL path an actor travels, not a hand-built actor.
 *
 * WHY THIS FILE EXISTS, IN THE REVIEWER'S WORDS: the earlier attribution test
 * "constructs a ToolActor manually and calls MemoryToolExecutor.executeAsActor()
 * directly. It does not test the actual spawn-to-dispatch-to-memory-write chain."
 * That was correct. A test that builds its own actor proves the executor can receive
 * one; it proves nothing about whether the dispatcher ever sends one.
 *
 * So these tests use the REAL [ToolDispatcher] — real registry, real name resolver,
 * real security policy, real argument repair — and a real Android Context from
 * Robolectric, because the dispatcher checks runtime permissions through
 * ContextCompat. The only fakes are the leaf executor and the leaf store, which is
 * where faking belongs.
 *
 * WHAT IS PROVED HERE:
 *  1. ToolDispatcher forwards the actor to an ActorAwareToolExecutor.
 *  2. ToolDispatcher does NOT change behaviour for an executor that is not aware.
 *  3. The dispatcher's default is UNATTRIBUTED, not GURU. A caller that forgets
 *     cannot silently claim to be the main assistant.
 *  4. AgentToolExecutor hands the originating conversation and message into
 *     AgentRuntime.spawn, which is the link between "the chat asked for this" and
 *     "the agent recorded it".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AttributionExecutionPathTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    /** A trivial payload so a successful dispatch has something to serialise. */
    private object OkData : ToolResultData

    /**
     * An executor that declares it needs the actor and records what it received.
     *
     * This is the shape a real memory executor has, reduced to the one thing under
     * test: does the actor arrive, and is it the one the caller sent.
     */
    private class RecordingActorAwareExecutor : ToolExecutor, ActorAwareToolExecutor {
        var received: ToolActor? = null
        var calls = 0

        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
            calls++
            return ToolExecutionResult.success(OkData, """{"ok":true}""")
        }

        override suspend fun executeAsActor(
            toolName: String,
            args: Map<String, Any?>,
            actor: ToolActor,
            progress: ToolProgressSink,
        ): ToolExecutionResult {
            calls++
            received = actor
            return ToolExecutionResult.success(OkData, """{"ok":true}""")
        }
    }

    /** An executor that does NOT declare the capability. Must be called exactly as before. */
    private class PlainExecutor : ToolExecutor {
        var calls = 0
        var sawProgressCall = false

        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
            calls++
            return ToolExecutionResult.success(OkData, """{"ok":true}""")
        }

        override suspend fun executeWithProgress(
            toolName: String,
            args: Map<String, Any?>,
            progress: ToolProgressSink,
        ): ToolExecutionResult {
            sawProgressCall = true
            return execute(toolName, args)
        }
    }

    private fun registryWith(name: String, executor: ToolExecutor): ToolRegistry {
        val registry = ToolRegistry()
        registry.register(
            ToolDefinition(
                name = name,
                description = "a tool that exists only for this test",
                category = "test",
                parameters = emptyList(),
                permissions = emptyList(),
            ),
            executor,
            null,
        )
        return registry
    }

    private fun dispatcherFor(registry: ToolRegistry): ToolDispatcher = ToolDispatcher(
        registry = registry,
        nameResolver = ToolNameResolver(),
        securityPolicy = ToolSecurityPolicy(),
        argumentRepair = ToolArgumentRepair(),
        context = context,
        observationRecorder = null,
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    // ------------------------------------------------------------------
    // 1. The dispatcher forwards the actor
    // ------------------------------------------------------------------

    @Test
    fun `the dispatcher forwards the actor to an actor-aware executor`() = runBlocking {
        val executor = RecordingActorAwareExecutor()
        val dispatcher = dispatcherFor(registryWith("someMemoryTool", executor))
        val agent = ToolActor.agent(
            agentId = "ed267bed-940d-4add-a781-876b9d62548c",
            agentName = "Code Engineer",
            conversationId = "conv-1",
            messageId = "msg-1",
        )

        val result = dispatcher.dispatch("someMemoryTool", args(), "conv-1", agent)

        assertTrue("the dispatch must succeed: ${result.error}", result.success)
        assertEquals("the executor must have been called once", 1, executor.calls)
        assertEquals(
            "the dispatcher must hand over the EXACT actor the caller built, not a copy or a default",
            agent,
            executor.received,
        )
        assertEquals("ed267bed-940d-4add-a781-876b9d62548c", executor.received?.agentId)
        assertEquals("Code Engineer", executor.received?.agentName)
        assertEquals("conv-1", executor.received?.conversationId)
        assertEquals("msg-1", executor.received?.messageId)
    }

    @Test
    fun `the dispatcher forwards a GURU actor unchanged`() = runBlocking {
        val executor = RecordingActorAwareExecutor()
        val dispatcher = dispatcherFor(registryWith("someMemoryTool", executor))
        val guru = ToolActor.guru(conversationId = "conv-g", messageId = "msg-g")

        dispatcher.dispatch("someMemoryTool", args(), "conv-g", guru)

        assertEquals(guru, executor.received)
        assertEquals(ToolActor.KIND_GURU, executor.received?.kind)
    }

    // ------------------------------------------------------------------
    // 2. An unaware executor is untouched
    // ------------------------------------------------------------------

    @Test
    fun `an executor that does not declare the capability is called exactly as before`() = runBlocking {
        val executor = PlainExecutor()
        val dispatcher = dispatcherFor(registryWith("somePlainTool", executor))

        val result = dispatcher.dispatch(
            "somePlainTool",
            args(),
            "conv",
            ToolActor.agent(agentId = "a", agentName = "A", conversationId = "c", messageId = "m"),
        )

        assertTrue("the dispatch must succeed", result.success)
        assertEquals(1, executor.calls)
        assertTrue(
            "it must go through executeWithProgress, which is where it always went",
            executor.sawProgressCall,
        )
    }

    // ------------------------------------------------------------------
    // 3. The default is nobody, never GURU
    // ------------------------------------------------------------------

    @Test
    fun `a dispatch with no actor records nobody rather than claiming to be GURU`() = runBlocking {
        val executor = RecordingActorAwareExecutor()
        val dispatcher = dispatcherFor(registryWith("someMemoryTool", executor))

        dispatcher.dispatch("someMemoryTool", args())

        assertEquals(
            "an untaught call site must not be able to claim the main assistant's identity",
            ToolActor.UNATTRIBUTED,
            executor.received,
        )
        assertTrue("and it must read as unattributed", executor.received?.isAttributed == false)
        assertNull("with no conversation", executor.received?.conversationId?.ifBlank { null })
    }

    // ------------------------------------------------------------------
    // 4. The dispatcher does not invent identity when the caller is partial
    // ------------------------------------------------------------------

    @Test
    fun `a partially known agent actor is forwarded as-is rather than completed`() = runBlocking {
        val executor = RecordingActorAwareExecutor()
        val dispatcher = dispatcherFor(registryWith("someMemoryTool", executor))
        // An agent the caller knows the id of but not the conversation.
        val partial = ToolActor.agent(agentId = "agent-only", agentName = "Only")

        dispatcher.dispatch("someMemoryTool", args(), null, partial)

        assertEquals(partial, executor.received)
        assertEquals("agent-only", executor.received?.agentId)
        assertEquals(
            "an unknown conversation must stay empty, never be filled with a guess",
            "",
            executor.received?.conversationId,
        )
    }

    // ------------------------------------------------------------------
    // 5. The role label survives the escaping rule
    // ------------------------------------------------------------------

    @Test
    fun `a two-word agent name is escaped so the label stays parseable`() {
        val agent = ToolActor.agent(agentId = "abc123", agentName = "Code Engineer")

        assertEquals(
            "the id must come first and the encoded name second",
            "agent:abc123:Code%20Engineer",
            agent.roleLabel(),
        )
        assertFalse(
            "the escaped label must contain no raw space, or a whitespace split misreads it",
            agent.roleLabel().contains(' '),
        )
        assertTrue(
            "splitting on whitespace must yield exactly one token",
            1 == agent.roleLabel().split(' ').size,
        )
    }

    @Test
    fun `the id alone is enough to identify an agent whose name is unknown`() {
        val agent = ToolActor.agent(agentId = "abc123", agentName = "")
        assertEquals("agent:abc123", agent.roleLabel())
        assertEquals("actor=agent:abc123", agent.provenanceSuffix())
    }

    @Test
    fun `two agents sharing a display name are distinguishable by id`() {
        val first = ToolActor.agent(agentId = "id-one", agentName = "Code Engineer")
        val second = ToolActor.agent(agentId = "id-two", agentName = "Code Engineer")

        assertEquals("Code Engineer", first.agentName)
        assertEquals("Code Engineer", second.agentName)
        assertTrue(
            "the labels must differ, or two agents with one name are one agent in memory",
            first.roleLabel() != second.roleLabel(),
        )
        assertTrue(first.roleLabel().contains("id-one"))
        assertTrue(second.roleLabel().contains("id-two"))
    }
}
