// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import kotlinx.coroutines.flow.Flow

/**
 * One run that is alive right now, as the Portal's ambient glow needs it.
 *
 * DELIBERATELY SMALL. The Portal draws a coloured edge and one tap target, so it needs the colour,
 * the agent it belongs to, and the state that decides whether the edge breathes. Everything else —
 * the name, the rounds, the tools, the reasoning — belongs on the dashboard one tap away, and
 * carrying it here would invite a screen to draw it in the thread.
 */
data class LivePresence(
    val runId: String,
    val agentId: String,
    val agentName: String,
    /** Which of the house colours this agent keeps. From its stored visual identity, not a list index. */
    val colourIndex: Int,
    val status: String,
    /**
     * The conversation this run belongs to, so unread state and the room's door are one identity.
     *
     * A RUN CANNOT EXIST WITHOUT ONE, so this is always populated on a row read from disk. It is
     * carried because the unread dot is per CONVERSATION and the glow is per RUN: without this the
     * Portal would have to look the conversation up again, and two lookups are two answers.
     */
    val conversationId: String = "",
    /**
     * Whether anything in that conversation is waiting to be read.
     *
     * THE DOT, AND THE ONLY THING THE PORTAL NEEDS TO KNOW ABOUT UNREAD. A count would be a number to
     * render next to a mark the specification says is a mark; and the mark does not depend on the run
     * being alive, which is why it is a separate flag rather than a status.
     */
    val hasUnread: Boolean = false,
) {
    /** Is work genuinely executing, as opposed to waiting or stopped? */
    val isExecuting: Boolean
        get() = status == AgentRunStatuses.RUNNING || status == AgentRunStatuses.STARTING

    /**
     * The most urgent live run, for a Portal that draws one.
     *
     * ONE, BECAUSE THE RUNTIME RUNS ONE. `AgentDelegationPolicy.MAX_CONCURRENT_RUNS` is 1 today, so
     * a second glow would be drawing work that is not happening. When concurrency is raised this
     * picks the run that most needs the person: one waiting on them beats one waiting on an answer,
     * which beats one that is merely working.
     */
    companion object {
        fun mostUrgent(runs: List<LivePresence>): LivePresence? =
            runs.minByOrNull { urgencyRank(it.status) }

        private fun urgencyRank(status: String): Int = when (status) {
            AgentRunStatuses.WAITING_FOR_USER -> 0
            AgentRunStatuses.WAITING_FOR_GURU -> 1
            AgentRunStatuses.PAUSED -> 2
            AgentRunStatuses.INTERRUPTED -> 2
            AgentRunStatuses.RUNNING -> 3
            AgentRunStatuses.STARTING -> 4
            AgentRunStatuses.CREATED -> 5
            else -> 6
        }
    }
}

/**
 * Where the Portal asks what is alive.
 *
 * AN INTERFACE IN THE DOMAIN SO THE PORTAL CAN SEE IT. The Portal's module depends on the domain and
 * not on the data module, which is what keeps a screen from reaching into a DAO. The implementation
 * lives in data and is bound in Koin, the same shape every repository in this codebase uses.
 *
 * A FLOW RATHER THAN A QUERY, because the glow must appear and disappear as runs start and finish.
 * A one-shot read would leave a glow behind after the work stopped, which is the worst version of
 * this feature: an indicator that says "busy" about nothing.
 */
interface AgentPresenceSource {

    /** Every non-terminal run, as it changes. Empty when nothing is running, which draws nothing. */
    fun livePresence(): Flow<List<LivePresence>>
}
