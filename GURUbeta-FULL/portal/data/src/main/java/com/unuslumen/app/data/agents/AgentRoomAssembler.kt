// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentArtefactDao
import com.unuslumen.app.database.dao.AgentContextReferenceDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.dao.AgentRunEventDao
import com.unuslumen.app.database.entity.AgentMessageEntity
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import com.unuslumen.app.domain.agents.AgentRoomState
import com.unuslumen.app.domain.agents.ApprovalView
import com.unuslumen.app.domain.agents.ArtefactView
import com.unuslumen.app.domain.agents.ContextSourceView
import com.unuslumen.app.domain.agents.DecisionView
import com.unuslumen.app.domain.agents.PlanStepView
import com.unuslumen.app.domain.agents.PlanView
import com.unuslumen.app.domain.agents.MessageView
import com.unuslumen.app.domain.agents.ReasoningView
import com.unuslumen.app.domain.agents.RunSummaryView
import com.unuslumen.app.domain.agents.RunView
import com.unuslumen.app.domain.agents.ToolActivityView
import com.unuslumen.app.domain.agents.VerificationView
import com.unuslumen.app.domain.model.AgentAutonomy
import com.unuslumen.app.domain.model.AgentVisibility
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.core.annotation.Single

/**
 * Builds the Agent Room's state from persisted facts.
 *
 * ONE PLACE THAT READS, SO THERE IS ONE PLACE TO CHECK. A screen that queries four DAOs directly
 * cannot be tested without a device, and it grows a fifth query until the layout and the data logic
 * are the same thing. This assembles a plain value that a JVM test can assert on, and the screen
 * only draws it.
 *
 * EVERY FIELD IS TRACED TO A ROW. The status comes from `agent_runs.status`. Tool activity is
 * reconstructed from the ledger's own events, not from a summary. Messages come from
 * `agent_messages` with their stored authorship. Artefacts come from `agent_artefacts`. Where a
 * thing does not exist the field is empty and the room says so, rather than being filled with
 * something plausible.
 *
 * THE LEDGER IS READ THROUGH THE LEDGER, NOT THE DAO, so payloads arrive decrypted. Reaching past it
 * to the DAO would hand the assembler sealed envelopes and every tool argument would render as
 * ciphertext, which is a failure that looks like a data problem rather than a call-site one.
 *
 * WHY THERE IS A FOLD CACHE, AND WHAT IT COSTS IF IT GOES.
 *
 * Every event payload is sealed on disk, so reading a run's history means one Keystore decryption per
 * event. This class is called on every change to a live run and it used to read the WHOLE ledger each
 * time: measured on a device, one assemble was 77 decrypts totalling 181,661 bytes, and at a
 * two-second poll that is 2,310 decrypts and 5.4 MB per minute, growing with the run because the
 * whole history was re-read every tick.
 *
 * So the derived state is folded once and kept. [RunDerived] holds everything the events contribute
 * (tool activity, plan, reasoning, decisions, verifications) plus the last sequence it has consumed,
 * and [foldFor] reads only `eventsAfter(lastSequence)`. The steady-state cost of an unchanged run is
 * therefore zero decrypts, and the cost of a change is one per NEW event rather than one per event
 * ever written.
 *
 * THE CACHE IS BOUNDED AND LOSING IT IS SAFE. Eight runs are kept, least recently used first out. An
 * evicted run is rebuilt from its ledger on the next assemble at the old price, which is correct and
 * merely slower. Nothing is derived from the cache that is not also derivable from the rows, so a
 * stale or missing entry can only ever cost time.
 */
@Single
class AgentRoomAssembler(
    private val agentRepository: GuruAgentRepository,
    private val runDao: AgentRunDao,
    private val eventDao: AgentRunEventDao,
    private val artefactDao: AgentArtefactDao,
    private val contextDao: AgentContextReferenceDao,
    private val ledger: AgentRunLedger,
    private val broker: AgentInteractionBroker,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private companion object {
        /** How many previous runs the history list keeps. Enough to scroll, not the whole archive. */
        const val HISTORY_LIMIT = 20

        /**
         * How many runs keep a folded event cache.
         *
         * A PERSON WATCHES ONE ROOM AT A TIME, so a handful covers the open room plus whatever it was
         * opened from. Larger values buy nothing and hold megabytes of strings to buy it with.
         */
        const val MAX_CACHED_RUNS = 8

        /**
         * How often a room with no run looks for one.
         *
         * ONLY WHEN THERE IS NOTHING TO OBSERVE. Once a run exists the row flow drives everything,
         * so this is a safety net for a room that was opened before its agent was ever given a job.
         */
        const val IDLE_RECHECK_MS = 5_000L
    }

    /**
     * Per-run derived state, folded from the ledger once.
     *
     * MUTABLE ON PURPOSE, AND THAT IS THE POINT. This is an accumulation, not a snapshot: new events
     * are applied to it and the answer grows. Everything in it is derivable from the events it has
     * consumed, so it can be thrown away at any moment and rebuilt.
     */
    private class RunDerived {
        /** The highest sequence consumed. The boundary the next fold reads after. */
        var lastSequence: Long = 0L

        /**
         * The run row's two sealed fields, opened once.
         *
         * BOTH ARE FROZEN AT CREATION. `assignment` is the original brief and `resolvedSystemPrompt`
         * is what the model was given; neither is ever edited, so opening them again on every
         * assemble would be paying a Keystore call for a string that cannot have changed.
         */
        var openedAssignment: String? = null
        var openedSystemPrompt: String? = null

        val toolActivity = LinkedHashMap<String, ToolActivityView>()
        val reasoning = StringBuilder()

        /** The newest plan revision seen, and its payload. Revisions only ever move forward. */
        var planRevision: Int = Int.MIN_VALUE
        var planPayload: JsonObject? = null

        val decisions = ArrayList<DecisionView>()
        val verifications = ArrayList<VerificationView>()
    }

    /**
     * The fold cache, access-ordered so the room being looked at is the last evicted.
     *
     * INSERTION ORDER WOULD EVICT THE WRONG RUN. A person opens one room, so that run must survive
     * eight other runs being read in the background; insertion order would throw it out first
     * because it is usually the oldest entry.
     */
    private val folds = java.util.LinkedHashMap<String, RunDerived>(16, 0.75f, true)

    /** Fold for one run, evicting the least recently used when the cache is full. */
    private fun foldSlot(runId: String): RunDerived {
        folds[runId]?.let { return it }
        val created = RunDerived()
        folds[runId] = created
        while (folds.size > MAX_CACHED_RUNS) {
            val oldest = folds.keys.firstOrNull() ?: break
            folds.remove(oldest)
        }
        return created
    }

    /**
     * One folder at a time.
     *
     * THE READ AND THE FOLD ARE ONE OPERATION. Two assembles that both read `eventsAfter(10)` would
     * both fold those events, and every tool call after the boundary would appear twice. Holding the
     * lock across the read is what makes the boundary honest; the read is a Room query on a
     * background dispatcher, so nothing is blocked that a person can see.
     */
    private val foldLock = Mutex()

    /**
     * The room, and then the room again every time the displayed run changes.
     *
     * THIS REPLACES A TWO-SECOND POLL AND IS THE WHOLE OF THE FIX FOR THE SLOW APP.
     *
     * The poll re-assembled everything whether anything had changed or not. Every assemble reads the
     * run's event ledger and every payload is sealed on disk, so a poll was a Keystore decryption per
     * event per tick: measured on a device, 77 decrypts and 181,661 bytes per assemble, 2,310
     * decrypts a minute, and it grew with the run because the whole history was re-read each time.
     *
     * TWO THINGS FIX IT AND BOTH ARE HERE. The run row is observed rather than polled, so an
     * unchanged room costs nothing and a changed one costs one assemble. And [foldFor] reads only
     * events committed since the last fold, so the assemble a change costs is proportional to the
     * NEW events rather than to everything the run has ever done.
     *
     * WHY THE TIMESTAMP IS READ BEFORE SUBSCRIBING. A write landing between the query and the
     * subscription has already moved `updatedAt`, so the first emission carries a value this loop has
     * not seen and is not dropped. A `drop(1)` would discard precisely that write, which is the one
     * somebody is most likely waiting for.
     *
     * A MESSAGE MOVES THE ROW TOO. The broker appends a ledger event in the same transaction, and
     * appending advances `updated_at`, so an exchange appears with no timer anywhere.
     */
    fun roomFlow(agentId: String): Flow<AgentRoomState?> = flow {
        while (currentCoroutineContext().isActive) {
            val room = runCatching { assemble(agentId) }.getOrNull()
            emit(room)
            val runId = room?.run?.runId
            if (runId == null) {
                /*
                 * NOTHING TO WATCH, SO LOOK AGAIN SHORTLY. A room with no run can gain one started
                 * from elsewhere in the app, and a flow that waited for ever would never notice. This
                 * is the only timer left in the room and it runs only while nothing is happening,
                 * which is exactly when the cost is affordable.
                 */
                delay(IDLE_RECHECK_MS)
                continue
            }
            val seen = runCatching { runDao.run(runId)?.updatedAt }.getOrNull() ?: Long.MIN_VALUE
            runCatching {
                runDao.observeRun(runId).first { row -> row == null || row.updatedAt != seen }
            }
        }
    }

    /**
     * Assemble the room for one agent.
     *
     * Returns null when the agent does not exist, which is the caller's cue to close the room rather
     * than draw an empty one for an agent somebody deleted while it was open.
     */
    suspend fun assemble(agentId: String): AgentRoomState? {
        val agent = agentRepository.getAgent(agentId) ?: return null

        val runs = runDao.runsForAgent(agentId, HISTORY_LIMIT)
        val active = runs.firstOrNull { it.status in AgentRoomState.ACTIVE_STATUSES }
        val finished = runs.filter { it.status in AgentRunEntity.TERMINAL_STATUSES }

        /*
         * WHEN NOTHING IS ACTIVE, THE NEWEST RUN IS STILL THE CONVERSATION.
         *
         * This used to show a run only while it was live, so the moment it finished the room went
         * blank: the chat emptied, the composer disappeared, and the only way to say anything else
         * was to start over. A person sent one message, got one answer, and the conversation was
         * gone. That is not a chat, it is a one-shot form.
         *
         * A finished run keeps its whole transcript, so it is what the room shows when nothing is
         * running. The status band says which state it is in, and sending from here starts the next
         * run rather than pretending this one is alive.
         */
        val displayed = active ?: runs.firstOrNull()

        return AgentRoomState(
            agentId = agent.id,
            agentName = agent.name,
            agentSlug = agent.slug,
            agentDescription = agent.description,
            agentEnabled = agent.enabled,
            run = displayed?.let { assembleRun(it, agent) },
            history = finished.map { summarise(it) },
            // A VISIBILITY CHANGE HIDES, IT NEVER DESTROYS. The room reads the rule and decides what
            // to draw; it never deletes what was captured.
            visibility = agent.visibility,
            autonomy = agent.autonomy,
            blockers = emptyList(),
            warnings = emptyList(),
        )
    }

    /**
     * Assemble one run in full, from its row plus its own history.
     *
     * THE ROW IS RE-READ THROUGH THE LEDGER, AND THAT IS THE WHOLE FIX FOR A ROOM THAT DREW
     * CIPHERTEXT. `runDao` returns the row as stored, and the assignment and the system prompt are
     * sealed on disk. The first version of this passed that row straight to the view, so "the job"
     * on the overview was `PD1:1:Dbs1m2JU…` — the seal, not the words. The ledger's own
     * [AgentRunLedger.run] opens the two protected columns and returns a row a surface can draw, and
     * this asks it for that rather than reaching past it.
     *
     * Falling back to the stored row keeps a run whose column cannot be opened readable rather than
     * blank; the ledger writes an explicit `[unavailable: …]` marker for those, which says more than
     * an empty panel.
     */
    private suspend fun assembleRun(runStored: AgentRunEntity, agent: GuruAgent): RunView {
        /*
         * FOLD FIRST, THEN THE ROW. The fold is what caches the two opened columns, so reading the
         * row before it would pay for the decryption the cache exists to avoid.
         */
        val derived = foldFor(runStored.runId)
        val run = openedRun(runStored, derived)

        /*
         * THE CONVERSATION, NOT THE RUN. A run's messages end when the run does, and a room showing
         * one run's messages emptied the moment a new run started in the same conversation. A person
         * watched their own transcript vanish and re-typed it.
         *
         * FALLING BACK TO THE RUN'S OWN MESSAGES when there is no conversation id keeps a run whose
         * column is somehow blank readable rather than silent.
         */
        val messages = runCatching {
            if (run.conversationId.isBlank()) {
                broker.conversation(run.runId)
            } else {
                broker.transcript(run.conversationId)
            }
        }.getOrDefault(emptyList())
        val artefacts = runCatching { artefactDao.forRun(run.runId) }.getOrDefault(emptyList())
        val approvals = runCatching { broker.approvalsFor(run.runId) }
            .getOrDefault(emptyList())
            .filter { it.state == com.unuslumen.app.database.entity.AgentApprovalEntity.STATE_PENDING }

        val effective = readObject(run.effectiveConfigSnapshotJson)
        /*
         * READ ONCE, AND THE COUNTS DERIVE FROM IT.
         *
         * `contextCounts` used to load every reference and discard all but two numbers. The rows are
         * the answer to section 9.14's question, so they are carried, and the counts come from the
         * same list so they cannot disagree with the sources beside them.
         */
        val contextSources = contextSourcesFor(run.runId)

        return RunView(
            runId = run.runId,
            conversationId = run.conversationId,
            assignment = run.assignment,
            status = run.status,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            failureMessage = run.failureMessage,
            resultSummary = run.resultSummary,
            planSteps = planStepsFrom(derived),
            plan = planFrom(derived),
            toolActivity = derived.toolActivity.values.toList(),
            messages = messages.map { it.toView(run.agentName) },
            reasoning = reasoningFrom(derived, effective, run.reasoningCaptureEnabledAtStart),
            decisions = derived.decisions.sortedBy { it.sequence },
            verifications = derived.verifications.sortedBy { it.sequence },
            contextSupplied = contextSources.count { it.suppliedToModel },
            contextOffered = contextSources.size,
            contextSources = contextSources,
            artefacts = artefacts.map {
                ArtefactView(
                    artefactId = it.artefactId,
                    title = it.title,
                    kind = it.kind,
                    storageKind = it.storageKind,
                    storageRef = it.storageRef,
                    sizeBytes = it.sizeBytes,
                    finalOutput = it.finalOutput,
                    at = it.createdAt,
                )
            },
            pendingApprovals = approvals.map {
                ApprovalView(
                    approvalId = it.approvalId,
                    action = it.action,
                    what = it.what,
                    why = it.why,
                    affected = it.affected,
                    reversible = it.reversible,
                    consequences = it.consequences,
                    requestedPermission = it.requestedPermission,
                    requestedAt = it.requestedAt,
                )
            },
            effectiveConfigSummary = summariseConfig(effective),
        )
    }

    /**
     * The run row with its two sealed columns opened, paying for the decryption once per run.
     *
     * THE OPENING IS THE LEDGER'S JOB, NOT THIS CLASS'S. `ledger.run` is where the sealing policy
     * lives, including the explicit `[unavailable: …]` and `[altered: …]` markers for a column that
     * will not open. This calls it the first time and then reuses what it returned, because both
     * values are frozen at creation and re-opening them cannot produce a different answer.
     *
     * A row that has gone since the fold was built returns null, which the caller treats as the agent
     * having been deleted.
     */
    private suspend fun openedRun(stored: AgentRunEntity, derived: RunDerived): AgentRunEntity {
        val cachedAssignment = derived.openedAssignment
        val cachedPrompt = derived.openedSystemPrompt
        if (cachedAssignment != null && cachedPrompt != null) {
            return stored.copy(
                assignment = cachedAssignment,
                resolvedSystemPrompt = cachedPrompt,
            )
        }
        val opened = runCatching { ledger.run(stored.runId) }.getOrNull() ?: stored
        derived.openedAssignment = opened.assignment
        derived.openedSystemPrompt = opened.resolvedSystemPrompt
        return opened
    }

    /**
     * Fold everything committed since this run was last folded.
     *
     * THE STEADY-STATE COST IS ZERO. A run whose ledger has not moved reads nothing and decrypts
     * nothing, which is the whole fix: the old code read every payload on every tick and grew with
     * the run, so a long run made the room slower the more it had already done.
     */
    private suspend fun foldFor(runId: String): RunDerived = foldLock.withLock {
        val derived = foldSlot(runId)
        val pending: List<AgentRunEventEntity> = if (derived.lastSequence == 0L) {
            // Never folded, or the cache was evicted. One full read, then only deltas.
            runCatching { ledger.events(runId) }.getOrDefault(emptyList())
        } else {
            runCatching { ledger.eventsAfter(runId, derived.lastSequence) }.getOrDefault(emptyList())
        }
        for (event in pending) {
            foldEvent(derived, event)
            if (event.sequenceNumber > derived.lastSequence) derived.lastSequence = event.sequenceNumber
        }
        derived
    }

    /**
     * Apply one event to the running state.
     *
     * DISPATCHED ON THE EVENT TYPE BEFORE ANY PARSING. The previous version parsed the payload of
     * every event in the run to look for an invocation id, including the ones that could never have
     * one, so a run with a thousand model calls paid a thousand JSON parses to learn nothing. Here a
     * type that contributes nothing costs a string comparison.
     */
    private fun foldEvent(derived: RunDerived, event: AgentRunEventEntity) {
        when (event.eventType) {
            AgentRunEventEntity.TOOL_CALL_STARTED,
            AgentRunEventEntity.TOOL_PERMISSION_GRANTED,
            AgentRunEventEntity.TOOL_PERMISSION_DENIED,
            AgentRunEventEntity.TOOL_CALL_COMPLETED,
            AgentRunEventEntity.TOOL_CALL_FAILED,
            AgentRunEventEntity.TOOL_CALL_REFUSED,
            AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN,
            -> foldToolEvent(derived, event)

            AgentRunEventEntity.MODEL_REASONING_COMPLETED -> {
                val text = stringOf(readObject(event.payloadJson), "reasoning") ?: return
                if (derived.reasoning.isNotEmpty()) derived.reasoning.append("\n\n")
                derived.reasoning.append(text)
            }

            com.unuslumen.app.domain.agents.AgentEventTypes.PLAN_REVISED -> {
                val payload = readObject(event.payloadJson) ?: return
                val revision = stringOf(payload, "revision")?.toIntOrNull() ?: return
                if (revision >= derived.planRevision) {
                    derived.planRevision = revision
                    derived.planPayload = payload
                }
            }

            com.unuslumen.app.domain.agents.AgentEventTypes.DECISION_RECORDED -> {
                val payload = readObject(event.payloadJson) ?: return
                val decision = stringOf(payload, "decision") ?: return
                derived.decisions.add(
                    DecisionView(
                        sequence = stringOf(payload, "sequence")?.toIntOrNull() ?: 0,
                        decision = decision,
                        problem = stringOf(payload, "problem").orEmpty(),
                        approach = stringOf(payload, "approach").orEmpty(),
                        alternatives = stringOf(payload, "alternatives")
                            .orEmpty()
                            .split('|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                        reason = stringOf(payload, "reason").orEmpty(),
                        evidence = stringOf(payload, "evidence").orEmpty(),
                        uncertainty = stringOf(payload, "uncertainty").orEmpty(),
                        next = stringOf(payload, "next").orEmpty(),
                        at = event.occurredAt,
                    )
                )
            }

            com.unuslumen.app.domain.agents.AgentEventTypes.VERIFICATION_RECORDED -> {
                val payload = readObject(event.payloadJson) ?: return
                val claim = stringOf(payload, "claim") ?: return
                derived.verifications.add(
                    VerificationView(
                        sequence = stringOf(payload, "sequence")?.toIntOrNull() ?: 0,
                        claim = claim,
                        level = stringOf(payload, "level").orEmpty(),
                        evidence = stringOf(payload, "evidence").orEmpty(),
                        check = stringOf(payload, "check").orEmpty(),
                        uncertainty = stringOf(payload, "uncertainty").orEmpty(),
                        at = event.occurredAt,
                    )
                )
            }
        }
    }

    /**
     * Tool activity, paired by invocation id, one event at a time.
     *
     * PAIRED BY INVOCATION ID, WHICH IS WHAT MAKES IT HONEST. A start with no completion is an
     * invocation still running or one whose outcome was never recorded, and those are different from
     * a failure. The pairing is what lets the room say which.
     *
     * A CALL THAT NEVER STARTED IS STILL DRAWN. A completion can arrive for an invocation whose start
     * event predates this run's fold boundary, so the map is created from whichever event arrives
     * first rather than assuming the start is always seen.
     */
    private fun foldToolEvent(derived: RunDerived, event: AgentRunEventEntity) {
        val payload = readObject(event.payloadJson) ?: return
        val invocationId = stringOf(payload, "invocationId") ?: return
        val existing = derived.toolActivity[invocationId]
        val tool = stringOf(payload, "tool") ?: existing?.toolName ?: return

        derived.toolActivity[invocationId] = when (event.eventType) {
            AgentRunEventEntity.TOOL_CALL_STARTED -> ToolActivityView(
                invocationId = invocationId,
                toolName = tool,
                arguments = stringOf(payload, "arguments") ?: "{}",
                result = null,
                failed = false,
                refused = false,
                outcomeUnknown = false,
                durationMillis = null,
                permissionOutcome = null,
                at = event.occurredAt,
            )

            AgentRunEventEntity.TOOL_PERMISSION_GRANTED ->
                (existing ?: base(invocationId, tool, event)).copy(permissionOutcome = "granted")

            AgentRunEventEntity.TOOL_PERMISSION_DENIED ->
                (existing ?: base(invocationId, tool, event)).copy(
                    permissionOutcome = "denied",
                    refused = true,
                )

            AgentRunEventEntity.TOOL_CALL_COMPLETED -> {
                val view = existing ?: base(invocationId, tool, event)
                view.copy(
                    result = stringOf(payload, "result"),
                    durationMillis = (event.occurredAt - view.at).coerceAtLeast(0),
                )
            }

            AgentRunEventEntity.TOOL_CALL_FAILED -> {
                val view = existing ?: base(invocationId, tool, event)
                view.copy(
                    result = stringOf(payload, "error"),
                    failed = true,
                    durationMillis = (event.occurredAt - view.at).coerceAtLeast(0),
                )
            }

            AgentRunEventEntity.TOOL_CALL_REFUSED ->
                (existing ?: base(invocationId, tool, event)).copy(
                    result = stringOf(payload, "reason"),
                    refused = true,
                )

            AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN ->
                (existing ?: base(invocationId, tool, event)).copy(
                    result = stringOf(payload, "reason"),
                    outcomeUnknown = true,
                )

            else -> existing ?: base(invocationId, tool, event)
        }
    }

    /** A finished run, as one line of history. */
    private suspend fun summarise(run: AgentRunEntity): RunSummaryView {
        val artefactCount = runCatching { artefactDao.countForRun(run.runId) }.getOrDefault(0)
        /*
         * A COUNT QUERY, NOT A READ OF EVERY EVENT. This is the history list: twenty finished runs,
         * none of which is changing, and the only fact wanted from their ledgers is how many tool
         * calls there were. Reading each run's events to count them was twenty full scans per
         * refresh, and the count is the only thing any of them produced.
         */
        val toolCalls = runCatching {
            eventDao.countOfType(run.runId, AgentRunEventEntity.TOOL_CALL_STARTED)
        }.getOrDefault(0)
        return RunSummaryView(
            runId = run.runId,
            assignment = run.assignment,
            status = run.status,
            startedAt = run.startedAt ?: run.createdAt,
            finishedAt = run.finishedAt,
            artefactCount = artefactCount,
            toolCallCount = toolCalls,
        )
    }

    /**
     * The newest plan revision's steps, as the room shows them.
     *
     * A PLAN IS A LINE OF REVISIONS, AND THE ROOM SHOWS THE LATEST. Each revision is an appended
     * event, so the history is intact and a person can see the current intent without reading every
     * version. The fold keeps only the highest, which is why this reads one payload rather than
     * sorting every revision on every assemble.
     *
     * STEP STATE COMES FROM THE REVISION KIND. A create produces proposed steps; a step update
     * records that one moved. Until a step has been marked done by an update, it is proposed, and the
     * room draws the two differently because a proposal is not an action.
     */
    private fun planStepsFrom(derived: RunDerived): List<PlanStepView> {
        val latest = derived.planPayload ?: return emptyList()
        val raw = stringOf(latest, "steps").orEmpty()
        if (raw.isBlank()) return emptyList()

        val kind = stringOf(latest, "kind").orEmpty()
        return raw.split(" | ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, step ->
                PlanStepView(
                    stepId = "step-${index + 1}",
                    description = step,
                    state = if (kind == "STEP_UPDATED") {
                        PlanStepView.STATE_ACTIVE
                    } else {
                        PlanStepView.STATE_PROPOSED
                    },
                    updatedAt = 0,
                )
            }
    }

    /**
     * The newest plan revision's own fields, as opposed to its steps.
     *
     * ONE REVISION IS READ, the same one the step reader reads, so the steps and the plan around
     * them cannot come from different versions. Null when the run recorded no plan, which the room
     * draws differently from a plan whose optional fields were left blank.
     */
    private fun planFrom(derived: RunDerived): PlanView? {
        val latest = derived.planPayload ?: return null
        val revision = derived.planRevision.takeIf { it != Int.MIN_VALUE } ?: return null
        return PlanView(
            revision = revision,
            title = stringOf(latest, "title").orEmpty(),
            objective = stringOf(latest, "objective").orEmpty(),
            dependencies = stringOf(latest, "dependencies").orEmpty(),
            expectedOutputs = stringOf(latest, "expectedOutputs").orEmpty(),
            verification = stringOf(latest, "verification").orEmpty(),
            tools = stringOf(latest, "tools").orEmpty(),
            reason = stringOf(latest, "reason").orEmpty(),
            at = 0,
        )
    }

    private fun base(invocationId: String, tool: String, event: AgentRunEventEntity) =
        ToolActivityView(
            invocationId = invocationId,
            toolName = tool,
            arguments = "{}",
            result = null,
            failed = false,
            refused = false,
            outcomeUnknown = false,
            durationMillis = null,
            permissionOutcome = null,
            at = event.occurredAt,
        )

    /**
     * The reasoning the model actually gave.
     *
     * THE PROVIDER CAPABILITY COMES FROM THE RUN'S OWN EFFECTIVE SNAPSHOT, not from what this build
     * would offer today. A run that used a model without a reasoning channel must keep saying so
     * even after the device is switched to one that has it.
     */
    private fun reasoningFrom(
        derived: RunDerived,
        effective: JsonObject?,
        capturedAtStart: Boolean,
    ): ReasoningView {
        val providerSupports = effective?.get("reasoningCaptureEnabled")
            ?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }
            ?: true

        return ReasoningView(
            text = derived.reasoning.toString(),
            providerSupportsReasoning = providerSupports,
            capturedAtStart = capturedAtStart,
        )
    }

    /**
     * Every source the run considered, in the retrieval's own rank order.
     *
     * READ FROM THE TABLE THAT RECORDS IT, not from the event stream. The event carries the fact that
     * a retrieval happened; the reference rows carry which sources, whether each was in scope and
     * whether each reached the model, which is what section 9.14 asks for and what the counts beside
     * them derive from.
     *
     * ORDERED BY THE RETRIEVAL'S RANK so the list reads as the search returned it rather than as the
     * rows happened to be written.
     */
    private suspend fun contextSourcesFor(runId: String): List<ContextSourceView> {
        /*
         * A TRY RATHER THAN runCatching. A suspend call inside runCatching's inline lambda is the
         * construct this toolchain misreports, the same way a local function inside a loop body was
         * misreported before: the build fails with a receiver-type mismatch on a generated Koin
         * extension two files away, which sends you looking at Koin and clean caches. The behaviour
         * here is identical and the compiler is happy with it.
         */
        return try {
            contextDao.forRun(runId)
                .sortedBy { it.rankInLane }
                .map { row ->
                    ContextSourceView(
                        sourceKind = row.sourceKind,
                        sourceLabel = row.sourceLabel,
                        retrievalQuery = row.retrievalQuery,
                        withinScope = row.withinScope,
                        scopeLabel = row.scopeLabel,
                        suppliedToModel = row.suppliedToModel,
                        excerpt = row.excerpt,
                        rankInLane = row.rankInLane,
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun AgentMessageEntity.toView(agentName: String): MessageView = MessageView(
        messageId = messageId,
        authorType = authorType,
        authorName = when (authorType) {
            AgentMessageEntity.AUTHOR_AGENT -> agentName
            AgentMessageEntity.AUTHOR_GURU -> "GURU"
            AgentMessageEntity.AUTHOR_USER -> "You"
            else -> "System"
        },
        recipientType = recipientType,
        messageType = messageType,
        content = content,
        deliveryState = deliveryState,
        at = createdAt,
    )

    private fun summariseConfig(effective: JsonObject?): String {
        if (effective == null) return "No configuration snapshot was recorded for this run."
        val model = stringOf(effective, "model") ?: return "Configuration recorded, without a model name."
        val tools = stringOf(effective, "toolsOffered").orEmpty()
        val toolCount = if (tools.isBlank()) 0 else tools.split(",").count { it.isNotBlank() }
        return "$model · $toolCount tool${if (toolCount == 1) "" else "s"}"
    }

    private fun readObject(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrNull()

    private fun stringOf(obj: JsonObject?, key: String): String? =
        obj?.get(key)?.let { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotBlank() && it != "null" }
}
