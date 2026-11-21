// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentMessageEntity
import com.unuslumen.app.domain.agents.AgentDelegationPolicy
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.flow.last
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Where a parent run asks for a child, and the only route by which it gets one.
 *
 * THE POLICY DECIDES AND THIS EXECUTES IT. Every check that guards delegation — the parent's
 * autonomy, the target's existence, the depth ceiling, the fan-out ceiling, the tool intersection —
 * already lives in [AgentDelegationPolicy] where it is tested exhaustively. This class resolves the
 * facts the policy needs from the real rows, asks, and refuses or starts.
 *
 * IT RESOLVES FROM THE DATABASE, NOT FROM THE CALLER'S CLAIM. A parent could otherwise report its
 * own depth and its own child count, and a lying parent would be an unbounded fan-out. Depth comes
 * from the parent run's own snapshot, the child count from the runs that name it, and the tool sets
 * from the agent rows.
 *
 * A DELEGATION IS A RUN, NOT A CALL. The child gets its own id, its own ledger and its own
 * conversation, and `parent_run_id` is the only thing that differs. That is what makes a chain
 * auditable: every level can be read as a complete execution and the chain is one indexed query.
 */
@OptIn(ExperimentalUuidApi::class)
@Single
class AgentDelegationService(
    private val agentRepository: GuruAgentRepository,
    private val runDao: AgentRunDao,
    private val runtime: AgentRuntime,
    private val broker: AgentInteractionBroker,
) {

    /** What the parent asked for, before any policy has been applied. */
    data class Request(
        val parentRunId: String,
        val targetAgent: String,
        val job: String,
        /** Tools the parent wants the child to have. Null means "whatever the policy grants". */
        val requestedTools: Set<String>? = null,
    )

    /** Where a delegation succeeded, the child run's id; where it did not, the reason. */
    sealed interface Outcome {
        data class Started(
            val childRunId: String,
            val agentName: String,
            val grantedTools: Set<String>,
            val depth: Int,
        ) : Outcome

        /**
         * Refused, with the reason in the policy's own words.
         *
         * NOT AN EXCEPTION, because a refusal is an ordinary answer the parent has to reason about:
         * it can do the work itself, ask its owner, or report the gap. Throwing would turn a
         * decision into a failure and tempt a caller to swallow it.
         */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * Ask for a child, apply the policy, and start it when permitted.
     *
     * THE PARENT'S RUN MUST EXIST AND MUST BE LIVE. A finished run cannot delegate: its
     * conversation belongs to work that is over, and a child appearing after the parent reported
     * completion would be work nobody is waiting for.
     */
    suspend fun delegate(request: Request): Outcome {
        val parentRun = runDao.run(request.parentRunId)
            ?: return Outcome.Refused(
                "No run '${request.parentRunId}' exists, so there is nothing to delegate from."
            )

        if (parentRun.status in AgentRunEntity.TERMINAL_STATUSES) {
            return Outcome.Refused(
                "That run has finished, so it cannot delegate new work. Start a fresh job instead."
            )
        }

        val parentAgent = agentRepository.getAgent(parentRun.agentId)
            ?: return Outcome.Refused(
                "The agent that owns this run no longer exists, so its authority cannot be resolved."
            )

        val targetAgent = agentRepository.resolveAgent(request.targetAgent)
        val children = runDao.childrenOf(request.parentRunId)

        val verdict = AgentDelegationPolicy.evaluate(
            AgentDelegationPolicy.Request(
                parentRunId = parentRun.runId,
                parentAgentId = parentRun.agentId,
                parentDepth = depthOf(parentRun),
                parentChildrenSoFar = children.size,
                parentTools = parentAgent.toolPermissions.toSet(),
                parentMayDelegate = parentAgent.autonomy.requestDelegation,
                parentMaxDelegations = parentAgent.budgets.maxDelegations,
                parentMaxDepth = parentAgent.budgets.maxDelegationDepth,
                targetAgentId = targetAgent?.id ?: request.targetAgent,
                targetExists = targetAgent != null,
                targetEnabled = targetAgent?.enabled == true,
                targetTools = targetAgent?.toolPermissions?.toSet().orEmpty(),
                requestedTools = request.requestedTools,
            )
        )

        return when (verdict) {
            is AgentDelegationPolicy.Verdict.Refused -> Outcome.Refused(verdict.reason)

            is AgentDelegationPolicy.Verdict.Permitted -> {
                val childAgent = targetAgent!!
                startChild(parentRun, childAgent, request.job, verdict)
            }
        }
    }

    /**
     * Start the child and hand its result back to the parent.
     *
     * THE CHILD RUNS THROUGH THE ORDINARY SPAWN PATH, with the delegated tool set and the parent's
     * id. There is no second execution engine for delegation, which is what keeps the ledger,
     * the permissions and the recovery rules the same at every level of a chain.
     */
    private suspend fun startChild(
        parentRun: AgentRunEntity,
        childAgent: GuruAgent,
        job: String,
        verdict: AgentDelegationPolicy.Verdict.Permitted,
    ): Outcome {
        val childRunId = Uuid.random().toString()

        val result = runtime.spawn(
            slugOrName = childAgent.slug.ifBlank { childAgent.name },
            job = job,
            // THE CHILD BELONGS TO THE SAME CONVERSATION. A delegated run is work within one
            // person's request, not a new thread, and deleting that conversation must take the
            // whole chain with it.
            conversationId = parentRun.conversationId,
            runId = childRunId,
            parentRunId = parentRun.runId,
            delegatedTools = verdict.grantedTools,
            depth = verdict.depth,
        ).last()

        /*
         * THE HANDOFF IS A MESSAGE, NOT A RETURN VALUE.
         *
         * The parent may be waiting on something else when the child finishes, so the result has to
         * arrive somewhere durable and attributable. Sending it as a message from the child to the
         * parent means it survives a restart, appears in the conversation timeline, and cannot be
         * mistaken for something GURU said.
         */
        runCatching {
            broker.send(
                runId = parentRun.runId,
                message = AgentInteractionBroker.Outgoing(
                    authorType = AgentMessageEntity.AUTHOR_AGENT,
                    authorId = childAgent.id,
                    recipientType = AgentMessageEntity.RECIPIENT_AGENT,
                    recipientId = parentRun.agentId,
                    messageType = if (result.success) {
                        AgentMessageEntity.MESSAGE_TYPE_COMPLETION
                    } else {
                        AgentMessageEntity.MESSAGE_TYPE_ERROR
                    },
                    content = buildString {
                        append("Delegated to ${childAgent.name} (run $childRunId). ")
                        if (result.success) {
                            append("It reported: ")
                            append(result.report.take(2_000))
                        } else {
                            append("It could not complete: ")
                            append(result.error ?: "no reason given")
                        }
                    },
                    correlationId = childRunId,
                ),
            )
        }

        return Outcome.Started(
            childRunId = childRunId,
            agentName = childAgent.name,
            grantedTools = verdict.grantedTools,
            depth = verdict.depth,
        )
    }

    /**
     * How deep a run is, read from its own snapshot rather than from its parent chain.
     *
     * THE SNAPSHOT IS THE AUTHORITY. Walking parent links would cost one query per level, could
     * loop if a link were ever wrong, and would give a different answer after a parent was deleted.
     * The depth was frozen when the run was created and cannot change.
     */
    private fun depthOf(run: AgentRunEntity): Int = runCatching {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(run.effectiveConfigSnapshotJson)
            as? kotlinx.serialization.json.JsonObject
        (obj?.get("delegationDepth") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    /** Every run that named this one as its parent. */
    suspend fun childrenOf(parentRunId: String): List<AgentRunEntity> = runDao.childrenOf(parentRunId)
}
