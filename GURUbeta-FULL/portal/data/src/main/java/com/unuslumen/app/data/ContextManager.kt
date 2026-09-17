package com.unuslumen.app.data

import com.unuslumen.app.domain.model.AiMessage
import android.util.Log

/**
 * Context manager for GURU — inspired by Lux's multi-layer context management.
 *
 * Layer 1: Microcompact — runs before every API call. Strips content from old
 *   tool call results, keeping only the most recent N tool results with full content.
 *   This prevents payload bloat from large tool results (file reads, shell output, etc.)
 *   that would otherwise accumulate in the message history and cause ANRs.
 *
 * Layer 2: Auto-compact — estimates token count on remaining messages. When it
 *   exceeds the threshold (900K tokens for a 1M context window), triggers a full
 *   conversation summary by sending a compact prompt to the LLM and replacing
 *   all messages with the summary.
 *
 * The ANR fix is Layer 1. The context limit fix is Layer 2.
 */
object ContextManager {

    private const val TAG = "guru"

    // Layer 1: Microcompact settings
    // How many recent tool call results to keep with full content.
    // Older tool results get their resultRawContent replaced with a stub.
    // Older results are retrievable via the tool_results table using getToolResult or searchToolResults tools.
    private const val KEEP_RECENT_TOOL_RESULTS = 30

    // Maximum size of a tool result before it gets truncated even if it's recent.
    // 50KB is ~12.5K tokens — enough for most useful tool output.
    private const val MAX_TOOL_RESULT_CHARS = 50_000

    // The stub that replaces old tool result content
    private const val CLEARED_TOOL_RESULT = "[Old tool result content cleared]"

    // Layer 2: Auto-compact settings
    // For Lux-v1 with 1M context: threshold = 1,000,000 - 100,000 buffer = 900,000
    // roughTokenCountEstimation uses chars / 4, so 900K tokens ≈ 3.6M chars
    private const val AUTOCOMPACT_THRESHOLD_TOKENS = 900_000
    private const val AUTOCOMPACT_BUFFER_TOKENS = 100_000
    private const val CONTEXT_WINDOW_TOKENS = 1_000_000
    private const val RESERVED_OUTPUT_TOKENS = 32_000

    // Rough token estimation: ~4 chars per token (matches Lux's roughTokenCountEstimation)
    private const val CHARS_PER_TOKEN = 4

    /**
     * Layer 1: Microcompact.
     *
     * Walks the message list and replaces old tool call results with a stub.
     * Keeps the most recent KEEP_RECENT_TOOL_RESULTS tool calls with full content.
     * Also truncates any tool result that exceeds MAX_TOOL_RESULT_CHARS, even if recent.
     *
     * This does NOT modify the original message list — it returns a new list.
     * The original messages are preserved in the database for full history.
     * Only the version sent to the LLM is compacted.
     *
     * Called before buildChatPrompt on every API request.
     */
    fun microcompact(messages: List<AiMessage>): List<AiMessage> {
        // Find all ToolCall messages and their indices
        val toolCallIndices = messages.mapIndexedNotNull { index, msg ->
            if (msg is AiMessage.ToolCall) index to msg else null
        }

        if (toolCallIndices.size <= KEEP_RECENT_TOOL_RESULTS) {
            // Not enough tool calls to bother compacting, but still check for oversized results
            return truncateOversizedToolResults(messages)
        }

        // Indices of tool calls to clear (all except the most recent KEEP_RECENT_TOOL_RESULTS)
        val indicesToClear = toolCallIndices
            .dropLast(KEEP_RECENT_TOOL_RESULTS)
            .map { it.first }
            .toSet()

        var tokensSaved = 0
        val result = messages.mapIndexed { index, message ->
            if (message is AiMessage.ToolCall && index in indicesToClear) {
                val originalSize = message.resultRawContent.length
                if (originalSize > CLEARED_TOOL_RESULT.length) {
                    tokensSaved += originalSize / CHARS_PER_TOKEN
                }
                message.copy(resultRawContent = CLEARED_TOOL_RESULT)
            } else {
                message
            }
        }

        // Also truncate oversized recent tool results
        val finalResult = truncateOversizedToolResults(result)

        if (tokensSaved > 0) {
            Log.d(TAG, "Microcompact: cleared ${indicesToClear.size} old tool results, saved ~${tokensSaved} tokens")
        }

        return finalResult
    }

    /**
     * Truncate any tool result that exceeds MAX_TOOL_RESULT_CHARS.
     * Keeps the first and last portions of the result so the LLM has context.
     */
    private fun truncateOversizedToolResults(messages: List<AiMessage>): List<AiMessage> {
        return messages.map { message ->
            if (message is AiMessage.ToolCall && message.resultRawContent.length > MAX_TOOL_RESULT_CHARS) {
                val truncated = message.resultRawContent.take(MAX_TOOL_RESULT_CHARS / 2) +
                    "\n\n[... content truncated, ${message.resultRawContent.length - MAX_TOOL_RESULT_CHARS} chars omitted ...]\n\n" +
                    message.resultRawContent.takeLast(MAX_TOOL_RESULT_CHARS / 2)
                message.copy(resultRawContent = truncated)
            } else {
                message
            }
        }
    }

    /**
     * Rough token count estimation for a message list.
     * Matches Lux's roughTokenCountEstimation: chars / 4.
     * Pads by 4/3 to be conservative.
     */
    fun estimateTokenCount(messages: List<AiMessage>): Int {
        var totalChars = 0
        for (message in messages) {
            when (message) {
                is AiMessage.UserMessage -> totalChars += message.content.length + message.attachmentsText.length
                is AiMessage.AssistantMessage -> totalChars += message.content.length + message.thinkingTokens.length
                is AiMessage.ToolCall -> totalChars += message.rawContent.length + message.resultRawContent.length
                is AiMessage.StreamingAssistant -> totalChars += message.partialContent.length
                is AiMessage.StreamingToolCall -> { /* not sent to LLM */ }
                is AiMessage.PortalMessage -> { /* not sent to LLM */ }
            }
        }
        // Pad by 4/3 to be conservative (matches Lux)
        return (totalChars / CHARS_PER_TOKEN) * 4 / 3
    }

    /**
     * Layer 2: Check if auto-compact is needed.
     *
     * Returns true if the estimated token count exceeds the auto-compact threshold.
     * Threshold = context window (1M) - reserved output (32K) - buffer (100K) = 868K tokens.
     */
    fun shouldAutoCompact(messages: List<AiMessage>): Boolean {
        val tokenCount = estimateTokenCount(messages)
        val threshold = CONTEXT_WINDOW_TOKENS - RESERVED_OUTPUT_TOKENS - AUTOCOMPACT_BUFFER_TOKENS
        Log.d(TAG, "AutoCompact check: tokens=$tokenCount threshold=$threshold contextWindow=$CONTEXT_WINDOW_TOKENS")
        return tokenCount > threshold
    }

    /**
     * Build the compact prompt for summarization.
     * REMOVED: hardcoded prompt text deleted per build doctrine — GURU carries
     * NO prompts in code. The summarisation prompt text now arrives from the
     * server inside the metadata config (master_metadata_config table) and is
     * passed in by the caller. See MetadataCompactionConfig.summaryPrompt.
     */

    /**
     * Layer 2: auto-compact. Compacts the older portion of the conversation
     * into a summary using the SERVER-DELIVERED summary prompt (never a
     * hardcoded one), and produces retrieval-pointer text from the
     * SERVER-DELIVERED pointer template. All prompt words live server-side so
     * Steven can edit them from superadmin without an APK ship.
     *
     * DB-first ordering: messages are persisted to the device database on
     * every exit path by memoryRepository.persistAiMessages BEFORE anything is
     * cleared from the LLM view. The compaction summary carries the exact
     * conversation id and the epoch-millisecond timestamps of the compacted
     * span so any compacted content can be retrieved later.
     *
     * Returns null if compaction could not run (no executor, no model, no
     * history worth compacting) — caller proceeds uncompact.
     */
    suspend fun performAutoCompact(
        messages: List<com.unuslumen.app.domain.model.AiMessage>,
        summaryPrompt: String,
        keepRecent: Int,
        executorProvider: () -> ai.koog.prompt.executor.llms.SingleLLMPromptExecutor?,
        modelProvider: () -> ai.koog.prompt.llm.LLModel?,
        persistDbFirst: () -> Unit,
        metadataConfig: com.unuslumen.app.data.metadata.MetadataConfig,
        conversationId: String
    ): AutoCompactResult? {
        val executor = executorProvider() ?: return null
        val model = modelProvider() ?: return null

        // Nothing to compact if we're at or below the keep-recent line
        if (messages.size <= keepRecent) return null

        val splitIndex = messages.size - keepRecent
        val older = messages.subList(0, splitIndex)
        val olderJson = buildString {
            append("[")
            older.forEachIndexed { i, m ->
                if (i > 0) append(",")
                append(serializeForSummary(m))
            }
            append("]")
        }

        // First and last timestamps of the compacted span — the retrieval anchors
        val fromTs = firstTimestampOf(older) ?: return null
        val toTs = lastTimestampOf(older) ?: return null

        persistDbFirst()

        // Run the server's summary prompt against the older messages.
        // NOTE: this fires with NO tools and its own fresh prompt so the
        // summariser sees only what it must.
        val summaryText = try {
            val client = koogExecutorToClient(executor)
                ?: return null
            val textBuilder = StringBuilder()
            val flow = client.executeStreaming(
                prompt = ai.koog.prompt.dsl.prompt("compaction", ai.koog.prompt.params.LLMParams()) {
                    system(summaryPrompt)
                    user(olderJson)
                },
                model = model,
                tools = emptyList()
            )
            flow.collect { frame ->
                when (frame) {
                    is ai.koog.prompt.streaming.StreamFrame.TextDelta -> textBuilder.append(frame.text)
                    is ai.koog.prompt.streaming.StreamFrame.TextComplete -> {
                        textBuilder.clear()
                        textBuilder.append(frame.text)
                    }
                    else -> {}
                }
            }
            textBuilder.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-compact summary call failed: ${e.message}")
            return null
        }

        if (summaryText.isBlank()) {
            Log.w(TAG, "Auto-compact produced empty summary — skipping")
            return null
        }

        val kept = messages.subList(splitIndex, messages.size)

        // The pointer block: server's template with our anchors filled in
        val pointerText = metadataConfig.compaction.pointerPrompt
            .replace("{CONVERSATION_ID}", conversationId)
            .replace("{FROM_TS}", fromTs.toString())
            .replace("{TO_TS}", toTs.toString())
            .replace("{SUMMARY}", summaryText.trim())

        val pointerMessage = com.unuslumen.app.domain.model.AiMessage.UserMessage(
            uuid = java.util.UUID.randomUUID().toString(),
            content = pointerText,
            time = System.currentTimeMillis(),
            attachmentsText = ""
        )

        return AutoCompactResult(
            messages = listOf(pointerMessage) + kept,
            pointerText = pointerText
        )
    }

    /**
     * Pull the underlying LLMClient out of the SingleLLMPromptExecutor so
     * compaction can run a plain two-message stream with an empty tool list.
     * Returns null when extraction fails, and callers proceed uncompact.
     */
    private fun koogExecutorToClient(executor: ai.koog.prompt.executor.llms.SingleLLMPromptExecutor): ai.koog.prompt.executor.clients.LLMClient? {
        return try {
            val field = ai.koog.prompt.executor.llms.SingleLLMPromptExecutor::class.java.declaredFields
                .firstOrNull { it.name.contains("client", ignoreCase = true) || it.name.contains("Client", ignoreCase = true) || it.name == "llmClient" }
                ?: return null
            field.isAccessible = true
            field.get(executor) as? ai.koog.prompt.executor.clients.LLMClient
        } catch (e: Exception) {
            null
        }
    }

    private fun serializeForSummary(message: com.unuslumen.app.domain.model.AiMessage): String {
        val role = when (message) {
            is com.unuslumen.app.domain.model.AiMessage.UserMessage -> "user"
            is com.unuslumen.app.domain.model.AiMessage.AssistantMessage -> "assistant"
            is com.unuslumen.app.domain.model.AiMessage.ToolCall -> "tool"
            is com.unuslumen.app.domain.model.AiMessage.StreamingAssistant -> "assistant"
            is com.unuslumen.app.domain.model.AiMessage.StreamingToolCall -> "tool"
            is com.unuslumen.app.domain.model.AiMessage.PortalMessage -> "system"
        }
        val content = when (message) {
            is com.unuslumen.app.domain.model.AiMessage.UserMessage -> message.content + message.attachmentsText
            is com.unuslumen.app.domain.model.AiMessage.AssistantMessage -> message.content
            is com.unuslumen.app.domain.model.AiMessage.ToolCall -> "tool=${message.name} args=${message.rawContent.take(500)} result=${message.resultRawContent.take(1500)}"
            is com.unuslumen.app.domain.model.AiMessage.StreamingAssistant -> message.partialContent
            is com.unuslumen.app.domain.model.AiMessage.StreamingToolCall -> message.partialContent
            is com.unuslumen.app.domain.model.AiMessage.PortalMessage -> message.html
        }
        val ts = when (message) {
            is com.unuslumen.app.domain.model.AiMessage.UserMessage -> message.time
            is com.unuslumen.app.domain.model.AiMessage.AssistantMessage -> message.time
            is com.unuslumen.app.domain.model.AiMessage.ToolCall -> message.time
            is com.unuslumen.app.domain.model.AiMessage.StreamingAssistant -> message.time
            is com.unuslumen.app.domain.model.AiMessage.StreamingToolCall -> message.time
            is com.unuslumen.app.domain.model.AiMessage.PortalMessage -> 0L
        }
        return """{"role":"$role","time":$ts,"content":${jsonQuote(content.take(4000))}}"""
    }

    private fun jsonQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun firstTimestampOf(messages: List<com.unuslumen.app.domain.model.AiMessage>): Long? =
        messages.mapNotNull { timestampOf(it) }.minOrNull()

    private fun lastTimestampOf(messages: List<com.unuslumen.app.domain.model.AiMessage>): Long? =
        messages.mapNotNull { timestampOf(it) }.maxOrNull()

    private fun timestampOf(m: com.unuslumen.app.domain.model.AiMessage): Long? = when (m) {
        is com.unuslumen.app.domain.model.AiMessage.UserMessage -> m.time
        is com.unuslumen.app.domain.model.AiMessage.AssistantMessage -> m.time
        is com.unuslumen.app.domain.model.AiMessage.ToolCall -> m.time
        else -> null
    }

    data class AutoCompactResult(
        val messages: List<com.unuslumen.app.domain.model.AiMessage>,
        val pointerText: String
    )
}
