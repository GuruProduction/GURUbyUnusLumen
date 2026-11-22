// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class MemoryFactInfo(val id: String, val category: String, val fact: String, val confidence: Float, val extractedDate: Long) : ToolResultData
@Serializable data class MemoryFactsResult(val facts: List<MemoryFactInfo>) : ToolResultData

/**
 * The memory listing, with its totals stated rather than implied.
 *
 * `shown` is how many rows this reply carries; `totalLive` is how many live
 * memories the brain holds; `totalMatching` is how many matched the category
 * filter. They are separate fields so a caller can tell a short page from a
 * short memory, which a bare list cannot express.
 */
@Serializable data class MemoryListingResult(
    val facts: List<MemoryFactInfo>,
    val shown: Int,
    val totalLive: Int,
    val totalMatching: Int,
) : ToolResultData
@Serializable data class MemoryFactResult(val fact: MemoryFactInfo) : ToolResultData
@Serializable data class DeleteMemoryFactResult(val deletedFactId: String, val deletedFact: String) : ToolResultData

/**
 * One revision of a memory, as a tool caller reads it.
 *
 * `superseded` is null on a creation and carries the replaced text on an update, which is
 * the whole reason the trail exists: a person asking what a memory used to say is asking
 * about the previous text, not about the current one.
 */
@Serializable data class MemoryRevisionInfo(
    val kind: String,
    val content: String,
    val superseded: String? = null,
    val reason: String = "",
    val actor: String = "",
    val recordedAt: String = "",
) : ToolResultData

/**
 * A memory's trail, with the fields a caller needs to interpret it.
 *
 * `everRetired` is separate from `lifecycle` because a memory that was deleted and then
 * restored reads as Active, and the fact that it was ever retired is the part a person
 * cannot recover from the current row.
 */
@Serializable data class MemoryHistoryResult(
    val memoryId: String,
    val lifecycle: String,
    val category: String,
    val everRetired: Boolean,
    val revisionCount: Int,
    val revisions: List<MemoryRevisionInfo>,
) : ToolResultData
@Serializable data class ConversationInfo(val id: String, val title: String, val createdDate: Long, val updatedDate: Long, val messageCount: Int) : ToolResultData
@Serializable data class SearchConversationsResult(val conversations: List<ConversationInfo>) : ToolResultData
@Serializable data class ConversationThreadInfo(val id: String, val title: String, val conversationIds: List<String>) : ToolResultData
@Serializable data class ConversationThreadResult(val thread: ConversationThreadInfo) : ToolResultData
@Serializable data class MessageSearchHit(val id: String, val conversationId: String, val role: String, val content: String, val timestamp: Long) : ToolResultData
@Serializable data class ToolResultHit(val id: String, val toolName: String, val resultText: String, val timestamp: Long, val conversationId: String) : ToolResultData
@Serializable data class UnifiedSearchResult(val facts: List<MemoryFactInfo>, val messages: List<MessageSearchHit>, val toolResults: List<ToolResultHit>) : ToolResultData
@Serializable data class ConversationWithMessagesResult(val conversation: ConversationInfo, val messages: List<MessageSearchHit>) : ToolResultData

/**
 * The graph around a subject: what the walk covered, and the facts it reached.
 *
 * `subject`, `edgeType` and `depth` are echoed because the engine chooses its own
 * start node from the text rather than being handed one. Without them a caller
 * cannot tell which walk it got, and a shallow walk over one edge type would
 * look identical to a deep walk over the whole graph.
 */
@Serializable data class TraverseMemoryGraphResult(
    val subject: String,
    val edgeType: String,
    val depth: Int,
    val connected: List<MemoryFactInfo>,
) : ToolResultData