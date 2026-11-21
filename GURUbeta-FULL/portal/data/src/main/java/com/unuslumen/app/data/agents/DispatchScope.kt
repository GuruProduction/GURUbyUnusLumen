// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

/**
 * The conversation a dispatch is happening inside, carried by the coroutine rather than stored.
 *
 * WHY THIS REPLACES A PROPERTY. The first version held the bound conversation in a
 * `@Volatile` field on the runtime, set before a dispatch and read during it. That is a
 * race with a quiet failure mode: two conversations dispatching at once would each set the
 * field, and whichever wrote last decided which conversation the other's agent run
 * belonged to. Nothing would error — the run would simply attach to the wrong conversation,
 * and the person would find an agent's history under a chat they never started it from.
 *
 * A coroutine context element cannot do that. It is attached to one coroutine and its
 * children, so two concurrent dispatches hold two different values and neither can see the
 * other's. There is no shared slot to overwrite because there is no shared slot.
 *
 * A dispatcher that forgets to install it produces `null`, which the spawn path refuses
 * with a sentence naming the cause, rather than silently inventing a conversation.
 */
data class DispatchScope(
    val conversationId: String?,
    val originMessageId: String? = null,
    /**
     * The agent whose run is dispatching this call, when an agent is the caller.
     *
     * CARRIED FOR THE SAME REASON THE CONVERSATION IS, AND WITH A SHARPER CONSEQUENCE. A tool that
     * writes into memory must write into the memory of the agent that called it, and a tool argument
     * naming an agent would let one agent write into another's memory with that other agent's
     * provenance on the fact. The agent is therefore read from the coroutine, where it cannot be
     * named by the model and cannot be raced by a second dispatch.
     *
     * NULL MEANS THE MAIN ASSISTANT IS CALLING, which is not the same as an unknown agent: a memory
     * write from the main chat belongs to GURU's own memory rather than to any agent's.
     */
    val agentName: String? = null,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<DispatchScope>

    /** The conversation this coroutine is dispatching inside, if one was set. */
    fun conversationId(): String? = conversationId

    /** The agent whose run is calling, or null when the main assistant is. */
    fun agentName(): String? = agentName
}

/** The scope this coroutine is running in, or null when none was installed. */
suspend fun currentDispatchScope(): DispatchScope? =
    kotlin.coroutines.coroutineContext[DispatchScope]

/** Run [block] with this conversation attached to the coroutine. */
suspend fun <T> withDispatchScope(
    conversationId: String?,
    originMessageId: String? = null,
    agentName: String? = null,
    block: suspend CoroutineScope.() -> T,
): T = kotlinx.coroutines.withContext(
    DispatchScope(conversationId, originMessageId, agentName),
    block,
)
