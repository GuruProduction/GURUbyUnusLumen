package com.unuslumen.app.domain.slashcommands

import com.unuslumen.app.domain.slashcommands.commands.ClearCommand
import com.unuslumen.app.domain.slashcommands.commands.StatusCommand
import com.unuslumen.app.domain.slashcommands.commands.StopCommand
import com.unuslumen.app.domain.slashcommands.commands.TasksCommand
import com.unuslumen.app.domain.slashcommands.commands.ToolsCommand
import com.unuslumen.app.domain.slashcommands.commands.UsageCommand
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dispatcher tests. These exercise the full compute-then-apply contract: every
 * test runs [runThrough] which executes the invocation and applies the result
 * to the recording host, mirroring what ChatHostRouter.route() does. A result
 * type added without an apply() branch or a test through this helper cannot
 * pass review, and no command can silently vanish after computing.
 */
class SlashCommandDispatcherTest {

    private lateinit var recordingHost: RecordingHost

    /** Host that records every effect it receives. */
    private class RecordingHost : SlashCommandHost {
        val replies = mutableListOf<String>()
        val details = mutableListOf<Pair<String, List<String>>>()
        var newConversation = 0
        var cancels = 0
        val forwards = mutableListOf<String>()
        var skillsOpened = 0
        var permissionGates = 0

        override fun showSystemReply(text: String) { replies += text }
        override fun showDetailList(title: String, items: List<String>) { details += title to items }
        override fun startNewConversation() { newConversation++ }
        override fun cancelRun() { cancels++ }
        override fun forwardToEngine(commandText: String) { forwards += commandText }
        override fun openSkills() { skillsOpened++ }
        override fun openPermissionGate() { permissionGates++ }
    }

    private val session = object : SlashCommandSession {
        override val conversationId: String? = "conv-1234"
        override val messageCount: Int = 7
        override val isBusy: Boolean = false
        override val providerId: String? = null
        override val tokenEstimate: Int = 1234
        override val recentToolCalls: List<ToolCallFact> = emptyList()
        override val conversationTasks: List<ConversationTaskSummary> = emptyList()
    }

    @Before
    fun setUp() {
        recordingHost = RecordingHost()
    }

    private fun dispatcherWith(vararg actions: SlashCommandAction) =
        SlashCommandDispatcher(actions.associateBy { it.key })

    /** The router's own flow: compute, then apply. One funnel, no skips. */
    private suspend fun runThrough(
        dispatcher: SlashCommandDispatcher,
        invocation: SlashInvocation,
        session: SlashCommandSession = this.session,
    ): SlashCommandResult {
        val result = dispatcher.execute(invocation, session)
        dispatcher.apply(result, recordingHost)
        return result
    }

    private fun invocation(key: String, raw: String? = null, values: Map<String, String> = emptyMap()) =
        SlashInvocation(key = key, rawArgs = raw, values = values)

    @Test
    fun `unknown key falls through to engine forward with full text`() = runTest {
        val dispatcher = dispatcherWith()
        runThrough(dispatcher, invocation("compact", raw = "keep the decisions"))
        assertEquals(listOf("/compact keep the decisions"), recordingHost.forwards)
    }

    @Test
    fun `bare unknown key forwards command name only`() = runTest {
        val dispatcher = dispatcherWith()
        runThrough(dispatcher, invocation("model"))
        assertEquals(listOf("/model"), recordingHost.forwards)
    }

    @Test
    fun `stop command cancels the run and replies`() = runTest {
        val dispatcher = dispatcherWith(StopCommand())
        val result = runThrough(dispatcher, invocation("stop"))
        assertTrue(result is SlashCommandResult.Cancelled)
        assertEquals(1, recordingHost.cancels)
        assertEquals(listOf("Stopped."), recordingHost.replies)
    }

    @Test
    fun `clear command starts a new conversation`() = runTest {
        val dispatcher = dispatcherWith(ClearCommand())
        val result = runThrough(dispatcher, invocation("clear"))
        assertTrue(result is SlashCommandResult.NewConversation)
        assertEquals(1, recordingHost.newConversation)
    }

    @Test
    fun `skills key routes through open skills result`() = runTest {
        val dispatcher = dispatcherWith()
        val result = runThrough(dispatcher, invocation("skills"))
        assertTrue(result is SlashCommandResult.OpenSkills)
        assertEquals(1, recordingHost.skillsOpened)
    }

    @Test
    fun `permissions key routes through open permission gate result`() = runTest {
        val dispatcher = dispatcherWith()
        val result = runThrough(dispatcher, invocation("permissions"))
        assertTrue(result is SlashCommandResult.OpenPermissionGate)
        assertEquals(1, recordingHost.permissionGates)
    }

    @Test
    fun `status command reads the session snapshot`() = runTest {
        val dispatcher = dispatcherWith(StatusCommand())
        runThrough(dispatcher, invocation("status"))
        val reply = recordingHost.replies.single()
        assertTrue(reply.contains("conv-123"))
        assertTrue(reply.contains("Messages: 7"))
        assertTrue(reply.contains("Idle"))
    }

    @Test
    fun `usage command renders bar and token count`() = runTest {
        val dispatcher = dispatcherWith(UsageCommand())
        runThrough(dispatcher, invocation("usage"))
        val reply = recordingHost.replies.single()
        assertTrue(reply.contains("1234 tokens"))
        assertTrue(reply.contains("[#"))
    }

    @Test
    fun `tasks command renders detail list when tasks exist`() = runTest {
        val tasksSession = object : SlashCommandSession {
            override val conversationId: String? = "conv"
            override val messageCount = 3
            override val isBusy = false
            override val providerId: String? = null
            override val tokenEstimate = 0
            override val recentToolCalls: List<ToolCallFact> = emptyList()
            override val conversationTasks = listOf(
                ConversationTaskSummary("1", "Write the thing", false),
                ConversationTaskSummary("2", "Ship it", false),
            )
        }
        val dispatcher = dispatcherWith(TasksCommand())
        val result = runThrough(dispatcher, invocation("tasks"), tasksSession)
        assertTrue(result is SlashCommandResult.DetailList)
        val (title, items) = recordingHost.details.single()
        assertEquals("Open tasks in this conversation", title)
        assertEquals(listOf("Write the thing", "Ship it"), items)
    }

    @Test
    fun `tasks command says so when empty`() = runTest {
        val dispatcher = dispatcherWith(TasksCommand())
        runThrough(dispatcher, invocation("tasks"))
        assertTrue(recordingHost.replies.single().contains("No open tasks"))
    }

    @Test
    fun `tools command counts distinct tools`() = runTest {
        val toolsSession = object : SlashCommandSession {
            override val conversationId: String? = null
            override val messageCount = 0
            override val isBusy = false
            override val providerId: String? = null
            override val tokenEstimate = 0
            override val recentToolCalls = listOf(
                ToolCallFact("readFile", 1, false),
                ToolCallFact("readFile", 2, false),
                ToolCallFact("searchWeb", 3, true),
            )
            override val conversationTasks: List<ConversationTaskSummary> = emptyList()
        }
        val dispatcher = dispatcherWith(ToolsCommand())
        runThrough(dispatcher, invocation("tools"), toolsSession)
        val (title, items) = recordingHost.details.single()
        assertEquals("Runtime tools used in this conversation", title)
        assertEquals(2, items.size)
        assertTrue(items.any { it.contains("readFile") })
        assertTrue(items.any { it.contains("searchWeb") })
    }

    @Test
    fun `action exception degrades to failure reply not a crash`() = runTest {
        val exploding = object : SlashCommandAction {
            override val key = "explode"
            override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
                throw IllegalStateException("boom")
            }
        }
        val dispatcher = dispatcherWith(exploding)
        runThrough(dispatcher, invocation("explode"))
        assertTrue(recordingHost.replies.single().contains("boom"))
    }
}