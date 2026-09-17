package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object MemoryToolDefinitions : ToolSetRegistration {
    const val SEARCH_MEMORY_FACTS = "searchMemoryFacts"
    const val ADD_MEMORY_FACT = "addMemoryFact"
    const val DELETE_MEMORY_FACT = "deleteMemoryFact"
    const val UPDATE_MEMORY_FACT = "updateMemoryFact"
    const val LIST_MEMORY_FACTS = "listMemoryFacts"
    const val SEARCH_CONVERSATIONS = "searchConversations"
    const val GET_CONVERSATION_THREAD = "getConversationThread"
    const val SEARCH_ALL_MEMORY = "searchAllMemory"

    override val definitions = listOf(
        ToolDefinition(name = SEARCH_MEMORY_FACTS, description = "Search your memory facts by text. Returns every fact containing the search term. No limits.", category = "memory", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query text"), ToolParameter("category", ToolParameterType.String, false, "Optional category filter")), permissions = emptyList()),
        ToolDefinition(name = ADD_MEMORY_FACT, description = "Add a new fact to your memory.", category = "memory", parameters = listOf(ToolParameter("fact", ToolParameterType.String, true, "The fact to remember"), ToolParameter("category", ToolParameterType.String, true, "Category"), ToolParameter("confidence", ToolParameterType.Float, false, "Confidence 0.0-1.0")), permissions = emptyList()),
        ToolDefinition(name = DELETE_MEMORY_FACT, description = "Delete a fact from your memory.", category = "memory", parameters = listOf(ToolParameter("factId", ToolParameterType.String, true, "Fact ID")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_MEMORY_FACT, description = "Update an existing fact in your memory.", category = "memory", parameters = listOf(ToolParameter("factId", ToolParameterType.String, true, "Fact ID"), ToolParameter("fact", ToolParameterType.String, false, "New fact content"), ToolParameter("category", ToolParameterType.String, false, "New category"), ToolParameter("confidence", ToolParameterType.Float, false, "New confidence")), permissions = emptyList()),
        ToolDefinition(name = LIST_MEMORY_FACTS, description = "List all facts in your memory, optionally filtered by category.", category = "memory", parameters = listOf(ToolParameter("category", ToolParameterType.String, false, "Optional category filter")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_CONVERSATIONS, description = "Search your past conversations by title or message content.", category = "memory", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = GET_CONVERSATION_THREAD, description = "Get a conversation and all its messages by conversation ID. Use this to read what was said in a past conversation.", category = "memory", parameters = listOf(ToolParameter("threadId", ToolParameterType.String, true, "Conversation ID")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_ALL_MEMORY, description = "Search everything in your memory at once: messages, facts, and tool results. Returns every hit across all data. Use this when you need to find something anywhere in your memory.", category = "memory", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query text")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = MemoryToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}