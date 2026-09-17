package com.unuslumen.app.presentation.components

import com.unuslumen.app.domain.model.AiMessage

/**
 * Wrapper for chat items that can either be a single message or a group of consecutive tool calls.
 * Computed in PortalScreen via derivedStateOf from the raw messages list.
 */
sealed interface ChatItem {
    data class Single(val message: AiMessage) : ChatItem
    data class ToolCallGroup(val toolCalls: List<AiMessage.ToolCall>) : ChatItem
}

/**
 * Group consecutive AiMessage.ToolCall entries into ToolCallGroup items.
 * Any run of 2+ consecutive tool calls gets grouped. Everything else stays as Single.
 */
fun buildChatItems(messages: List<AiMessage>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()

    val result = mutableListOf<ChatItem>()
    var i = 0

    while (i < messages.size) {
        val current = messages[i]
        if (current is AiMessage.ToolCall) {
            // Collect consecutive tool calls
            val toolCallRun = mutableListOf<AiMessage.ToolCall>()
            while (i < messages.size && messages[i] is AiMessage.ToolCall) {
                toolCallRun.add(messages[i] as AiMessage.ToolCall)
                i++
            }
            if (toolCallRun.size >= 2) {
                result.add(ChatItem.ToolCallGroup(toolCallRun))
            } else {
                // Single tool call stays standalone
                result.add(ChatItem.Single(toolCallRun.first()))
            }
        } else {
            result.add(ChatItem.Single(current))
            i++
        }
    }

    return result
}
