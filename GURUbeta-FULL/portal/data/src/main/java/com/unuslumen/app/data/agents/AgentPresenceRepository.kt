// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.domain.agents.AgentPresenceSource
import com.unuslumen.app.domain.agents.LivePresence
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/**
 * What the Portal's glow reads.
 *
 * READ FROM THE RUN ROWS, NOT FROM THE HOST. `AgentRunHost.activeRuns` knows what this process is
 * executing, which is a narrower question: a run that went PAUSED released its job deliberately, and
 * one that was INTERRUPTED has no job at all, and both still belong in front of the person. The rows
 * carry the materialised status, so this asks the same question the room does and cannot disagree
 * with it.
 *
 * THE COLOUR COMES FROM THE AGENT, NOT FROM THE LIST. An agent's colour is part of its stored
 * identity, so the glow is the same colour every time that agent runs rather than the colour of
 * whichever row happened to be first.
 *
 * A FAILURE TO READ DRAWS NOTHING. A glow is ambient presence, not surveillance of the database; a
 * read that fails while the app is starting should leave the Portal as it was, not crash it or put
 * an error in the person's conversation.
 */
@Single(binds = [AgentPresenceSource::class])
class AgentPresenceRepository(
    private val runDao: AgentRunDao,
    private val agents: GuruAgentRepository,
    /**
     * The unread state, so the Portal's dot is answered from rows rather than from the glow.
     *
     * REQUIRED, NOT NULLABLE. A dot that silently stops working because nobody wired it is the
     * defect the dot exists to report, one level up.
     */
    private val unread: AgentUnreadStore,
) : AgentPresenceSource {

    override fun livePresence(): Flow<List<LivePresence>> =
        combine(
            runDao.observeActiveRuns(),
            agents.getAllAgentsFlow(),
        ) { runs, all ->
            val byId = all.associateBy { it.id }
            val mapped = runs.mapNotNull { run ->
                val agent = byId[run.agentId] ?: return@mapNotNull null
                run.toPresence(agent.visual.colourIndex)
            }
            /*
             * THE UNREAD FLAG IS RESOLVED PER EMISSION, HERE AND NOT IN THE SCREEN.
             *
             * It has to be part of this flow rather than a second subscription on the Portal, because
             * a dot and a glow that are read separately can disagree for a frame: the glow clears on
             * completion while the dot keeps saying there is something to read, which is the whole
             * point of the dot. One emission carries both.
             *
             * A READ FAILURE MEANS NO DOT, NOT A CRASH. A glow is ambient; failing the Portal because
             * a SharedPreferences read failed would be trading a missing dot for a broken screen.
             */
            mapped.map { node ->
                node.copy(hasUnread = runCatching { unread.hasUnread(node.conversationId) }
                    .getOrDefault(false))
            }
        }

    /** One run as the glow needs it. Kept here so the mapping is in one place. */
    private fun AgentRunEntity.toPresence(colourIndex: Int) = LivePresence(
        runId = runId,
        agentId = agentId,
        agentName = agentName,
        colourIndex = colourIndex,
        status = status,
        conversationId = conversationId,
    )
}
