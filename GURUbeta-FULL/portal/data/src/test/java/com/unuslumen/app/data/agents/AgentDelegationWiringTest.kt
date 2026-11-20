// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.domain.model.GuruAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * A delegated run holds what it was granted, not everything it declares.
 *
 * THE POLICY IS PROVED SEPARATELY AND THAT IS NOT ENOUGH. A correct decision that the runtime then
 * ignores is worse than no policy, because it looks like a guarantee. This test drives the actual
 * resolution the spawn path calls, so the link between "the policy said these three tools" and "the
 * model is offered these three tools" is executed rather than assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentDelegationWiringTest {

    private class DummyExecutor : ToolExecutor {
        override suspend fun execute(toolName: String, args: Map<String, Any?>):
            com.unuslumen.app.data.tools.registry.ToolExecutionResult =
            com.unuslumen.app.data.tools.registry.ToolExecutionResult.error("not used")
    }

    private class FakeToolSet(override val definitions: List<ToolDefinition>) : ToolSetRegistration {
        override fun executorClass(): KClass<out ToolExecutor> = DummyExecutor::class
        override fun extractorClass(): KClass<out ToolResultExtractor>? = null
    }

    /** A registry holding the four tools these tests name, plus the question tool by definition. */
    private fun registry(): ToolRegistry {
        val registry = ToolRegistry()
        val executor = DummyExecutor()
        val sets = listOf(
            com.unuslumen.app.data.tools.AgentToolDefinitions,
            FakeToolSet(
                listOf(
                    ToolDefinition("captureScreen", "screenshot", "media", emptyList(), emptyList()),
                    ToolDefinition("phoneCall", "call", "phone", emptyList(), emptyList()),
                    ToolDefinition("sendSms", "text", "phone", emptyList(), emptyList()),
                    ToolDefinition("webSearch", "search", "network", emptyList(), emptyList()),
                )
            ),
        )
        sets.forEach { set -> set.definitions.forEach { registry.register(it, executor, null) } }
        return registry
    }

    private fun runtime(registry: ToolRegistry): AgentRuntime {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.unuslumen.app.database.guruDatabase::class.java,
        ).allowMainThreadQueries().build()
        val ledger = AgentRunLedger(
            database = db,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            checkpointDao = db.agentRunCheckpointDao(),
            protector = PassThrough,
        )
        val broker = AgentInteractionBroker(
            database = db,
            messageDao = db.agentMessageDao(),
            approvalDao = db.agentApprovalDao(),
            runDao = db.agentRunDao(),
            ledger = ledger,
        )
        return AgentRuntime(
            agentRepository = io.mockk.mockk(relaxed = true),
            toolRegistry = registry,
            toolDispatcher = io.mockk.mockk(relaxed = true),
            getPreferenceUseCase = io.mockk.mockk(relaxed = true),
            context = context,
            ledger = ledger,
            broker = broker,
            // A MOCK, because this test is about tool resolution rather than memory. The real
            // service has its own tests, and a mock here keeps the subject single.
            memory = io.mockk.mockk(relaxed = true),
        )
    }

    private object PassThrough : com.unuslumen.app.domain.security.DataProtector {
        override fun seal(
            context: com.unuslumen.app.domain.security.ProtectionContext,
            plaintext: String,
        ): com.unuslumen.app.domain.security.ProtectedWrite =
            com.unuslumen.app.domain.security.ProtectedWrite.Sealed(plaintext)

        override fun open(
            context: com.unuslumen.app.domain.security.ProtectionContext,
            stored: String?,
        ): com.unuslumen.app.domain.security.ProtectedRead =
            if (stored == null) {
                com.unuslumen.app.domain.security.ProtectedRead.Absent("none")
            } else {
                com.unuslumen.app.domain.security.ProtectedRead.Value(stored)
            }

        override fun isEnvelope(stored: String?): Boolean = false
    }

    private fun agent(declares: List<String>) = GuruAgent(
        id = "agent-child",
        slug = "child",
        name = "Child",
        description = "",
        systemPrompt = "you work",
        toolPermissions = declares,
        source = GuruAgent.SOURCE_USER,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun aDelegatedRunIsOfferedOnlyWhatItWasGranted() {
        /*
         * THE CHILD DECLARES FOUR TOOLS AND IS GRANTED ONE. If the spawn path used its own
         * declaration the model would be offered all four, and the policy's intersection would have
         * been decoration.
         */
        val runtime = runtime(registry())
        val child = agent(listOf("captureScreen", "phoneCall", "sendSms", "webSearch"))

        val offered = runtime.allowedToolsFrom(child, setOf("captureScreen"))

        val names = offered.map { it.name }.toSet()
        assertTrue("the granted tool is offered", "captureScreen" in names)
        assertFalse("a tool it declares but was not granted is not offered", "phoneCall" in names)
        assertFalse("nor the dangerous one", "sendSms" in names)
        assertFalse("nor a capability its parent lacked", "webSearch" in names)
    }

    @Test
    fun aDelegatedRunCanStillAskTheAssistant() {
        /*
         * THE QUESTION TOOL SURVIVES THE NARROWING. A delegated agent that cannot ask is an agent
         * that guesses or gives up when it hits a gap, which is the defect the ask tool exists to
         * remove. Narrowing the grant must not narrow that.
         */
        val runtime = runtime(registry())
        val child = agent(listOf("captureScreen"))

        val offered = runtime.allowedToolsFrom(child, setOf("captureScreen"))
        assertTrue(
            "the question tool is always offered",
            offered.any { it.name == com.unuslumen.app.data.tools.AgentToolDefinitions.ASK_ASSISTANT },
        )
    }

    @Test
    fun anEmptyGrantLeavesOnlyTheQuestionTool() {
        val runtime = runtime(registry())
        val child = agent(listOf("captureScreen", "phoneCall"))

        val names = runtime.allowedToolsFrom(child, emptySet()).map { it.name }.toSet()
        assertEquals(
            "a toolless delegated agent reasons and asks, and touches nothing",
            setOf(com.unuslumen.app.data.tools.AgentToolDefinitions.ASK_ASSISTANT),
            names,
        )
    }

    @Test
    fun aGrantedNameThatNoLongerExistsIsDroppedRatherThanOffered() {
        /*
         * THE REGISTRY IS THE AUTHORITY ON WHAT EXISTS. A grant naming a tool this build no longer
         * ships would otherwise be handed to the model as callable, and every call would be refused
         * at dispatch with a message about an unknown tool rather than about a stale grant.
         */
        val runtime = runtime(registry())
        val child = agent(listOf("captureScreen"))

        val names = runtime.allowedToolsFrom(
            child,
            setOf("captureScreen", "toolThatWasRemoved"),
        ).map { it.name }.toSet()

        assertTrue("the real one is offered", "captureScreen" in names)
        assertFalse("the vanished one is not", "toolThatWasRemoved" in names)
    }

    @Test
    fun theAgentVerbsAreStrippedEvenWhenGranted() {
        /*
         * AN AGENT CANNOT SPAWN AN AGENT, AND DELEGATION DOES NOT CHANGE THAT. If a grant named
         * spawnAgent the child would recurse, which is the runaway the depth ceiling exists to stop
         * as well. Two independent guards, and this is the nearer one.
         */
        val runtime = runtime(registry())
        val child = agent(listOf("captureScreen"))

        val names = runtime.allowedToolsFrom(
            child,
            setOf("captureScreen", "spawnAgent", "resumeAgent"),
        ).map { it.name }.toSet()

        assertFalse("a delegated agent may not spawn", "spawnAgent" in names)
        assertFalse("nor resume another", "resumeAgent" in names)
        assertTrue("but it keeps what was usefully granted", "captureScreen" in names)
    }
}
