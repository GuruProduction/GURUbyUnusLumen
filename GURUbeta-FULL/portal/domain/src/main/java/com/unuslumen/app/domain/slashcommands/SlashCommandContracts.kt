package com.unuslumen.app.domain.slashcommands

/**
 * The session a slash command can observe. Read-only snapshot interface; each
 * chat surface implements it against its own ViewModel state so commands never
 * touch a ViewModel or screen directly.
 */
interface SlashCommandSession {
    /** Active conversation id, null when none. */
    val conversationId: String?
    /** Total messages in the visible conversation. */
    val messageCount: Int
    /** True while the engine is streaming a response. */
    val isBusy: Boolean
    /** Registered provider brand id (null when default). */
    val providerId: String?
    /** Total context-window tokens of the current conversation (rough estimate). */
    val tokenEstimate: Int
    /** Tool calls already executed in this conversation, newest first. */
    val recentToolCalls: List<ToolCallFact>
    /** User-visible task list captured from tool results in this conversation. */
    val conversationTasks: List<ConversationTaskSummary>
}

/** One executed tool call as /tasks and /tools may present it. */
data class ToolCallFact(
    val name: String,
    val timestamp: Long,
    val failed: Boolean,
)

/** One task surfaced by a tool result inside this conversation. */
data class ConversationTaskSummary(
    val id: String,
    val title: String,
    val isCompleted: Boolean,
)

/**
 * Everything a slash command may ask the app to do. Passed to every command;
 * implementations decide what is relevant. One small interface per concern —
 * no god object.
 */
interface SlashCommandHost {
    /** Show [text] in the chat as a local system reply, no engine round trip. */
    fun showSystemReply(text: String)
    /** Show [detail] as a local expandable list block in the chat (task/tool lists). */
    fun showDetailList(title: String, items: List<String>)
    /** Start a fresh conversation, preserving the current one. */
    fun startNewConversation()
    /** Interrupt the in-flight engine run. */
    fun cancelRun()
    /** Report an engine-owned command for delivery as an assistant request. */
    fun forwardToEngine(commandText: String)
    /** Navigate to the Luxify skills screen. */
    fun openSkills()
    /** Show the device permission gate. */
    fun openPermissionGate()
}