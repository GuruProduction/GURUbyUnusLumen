// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

data class Conversation(
    val id: String,
    val title: String,
    val createdDate: Long,
    val updatedDate: Long,
    val messageCount: Int = 0
)

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val embedding: List<Float> = emptyList(),
    val toolCalls: String = "",
    val toolResults: String = "",
    /**
     * Tool identity for a "tool" row. Empty when the row predates v29, which
     * means the call cannot be replayed to a model: a tool_call with no
     * function name is rejected by the upstream. Restored rows with an empty
     * name are kept on screen but never sent.
     */
    val toolName: String = "",
    val toolCallId: String = ""
)

data class MemoryFact(
    val id: String,
    val category: String,
    val fact: String,
    val embedding: List<Float> = emptyList(),
    val confidence: Float = 1.0f,
    val sourceConversationIds: List<String> = emptyList(),
    val extractedDate: Long,
    val lastRecalledDate: Long = 0L,
    // Brain fields
    val layer: String = "BUFFER",
    val strength: Float = 1.0f,
    val accessCount: Int = 0,
    val source: String = "",
    val trigger: String = "",
    val beforeContext: String = "",
    val afterContext: String = "",
    val emotionalValence: Float = 0.0f,
    val domain: String = "",
    val topic: String = "",
    val subtopic: String = "",
    val signature: String = "",
    /**
     * The stable id of the agent that wrote this fact, empty when none did.
     *
     * TYPED, FROM THE STORE. Ownership used to exist only as a marker inside [source], which
     * meant every permission decision parsed prose and depended on a convention rather than on
     * data. The brain now holds it as a field and this carries it through.
     */
    val ownerAgentId: String = "",
    /** Shared, Owned or OwnedAndShared, from the store. */
    val visibility: String = ""
)

data class ConversationThread(
    val id: String,
    val title: String,
    val summary: String,
    val conversationIds: List<String> = emptyList(),
    val createdDate: Long
)

data class RetrievedContext(
    val messages: List<ConversationMessage>,
    val facts: List<MemoryFact>,
    val threads: List<ConversationThread>,
    val preamble: String
)

data class ToolResultSearchHit(
    val id: String,
    val toolName: String,
    val resultText: String,
    val timestamp: Long,
    val conversationId: String
)

/**
 * One listing of the memory, with its totals stated rather than implied.
 *
 * [facts] is the page; [totalLive] is every live memory the brain holds and
 * [totalMatching] is every one that matched the category filter. They are
 * separate so a reader can tell a short page from a short memory, and
 * [brainReachable] distinguishes "nothing remembered" from "the brain did not
 * answer", which are different truths about the machine.
 */
data class FactListing(
    val facts: List<MemoryFact>,
    val totalLive: Int,
    val totalMatching: Int,
    val brainReachable: Boolean,
)

/**
 * One revision of a memory, as the brain's event trail recorded it.
 *
 * A MEMORY HAS A HISTORY WHETHER OR NOT ANYTHING SHOWS IT. The engine has always written an
 * append-only event per mutation, including the text an update replaced, and
 * `EventStore::history_for` has always answered from it. Until this existed there was no
 * route from the app, so "what did this say before", "who deleted it" and "why" were stored
 * and unreadable.
 *
 * [superseded] is the text this revision replaced, present only on an update. [actor] is the
 * provenance label the mutation carried, which is where the writer's identity lives. A
 * memory that was deleted still answers, because the trail is the one read that must
 * outlive the row.
 */
data class MemoryRevision(
    /** Created, Updated, Deleted, Archived, Hibernated, Revived or Consolidated. */
    val kind: String,
    /** The memory's text as of this revision. */
    val content: String,
    /** The text this revision replaced, or null when it replaced nothing. */
    val superseded: String?,
    /** Why the change was made, as the writer stated it. Empty when none was given. */
    val reason: String,
    /** The provenance label of whoever made the change. */
    val actor: String,
    /** When the brain recorded it, from the event's own timestamp. */
    val recordedAt: String,
)

/** One memory's full trail, including the identity and whether it was ever retired. */
data class MemoryHistory(
    val memoryId: String,
    /** The row's current lifecycle: Active, Archived or Hibernated. */
    val lifecycle: String,
    /** The category the row was filed under. */
    val category: String,
    /** True when the row was retired at any point, even if it is live again. */
    val everRetired: Boolean,
    /** Every revision, oldest first. */
    val revisions: List<MemoryRevision>,
)
