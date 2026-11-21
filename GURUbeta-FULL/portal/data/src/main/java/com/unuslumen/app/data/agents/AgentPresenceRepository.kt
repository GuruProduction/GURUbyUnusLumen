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
) : AgentPresenceSource {

    override fun livePresence(): Flow<List<LivePresence>> =
        combine(
            runDao.observeActiveRuns(),
            agents.getAllAgentsFlow(),
        ) { runs, all ->
            val byId = all.associateBy { it.id }
            runs.mapNotNull { run ->
                val agent = byId[run.agentId] ?: return@mapNotNull null
                run.toPresence(agent.visual.colourIndex)
            }
        }

    /** One run as the glow needs it. Kept here so the mapping is in one place. */
    private fun AgentRunEntity.toPresence(colourIndex: Int) = LivePresence(
        runId = runId,
        agentId = agentId,
        agentName = agentName,
        colourIndex = colourIndex,
        status = status,
    )
}
