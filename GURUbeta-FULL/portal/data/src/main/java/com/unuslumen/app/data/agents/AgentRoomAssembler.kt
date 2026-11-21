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
import com.unuslumen.app.domain.model.AgentAutonomy
import com.unuslumen.app.domain.model.AgentVisibility
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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

    /** How many previous runs the history list keeps. Enough to scroll, not the whole archive. */
    private companion object {
        const val HISTORY_LIMIT = 20
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
        val run = runCatching { ledger.run(runStored.runId) }.getOrNull() ?: runStored
        val events = runCatching { ledger.events(run.runId) }.getOrDefault(emptyList())
        val messages = runCatching { broker.conversation(run.runId) }.getOrDefault(emptyList())
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
            planSteps = planStepsFrom(events),
            plan = planFrom(events),
            toolActivity = toolActivityFrom(events),
            messages = messages.map { it.toView(run.agentName) },
            reasoning = reasoningFrom(events, effective, run.reasoningCaptureEnabledAtStart),
            decisions = decisionsFrom(events),
            verifications = verificationsFrom(events),
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

    /** A finished run, as one line of history. */
    private suspend fun summarise(run: AgentRunEntity): RunSummaryView {
        val artefactCount = runCatching { artefactDao.countForRun(run.runId) }.getOrDefault(0)
        val toolCalls = runCatching {
            eventDao.eventsForRun(run.runId).count {
                it.eventType == AgentRunEventEntity.TOOL_CALL_STARTED
            }
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
     * Tool activity, reconstructed from the ledger's own events.
     *
     * PAIRED BY INVOCATION ID, WHICH IS WHAT MAKES IT HONEST. A start with no completion is an
     * invocation still running or one whose outcome was never recorded, and those are different
     * from a failure. The pairing is what lets the room say which.
     */
    private fun toolActivityFrom(events: List<AgentRunEventEntity>): List<ToolActivityView> {
        val byInvocation = linkedMapOf<String, ToolActivityView>()

        for (event in events) {
            val payload = readObject(event.payloadJson)
            val invocationId = stringOf(payload, "invocationId") ?: continue
            val tool = stringOf(payload, "tool") ?: byInvocation[invocationId]?.toolName ?: continue

            when (event.eventType) {
                AgentRunEventEntity.TOOL_CALL_STARTED -> {
                    byInvocation[invocationId] = ToolActivityView(
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
                }
                AgentRunEventEntity.TOOL_PERMISSION_GRANTED -> {
                    byInvocation[invocationId] = (byInvocation[invocationId] ?: base(invocationId, tool, event))
                        .copy(permissionOutcome = "granted")
                }
                AgentRunEventEntity.TOOL_PERMISSION_DENIED -> {
                    byInvocation[invocationId] = (byInvocation[invocationId] ?: base(invocationId, tool, event))
                        .copy(permissionOutcome = "denied", refused = true)
                }
                AgentRunEventEntity.TOOL_CALL_COMPLETED -> {
                    val existing = byInvocation[invocationId] ?: base(invocationId, tool, event)
                    byInvocation[invocationId] = existing.copy(
                        result = stringOf(payload, "result"),
                        durationMillis = (event.occurredAt - existing.at).coerceAtLeast(0),
                    )
                }
                AgentRunEventEntity.TOOL_CALL_FAILED -> {
                    val existing = byInvocation[invocationId] ?: base(invocationId, tool, event)
                    byInvocation[invocationId] = existing.copy(
                        result = stringOf(payload, "error"),
                        failed = true,
                        durationMillis = (event.occurredAt - existing.at).coerceAtLeast(0),
                    )
                }
                AgentRunEventEntity.TOOL_CALL_REFUSED -> {
                    val existing = byInvocation[invocationId] ?: base(invocationId, tool, event)
                    byInvocation[invocationId] = existing.copy(
                        result = stringOf(payload, "reason"),
                        refused = true,
                    )
                }
                AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN -> {
                    val existing = byInvocation[invocationId] ?: base(invocationId, tool, event)
                    byInvocation[invocationId] = existing.copy(
                        result = stringOf(payload, "reason"),
                        outcomeUnknown = true,
                    )
                }
            }
        }
        return byInvocation.values.toList()
    }


    /**
     * The newest plan revision's steps, as the room shows them.
     *
     * A PLAN IS A LINE OF REVISIONS, AND THE ROOM SHOWS THE LATEST. Each revision is an appended
     * event, so the history is intact and a person can see the current intent without reading every
     * version. Revisions are numbered per run and this takes the highest.
     *
     * STEP STATE COMES FROM THE REVISION KIND. A create produces proposed steps; a step update
     * records that one moved. Until a step has been marked done by an update, it is proposed, and the
     * room draws the two differently because a proposal is not an action.
     */
    private fun planStepsFrom(events: List<AgentRunEventEntity>): List<PlanStepView> {
        val revisions = events
            .filter { it.eventType == com.unuslumen.app.domain.agents.AgentEventTypes.PLAN_REVISED }
            .mapNotNull { event ->
                val payload = readObject(event.payloadJson) ?: return@mapNotNull null
                val revision = stringOf(payload, "revision")?.toIntOrNull() ?: return@mapNotNull null
                revision to payload
            }
            .sortedBy { it.first }

        val latest = revisions.lastOrNull()?.second ?: return emptyList()
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
    private fun planFrom(events: List<AgentRunEventEntity>): PlanView? {
        val latest = events
            .filter { it.eventType == com.unuslumen.app.domain.agents.AgentEventTypes.PLAN_REVISED }
            .mapNotNull { event ->
                val payload = readObject(event.payloadJson) ?: return@mapNotNull null
                val revision = stringOf(payload, "revision")?.toIntOrNull() ?: return@mapNotNull null
                Triple(revision, payload, event.occurredAt)
            }
            .maxByOrNull { it.first } ?: return null

        val plan = latest.second
        return PlanView(
            revision = latest.first,
            title = stringOf(plan, "title").orEmpty(),
            objective = stringOf(plan, "objective").orEmpty(),
            dependencies = stringOf(plan, "dependencies").orEmpty(),
            expectedOutputs = stringOf(plan, "expectedOutputs").orEmpty(),
            verification = stringOf(plan, "verification").orEmpty(),
            tools = stringOf(plan, "tools").orEmpty(),
            reason = stringOf(plan, "reason").orEmpty(),
            at = latest.third,
        )
    }

    /**
     * What the agent said it decided, in the order it said it.
     *
     * READ FROM THE LEDGER, NOT FROM THE CONVERSATION AND NOT FROM THE REASONING. A decision is an
     * event the agent deliberately wrote; reconstructing one out of a message or out of reasoning
     * prose would be inventing a record from text that was never meant to be one, which the
     * specification forbids in the same breath as it requires the record.
     *
     * ORDERED BY THE RUN'S OWN SEQUENCE, not by when the row was written. The two are almost always
     * the same and the sequence is the field that actually means "the third decision", so it is the
     * one used.
     */
    private fun decisionsFrom(events: List<AgentRunEventEntity>): List<DecisionView> =
        events
            .filter { it.eventType == com.unuslumen.app.domain.agents.AgentEventTypes.DECISION_RECORDED }
            .mapNotNull { event ->
                val payload = readObject(event.payloadJson) ?: return@mapNotNull null
                val decision = stringOf(payload, "decision") ?: return@mapNotNull null
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
            }
            .sortedBy { it.sequence }

    /**
     * What the agent recorded checking, in the order it recorded it.
     *
     * READ FROM THE LEDGER, THE SAME WAY DECISIONS ARE. A check is a fact the agent deliberately
     * wrote, so reconstructing one from its report would be inventing evidence out of prose. Ordered
     * by the run's own sequence so "the second thing it checked" means something.
     *
     * A RECORD WITH NO CLAIM IS SKIPPED rather than drawn with a blank heading, because the runtime
     * already refuses to write one.
     */
    private fun verificationsFrom(events: List<AgentRunEventEntity>): List<com.unuslumen.app.domain.agents.VerificationView> =
        events
            .filter {
                it.eventType == com.unuslumen.app.domain.agents.AgentEventTypes.VERIFICATION_RECORDED
            }
            .mapNotNull { event ->
                val payload = readObject(event.payloadJson) ?: return@mapNotNull null
                val claim = stringOf(payload, "claim") ?: return@mapNotNull null
                com.unuslumen.app.domain.agents.VerificationView(
                    sequence = stringOf(payload, "sequence")?.toIntOrNull() ?: 0,
                    claim = claim,
                    level = stringOf(payload, "level").orEmpty(),
                    evidence = stringOf(payload, "evidence").orEmpty(),
                    check = stringOf(payload, "check").orEmpty(),
                    uncertainty = stringOf(payload, "uncertainty").orEmpty(),
                    at = event.occurredAt,
                )
            }
            .sortedBy { it.sequence }

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
        events: List<AgentRunEventEntity>,
        effective: JsonObject?,
        capturedAtStart: Boolean,
    ): ReasoningView {
        val text = events
            .filter { it.eventType == AgentRunEventEntity.MODEL_REASONING_COMPLETED }
            .mapNotNull { stringOf(readObject(it.payloadJson), "reasoning") }
            .joinToString("\n\n")

        val providerSupports = effective?.get("reasoningCaptureEnabled")
            ?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }
            ?: true

        return ReasoningView(
            text = text,
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
        return "$model · $toolCount tool${if (toolCount == 1) "" else "s"} offered"
    }

    private fun readObject(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrNull()

    private fun stringOf(obj: JsonObject?, key: String): String? =
        obj?.get(key)?.let { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotBlank() && it != "null" }
}
