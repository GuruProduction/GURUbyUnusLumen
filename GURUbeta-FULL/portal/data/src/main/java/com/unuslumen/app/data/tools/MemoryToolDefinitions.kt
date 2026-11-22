// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.domain.memory.MemoryCategories
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
    const val TRAVERSE_MEMORY_GRAPH = "traverseMemoryGraph"

    /**
     * A memory's own revision trail.
     *
     * EVERY OTHER VERB HERE ANSWERS ABOUT A MEMORY'S PRESENT. This one answers about its
     * past: what it said before it was changed, when each change landed, who made it and
     * why, and whether it was ever retired. It works on a deleted memory, which is the case
     * it most needs to, because the record is kept in the brain's append-only event trail
     * rather than in the row.
     */
    const val MEMORY_HISTORY = "memoryHistory"

    /**
     * The full curation surface, under the name an agent recognises (issue 486).
     *
     * The Archivist prompt calls this `cortex.record` and nothing in the repo
     * answered to it. Building it is the honest fix rather than deleting the line,
     * because everything it should do already exists in the brain and was
     * unreachable: `cerebrum_curate` accepts the whole typed entry, and the four
     * memory tools flattened it to a fact, a category and a confidence. This is the
     * second door, for a caller that has decided where a memory belongs and what
     * it connects to.
     */
    const val CORTEX_RECORD = "cortexRecord"

    /**
     * The category list every memory tool carries, in one string.
     *
     * WHY IT IS IN THE SCHEMA (issue 485). The category argument was free text with
     * the description "Category" and nothing else, so a model invented whatever
     * spelling it liked and the category filter could find one spelling at a time.
     * A schema is the only documentation a model has, so the permitted set lives
     * here and is the same set the executor enforces. The names are Steven's, from
     * his own Archivist prompt, not a vocabulary invented for this file.
     */
    private val CATEGORY_HELP: String =
        "One of: " + MemoryCategories.ALL.joinToString(", ")

    override val definitions = listOf(
        ToolDefinition(name = SEARCH_MEMORY_FACTS, description = "Search your memory facts by text. Hits come from your encrypted on-device brain (ranked by semantic similarity, best first). Optional category filter narrows the result set.", category = "memory", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query text"), ToolParameter("category", ToolParameterType.String, false, "Optional category filter. $CATEGORY_HELP")), permissions = emptyList()),
        ToolDefinition(name = ADD_MEMORY_FACT, description = "Add a new fact to your memory. Land it straight in your encrypted brain; the same text updates one stored row instead of duplicating, so a repeat save is a refresh, never a copy.", category = "memory", parameters = listOf(ToolParameter("fact", ToolParameterType.String, true, "The fact to remember"), ToolParameter("category", ToolParameterType.String, true, "Category. $CATEGORY_HELP"), ToolParameter("confidence", ToolParameterType.Float, false, "Confidence 0.0-1.0")), permissions = emptyList()),
        ToolDefinition(name = DELETE_MEMORY_FACT, description = "Delete a fact from your memory, using the real memory id from a searchMemoryFacts hit (a 64-hex id). The brain marks it archived; content stays recoverable in the memory trail.", category = "memory", parameters = listOf(ToolParameter("factId", ToolParameterType.String, true, "Fact ID (real 64-hex id from search hits)")), permissions = emptyList()),
        ToolDefinition(
            name = UPDATE_MEMORY_FACT,
            description = "Change a memory in place: give the real memory id from a search hit plus the new fact content. " +
                "The row KEEPS ITS ID, so every id you already hold still resolves, and the text it replaced is recorded " +
                "in its trail where memoryHistory can read it back. Use this rather than deleting and re-adding, which " +
                "would create a different row and lose the old text.",
            category = "memory",
            parameters = listOf(
                ToolParameter("factId", ToolParameterType.String, true, "Fact ID (real 64-hex id from search hits)"),
                ToolParameter("fact", ToolParameterType.String, false, "New fact content"),
                ToolParameter("category", ToolParameterType.String, false, "New category. $CATEGORY_HELP"),
                ToolParameter("confidence", ToolParameterType.Float, false, "New confidence"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = MEMORY_HISTORY,
            description = "Read a memory's full trail: every version it has had, what each change replaced, when it " +
                "happened, who made it and why, plus whether it was ever retired. THIS IS THE ONLY VERB THAT STILL " +
                "ANSWERS FOR A DELETED MEMORY, so use it when asked what a memory used to say, when something was " +
                "changed, or who removed it.",
            category = "memory",
            parameters = listOf(
                ToolParameter(
                    "factId", ToolParameterType.String, true,
                    "The memory's real 64-hex id, from a search hit, a listing, or the id a delete or update reported",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(name = LIST_MEMORY_FACTS, description = "Show your stored memories, newest first; optionally filtered by category. Your memory is the encrypted brain: everything it holds is real, searchable, and ranked by live frequency of your attention.", category = "memory", parameters = listOf(ToolParameter("category", ToolParameterType.String, false, "Optional category filter. $CATEGORY_HELP")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_CONVERSATIONS, description = "Search your past conversations by title or message content. Hits come from memory.", category = "memory", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = GET_CONVERSATION_THREAD, description = "Get a conversation and all of its exchanges by talking to your memory brain for the exact rows of that chat.", category = "memory", parameters = listOf(ToolParameter("threadId", ToolParameterType.String, true, "Conversation ID")), permissions = emptyList()),
        ToolDefinition(
            name = TRAVERSE_MEMORY_GRAPH,
            description = "Walk the graph of connected memories around a subject instead of searching for words. A search answers what shares words with your query; this answers what is CONNECTED to something. The brain picks the best start node from your subject and follows typed edges out from it: semantic (facts that mean similar things), temporal (what came before or after), causal (what led to what), entity (a person, place or organisation and what they are involved in), or composite (all of them fused). Use it when a name, project or supplier comes up and you want the surrounding picture rather than a keyword match.",
            category = "memory",
            parameters = listOf(
                ToolParameter("subject", ToolParameterType.String, true, "What to walk around, in plain words: a person, project, organisation, place or idea. The brain matches this against the labels and content it holds to choose where to start"),
                ToolParameter("edgeType", ToolParameterType.String, false, "Which relationship to follow: semantic, temporal, causal, entity, or composite for all of them. Default composite"),
                ToolParameter("depth", ToolParameterType.Integer, false, "How many hops out from the start node, 1 to 6. Default 3. A deeper walk reaches further relations and costs more"),
                ToolParameter("maxNodes", ToolParameterType.Integer, false, "Cap on how many memories the walk may return, 1 to 200. Default 40")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(name = SEARCH_ALL_MEMORY, description = "Search your whole brain at once: your stored facts, each exchanged message, and your past tool results all live here. Ranked, one real ranked list of everything you remember. Say what you want remembered about something and this answers in one pass.", category = "memory", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query text")), permissions = emptyList()),
        ToolDefinition(
            name = CORTEX_RECORD,
            description = "Write one memory with its full place in the knowledge graph: where it belongs (domain, topic, subtopic), why it is worth keeping, and which other memories it connects to. Use this instead of addMemoryFact when you have decided WHERE a memory belongs and HOW it relates, rather than just that it should be remembered. addMemoryFact stores a line of prose with a category; this stores a node in the graph. Returns the brain's real 64-hex id for the record, which you can cite later and pass to deleteMemoryFact or updateMemoryFact.",
            category = "memory",
            parameters = listOf(
                ToolParameter("content", ToolParameterType.String, true, "What the memory says, in prose"),
                ToolParameter("domain", ToolParameterType.String, false, "The top-level branch it belongs under, such as the_human, guru, work or home. Default memory"),
                ToolParameter("topic", ToolParameterType.String, false, "The branch inside the domain, such as preferences, projects or tools. Default general"),
                ToolParameter("subtopic", ToolParameterType.String, false, "The leaf inside the topic, such as food, guru_itself or network. Default general"),
                ToolParameter("rationale", ToolParameterType.String, false, "Why this memory is worth keeping, in your own words. Required when revising a belief, because the reason for a change is part of the change"),
                ToolParameter("relatedTo", ToolParameterType.String, false, "Comma-separated 64-hex memory ids this record connects to, taken from search results"),
                ToolParameter("provenance", ToolParameterType.String, false, "Where it came from: conversation, tool_result, web, inference. Default guru"),
                ToolParameter("confidence", ToolParameterType.Float, false, "How far you believe it, 0.0 to 1.0. Default 0.9"),
            ),
            permissions = emptyList(),
        )
    )
    override fun executorClass(): KClass<out ToolExecutor> = MemoryToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}