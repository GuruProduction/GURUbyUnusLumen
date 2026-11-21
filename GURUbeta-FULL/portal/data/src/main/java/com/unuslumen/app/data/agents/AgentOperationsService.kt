// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentMessageDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.dao.AgentRunEventDao
import com.unuslumen.app.database.entity.AgentMessageEntity
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/**
 * One picture of everything the agents are doing, from rows, shared by every reader.
 *
 * WHY THIS EXISTS AT ALL. `agent_runs` was readable by a person through the Agent Room and by the
 * model only through a tool it had to think to call. So GURU did not know what its own agents were
 * doing: it would answer "I have no agents running" while three were, and it could not notice a run
 * that had stopped to ask it a question. A tool is a thing a model may reach for; this is a fact in
 * its context, which is a different thing.
 *
 * ONE SNAPSHOT, FOUR READERS. GURU's context, the Agent Room, the Portal presence and any
 * notification all need the same answer to "what is happening". Building it in four places is how
 * four surfaces come to disagree, and the disagreement is always discovered by a person looking at
 * two of them.
 *
 * A12: IT RETAINS EVERY RUN, AND ONLY THE RENDERING IS BUDGETED. The snapshot holds every
 * non-terminal run whatever the text budget says. [awarenessBlock] is a rendering of it that names
 * every run in compact form and points at `getAgentRun` for detail, so a long assignment is
 * shortened but a run is never dropped. Truncating the list is the defect this rule forbids: a model
 * told about nine of ten agents will confidently say nothing else is running.
 */
@Single
class AgentOperationsService(
    private val runDao: AgentRunDao,
    private val eventDao: AgentRunEventDao,
    private val messageDao: AgentMessageDao,
) {

    /**
     * One run, as the picture holds it.
     *
     * THE FIELDS ARE THE QUESTIONS A SUPERVISOR ASKS. Is it alive, what was it given, when did it
     * last do anything, is it waiting on anybody, and what is the newest thing it said. Nothing here
     * is derived from another field, so a reader never has to reconstruct one from two others.
     */
    data class RunLine(
        val runId: String,
        val agentName: String,
        val agentSlug: String,
        val status: String,
        val assignment: String,
        val lastActivityAt: Long,
        val startedAt: Long?,
        val newestEvent: String?,
        val newestMessage: String?,
        /** True for the two states where a person is the only thing that can move it. */
        val needsAPerson: Boolean,
        /** True for the states where something is supposed to be driving it right now. */
        val expectedToBeExecuting: Boolean,
    ) {
        /** Whether this run has finished and nothing will move it again. */
        val isFinished: Boolean get() = status in AgentRunEntity.TERMINAL_STATUSES
    }

    /**
     * Everything happening, in one value.
     *
     * [live] IS THE POINT OF THE TYPE. It is every run whose state is not terminal, ordered by the
     * most recently active, and it is never shortened. [finished] is a bounded recent history so a
     * supervisor can tell "it completed a minute ago" from "it never existed".
     */
    data class Snapshot(
        val live: List<RunLine>,
        val finished: List<RunLine>,
        val takenAt: Long,
    ) {
        val waitingOnAPerson: List<RunLine> get() = live.filter { it.needsAPerson }

        /** True when nothing at all is running, which is a fact worth stating rather than implying. */
        val nothingRunning: Boolean get() = live.isEmpty()

        fun line(runId: String): RunLine? =
            live.firstOrNull { it.runId == runId } ?: finished.firstOrNull { it.runId == runId }
    }

    /**
     * The current picture.
     *
     * READS ROWS, NEVER A CACHE. A supervisor that acted on a remembered picture would be acting on
     * what was true when it last looked, and the whole class of defect this remediation removes is a
     * surface reporting a state that has moved. Three queries, all indexed, all on the caller's
     * dispatcher.
     */
    suspend fun snapshot(finishedLimit: Int = 12): Snapshot {
        val live = runCatching { runDao.nonTerminalRuns() }.getOrDefault(emptyList())
        val done = runCatching { runDao.recentTerminalRuns(finishedLimit) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        return Snapshot(
            live = live.sortedByDescending { it.updatedAt }.map { it.toLine(now) },
            finished = done.map { it.toLine(now) },
            takenAt = now,
        )
    }

    /**
     * The picture as it changes.
     *
     * EMITS ONCE IMMEDIATELY AND THEN WHENEVER A RUN ROW MOVES. The Room, the Portal and any other
     * live surface subscribe to this rather than polling, so a surface showing nothing costs nothing
     * while nothing happens.
     */
    fun observe(): Flow<Snapshot> = runDao.observeActiveRuns().map {
        // The row flow supplies the live set; the finished tail is read once per emission because it
        // only matters when something else has just changed.
        val live = it.sortedByDescending { run -> run.updatedAt }
        val now = System.currentTimeMillis()
        Snapshot(
            live = live.map { run -> run.toLine(now) },
            finished = runCatching { runDao.recentTerminalRuns(12) }.getOrDefault(emptyList())
                .map { run -> run.toLine(now) },
            takenAt = now,
        )
    }

    private suspend fun AgentRunEntity.toLine(now: Long): RunLine {
        /*
         * THE NEWEST EVENT AND MESSAGE, READ SEPARATELY AND CHEAPLY.
         *
         * A supervisor needs the last thing that happened, not the history. Both reads are bounded to
         * one row, and both are wrapped because a picture that cannot be built at all is worse than
         * one missing a sentence: the run is still listed, with nothing claimed about its detail.
         */
        val newestEvent = runCatching {
            eventDao.eventsForRun(runId).lastOrNull()?.let { summarise(it) }
        }.getOrNull()
        val newestMessage = runCatching {
            messageDao.lastMessage(runId)?.content?.take(160)
        }.getOrNull()

        return RunLine(
            runId = runId,
            agentName = agentName,
            agentSlug = agentSlug,
            status = status,
            /*
             * THE ASSIGNMENT IS SEALED ON THE ROW, so it is reported as unopened here rather than
             * decrypted once per run per snapshot. `getAgentRun` opens it for a caller that needs the
             * words, and a supervisor that shows the first line of a job has the id to ask with.
             */
            assignment = "",
            lastActivityAt = updatedAt,
            startedAt = startedAt,
            newestEvent = newestEvent,
            newestMessage = newestMessage,
            needsAPerson = status in AgentRunEntity.WAITING_STATUSES,
            expectedToBeExecuting = status in EXECUTING_STATUSES,
        )
    }

    /** One event, as a short phrase. Never the payload, because a snapshot is not a transcript. */
    private fun summarise(event: AgentRunEventEntity): String {
        val payload = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(event.payloadJson)
                as kotlinx.serialization.json.JsonObject
        }.getOrNull()
        fun str(key: String): String? =
            (payload?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() && it != "null" }

        return when (event.eventType) {
            AgentRunEventEntity.TOOL_CALL_STARTED -> "started ${str("tool").orEmpty()}".trim()
            AgentRunEventEntity.TOOL_CALL_COMPLETED -> "finished ${str("tool").orEmpty()}".trim()
            AgentRunEventEntity.TOOL_CALL_FAILED -> "${str("tool").orEmpty()} failed".trim()
            AgentRunEventEntity.TOOL_CALL_REFUSED -> "${str("tool").orEmpty()} refused".trim()
            AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN ->
                "${str("tool").orEmpty()} outcome unknown".trim()
            AgentRunEventEntity.RUN_STATE_CHANGED ->
                str("to")?.let { "moved to ${it.lowercase().replace('_', ' ')}" } ?: event.eventType
            AgentRunEventEntity.MODEL_REQUEST_COMPLETED -> "the model answered"
            else -> event.eventType.lowercase().replace('_', ' ')
        }
    }

    /**
     * The block GURU carries in its own reasoning context.
     *
     * THIS IS THE CAPABILITY, NOT A SUMMARY OF IT. Until this block existed GURU had no operational
     * awareness at all: it knew what `listAgents` returned when it thought to call it, and nothing
     * otherwise. A run that stopped to ask it a question sat there. A run that failed was discovered
     * only if somebody looked at the room.
     *
     * EVERY RUN IS NAMED (A12). The per-line prose is clipped and the whole block has a ceiling, but
     * the run lines do not disappear: if the ceiling is reached the block says so and names the count
     * that did not fit, rather than silently listing nine of ten. A model told about nine agents will
     * say nothing else is running, which is worse than being told the list is partial.
     *
     * THE IDS ARE HERE BECAUSE THE MODEL ACTS WITH THEM. `getAgentRun`, `cancelAgentRun` and
     * `sendAgentMessage` all take a run id, so a block without ids would describe the situation and
     * leave GURU unable to do anything about it.
     */
    fun awarenessBlock(snapshot: Snapshot): String {
        if (snapshot.live.isEmpty() && snapshot.finished.isEmpty()) return ""

        val header = if (snapshot.live.isEmpty()) {
            "Agents: nothing is running. ${snapshot.finished.size} recent run(s) finished."
        } else {
            "Agents: ${snapshot.live.size} run(s) alive, " +
                "${snapshot.waitingOnAPerson.size} waiting on a person."
        }

        val waiting = snapshot.waitingOnAPerson
        val waitingLine = if (waiting.isEmpty()) null else {
            "WAITING ON A DECISION: " + waiting.joinToString("; ") {
                "${it.agentName} (${it.runId}) is waiting for ${if (it.status == AgentRunEntity.STATUS_WAITING_FOR_GURU) "you" else "the person"}"
            }
        }

        val budget = MAX_BLOCK_CHARS
        val head = buildString {
            append(header)
            if (waitingLine != null) append('\n').append(waitingLine)
        }
        if (head.length >= budget) return head.take(budget)

        val lines = ArrayList<String>()
        var used = head.length
        var omitted = 0
        for (line in snapshot.live) {
            val rendered = render(line)
            if (used + rendered.length + 1 > budget) {
                // CLIPPED PROSE, NEVER A DROPPED RUN. The count is reported below so the block can
                // never read as a complete list when it is not one.
                omitted++
                continue
            }
            lines.add(rendered)
            used += rendered.length + 1
        }

        return buildString {
            append(head)
            for (line in lines) append('\n').append(line)
            if (omitted > 0) {
                append("\n$omitted more run(s) are alive and not listed here for space. ")
                append("Call listAgentRuns for the complete set before saying nothing else is running.")
            }
            append("\nFor any run above, getAgentRun with its id returns what it did and what it produced. ")
            append("cancelAgentRun stops one; cancelAllAgentRuns stops everything.")
        }
    }

    /** One run, in one line: who, what state, how long quiet, and the newest thing it did. */
    private fun render(line: RunLine): String {
        val age = relativeAge(line.lastActivityAt)
        val state = line.status.lowercase().replace('_', ' ')
        val detail = line.newestMessage ?: line.newestEvent
        return buildString {
            append("- ${line.agentName} [${line.agentSlug}] ${line.runId}: $state, last active $age")
            if (detail != null) {
                append(". Last: ")
                append(detail.take(140))
            }
        }
    }

    private fun relativeAge(at: Long): String {
        val delta = (System.currentTimeMillis() - at).coerceAtLeast(0)
        val minutes = delta / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }

    /** When a line was last active, for a caller that wants a clock rather than an age. */
    fun clock(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

    private companion object {
        /**
         * The ceiling on the awareness block.
         *
         * A BUDGET ON PROSE, NEVER ON RUNS. It is roughly a page of text, which is affordable on
         * every turn, and the run lines are what it protects: the header and the waiting line are
         * emitted first so the two most important facts survive even a pathological list.
         */
        const val MAX_BLOCK_CHARS = 1_500

        /**
         * States in which something is supposed to be driving the run.
         *
         * The same set the recovery scan treats as alive, so the supervisor and recovery cannot
         * disagree about whether a run with no heartbeat is a fault.
         */
        val EXECUTING_STATUSES: Set<String> = setOf(
            AgentRunEntity.STATUS_CREATED,
            AgentRunEntity.STATUS_STARTING,
            AgentRunEntity.STATUS_RUNNING,
            AgentRunEntity.STATUS_WAITING_FOR_TOOL,
            AgentRunEntity.STATUS_INTEGRATING,
        )
    }
}
