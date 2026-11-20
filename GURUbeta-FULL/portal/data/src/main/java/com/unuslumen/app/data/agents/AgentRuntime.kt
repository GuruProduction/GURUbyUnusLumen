// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.unuslumen.app.data.LlmConfig
import com.unuslumen.app.data.LlmConfigFetcher
import com.unuslumen.app.data.buildChatPrompt
import com.unuslumen.app.data.getRootCause
import com.unuslumen.app.data.gurutools.GuruToolSchemaMapper
import com.unuslumen.app.data.repository.UnusLumenStreamingClient
import com.unuslumen.app.data.tools.AgentToolDefinitions
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolDispatcher
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.toLLMProvider
import com.unuslumen.app.data.toLLModel
import com.unuslumen.app.data.tor.TorEgress
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GuruAgentRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.fixedBaseUrl
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProvider
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.koin.core.annotation.Factory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Runs a saved agent in conversation with the main assistant.
 *
 * WHAT THIS IS. GURU hands a job to a named agent. The agent runs with its own
 * system prompt and exactly the tools it declares, and it can stop mid-run to ask
 * the assistant a question. The answer comes back through [resume], and the agent
 * carries on with everything it had already worked out. A session is the
 * conversation; a report is how it ends.
 *
 * WHY THE SESSION EXISTS AT ALL. The question and the answer are separated by a
 * model round trip through the assistant, which cannot happen while that assistant
 * is itself blocked inside a tool call. So the exchange travels as tool calls:
 * spawn returns a question, the assistant's next turn answers it with resume, and
 * the loop continues. That is what makes the conversation visible, and it is why
 * the round cap could go.
 *
 * THROUGH THE DISPATCHER, NOT AROUND IT. Every tool call goes through
 * [ToolDispatcher], which is where permissions are checked against the device,
 * the security policy inspects arguments, and the argument repair fixes a
 * malformed call. Calling a tool's executor directly would skip all three, so an
 * agent could reach a capability the person had denied. The dispatcher is the
 * only door, the same as it is for the main conversation.
 *
 * THE TOOLS ARE THE TOOLS. [GuruAgent.toolPermissions] is the complete set. A
 * tool not named there is not offered to the model and is refused at dispatch
 * even if the model writes the name by hand. An empty list means it reasons and
 * reports without touching anything.
 *
 * AN AGENT CANNOT SPAWN AN AGENT. The agent verbs are removed from every agent's
 * tool set regardless of its definition. Without that, one agent recurses into
 * spawnAgent and each level burns a full model call.
 *
 * BOTH WIRE PATHS ARE HONOURED. The app has two: its own Ollama-format client
 * for the Lumen gateway and local endpoints, and koog brand clients for cloud
 * BYO. The main send path picks between them on whether the streaming client
 * exists, and this does exactly the same, because an agent on OpenAI must not be
 * sent down an Ollama wire.
 *
 * COST IS COUNTED, NOT CAPPED. [SpawnResult.toolRounds] reports what a job took.
 * The old twelve-round cap existed to stop a runaway, but it also killed real work
 * halfway through and made a two-question clarification ruinous. The loop ends when
 * the model stops asking, which is the only honest stopping condition; the exchange
 * count bounds the conversation in [MAX_EXCHANGES] rather than the work.
 */
@OptIn(ExperimentalUuidApi::class)
@Factory
class AgentRuntime(
    private val agentRepository: GuruAgentRepository,
    private val toolRegistry: ToolRegistry,
    private val toolDispatcher: ToolDispatcher,
    private val getPreferenceUseCase: GetPreferenceUseCase,
    private val context: android.content.Context,
    /** The durable run ledger. A run that is not written down has not happened. */
    private val ledger: AgentRunLedger,
    /**
     * The conversation between the user, GURU and the agent.
     *
     * INJECTED RATHER THAN CONSTRUCTED HERE, so the messages this run exchanges are the same rows a
     * surface reads. A runtime with its own private notion of the conversation would produce a room
     * that shows something different from what the agent was actually told.
     */
    private val broker: AgentInteractionBroker,
) {

    companion object {
        /**
         * Tool rounds are not capped.
         *
         * THE CAP WAS THE WRONG TOOL FOR THE PROBLEM. Twelve rounds was set when a
         * round could only be a tool call, and its job was to stop a runaway loop
         * costing money. Now a round can also be an exchange with the assistant, so
         * the same counter would let a two-question clarification eat the budget for
         * the work itself, and a real job would die halfway through with the agent
         * still holding a half-built answer.
         *
         * What actually stops a runaway is the loop's own shape: it ends when the
         * model stops asking for tools or questions, and every round needs a real
         * model response to continue. A model that is not making progress stops
         * asking. Cost is reported per session in [SpawnResult.toolRounds], so a
         * person can see what a job took rather than being told it was not allowed
         * to finish.
         */
        const val TOOL_ROUNDS_UNLIMITED = Int.MAX_VALUE

        /** Tool names no agent may ever call, whatever its definition says. */
        val NEVER_SPAWNABLE = setOf(
            "spawnAgent",
            "resumeAgent",
            "listAgents",
            "saveAgent",
            "deleteAgent"
        )

        private const val MAX_REPORT_CHARS = 8_000

        /** How many agent conversations may be live at once before the oldest is dropped. */
        const val MAX_LIVE_SESSIONS = 8

        /**
         * How many exchanges one session may take before it has to finish.
         *
         * This is NOT the old tool cap. It bounds the conversation, not the work:
         * an agent may take as many tool rounds as the job needs, but it cannot
         * keep asking questions indefinitely, because every question costs the
         * person an answer and a session that asks forty times is not collaborating,
         * it is failing to think. When a session reaches this, it is told to finish
         * with what it has and say what remains unknown.
         */
        const val MAX_EXCHANGES = 24

        /**
         * How many recent messages survive compaction unread.
         *
         * THE TAIL IS THE CONTEXT THAT MATTERS. The last few turns are what the agent
         * is actually working on: the job, the last exchange, the most recent tool
         * results. Compacting those away would make it forget what it was doing. Older
         * turns are replaced by the notebook instead, which keeps what was learned
         * without keeping every payload it was learned from.
         */
        const val RECENT_MESSAGES_KEPT = 8

        /**
         * How large the conversation may grow before it is compacted.
         *
         * THIS IS NOT A LIMIT ON THE WORK. It is the threshold at which the agent folds
         * its history down and carries on, the same way a long session does. The window
         * is a million tokens and the agent is not supposed to reach it, because a
         * request that big is slow to start and a slow start is what a proxy in front of
         * the model gives up on. Compacting well below the ceiling keeps the work fast
         * enough to finish, which is the real constraint.
         */
        const val COMPACTION_THRESHOLD_CHARS = 320_000

        /** How much of one tool result is quoted verbatim in the notebook before it is shortened. */
        private const val NOTEBOOK_EXCERPT_CHARS = 300

        /**
         * When buffered model output is written, rather than held.
         *
         * THE BOUND IS THE POINT. Holding everything until the stream ends means an abrupt
         * death loses the whole reply instead of the last fragment, and a long reply is
         * exactly when that matters. 250 ms or 8 KiB, whichever comes first, keeps both
         * the write count and the worst-case loss small.
         */
        const val OUTPUT_FLUSH_MILLIS = 250L
        const val OUTPUT_FLUSH_CHARS = 8 * 1024

        /** Shared so every component in this process reports the same lease owner. */
        @Volatile
        private var processLeaseOwner: String? = null
        private val leaseOwnerLock = Any()

        /**
         * The process incarnation id, minted once.
         *
         * ONE ID FOR THE WHOLE PROCESS, NOT A PID. The runtime and the foreground service
         * both write the lease and they are the same process, so they must agree about who
         * they are or the service renews a claim the runtime never made. A PID cannot serve
         * because the kernel reuses it, and a recycled number would make a dead owner look
         * alive, which is the one failure the lease exists to prevent.
         */
        fun leaseOwner(): String = synchronized(leaseOwnerLock) {
            processLeaseOwner ?: com.unuslumen.app.data.agents.AgentRecoveryCoordinator
                .newOwnerId()
                .also { processLeaseOwner = it }
        }

        private val NO_TIMEOUTS = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(
            requestTimeoutMillis = Long.MAX_VALUE,
            connectTimeoutMillis = Long.MAX_VALUE,
            socketTimeoutMillis = Long.MAX_VALUE
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Where fire-and-forget telemetry writes run.
     *
     * ONE SCOPE FOR THE RUNTIME'S LIFETIME. A permission decision is evidence recorded for
     * later reading; it must never delay a tool call or fail it, and it must never leak a
     * scope per invocation. IO because every write is a database transaction.
     */
    private val telemetryScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * What a turn of an agent produced.
     *
     * THREE OUTCOMES, NOT TWO. The old result could only say finished or failed,
     * because an agent had no way to speak mid-run. [status] now carries
     * [Status.FINISHED], [Status.ASKING] or [Status.FAILED], so a caller can tell
     * "here is the work" from "it is waiting on you" from "it could not run at all".
     * Collapsing ASKING into FAILED would strand the work, and collapsing it into
     * FINISHED would hand back an empty report as though it were an answer.
     */
    data class SpawnResult(
        val success: Boolean,
        val agentName: String,
        val report: String,
        val toolRounds: Int,
        /** Present whenever the agent is waiting on an answer, and null otherwise. */
        val sessionId: String? = null,
        val status: Status = Status.FINISHED,
        /** The question the agent is waiting on, when [status] is ASKING. */
        val question: String? = null,
        /** Why the agent needed to ask, when it said. */
        val why: String? = null,
        /** The answers it said it could act on, when it offered any. */
        val options: List<String> = emptyList(),
        /** How many times this session folded its history into notes. Diagnostic. */
        val compactions: Int = 0,
        val error: String? = null
    ) {
        enum class Status { FINISHED, ASKING, FAILED }
    }

    /**
     * One agent's live session: the conversation so far, and what it is waiting on.
     *
     * A SESSION IS THE WHOLE POINT. An agent that runs one turn and dies cannot ask
     * anything, because the answer has nowhere to arrive. Keeping the message list
     * and the agent's identity alive between calls is what turns a dispatch into a
     * conversation, and it is why the round cap could then go: the agent is not
     * burning rounds in a void, it is waiting for a person to speak through the
     * assistant.
     *
     * Held in memory on purpose. A session is live work, not a record; the durable
     * artefact is the report, and a session that outlives the process it was talking
     * through has nobody left to answer it.
     */
    private data class Session(
        /**
         * The run id, and therefore the session id.
         *
         * ONE IDENTITY. There is no second handle for this session; `spawnAgent`
         * returns this value and `resumeAgent` takes it, and a restore after process
         * death arrives under the same value. Two identifiers for one execution would
         * be two things that must agree and eventually would not.
         */
        val id: String,
        val agent: GuruAgent,
        val wiring: Wiring,
        val allowed: List<ToolDefinition>,
        val descriptors: List<ToolDescriptor>,
        val systemPrompt: String,
        var messages: List<AiMessage>,
        var rounds: Int = 0,
        var exchangeCount: Int = 0,
        var pendingQuestion: String? = null,
        /**
         * The tool proposals in the round currently being executed.
         *
         * Kept so a resume can rebuild the exact message list rather than a
         * reconstruction of it: a tool call and its result are paired by the model's
         * own id, and losing that id turns a valid history into one the upstream
         * rejects.
         */
        var lastProposals: List<StreamFrame.ToolCallComplete> = emptyList(),
        /**
         * THE NOTEBOOK. Everything the agent has learned that no longer fits in the
         * live conversation, written down in its own words.
         *
         * This is what makes the work endless. An agent that can only remember the
         * last forty messages is an agent that gets tired; an agent carrying a
         * notebook forward is an agent that has read its own notes and carries on.
         * It is the same mechanism a long session uses on itself.
         */
        var notebook: String = "",
        /** How many times this session has folded its history down. */
        var compactions: Int = 0,
        /**
         * This run's limits, resolved once at spawn from the agent's own budgets.
         *
         * RESOLVED AT SPAWN AND FROZEN IN THE SESSION, because a limit is part of what the run
         * agreed to. Reading the agent's current budget on every round would let an edit made
         * while the work is in flight change how much work it is allowed to do, which is the
         * opposite of "a definition change does not rewrite a previous run".
         */
        val limits: RunLimits = RunLimits.SYSTEM_DEFAULT,
        /** When this run started, for the wall-clock ceiling. */
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        /** Exchanges this session has already spent asking the assistant something. */
        fun exchangesUsed(): Int = exchangeCount
    }

    /**
     * What one run is allowed to spend.
     *
     * NULL MEANS THE SYSTEM DEFAULT, NOT UNLIMITED. An agent that declares no budget gets
     * [SYSTEM_DEFAULT], which is the same behaviour every agent had before budgets existed:
     * the exchange cap, the tool-round ceiling and no wall-clock limit. That distinction is the
     * one that stops a missing field from silently meaning "spend anything".
     */
    data class RunLimits(
        val maxExchanges: Int,
        val maxToolRounds: Int,
        val maxToolCalls: Int?,
        val maxDurationMillis: Long?,
    ) {
        companion object {
            val SYSTEM_DEFAULT = RunLimits(
                maxExchanges = AgentRuntime.MAX_EXCHANGES,
                maxToolRounds = AgentRuntime.TOOL_ROUNDS_UNLIMITED,
                maxToolCalls = null,
                maxDurationMillis = null,
            )

            /**
             * Resolve an agent's declared budgets against the system defaults.
             *
             * THE AGENT MAY TIGHTEN, NEVER WIDEN. A budget larger than the system default is
             * clamped to the default rather than honoured, because the system values are the
             * safety policy and an agent's own configuration is the narrower preference. An
             * agent that asks for more exchange than the system allows does not get it by
             * declaring it.
             */
            fun from(agent: GuruAgent): RunLimits {
                val budgets = agent.budgets
                return RunLimits(
                    maxExchanges = budgets.maxExchanges
                        ?.coerceIn(1, AgentRuntime.MAX_EXCHANGES)
                        ?: AgentRuntime.MAX_EXCHANGES,
                    maxToolRounds = AgentRuntime.TOOL_ROUNDS_UNLIMITED,
                    maxToolCalls = budgets.maxToolCalls?.coerceAtLeast(1),
                    maxDurationMillis = budgets.maxDurationMillis?.coerceAtLeast(1_000L),
                )
            }
        }
    }

    /**
     * Live sessions by id.
     *
     * A LinkedHashMap so the oldest can be evicted when the store is full, and
     * capped because every session holds a message history and a model wiring
     * alive. A person running several agents at once should not be able to fill
     * the device's memory with abandoned conversations.
     */
    private val sessions = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Session>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>): Boolean =
                size > MAX_LIVE_SESSIONS
        }
    )

    /**
     * The wiring an agent runs on, resolved fresh at spawn time so switching
     * provider mid-session takes effect on the next job.
     */
    private data class Wiring(
        val model: ai.koog.prompt.llm.LLModel,
        val executor: PromptExecutor,
        val streamingClient: UnusLumenStreamingClient?,
        val config: LlmConfig
    )

    /**
     * Start a job, and run it until it finishes or stops to ask something.
     *
     * ONE TURN PER CALL, ONE SESSION ACROSS CALLS. This used to be fire-and-forget:
     * the agent got a job, ran to completion, and returned a report, so it could not
     * ask what it did not know. Now it can pause on a question and hand it back, and
     * [resume] carries the answer in and lets it continue. The assistant is the one
     * that answers, because the assistant is what the person is talking to.
     */
    fun spawn(
        slugOrName: String,
        job: String,
        onEvent: ((String) -> Unit)? = null,
        /** Where this work came from, so the run can be found from the conversation. */
        conversationId: String? = null,
        originMessageId: String? = null,
        /**
         * The run id, supplied by a caller that has to name the run before it starts.
         *
         * WHY A CALLER WOULD NEED IT FIRST. Work now runs under [AgentRunHost]'s
         * application-lifetime scope so it outlives whoever started it, and the host keys
         * its jobs by run id. A caller therefore has to allocate the id before dispatching,
         * not learn it from the result. Defaulted so every existing caller keeps working.
         */
        runId: String = Uuid.random().toString(),
    ) = flow {
        val agent = agentRepository.resolveAgent(slugOrName)
        if (agent == null) {
            emit(failed(slugOrName, "No agent called '$slugOrName'. Check the listing for the exact name."))
            return@flow
        }
        if (!agent.enabled) {
            emit(failed(agent.name, "'${agent.name}' is switched off, so it was not called."))
            return@flow
        }

        /*
         * NO CONVERSATION MEANS NO RUN, AND NO RUN MEANS NO EXECUTION.
         *
         * The foreign key on `agent_runs.conversation_id` is not decoration: a run belongs
         * to the conversation that owns it, and deleting that conversation must take the
         * run and its whole history with it. Substituting the run id here — which the first
         * version did — points at a conversation that does not exist, so the insert fails
         * and the agent cannot start at all. A sentence naming the actual cause is far
         * better than a foreign-key violation nobody can read.
         */
        val owningConversation = conversationId
        if (owningConversation.isNullOrBlank()) {
            emit(
                failed(
                    agent.name,
                    "${agent.name} was not started because no conversation was supplied. An agent " +
                        "run belongs to the conversation that asked for it, so the caller must pass " +
                        "one rather than relying on ambient state that another dispatch could change."
                )
            )
            return@flow
        }

        val wiring = wiring()
        if (wiring == null) {
            emit(failed(agent.name, "No model is connected right now, so '${agent.name}' could not run."))
            return@flow
        }

        /*
         * THE CONFIGURATION IS CHECKED BEFORE THE RUN IS RECORDED.
         *
         * WHY THIS GATE EXISTS. An agent whose manifest requires a tool it was not granted, or a
         * capability this build does not have, would previously start, fail inside a tool call, and
         * report that failure as though the tool were broken. The owner would then be looking at
         * the wrong component. Validating first means the refusal names the actual cause and no run
         * row is created for work that could never have succeeded.
         *
         * A WARNING DOES NOT STOP THE RUN. The validator distinguishes an error from a warning
         * precisely so an unavailable reasoning channel, which leaves the agent working and only its
         * reasoning area empty, does not block the job. What a warning does do is get recorded, so
         * the run says plainly which requested capability it is running without.
         */
        val verdict = com.unuslumen.app.domain.model.AgentConfigurationValidator.validate(
            agent,
            runtimeCapabilities(),
        )
        if (!verdict.canRun) {
            emit(
                failed(
                    agent.name,
                    "${agent.name} cannot run as configured: " +
                        verdict.errors.joinToString(" ") { it.message } +
                        " Correct the agent's configuration or what it asks for in its manifest.",
                )
            )
            return@flow
        }

        val allowed = allowedTools(agent)
        val resolvedPrompt = buildSystemPrompt(agent, allowed)

        // THE RUN IS WRITTEN BEFORE THE FIRST MODEL CALL, NOT AFTER THE LAST.
        // A run recorded only on completion records nothing for the runs that matter
        // most: the ones that were interrupted, that failed, or that are still waiting
        // on an answer. Everything below assumes this row exists, so a failure to write
        // it stops the job rather than running an unrecorded agent.
        val now = System.currentTimeMillis()
        try {
            ledger.createRun(
                com.unuslumen.app.database.entity.AgentRunEntity(
                    runId = runId,
                    conversationId = owningConversation,
                    originMessageId = originMessageId,
                    agentId = agent.id,
                    agentSlug = agent.slug,
                    agentName = agent.name,
                    agentVersion = agent.version,
                    assignment = job,
                    status = com.unuslumen.app.database.entity.AgentRunEntity.STATUS_CREATED,
                    agentDefinitionSnapshotJson = definitionSnapshot(agent),
                    requestedConfigSnapshotJson = jsonObject {
                        put("provider", wiring.config.baseModel)
                        put("reasoningVisibility", "FULL")
                    },
                    effectiveConfigSnapshotJson = jsonObject {
                        put("provider", wiring.model.provider.id)
                        put("model", wiring.model.id)
                        put("contextWindow", wiring.config.contextWindow)
                        put("maxOutputTokens", wiring.config.maxTokens)
                        put("toolsOffered", allowed.map { it.name }.joinToString(","))
                        put("maxExchanges", MAX_EXCHANGES)
                        put("compactionThresholdChars", COMPACTION_THRESHOLD_CHARS)
                        put("recentMessagesKept", RECENT_MESSAGES_KEPT)
                        put("reasoningCaptureEnabled", true)
                    },
                    resolvedSystemPrompt = resolvedPrompt,
                    toolManifestSnapshotJson = jsonObject {
                        put("tools", allowed.map { it.name }.joinToString(","))
                    },
                    modelProvider = wiring.model.provider.id,
                    modelName = wiring.model.id,
                    reasoningCaptureEnabledAtStart = true,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } catch (e: Exception) {
            emit(
                failed(
                    agent.name,
                    "${agent.name} was not started because its run could not be recorded: " +
                        "${e.getRootCause().message ?: e.message}. An agent that runs without a record " +
                        "is work nobody can recover or explain, so the job was stopped instead."
                )
            )
            return@flow
        }

        val session = Session(
            id = runId,
            agent = agent,
            wiring = wiring,
            allowed = allowed,
            descriptors = allowed.map { GuruToolSchemaMapper.definitionToDescriptor(it) },
            systemPrompt = resolvedPrompt,
            messages = listOf(
                AiMessage.UserMessage(
                    uuid = Uuid.random().toString(),
                    content = job,
                    time = now
                )
            ),
            // THE LIMITS ARE PART OF WHAT THE RUN AGREED TO, so they are resolved here and carried
            // for the life of the session rather than re-read from a mutable agent row each round.
            limits = RunLimits.from(agent),
            startedAt = now,
        )
        sessions[session.id] = session

        /*
         * THE CONVERSATION OPENS WITH THE BRIEF, AND IT IS MARKED PROCESSED.
         *
         * The run row already carries the assignment and is the authoritative copy. This is the
         * conversation's opening message, so a room that shows the exchange has a first line
         * rather than beginning mid-thread. It is marked processed immediately because the agent
         * was given it as its first turn when the session was constructed; leaving it queued would
         * make the loop inject the job a second time on its very first round.
         */
        runCatching {
            val opening = broker.send(
                session.id,
                AgentInteractionBroker.Outgoing(
                    authorType = com.unuslumen.app.database.entity.AgentMessageEntity.AUTHOR_GURU,
                    recipientType = com.unuslumen.app.database.entity.AgentMessageEntity.RECIPIENT_AGENT,
                    recipientId = agent.id,
                    messageType = com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_ASSIGNMENT,
                    content = job,
                ),
            )
            opening?.let { broker.markProcessed(it.messageId) }
        }

        // A SESSION THAT BEGAN SAYS SO. Until this existed the ledger could show a run
        // receiving model output with no record that a session was ever constructed, which
        // reads as work appearing from nowhere.
        runCatching {
            ledger.append(
                session.id,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.SESSION_CREATED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                    payload = jsonObject {
                        put("sessionId", session.id)
                        put("conversationId", owningConversation)
                        put("runtimeKind", "AGENT_RUNTIME")
                        put("toolsOffered", allowed.size)
                        put("messageCount", session.messages.size)
                    },
                )
            )
        }

        // TAKE THE LEASE, THEN SAY IT IS RUNNING. The lease is what makes a dead run
        // detectable: a process that vanishes leaves a stale heartbeat, and the recovery
        // scan finds it. Without this, a killed run is indistinguishable from one that is
        // thinking hard.
        runCatching {
            ledger.acquireLease(session.id, leaseOwner())
            ledger.appendStateChange(
                session.id,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_CREATED,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                "execution started",
            )
            /*
             * THE FIRST CHECKPOINT, BEFORE ANY MODEL CALL.
             *
             * Without it a run that dies during its very first request has a record of
             * having started and nothing to restore from, which is the one case where the
             * ledger exists and still cannot help. This captures the job, the resolved
             * prompt and the tool set, which is the whole of the state at this point.
             */
            ledger.commitCheckpoint(
                runId = session.id,
                reason = com.unuslumen.app.database.entity.AgentRunCheckpointEntity.REASON_RUN_CREATED,
                stateJson = checkpointState(session),
            )
        }

        // The foreground service holds this run so it survives the Portal being backgrounded.
        foreground(session.id, agent.name)

        emit(bounded(session, onEvent))
    }

    /**
     * Send a message to a live agent without waiting for it.
     *
     * THE DOOR THE USER NEEDED. Every other entry point blocks: `spawn` starts work and `resume`
     * hands over an answer and both wait for the result. Neither can be used while a run is in
     * flight, which is exactly when a person wants to say "no, the other one" or "here is the
     * thing you were missing".
     *
     * THE MESSAGE IS QUEUED, NOT INJECTED. The loop takes it at its next round boundary, so nothing
     * arrives in the middle of a tool call. The return value says whether it was accepted; whether
     * it has been read yet is the message's own delivery state, which the caller can watch.
     */
    suspend fun steer(
        runId: String,
        text: String,
        messageType: String = com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_INSTRUCTION,
        authorType: String = com.unuslumen.app.database.entity.AgentMessageEntity.AUTHOR_USER,
    ): Boolean {
        if (text.isBlank()) return false
        val session = sessions[runId]
        val sent = broker.send(
            runId,
            AgentInteractionBroker.Outgoing(
                authorType = authorType,
                recipientType = com.unuslumen.app.database.entity.AgentMessageEntity.RECIPIENT_AGENT,
                recipientId = session?.agent?.id,
                messageType = messageType,
                content = text.trim(),
            ),
        )
        // A BOOLEAN, NOT THE ROW. A caller needs to know the message was accepted; handing back an
        // entity would drag the database type across every module boundary that can reach this.
        return sent != null
    }

    /**
     * Hand a waiting agent the answer it asked for, and let it carry on.
     *
     * The answer is appended as a user message, which is exactly what it is from the
     * agent's point of view: something the assistant said to it. Nothing else about
     * the session changes, so it keeps its instructions, its tools and everything it
     * has already found.
     */
    fun resume(
        sessionId: String,
        answer: String,
        onEvent: ((String) -> Unit)? = null
    ) = flow {
        val id = sessionId.trim()
        val live = sessions[id]
        if (live == null) {
            /*
             * THE SESSION IS GONE, SO THE LEDGER ANSWERS.
             *
             * This used to be a dead end: "a session lasts while the app is running". That
             * sentence was true and it was the whole defect, because it meant a question
             * asked before a restart could never be answered and the work behind it was
             * lost. The checkpoint holds what the session held — messages, notebook,
             * counters, the pending question — so it can be rebuilt and carried on.
             *
             * A REFUSAL HERE IS A REAL ANSWER, NOT A FAILURE TO TRY. If there is no
             * checkpoint, or its hash does not match, or the recorded model no longer
             * resolves, the run stays interrupted with the reason named. Resuming under a
             * different model or against altered state would be worse than stopping.
             */
            val restored = restoreFromLedger(id)
            if (restored == null) {
                emit(
                    failed(
                        "",
                        "No live session and no recoverable checkpoint for '$id'. The run either " +
                            "never reached a checkpoint or its state could not be verified, so it " +
                            "was not resumed. Start the job again with spawnAgent if the work is " +
                            "still wanted."
                    )
                )
                return@flow
            }
            if (answer.isBlank()) {
                sessions[restored.id] = restored
                emit(bounded(restored, onEvent, errorOverride = "The answer was empty, so the agent is still waiting."))
                return@flow
            }
            sessions[restored.id] = restored
            restored.pendingQuestion = null
            restored.messages = restored.messages + AiMessage.UserMessage(
                uuid = Uuid.random().toString(),
                content = answer.trim(),
                time = System.currentTimeMillis()
            )
            recordResume(restored.id, answer.trim())
            // A RESUMED RUN IS A LIVE RUN AND GETS THE SAME FOREGROUND TREATMENT.
            // The earlier version started the service from spawn only, so a resumed run
            // carried on with no notification and no foreground protection: the process
            // could be reaped mid-turn while the person saw nothing at all.
            foreground(restored.id, restored.agent.name)
            emit(bounded(restored, onEvent))
            return@flow
        }

        if (answer.isBlank()) {
            emit(bounded(live, onEvent, errorOverride = "The answer was empty, so the agent is still waiting."))
            return@flow
        }

        live.pendingQuestion = null
        live.messages = live.messages + AiMessage.UserMessage(
            uuid = Uuid.random().toString(),
            content = answer.trim(),
            time = System.currentTimeMillis()
        )
        recordResume(live.id, answer.trim())
        foreground(live.id, live.agent.name)
        emit(bounded(live, onEvent))
    }

    /**
     * Put a run in the foreground, so it survives the Portal being backgrounded.
     *
     * FAILING TO START THE SERVICE IS NOT FATAL. The work is already running on
     * [AgentRunHost]; a service that will not start costs the notification and the
     * process protection, not the execution. Logged rather than thrown so a device that
     * refuses a foreground start does not also lose the turn.
     */
    /**
     * The telemetry sink for one run.
     *
     * BUILT PER CALL RATHER THAN HELD, and cheap because it is: the object carries a run id
     * and two references that already exist. Held state would be wrong here for the same
     * reason a `@Volatile` conversation was wrong: two runs dispatching at once would share
     * one sink and the last writer would decide whose history the other's permission
     * decision landed in.
     *
     * THE SCOPE IS SHARED AND APPLICATION-LIFETIME, not per sink. A scope created per call
     * would never be cancelled and would accumulate one leak per tool invocation. One scope
     * for all telemetry writes costs nothing and cannot leak.
     */
    private fun runTelemetry(runId: String): AgentRunToolTelemetry =
        AgentRunToolTelemetry(runId = runId, ledger = ledger, scope = telemetryScope)

    private fun foreground(runId: String, agentName: String) {
        runCatching { AgentRunService.start(context, runId, agentName) }
            .onFailure {
                android.util.Log.w("guru_agents", "AgentRunService did not start: ${it.message}")
            }
    }

    /**
     * Rebuild a session from its newest checkpoint.
     *
     * Returns null when the run cannot be resumed honestly, and the reasons are all
     * refusals rather than fallbacks: no checkpoint, an unreadable one, a hash that does
     * not match, or a provider that is no longer connected. Each of those means the work
     * cannot continue as it was, and continuing as something else is worse.
     */
    private suspend fun restoreFromLedger(runId: String): Session? {
        val run = runCatching { ledger.run(runId) }.getOrNull() ?: return null
        if (run.status in com.unuslumen.app.database.entity.AgentRunEntity.TERMINAL_STATUSES) return null
        val checkpoint = runCatching { ledger.latestCheckpoint(runId) }.getOrNull() ?: return null
        when (val verdict = ledger.verifyCheckpoint(checkpoint)) {
            is AgentRunLedger.CheckpointVerdict.Unreadable -> {
                android.util.Log.w("guru_agents", "Run '$runId' not resumed: ${verdict.reason}")
                return null
            }
            AgentRunLedger.CheckpointVerdict.Verified -> Unit
        }

        // THE SAME MODEL OR NONE. A resume under whatever happens to be connected would
        // execute an old assignment with a new model, which nobody decided.
        val wiring = wiring() ?: return null
        if (wiring.model.id != run.modelName) {
            android.util.Log.w(
                "guru_agents",
                "Run '$runId' not resumed: it ran on '${run.modelName}' and this build has '${wiring.model.id}'"
            )
            return null
        }

        val agent = runCatching { agentRepository.resolveAgent(run.agentSlug.ifBlank { run.agentName }) }
            .getOrNull() ?: return null
        val allowed = allowedTools(agent)

        /*
         * THE STATE IS SEALED AT REST, SO IT IS OPENED HERE, AND A REFUSAL IS A REFUSAL.
         *
         * A locked vault means the key is not available yet, so the run waits rather than
         * resuming against a hole. A tampered state means somebody altered the record, so
         * it must never be resumed at all. Both are returns of null with the reason logged,
         * which leaves the run INTERRUPTED where a person can see it.
         */
        val state = when (val opened = ledger.openCheckpointState(checkpoint)) {
            is com.unuslumen.app.domain.security.ProtectedRead.Value -> opened.plaintext
            else -> {
                android.util.Log.w(
                    "guru_agents",
                    "Checkpoint for '$runId' could not be opened: ${opened::class.simpleName}"
                )
                return null
            }
        }

        val restoredMessages = decodeCheckpointMessages(state)
        if (restoredMessages.isEmpty()) return null

        val pending = readCheckpointField(state, "pendingQuestion")
        val rounds = readCheckpointField(state, "rounds")?.toIntOrNull() ?: 0
        val exchanges = readCheckpointField(state, "exchangeCount")?.toIntOrNull() ?: 0
        val notebook = readCheckpointField(state, "notebook").orEmpty()

        /*
         * A RESTORE SAYS SO, AND NAMES ITS BOUNDARY.
         *
         * The event carries the checkpoint it came from and the event sequence that
         * checkpoint already reflects. Without the boundary a reader cannot tell which
         * events were applied after the restore and which were already inside it, which is
         * the difference between a session and a session plus or minus some history.
         *
         * Written after every check has passed, so a refused restore leaves no event
         * claiming the work carried on.
         */
        runCatching {
            ledger.append(
                runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.SESSION_RESTORED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                    payload = jsonObject {
                        put("sessionId", runId)
                        put("checkpointId", checkpoint.checkpointId)
                        put("eventBoundary", checkpoint.eventSequence)
                        put("runtimeKind", "AGENT_RUNTIME")
                    },
                )
            )
        }

        return Session(
            id = runId,
            agent = agent,
            wiring = wiring,
            allowed = allowed,
            descriptors = allowed.map { GuruToolSchemaMapper.definitionToDescriptor(it) },
            systemPrompt = run.resolvedSystemPrompt,
            messages = restoredMessages,
            rounds = rounds,
            exchangeCount = exchanges,
            pendingQuestion = pending,
            notebook = notebook,
        )
    }

    /** Record a resume and reopen the run, so the ledger says the work carried on. */
    private suspend fun recordResume(runId: String, answer: String) {
        /*
         * THE ANSWER IS A MESSAGE TOO, WITH GURU AS ITS AUTHOR.
         *
         * The correlation is left null because the question it answers already carries the thread
         * id and the resume carries no reference back to it — the runtime answers one outstanding
         * question per run by construction. Naming an author is the part that matters: an answer
         * arriving in the conversation must not read as though the user said it.
         */
        runCatching {
            broker.send(
                runId,
                AgentInteractionBroker.Outgoing(
                    authorType = com.unuslumen.app.database.entity.AgentMessageEntity.AUTHOR_GURU,
                    recipientType = com.unuslumen.app.database.entity.AgentMessageEntity.RECIPIENT_AGENT,
                    messageType = com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_ANSWER,
                    content = answer,
                ),
            )?.let { broker.markProcessed(it.messageId) }
        }
        runCatching {
            ledger.append(
                runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.QUESTION_ANSWERED,
                    actorType = "GURU",
                    payload = jsonObject { put("answer", answer) },
                )
            )
            ledger.appendStateChange(
                runId,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_WAITING_FOR_GURU,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                "the answer arrived and the work carried on",
            )
            ledger.acquireLease(runId, leaseOwner())
        }
    }

    /**
     * Rebuild the message list from a checkpoint's serialised form.
     *
     * The form is the one [checkpointState] writes: a record separator between entries,
     * each entry prefixed with its role. A message that does not parse is dropped rather
     * than aborting the whole restore, because a partial session that can still answer is
     * better than a refusal over one malformed line — but a session that ends up empty is
     * refused, because continuing with no history is not a restore.
     */
    private fun decodeCheckpointMessages(stateJson: String): List<AiMessage> {
        val raw = readCheckpointField(stateJson, "messages") ?: return emptyList()
        return raw.split("\n\u001E\n").mapNotNull { entry ->
            when {
                entry.startsWith("user:") -> AiMessage.UserMessage(
                    uuid = Uuid.random().toString(),
                    content = entry.removePrefix("user:"),
                    time = System.currentTimeMillis(),
                )
                entry.startsWith("assistant:") -> AiMessage.AssistantMessage(
                    uuid = Uuid.random().toString(),
                    content = entry.removePrefix("assistant:"),
                    time = System.currentTimeMillis(),
                )
                entry.startsWith("tool:") -> {
                    // name:id:arguments:result
                    val parts = entry.removePrefix("tool:").split(":", limit = 4)
                    if (parts.size < 4) null else AiMessage.ToolCall(
                        uuid = Uuid.random().toString(),
                        id = parts[1].ifBlank { null },
                        name = parts[0],
                        rawContent = parts[2],
                        resultRawContent = parts[3],
                        time = System.currentTimeMillis(),
                    )
                }
                else -> null
            }
        }
    }

    /** Read one flat string field out of a checkpoint state document. */
    private fun readCheckpointField(json: String, name: String): String? = runCatching {
        val obj = Json.parseToJsonElement(json) as JsonObject
        (obj[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()

    /** Run one turn of a session, turning any throw into an honest failure. */
    private suspend fun bounded(
        session: Session,
        onEvent: ((String) -> Unit)?,
        errorOverride: String? = null,
    ): SpawnResult {
        if (errorOverride != null) {
            return SpawnResult(
                success = false,
                agentName = session.agent.name,
                report = "",
                toolRounds = session.rounds,
                sessionId = session.id,
                status = SpawnResult.Status.ASKING,
                question = session.pendingQuestion,
                error = errorOverride,
            )
        }
        return try {
            runTurn(session, onEvent)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A deliberate stop. Recorded as cancelled rather than failed, because a
            // person stopping their own work is not the work going wrong.
            runCatching {
                ledger.appendStateChange(
                    session.id,
                    com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                    com.unuslumen.app.database.entity.AgentRunEntity.STATUS_CANCELLED,
                    "cancelled by the caller",
                    finishedAt = System.currentTimeMillis(),
                )
                ledger.releaseLease(session.id, leaseOwner(), "cancelled")
            }
            throw e
        } catch (e: Exception) {
            sessions.remove(session.id)
            val reason = e.getRootCause().message ?: e.message ?: e::class.simpleName
            // THE RUN IS MARKED FAILED, NOT LEFT RUNNING. A run whose loop threw and whose
            // row still says RUNNING is a run the recovery scan will keep revisiting and a
            // person will keep seeing as alive.
            runCatching {
                ledger.appendStateChange(
                    session.id,
                    com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                    com.unuslumen.app.database.entity.AgentRunEntity.STATUS_FAILED,
                    "${session.agent.name} failed: $reason",
                    finishedAt = System.currentTimeMillis(),
                )
                ledger.releaseLease(session.id, leaseOwner(), "failed")
            }
            failed(session.agent.name, "${session.agent.name} failed: $reason")
        }
    }

    private fun failed(agentName: String, message: String) = SpawnResult(
        success = false,
        agentName = agentName,
        report = "",
        toolRounds = 0,
        status = SpawnResult.Status.FAILED,
        error = message,
    )

    /**
     * Run one turn of an agent's conversation.
     *
     * It ends in exactly one of three ways, and the caller must be able to tell
     * which: the model answered without asking for anything, so the job is done; the
     * model called [AgentToolDefinitions.ASK_ASSISTANT], so it is waiting on a person
     * through the assistant; or it threw, and the error says what happened.
     */
    private suspend fun runTurn(
        session: Session,
        onEvent: ((String) -> Unit)?
    ): SpawnResult {
        val agent = session.agent
        val allowed = session.allowed
        val descriptors = session.descriptors
        val systemPrompt = session.systemPrompt
        val wiring = session.wiring

        var messages = session.messages
        var rounds = session.rounds
        var lastText = ""
        var toolCallsUsed = 0

        while (rounds < session.limits.maxToolRounds) {
            rounds++
            session.rounds = rounds

            /*
             * THE WALL-CLOCK CEILING, CHECKED BEFORE A ROUND RATHER THAN DURING ONE.
             *
             * A limit that interrupts a model call would leave a request in flight with no result
             * and the ledger unable to say whether it completed, which is precisely the unknown
             * outcome the recovery rules exist to avoid. Checking at the boundary means the run
             * stops between rounds, with the previous round complete and the checkpoint current.
             *
             * IT PRESERVES THE WORK. The report carries whatever the agent had produced, the state
             * says LIMIT_REACHED rather than COMPLETED, and the partial findings survive in the
             * ledger. A limit is an honest stop, never a success.
             */
            val elapsed = System.currentTimeMillis() - session.startedAt
            val ceiling = session.limits.maxDurationMillis
            if (ceiling != null && elapsed >= ceiling) {
                sessions.remove(session.id)
                runCatching {
                    ledger.append(
                        session.id,
                        AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.RUN_STATE_CHANGED,
                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                            payload = jsonObject {
                                put("reason", "the run reached its time limit of ${ceiling}ms")
                                put("elapsedMillis", elapsed)
                            },
                        )
                    )
                    ledger.appendStateChange(
                        session.id,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_LIMIT_REACHED,
                        "the run reached its time limit of ${ceiling}ms after $elapsed",
                        finishedAt = System.currentTimeMillis(),
                    )
                    ledger.releaseLease(session.id, leaseOwner(), "limit reached")
                }
                return SpawnResult(
                    success = false,
                    agentName = agent.name,
                    report = lastText.take(MAX_REPORT_CHARS),
                    toolRounds = rounds,
                    sessionId = session.id,
                    status = SpawnResult.Status.FAILED,
                    error = "Reached its time limit of ${ceiling}ms. The work it had produced is " +
                        "above; anything not yet done is not done.",
                )
            }

            /*
             * THE TOOL-CALL CEILING, CHECKED BEFORE THE ROUND PROCEEDS.
             *
             * A per-run cap on invocations rather than on rounds, because one round can propose
             * many calls and the cost that matters is the calls. Checked here so a run stops with
             * the previous round's results recorded and nothing half-dispatched.
             */
            val callCeiling = session.limits.maxToolCalls
            if (callCeiling != null && toolCallsUsed >= callCeiling) {
                sessions.remove(session.id)
                runCatching {
                    ledger.appendStateChange(
                        session.id,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_LIMIT_REACHED,
                        "the run reached its limit of $callCeiling tool calls",
                        finishedAt = System.currentTimeMillis(),
                    )
                    ledger.releaseLease(session.id, leaseOwner(), "limit reached")
                }
                return SpawnResult(
                    success = false,
                    agentName = agent.name,
                    report = lastText.take(MAX_REPORT_CHARS),
                    toolRounds = rounds,
                    sessionId = session.id,
                    status = SpawnResult.Status.FAILED,
                    error = "Reached its limit of $callCeiling tool calls. The work it had produced " +
                        "is above; anything not yet done is not done.",
                )
            }

            /*
             * WHAT THE PERSON SAID WHILE THE AGENT WAS WORKING, TAKEN IN AT A SAFE BOUNDARY.
             *
             * THIS IS THE HALF OF THE THREE-WAY COLLABORATION THAT WAS MISSING. Until now the only
             * thing a person could do to a running agent was answer its own question: a correction,
             * a new priority or a piece of information had to wait for the run to end, which is
             * exactly when it is useless.
             *
             * IT IS READ HERE, BETWEEN ROUNDS, NOT DURING ONE. Nothing is in flight at this point,
             * so an instruction cannot arrive in the middle of a tool call and cannot be acted on
             * by half a round. Messages addressed to the agent that are still queued are appended
             * as user turns and marked processed, so the same instruction is never delivered twice
             * on the next round.
             *
             * THE ORIGINAL ASSIGNMENT IS UNTOUCHED. An amendment is a message with its own author
             * and time, and the run row's assignment stays as it was. That is what makes "why did
             * the work change direction" answerable later.
             */
            ingestPendingUserMessages(session)
            messages = session.messages

            // FOLD BEFORE THE CALL, NOT AFTER. The point is to keep the request small
            // enough to start quickly, so compacting after building the prompt would
            // leave the one slow call in place. The agent keeps working afterwards with
            // its notes in front of it and its recent turns intact.
            compactIfNeeded(session)
            messages = session.messages

            val prompt = messages.buildChatPrompt(
                systemMessage = systemPrompt,
                tools = descriptors
            )

            val textBuilder = StringBuilder()
            val toolCalls = mutableListOf<StreamFrame.ToolCallComplete>()
            /*
             * REASONING IS CAPTURED, NOT DISCARDED.
             *
             * The wire carries it: `UnusLumenStreamingClient` emits `ReasoningDelta` from
             * the model's `thinking` field, and the main chat already accumulates and
             * displays it. This loop used to fall into `else -> Unit`, which silently
             * threw away the one form of reasoning the connected model genuinely
             * provides. Capture and display are separate: this always records what came
             * back, and the Agent Room decides separately what it shows.
             *
             * The frames are collected in arrival order and flushed as one
             * `MODEL_OUTPUT_BATCH` per round, because a database write per chunk would
             * cost more than the conversation is worth. The provider's own stream index
             * is kept alongside, since it answers a different question from the ledger's
             * sequence.
             */
            val reasoningBuilder = StringBuilder()
            val outputFrames = mutableListOf<com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame>()
            val requestId = Uuid.random().toString()
            val roundIndex = rounds

            /**
             * How many frames this request actually produced.
             *
             * COUNTED AT ARRIVAL, NOT DERIVED LATER. `outputFrames` is drained by every
             * flush, so summing it after the fact would count only the last batch. It is
             * declared here rather than beside the buffer because the stream-end event reads
             * it, and that event is written before the buffer's own block closes.
             */
            var outputFrameCount = 0

            ledger.append(
                session.id,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.MODEL_REQUEST_STARTED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                    correlationId = requestId,
                    payload = jsonObject {
                        put("requestId", requestId)
                        put("provider", wiring.model.provider.id)
                        put("model", wiring.model.id)
                        put("roundIndex", roundIndex)
                        put("exchangeIndex", session.exchangeCount)
                    },
                )
            )

            // The same branch the main send path takes: the app's own client
            // when it exists (Lumen gateway, Ollama, OpenAI-compatible), the
            // koog brand executor otherwise (cloud BYO).
            val streamFlow = wiring.streamingClient?.executeStreamingWithTools(
                prompt = prompt,
                model = wiring.model,
                tools = descriptors,
                toolDefinitions = allowed,
                llmConfig = wiring.config
            ) ?: run {
                val koogExecutor = wiring.executor as? SingleLLMPromptExecutor
                    ?: throw IllegalStateException(
                        "No streaming path is available for the connected model."
                    )
                koogExecutor.executeStreaming(
                    prompt = prompt,
                    model = wiring.model,
                    tools = descriptors
                )
            }

            /*
             * THE REQUEST IS DECLARED BEFORE IT CAN FAIL.
             *
             * The collect below is where a provider, a proxy or a network can throw, and the
             * event that names WHICH request failed has to carry this id. Wrapping it means
             * the failure is recorded against the request that produced it rather than
             * against the run as a whole, which is the difference between "the agent failed"
             * and "round 3's request to this model failed with this reason".
             *
             * A CANCELLATION IS NOT A FAILURE. It is a person stopping their own work, so it
             * gets its own event and the loop rethrows it so the caller's cancellation
             * semantics are unchanged.
             */
            try {
            /*
             * FLUSH ON A BOUNDARY, NOT AT THE END.
             *
             * Waiting until the stream finishes means a long reply has one uncommitted
             * buffer for its whole duration, so a process death loses all of it rather
             * than the agreed final fragment. The boundary is 250 ms or 8 KiB, whichever
             * comes first, and a flush is forced before anything that follows: a tool
             * proposal, a completion, a checkpoint, a state change.
             *
             * The provider's own stream index is carried through. It answers a different
             * question from the ledger's sequence — which chunk of the wire this was — and
             * dropping it loses the ability to line a batch up against a provider log.
             */
            var pendingChars = 0
            var lastFlushAt = System.currentTimeMillis()

            suspend fun flushOutput(force: Boolean) {
                if (outputFrames.isEmpty()) return
                val elapsed = System.currentTimeMillis() - lastFlushAt
                val overSize = pendingChars >= OUTPUT_FLUSH_CHARS
                val overTime = elapsed >= OUTPUT_FLUSH_MILLIS
                if (!force && !overSize && !overTime) return

                val toWrite = outputFrames.toList()
                outputFrames.clear()
                pendingChars = 0
                lastFlushAt = System.currentTimeMillis()
                ledger.append(
                    session.id,
                    AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.MODEL_OUTPUT_BATCH,
                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                        correlationId = requestId,
                        payload = jsonObject {
                            put("requestId", requestId)
                            put("frames", com.unuslumen.app.domain.agents.OutputFrames.encode(toWrite))
                        },
                    )
                )
            }

            streamFlow.collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        textBuilder.append(frame.text)
                        pendingChars += frame.text.length
                        outputFrameCount++
                        outputFrames.add(outputFrame(com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame.Kind.TEXT, frame.text, frame.index))
                        flushOutput(force = false)
                    }
                    is StreamFrame.TextComplete -> {
                        // The complete form supersedes any deltas already
                        // gathered for this block, exactly as the main path
                        // treats it.
                        if (frame.text.length > textBuilder.length) {
                            textBuilder.clear()
                            textBuilder.append(frame.text)
                        }
                    }
                    is StreamFrame.ReasoningDelta -> {
                        // THE LINE THAT USED TO BE `else -> Unit`. Capture, always:
                        // visibility is a display concern and decides nothing here.
                        //
                        // The frame's text is nullable because a provider may send a
                        // reasoning frame carrying nothing but an index. `orEmpty()` keeps
                        // the frame in the batch as evidence that the channel was live,
                        // without inventing content for it.
                        val chunk = frame.text.orEmpty()
                        if (chunk.isNotEmpty()) reasoningBuilder.append(chunk)
                        pendingChars += chunk.length
                        outputFrameCount++
                        outputFrames.add(outputFrame(com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame.Kind.REASONING, chunk, frame.index))
                        flushOutput(force = false)
                    }
                    is StreamFrame.ToolCallComplete -> {
                        // A tool proposal is a boundary: nothing may be dispatched while
                        // output is still only in memory.
                        flushOutput(force = true)
                        toolCalls.add(frame)
                    }
                    is StreamFrame.ToolCallDelta -> Unit
                    is StreamFrame.ReasoningComplete -> flushOutput(force = true)
                    is StreamFrame.End -> flushOutput(force = true)
                }
            }

            // THE CLEAN PATH IS INSIDE THE TRY, so a throw cannot reach the flush below and
            // write a stream-end event for a stream that broke.
            // Whatever is left when the stream ends, then the canonical completions. Both
            // land before anything is dispatched, so a tool call always has a recorded
            // request behind it.
            flushOutput(force = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // DELIBERATE STOP. Its own event, and the exception is rethrown so the
                // caller's cancellation semantics are exactly what they were.
                runCatching {
                    ledger.append(
                        session.id,
                        AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REQUEST_INTERRUPTED,
                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                            correlationId = requestId,
                            payload = jsonObject {
                                put("requestId", requestId)
                                put("roundIndex", roundIndex)
                                put("reason", "the work was cancelled while this request was in flight")
                            },
                        )
                    )
                }
                throw e
            } catch (e: Exception) {
                /*
                 * THE REQUEST THAT FAILED, NAMED WITH ITS REASON.
                 *
                 * Before this, a model failure left only a RUN_STATE_CHANGED saying the run
                 * failed. The ledger could say a run died and could not say which request
                 * died or why, which is the question anyone reading it actually has.
                 *
                 * THE REASON IS BOUNDED. A provider exception can carry a stack trace or a
                 * multi-kilobyte body, and neither belongs in an event payload.
                 */
                runCatching {
                    ledger.append(
                        session.id,
                        AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REQUEST_FAILED,
                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                            correlationId = requestId,
                            payload = jsonObject {
                                put("requestId", requestId)
                                put("provider", wiring.model.provider.id)
                                put("model", wiring.model.id)
                                put("roundIndex", roundIndex)
                                put("failureKind", e::class.simpleName ?: "Exception")
                                put("reason", (e.getRootCause().message ?: e.message ?: "no message")
                                    .take(1_000))
                            },
                        )
                    )
                }
                throw e
            }

            /*
             * A STREAM THAT ENDED CLEANLY SAYS SO, WITH ITS COUNTS.
             *
             * THIS IS EMITTED HERE BECAUSE THE COLLECTOR COMPLETED, NOT BECAUSE A ROUND DID.
             * The `collect` above returns only when the flow finished without throwing, so
             * reaching this line IS the clean end. A throw skips it and is recorded as a
             * failure instead, which keeps the two facts apart: "the stream ended" and "the
             * stream broke" must never both be written for one request.
             *
             * The counts are the runtime's own - frames seen, characters of text, characters
             * of reasoning - so a reader can tell a reply that was empty from one that was
             * never delivered without parsing the batches.
             */
            runCatching {
                ledger.append(
                    session.id,
                    AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_STREAM_ENDED,
                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                        correlationId = requestId,
                        payload = jsonObject {
                            put("requestId", requestId)
                            put("frameCount", outputFrameCount)
                            put("textChars", textBuilder.length)
                            put("reasoningChars", reasoningBuilder.length)
                        },
                    )
                )
            }

            lastText = textBuilder.toString()
            if (textBuilder.isNotEmpty()) {
                ledger.append(
                    session.id,
                    AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.MODEL_TEXT_COMPLETED,
                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                        correlationId = requestId,
                        payload = jsonObject { put("requestId", requestId); put("text", textBuilder.toString()) },
                    )
                )
            }
            if (reasoningBuilder.isNotEmpty()) {
                ledger.append(
                    session.id,
                    AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.MODEL_REASONING_COMPLETED,
                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                        correlationId = requestId,
                        payload = jsonObject {
                            put("requestId", requestId)
                            put("reasoning", reasoningBuilder.toString())
                        },
                    )
                )
            }
            session.lastProposals = toolCalls.toList()

            // A QUESTION IS A PAUSE, NOT A TOOL CALL.
            //
            // This is checked before anything is dispatched, because askAssistant is
            // the one "tool" that must not run: its whole purpose is to stop the loop
            // and hand the question outward. If it were dispatched like an ordinary
            // tool it would return a result and the agent would carry on talking to a
            // wall, which is exactly the defect this build removes.
            val questionCall = toolCalls.firstOrNull { it.name == AgentToolDefinitions.ASK_ASSISTANT }
            if (questionCall != null) {
                val args = runCatching {
                    json.parseToJsonElement(questionCall.content) as? JsonObject
                }.getOrNull() ?: buildJsonObject { }
                val asked = (args["question"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val why = (args["why"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val options = (args["options"] as? JsonPrimitive)?.content
                    ?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    .orEmpty()

                if (asked.isEmpty()) {
                    // A question with no question in it. Tell the agent rather than
                    // passing an empty prompt to the assistant, and let it retry.
                    val repaired = AiMessage.ToolCall(
                        uuid = Uuid.random().toString(),
                        id = questionCall.id ?: Uuid.random().toString(),
                        name = AgentToolDefinitions.ASK_ASSISTANT,
                        rawContent = questionCall.content,
                        resultRawContent = """{"error":"askAssistant needs a 'question'. Ask one clear question, then wait."}""",
                        time = System.currentTimeMillis(),
                        isFailed = true,
                    )
                    messages = messages + repaired
                    session.messages = messages
                    continue
                }

                if (session.exchangesUsed() >= session.limits.maxExchanges) {
                    // Out of exchanges. Do not strand the work: tell it to finish.
                    val stopped = AiMessage.ToolCall(
                        uuid = Uuid.random().toString(),
                        id = questionCall.id ?: Uuid.random().toString(),
                        name = AgentToolDefinitions.ASK_ASSISTANT,
                        rawContent = questionCall.content,
                        resultRawContent = """{"error":"No more exchanges. Finish the job with what you already have, and say plainly what remains unknown."}""",
                        time = System.currentTimeMillis(),
                        isFailed = true,
                    )
                    messages = messages + stopped
                    session.messages = messages
                    continue
                }

                session.pendingQuestion = asked
                session.exchangeCount += 1
                onEvent?.invoke("${agent.name} is asking you something")

                // Record the ask in the session so a resumed conversation shows what
                // it asked, and so the transcript has the agent's own words in it.
                session.messages = messages + AiMessage.ToolCall(
                    uuid = Uuid.random().toString(),
                    id = questionCall.id ?: Uuid.random().toString(),
                    name = AgentToolDefinitions.ASK_ASSISTANT,
                    rawContent = questionCall.content,
                    resultRawContent = """{"status":"waiting","question":${jsonString(asked)}}""",
                    time = System.currentTimeMillis(),
                    isFailed = false,
                )

                /*
                 * THE QUESTION BECOMES A MESSAGE WITH AN AUTHOR AND A CORRELATION.
                 *
                 * Before this, the question existed only as a field on the return value and a
                 * ledger event, so the conversation had a hole where the exchange was. Now it is a
                 * real message from the agent, correlated to whatever will answer it, and a room
                 * showing the exchange has both halves.
                 */
                val questionThreadId = Uuid.random().toString()
                runCatching {
                    val asked_message = broker.send(
                        session.id,
                        AgentInteractionBroker.Outgoing(
                            authorType = com.unuslumen.app.database.entity.AgentMessageEntity.AUTHOR_AGENT,
                            authorId = agent.id,
                            recipientType = com.unuslumen.app.database.entity.AgentMessageEntity.RECIPIENT_GURU,
                            messageType = com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_QUESTION,
                            content = asked,
                            correlationId = questionThreadId,
                        ),
                    )
                    // It has been handed outward with the result, so it is delivered, not queued.
                    asked_message?.let { broker.markDelivered(it.messageId, session.id) }
                }

                // ASKING IS A RECORDED STATE, NOT A RETURN VALUE. The run row has to say
                // WAITING_FOR_GURU or a restart has no idea the work is paused on a
                // question, and the lease has to be released because nothing is driving
                // it: waiting is not dying.
                runCatching {
                    ledger.append(
                        session.id,
                        AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.QUESTION_OPENED,
                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                            actorId = agent.id,
                            payload = jsonObject {
                                put("question", asked)
                                put("why", why)
                                put("options", options.joinToString("|"))
                                put("expectedResponder", "GURU")
                            },
                        )
                    )
                    ledger.appendStateChange(
                        session.id,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_WAITING_FOR_GURU,
                        "the agent asked a question",
                    )
                    ledger.commitCheckpoint(
                        runId = session.id,
                        reason = com.unuslumen.app.database.entity.AgentRunCheckpointEntity.REASON_ENTERING_WAITING,
                        stateJson = checkpointState(session),
                    )
                    ledger.releaseLease(session.id, leaseOwner(), "waiting for an answer")
                }

                return SpawnResult(
                    success = true,
                    agentName = agent.name,
                    report = lastText.take(MAX_REPORT_CHARS),
                    toolRounds = rounds,
                    sessionId = session.id,
                    status = SpawnResult.Status.ASKING,
                    question = asked,
                    why = why.ifEmpty { null },
                    options = options,
                    compactions = session.compactions,
                )
            }

            if (toolCalls.isEmpty()) {
                sessions.remove(session.id)
                // FINISHED IS A RECORDED STATE TOO. The report goes on the run so a
                // reopened Agent Room shows what the work produced without having to
                // replay the whole ledger, and the row stops being non-terminal so the
                // recovery scan leaves it alone.
                runCatching {
                    ledger.append(
                        session.id,
                        AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.MODEL_REQUEST_COMPLETED,
                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                            actorId = agent.id,
                            payload = jsonObject {
                                put("rounds", rounds)
                                put("report", lastText.take(MAX_REPORT_CHARS))
                            },
                        )
                    )
                    ledger.appendStateChange(
                        session.id,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_COMPLETED,
                        "the agent finished its assignment",
                        finishedAt = System.currentTimeMillis(),
                    )
                    ledger.releaseLease(session.id, leaseOwner(), "completed")
                }
                return SpawnResult(
                    success = true,
                    agentName = agent.name,
                    report = lastText.take(MAX_REPORT_CHARS),
                    toolRounds = rounds,
                    sessionId = session.id,
                    status = SpawnResult.Status.FINISHED,
                )
            }

            onEvent?.invoke("${agent.name} is using ${toolCalls.size} tool(s), round $rounds")

            // ONE ROUND CHECKPOINT, BEFORE THE FIRST DISPATCH.
            //
            // Not one per call. The model state every call in this round shares is what
            // a resume needs, and it is identical for all of them; the per-call proof
            // that an attempt was made is the TOOL_CALL_STARTED row written below, which
            // is what stops recovery repeating a side effect it cannot prove did not
            // happen.
            if (toolCalls.isNotEmpty()) {
                ledger.commitCheckpoint(
                    runId = session.id,
                    reason = com.unuslumen.app.database.entity.AgentRunCheckpointEntity.REASON_ROUND_BOUNDARY,
                    stateJson = checkpointState(session),
                )
            }

            val executed = toolCalls.map { call ->
                val toolName = call.name
                val declared = allowed.firstOrNull { it.name == toolName }
                val invocationId = Uuid.random().toString()
                // Counted as it is dispatched, so the ceiling above is measured against calls that
                // were actually attempted rather than proposals the model made.
                toolCallsUsed++

                val result = if (declared == null) {
                    // A name outside the declaration. Refuse it here rather than trusting
                    // the model to stay inside its brief, and record the refusal as a
                    // refusal rather than as a tool that ran and failed.
                    ledger.append(
                        session.id,
                        AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_REFUSED,
                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                            actorId = agent.id,
                            payload = jsonObject {
                                put("invocationId", invocationId)
                                put("tool", toolName)
                                put("reason", "'${agent.name}' is not allowed to use '$toolName'")
                            },
                        )
                    )
                    RefusedResult(
                        """{"error":"Refused: '${agent.name}' is not allowed to use '$toolName'."}"""
                    )
                } else {
                    try {
                        val argsJson = runCatching {
                            json.parseToJsonElement(call.content) as? JsonObject
                        }.getOrNull() ?: buildJsonObject { }

                        // THE PROPOSAL IS RECORDED AS THE MODEL WROTE IT, and then the
                        // prepared boundary decides what will actually run.
                        ledger.append(
                            session.id,
                            AgentRunLedger.EventDraft(
                                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.MODEL_TOOL_CALL_PROPOSED,
                                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                                actorId = agent.id,
                                payload = jsonObject {
                                    put("tool", toolName)
                                    put("callId", call.id ?: "")
                                    put("arguments", call.content)
                                },
                            )
                        )

                        /*
                         * THE RUN AND THE INVOCATION TRAVEL WITH THE CALL, SO THE
                         * PERMISSION DECISION LANDS ON THE RIGHT EXECUTION.
                         *
                         * Without them the dispatcher's telemetry would have no run to
                         * attach to and would either write nothing or guess. They are also
                         * what pairs the decision to the invocation whose start and
                         * completion already exist, so a reader can line up all three.
                         */
                        /*
                         * ONE SINK FOR THIS CALL, SO THE DECISION AND THE PROGRESS IT
                         * REPORTS LAND ON THE SAME RUN.
                         *
                         * Built once here rather than twice below, because two sinks would
                         * be two objects that must agree about which execution they belong
                         * to, and the second one would eventually be the one that forgot.
                         */
                        val telemetryForThisCall = runTelemetry(session.id) as AgentRunToolTelemetry

                        when (val prepared = toolDispatcher.prepare(
                            toolName,
                            argsJson,
                            runId = session.id,
                            invocationId = invocationId,
                            telemetry = telemetryForThisCall,
                        )) {
                            is com.unuslumen.app.data.tools.registry.ToolDispatcher.Preparation.Refused -> {
                                ledger.append(
                                    session.id,
                                    AgentRunLedger.EventDraft(
                                        eventType = if (prepared.kind == com.unuslumen.app.data.tools.registry.ToolDispatcher.RefusalKind.PERMISSION_DENIED) {
                                            com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_PERMISSION_DENIED
                                        } else {
                                            com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_REFUSED
                                        },
                                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                                        actorId = agent.id,
                                        payload = jsonObject {
                                            put("invocationId", invocationId)
                                            put("tool", prepared.resolvedName ?: toolName)
                                            put("reason", prepared.reason)
                                            put("kind", prepared.kind.name)
                                        },
                                    )
                                )
                                RefusedResult("""{"error":${jsonString(prepared.reason)}}""")
                            }

                            is com.unuslumen.app.data.tools.registry.ToolDispatcher.Preparation.Ready -> {
                                // STARTED BEFORE THE TOOL RUNS. This row is the only proof
                                // an attempt was made, and it is why a resumed run refuses
                                // to repeat a call whose result never landed.
                                ledger.append(
                                    session.id,
                                    AgentRunLedger.EventDraft(
                                        eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED,
                                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                                        actorId = agent.id,
                                        payload = jsonObject {
                                            put("invocationId", invocationId)
                                            put("tool", prepared.invocation.resolvedName)
                                            // THE REPAIRED ARGUMENTS, not the model's. These
                                            // are what the tool receives, and a record of
                                            // anything else describes a call that did not run.
                                            put("arguments", prepared.invocation.repairedArgs.toString())
                                            put("proposedArguments", call.content)
                                        },
                                    )
                                )

                                /*
                                 * A REAL PROGRESS SINK, SO `TOOL_PROGRESS` HAS A PRODUCER.
                                 *
                                 * A tool that takes minutes has things worth saying while it
                                 * runs, and the executor's own channel already exists. Handing
                                 * it a sink that writes to the ledger means a network walk
                                 * reports what it has found so far instead of a spinner and
                                 * then one result.
                                 *
                                 * A TOOL THAT SAYS NOTHING PRODUCES NOTHING. The sink is
                                 * passive: it only writes what an executor chose to report,
                                 * so a silent tool leaves no fabricated ticks in the history.
                                 */
                                val progressSink = com.unuslumen.app.data.tools.registry.ToolProgressSink { event ->
                                    runCatching {
                                        telemetryForThisCall.progressFor(prepared.invocation.resolvedName, invocationId, event)
                                    }
                                }

                                val dispatch = toolDispatcher.executePrepared(
                                    invocation = prepared.invocation,
                                    conversationId = session.id,
                                    agentRunId = session.id,
                                    agentInvocationId = invocationId,
                                    progress = progressSink,
                                )

                                if (dispatch.success) {
                                    ledger.append(
                                        session.id,
                                        AgentRunLedger.EventDraft(
                                            eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_COMPLETED,
                                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_TOOL,
                                            actorId = prepared.invocation.resolvedName,
                                            payload = jsonObject {
                                                put("invocationId", invocationId)
                                                put("tool", prepared.invocation.resolvedName)
                                                // The complete content handed back to the
                                                // session. The tool_results table truncates
                                                // at 100k, so it cannot be the recovery source.
                                                put("result", dispatch.rawJson)
                                            },
                                        )
                                    )
                                    ExecutedResult(dispatch.rawJson)
                                } else {
                                    // A CONFIRMED FAILURE, because the executor returned
                                    // one. A throw or a timeout is a different fact and is
                                    // handled in the catch below as unknown, never as
                                    // failed.
                                    ledger.append(
                                        session.id,
                                        AgentRunLedger.EventDraft(
                                            eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_FAILED,
                                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_TOOL,
                                            actorId = prepared.invocation.resolvedName,
                                            payload = jsonObject {
                                                put("invocationId", invocationId)
                                                put("tool", prepared.invocation.resolvedName)
                                                put("error", dispatch.error ?: "tool failed")
                                            },
                                        )
                                    )
                                    ExecutedResult("""{"error":${jsonString(dispatch.error ?: "tool failed")}}""")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // An exception out of the dispatcher does NOT prove the call did
                        // not run: it may have reached the device and lost its reply. That
                        // is an unknown outcome, and it must never be retried automatically.
                        ledger.append(
                            session.id,
                            AgentRunLedger.EventDraft(
                                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN,
                                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                                actorId = agent.id,
                                payload = jsonObject {
                                    put("invocationId", invocationId)
                                    put("tool", toolName)
                                    put("reason", e.getRootCause().message ?: "the call threw before a result arrived")
                                },
                            )
                        )
                        ExecutedResult("""{"error":${jsonString(e.getRootCause().message ?: "tool failed")}}""")
                    }
                }

                AiMessage.ToolCall(
                    uuid = Uuid.random().toString(),
                    id = call.id ?: Uuid.random().toString(),
                    name = toolName,
                    rawContent = call.content,
                    resultRawContent = result.content,
                    time = System.currentTimeMillis(),
                    isFailed = result.content.contains("\"error\"")
                )
            }

            messages = messages + executed
            session.messages = messages
        }

        return failed(
            agent.name,
            "${agent.name} stopped without answering or asking, which should not be possible. " +
                "The job did not complete.",
        )
    }

    /**
     * Turn anything the person has said into part of the agent's working context.
     *
     * THE MESSAGE ROW IS THE SOURCE OF TRUTH, NOT THE SESSION. Reading the session would only ever
     * find what this loop already knows; reading the broker finds what was written while the loop
     * was busy, which is the whole point.
     *
     * EACH MESSAGE IS MARKED PROCESSED IMMEDIATELY AFTER IT IS APPENDED. If the process dies between
     * the two, the message is delivered twice on the next resume — which is the safe direction for
     * an instruction, because repeating an instruction is recoverable and silently dropping one is
     * not. A tool call is the opposite case and is handled by the unknown-outcome rule instead.
     *
     * A FAILURE HERE DOES NOT KILL THE RUN. If the broker cannot be read, the agent carries on with
     * the context it has rather than dying on a database wobble, and the message stays queued for
     * the next boundary.
     */
    private suspend fun ingestPendingUserMessages(session: Session) {
        val pending = runCatching {
            broker.pendingFor(session.id, com.unuslumen.app.database.entity.AgentMessageEntity.RECIPIENT_AGENT)
        }.getOrNull().orEmpty()

        if (pending.isEmpty()) return

        val additions = mutableListOf<AiMessage>()
        for (message in pending) {
            /*
             * ONLY THE PERSON'S OWN WORDS BECOME TURNS. An answer to a question travels through
             * resumeAgent and is already in the session; re-injecting it here would make the agent
             * read the same answer twice and infer that two separate answers had been given.
             */
            if (message.messageType == com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_ANSWER) {
                runCatching { broker.markProcessed(message.messageId) }
                continue
            }

            val framing = when (message.messageType) {
                com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_ASSIGNMENT_AMENDMENT ->
                    "THE PERSON AMENDED YOUR ASSIGNMENT. Your original brief still stands except " +
                        "where this changes it. Take it into account from now on:\n\n"
                com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_PROGRESS_REQUEST ->
                    "THE PERSON ASKED FOR PROGRESS. Answer this in your next reply:\n\n"
                else -> "THE PERSON SAID, WHILE YOU WERE WORKING:\n\n"
            }

            additions += AiMessage.UserMessage(
                uuid = Uuid.random().toString(),
                content = framing + message.content,
                time = System.currentTimeMillis(),
            )
            runCatching { broker.markProcessed(message.messageId) }
        }

        if (additions.isEmpty()) return
        session.messages = session.messages + additions
    }

    /**
     * What this device and this build can actually provide, for the validator.
     *
     * MEASURED FROM THE REGISTRY AND THE CONNECTED MODEL, NEVER ASSUMED. The tool set comes from
     * the registry that will dispatch the calls, so a manifest requiring something absent is
     * caught against the same list the dispatcher would refuse from. The reasoning-channel flag
     * comes from whether the connected provider actually emits reasoning frames: the app's own
     * streaming client reads the wire's `thinking` field and emits `ReasoningDelta`, and the koog
     * brand clients do not, so this reports the truth per wiring rather than promising it always.
     *
     * SKILLS AND INTEGRATIONS ARE EMPTY TODAY AND THAT IS HONEST. No skill registry lookup and no
     * integration registry exist for an agent to consult, so a manifest requiring one is told it
     * is absent rather than being told it is present and then failing later. When those registries
     * exist, this is the one method that changes.
     */
    private suspend fun runtimeCapabilities(): com.unuslumen.app.domain.model.AgentConfigurationValidator.RuntimeCapabilities {
        val provider = getPreferenceUseCase(
            intPreferencesKey(PrefsConstants.AI_PROVIDER_KEY),
            AiProvider.None.id
        ).first().toAiProvider()

        /*
         * WHICH WIRE IS CONNECTED DECIDES THE REASONING ANSWER.
         *
         * The app's own client is used for the Lumen gateway and for Ollama or OpenAI-compatible
         * endpoints, and it is the one that reads `thinking` off the wire. The cloud brand clients
         * go through their own koog executors, which do not surface a reasoning channel here. So a
         * BYO Anthropic, Google or OpenAI agent is told honestly that raw reasoning will be
         * unavailable rather than being drawn an empty panel with no explanation.
         */
        val usesAppClient = provider == AiProvider.UnusLumen ||
            provider == AiProvider.Ollama ||
            provider == AiProvider.OpenAICompat

        return com.unuslumen.app.domain.model.AgentConfigurationValidator.RuntimeCapabilities(
            availableTools = toolRegistry.getAllDefinitions().map { it.name }.toSet(),
            // No skill registry is reachable from here yet. An agent declaring one is told it is
            // missing rather than being told it is present.
            availableSkills = emptySet(),
            availableIntegrations = emptySet(),
            supportsReasoningChannel = usesAppClient,
            supportsToolCalling = true,
            supportsStreaming = true,
            runtimeSchemaVersion = com.unuslumen.app.database.guruDatabase.SCHEMA_VERSION,
            modelUnavailableReason = null,
        )
    }

    /**
     * The tool set this agent may use: its own declaration, filtered to what exists
     * in the registry and what no agent may ever call, plus [ASK_ASSISTANT].
     *
     * An agent that declares nothing still gets the question tool, because a blank
     * brief should produce a question rather than a guess.
     */
    fun allowedTools(agent: GuruAgent): List<ToolDefinition> {
        // THE QUESTION TOOL IS ALWAYS OFFERED. Every spawned agent can stop and ask
        // the assistant something, whatever its own definition declares, because the
        // alternative is an agent that guesses or gives up when it hits a gap. It is
        // added here rather than listed in every agent's permissions so no agent
        // arrives without it and nobody has to remember to grant it.
        val ask = toolRegistry.getDefinition(AgentToolDefinitions.ASK_ASSISTANT)
        val declared = agent.toolPermissions
            .filter { it.isNotBlank() }
            .filter { it !in NEVER_SPAWNABLE }
            .mapNotNull { name -> toolRegistry.getDefinition(name) }
        return if (ask == null) declared else listOf(ask) + declared.filter { it.name != ask.name }
    }

    /**
     * Fold the older half of a conversation into the notebook.
     *
     * THE SHAPE. Keep the first message, because that is the job and losing it loses
     * the brief. Keep the last [RECENT_MESSAGES_KEPT] messages whole, because that is
     * what the agent is working on right now. Everything between them becomes prose in
     * the notebook: what was asked, what was done, what came back, and what it means.
     *
     * WHY IT IS MECHANICAL RATHER THAN A MODEL SUMMARY. A summariser call is another
     * slow round trip through the same proxy that is already killing long requests,
     * and it costs money on every fold. The raw material here is already structured:
     * an agent turn is a tool name, its arguments and its result. Writing those down as
     * lines is deterministic, cannot hallucinate, and is exactly what a person would
     * write in a notebook. The agent reads its own notes, which is enough to carry on.
     *
     * Nothing is lost that mattered. A tool result is quoted up to
     * [NOTEBOOK_EXCERPT_CHARS]; the agent can re-run a tool if it needs the whole
     * payload again, and the note says what it was so it knows to.
     */
    /**
     * Suspend, because a fold is now a recorded fact rather than a private mutation.
     *
     * The write is the whole point: a fold that changed the conversation and left no trace
     * made every later discontinuity a mystery. Callers already sit in a suspend context.
     */
    private suspend fun compactIfNeeded(session: Session) {
        val total = session.messages.sumOf { messageWeight(it) }
        if (total < COMPACTION_THRESHOLD_CHARS) return
        if (session.messages.size <= RECENT_MESSAGES_KEPT + 2) return

        /*
         * WHAT THE FOLD IS ABOUT TO DO, CAPTURED BEFORE IT DOES IT.
         *
         * The event reports the message count before the fold and the notes produced by it,
         * so a reader can tell a fold that removed a thousand turns from one that removed
         * three. Reading those after the mutation would report the state the fold created
         * rather than the state it replaced.
         */
        val before = session.messages.size
        val notesBefore = session.notebook.length

        val first = session.messages.first()
        val tail = session.messages.takeLast(RECENT_MESSAGES_KEPT)
        val middle = session.messages.drop(1).dropLast(RECENT_MESSAGES_KEPT)

        val written = StringBuilder(session.notebook)
        if (written.isNotEmpty()) written.append('\n')
        written.append("— folded at round ").append(session.rounds).append(" —\n")

        for (message in middle) {
            when (message) {
                is AiMessage.UserMessage -> {
                    written.append("INSTRUCTION: ")
                        .append(message.content.take(NOTEBOOK_EXCERPT_CHARS).replace('\n', ' '))
                        .append('\n')
                }
                is AiMessage.ToolCall -> {
                    val failed = if (message.isFailed) " (FAILED)" else ""
                    written.append("RAN ").append(message.name).append(failed).append(": ")
                        .append(message.rawContent.take(NOTEBOOK_EXCERPT_CHARS).replace('\n', ' '))
                        .append('\n')
                    val result = message.resultRawContent
                    if (result.isNotBlank()) {
                        written.append("  RETURNED: ")
                            .append(result.take(NOTEBOOK_EXCERPT_CHARS).replace('\n', ' '))
                            .append('\n')
                    }
                }
                is AiMessage.AssistantMessage -> {
                    if (message.content.isNotBlank()) {
                        written.append("SAID: ")
                            .append(message.content.take(NOTEBOOK_EXCERPT_CHARS).replace('\n', ' '))
                            .append('\n')
                    }
                }
                else -> Unit
            }
        }

        session.notebook = written.toString()
        session.compactions += 1

        // The notebook rides as a system-role note at the head of the conversation, so
        // it is read on every round without pretending to be something the person said.
        session.messages = listOf(first) + notebookMessage(session.notebook) + tail

        /*
         * A FOLD THAT HAPPENED SAYS SO.
         *
         * THIS WAS INVISIBLE FOR THE WHOLE OF PHASE 3. The runtime folded its history,
         * dropped most of the conversation and carried on, and the ledger recorded nothing,
         * so a session that had lost a thousand turns looked identical to one that had
         * never folded. Anyone reading a run afterwards could not tell why the agent had
         * stopped referring to work it plainly did.
         *
         * It is written even though it is not a rebuild input, because it is the only
         * explanation for a discontinuity in the conversation that is otherwise a mystery.
         * The reducer ignores it by declared policy, not by falling through.
         */
        runCatching {
            ledger.append(
                session.id,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.SESSION_COMPACTED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                    payload = jsonObject {
                        put("sessionId", session.id)
                        put("reason", "the conversation passed $COMPACTION_THRESHOLD_CHARS characters, " +
                            "so the older turns were folded into the notebook")
                        put("messagesBefore", before)
                        put("messagesAfter", session.messages.size)
                        put("notesBefore", notesBefore)
                        put("notesAfter", session.notebook.length)
                        put("roundIndex", session.rounds)
                        put("compactionCount", session.compactions)
                        // The notebook whole, so replay can restore without reproducing the
                        // folding algorithm.
                        put("notebook", session.notebook)
                    },
                )
            )
        }
    }

    /** The notebook as a message the model reads before the recent turns. */
    private fun notebookMessage(notebook: String): AiMessage.AssistantMessage =
        AiMessage.AssistantMessage(
            uuid = Uuid.random().toString(),
            content = "YOUR NOTES SO FAR, written as you went. Everything here already " +
                "happened and you did it; do not repeat it. Carry on from the recent " +
                "messages below.\n\n$notebook",
            time = System.currentTimeMillis(),
        )

    /** Rough weight of one message in characters, which is what prefill cost follows. */
    private fun messageWeight(message: AiMessage): Int = when (message) {
        is AiMessage.UserMessage -> message.content.length
        is AiMessage.AssistantMessage -> message.content.length
        is AiMessage.ToolCall ->
            message.name.length + message.rawContent.length + message.resultRawContent.length
        is AiMessage.StreamingAssistant -> message.partialContent.length
        is AiMessage.StreamingToolCall -> message.partialContent.length
        is AiMessage.PortalMessage -> message.html.length + message.css.length + message.js.length
    }

    private fun buildSystemPrompt(agent: GuruAgent, allowed: List<ToolDefinition>): String {
        val toolLine = if (allowed.isEmpty()) {
            "You have no tools. Reason about the job and answer with what you know."
        } else {
            "You may use only these tools: ${allowed.joinToString(", ") { it.name }}."
        }
        return buildString {
            appendLine(agent.systemPrompt.trim())
            appendLine()
            appendLine("YOU ARE A SPAWNED AGENT, NOT THE MAIN ASSISTANT.")
            appendLine("You were handed one job. Complete it and answer with a report.")
            appendLine("Your report is read by the assistant that called you, not by a person.")
            appendLine("Say what you did, what you found, and anything that blocked you.")
            // It has to know it can talk, or it will guess or give up instead.
            if (allowed.any { it.name == AgentToolDefinitions.ASK_ASSISTANT }) {
                appendLine(
                    "YOU CAN ASK BEFORE YOU GUESS. If you genuinely cannot finish without " +
                        "knowing something, call askAssistant with one clear question and stop. " +
                        "You will be paused and the assistant will answer, then you carry on with " +
                        "everything you had already found. Ask only when the answer changes what " +
                        "you do, and never to ask permission for work you were already given."
                )
            }
            // It has to know the notebook exists, or a folded conversation reads to it
            // as a conversation that has lost its beginning.
            appendLine(
                "WHEN THE WORK RUNS LONG, YOUR OLDER TURNS ARE FOLDED INTO NOTES. You will " +
                    "see a message headed YOUR NOTES SO FAR. It is your own record of what " +
                    "you already did, in order, and it is trustworthy. Read it, do not " +
                    "repeat what it says you already did, and carry on. Nothing you " +
                    "discovered is lost; the note says which tool returned what."
            )
            appendLine(toolLine)
            if (agent.description.isNotBlank()) {
                appendLine("Your role: ${agent.description}")
            }
        }
    }

    /**
     * Build the wiring from current device settings, mirroring the main send
     * path's two providers:
     *
     * - UnusLumen reads its model name and wire options from the server config.
     * - A BYO provider builds its executor and model from the device preferences,
     *   and only Ollama / OpenAI-compatible get the app's own streaming client;
     *   cloud brands stream through their koog executor.
     *
     * Nothing here invents a base URL or a header. It reads what the send path
     * reads and builds what the send path builds.
     */
    private suspend fun wiring(): Wiring? {
        val provider = getPreferenceUseCase(
            intPreferencesKey(PrefsConstants.AI_PROVIDER_KEY),
            AiProvider.None.id
        ).first().toAiProvider()

        if (provider == AiProvider.None) return null

        return if (provider == AiProvider.UnusLumen) {
            LlmConfigFetcher.fetchAndCache(context)
            val config = LlmConfigFetcher.getCachedConfig(context) ?: return null
            if (config.baseModel.isBlank()) return null

            val model = config.baseModel.toLLModel(
                provider,
                withTools = true,
                contextWindow = config.contextWindow.toLong(),
                maxOutputTokens = config.maxTokens.toLong()
            )
            val baseUrl = "https://api.unuslumen.com/"
            Wiring(
                model = model,
                executor = SingleLLMPromptExecutor(
                    com.unuslumen.app.data.UnusLumenClient(
                        baseUrl,
                        baseClient = TorEgress.httpClient(),
                        timeoutConfig = NO_TIMEOUTS
                    )
                ),
                streamingClient = UnusLumenStreamingClient(
                    baseUrl.trimEnd('/') + "/",
                    TorEgress.httpClient()
                ),
                config = config
            )
        } else {
            byoWiring(provider)
        }
    }

    /** The BYO half, matching initializeByoProvider's decisions without duplicating its side effects. */
    private suspend fun byoWiring(provider: AiProvider): Wiring? {
        val byoKey = getPreferenceUseCase(
            stringPreferencesKey(PrefsConstants.BYO_API_KEY_KEY), ""
        ).first().trim()
        val byoUrl = getPreferenceUseCase(
            stringPreferencesKey(PrefsConstants.BYO_BASE_URL_KEY), ""
        ).first().trim()
        val byoModel = getPreferenceUseCase(
            stringPreferencesKey(PrefsConstants.BYO_MODEL_NAME_KEY), ""
        ).first().trim()

        if (byoModel.isBlank()) return null
        val needsUrl = provider == AiProvider.Ollama || provider == AiProvider.OpenAICompat
        if (needsUrl && byoUrl.isBlank()) return null

        val config = LlmConfig(baseModel = byoModel)

        return try {
            val model = when (provider) {
                AiProvider.Anthropic ->
                    ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Sonnet_4_5.copy(
                        id = byoModel,
                        provider = provider.toLLMProvider()
                    )
                else -> byoModel.toLLModel(
                    provider,
                    withTools = true,
                    contextWindow = 1_000_000L,
                    maxOutputTokens = 131_072L
                )
            }

            val executor: PromptExecutor = when (provider) {
                AiProvider.OpenAI, AiProvider.XAI -> SingleLLMPromptExecutor(
                    ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
                        byoKey,
                        ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                            baseUrl = provider.fixedBaseUrl()!!
                        ),
                        baseClient = TorEgress.httpClient()
                    )
                )
                AiProvider.Anthropic -> SingleLLMPromptExecutor(
                    ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient(
                        apiKey = byoKey,
                        baseClient = TorEgress.httpClient()
                    )
                )
                AiProvider.Google -> SingleLLMPromptExecutor(
                    ai.koog.prompt.executor.clients.google.GoogleLLMClient(
                        apiKey = byoKey,
                        baseClient = TorEgress.httpClient()
                    )
                )
                AiProvider.Ollama -> SingleLLMPromptExecutor(
                    com.unuslumen.app.data.UnusLumenClient(
                        byoUrl.trimEnd('/') + "/",
                        baseClient = TorEgress.httpClient(),
                        timeoutConfig = NO_TIMEOUTS
                    )
                )
                AiProvider.OpenAICompat -> {
                    val trimmed = byoUrl.trimEnd('/')
                    val base = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
                    SingleLLMPromptExecutor(
                        ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
                            apiKey = byoKey.ifBlank { "no-key" },
                            settings = ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                                baseUrl = base
                            ),
                            baseClient = TorEgress.httpClient()
                        )
                    )
                }
                else -> return null
            }

            val streaming = if (needsUrl) {
                val effective = if (byoUrl.endsWith('/')) byoUrl else "$byoUrl/"
                UnusLumenStreamingClient(
                    effective,
                    TorEgress.httpClient(),
                    byoApiKey = byoKey.ifBlank { null }
                )
            } else null

            Wiring(
                model = model,
                executor = executor,
                streamingClient = streaming,
                config = config
            )
        } catch (e: Exception) {
            android.util.Log.w("guru", "Agent BYO wiring failed for $provider: ${e.message}")
            null
        }
    }

    /**
     * One captured frame of model output, taken straight from the domain type.
     *
     * NO LOCAL COPY AND NO TYPEALIAS. A second class with the same shape would drift the
     * moment either side gained a field, and a `typealias` to a nested class crashes KSP
     * on this toolchain — a trap already paid for once in this codebase. The full name is
     * written at each site so the writer and the reducer are provably the same type.
     */
    private fun outputFrame(
        kind: com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame.Kind,
        text: String,
        sourceIndex: Int?,
    ) = com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame(kind, text, sourceIndex)

    /** A result the loop produced, before it is turned into a message. */
    private sealed interface ToolOutcome { val content: String }
    private data class ExecutedResult(override val content: String) : ToolOutcome
    private data class RefusedResult(override val content: String) : ToolOutcome

    /** A JSON object built from key/value pairs, with the string escaping this file already uses. */
    private fun jsonObject(build: JsonObjectBuilderScope.() -> Unit): String {
        val map = linkedMapOf<String, Any?>()
        JsonObjectBuilderScope(map).build()
        return buildString {
            append('{')
            var first = true
            for ((key, value) in map) {
                if (!first) append(',')
                first = false
                append(jsonString(key)).append(':')
                when (value) {
                    null -> append("null")
                    is Number, is Boolean -> append(value.toString())
                    else -> append(jsonString(value.toString()))
                }
            }
            append('}')
        }
    }

    private class JsonObjectBuilderScope(private val map: MutableMap<String, Any?>) {
        fun put(key: String, value: String?) { map[key] = value }
        fun put(key: String, value: Int) { map[key] = value }
        fun put(key: String, value: Long) { map[key] = value }
        fun put(key: String, value: Boolean) { map[key] = value }
    }

    /** The definition as values, not a reference, so a later edit cannot rewrite history. */
    private fun definitionSnapshot(agent: GuruAgent): String = jsonObject {
        put("schemaVersion", 1)
        put("id", agent.id)
        put("slug", agent.slug)
        put("name", agent.name)
        put("description", agent.description)
        put("version", agent.version)
        put("systemPrompt", agent.systemPrompt)
        put("toolPermissions", agent.toolPermissions.joinToString(","))
        put("source", agent.source)
        put("enabled", agent.enabled)
    }

    /**
     * The working state, as the checkpoint stores it.
     *
     * It carries what the in-memory session carries and nothing it does not: the exact
     * messages, the notebook, the counters, the question it is waiting on, the tools it
     * was given. Live objects are deliberately absent, because an executor cannot be
     * serialised and pretending otherwise would produce a checkpoint that looks complete
     * and restores nothing.
     */
    private fun checkpointState(session: Session): String = jsonObject {
        put("schemaVersion", 1)
        put("runId", session.id)
        put("rounds", session.rounds)
        put("exchangeCount", session.exchangeCount)
        put("pendingQuestion", session.pendingQuestion)
        put("notebook", session.notebook)
        put("compactionCount", session.compactions)
        put("systemPrompt", session.systemPrompt)
        put("messageCount", session.messages.size)
        put("messages", session.messages.joinToString("\n\u001E\n") { m ->
            when (m) {
                is AiMessage.UserMessage -> "user:${m.content}"
                is AiMessage.AssistantMessage -> "assistant:${m.content}"
                is AiMessage.ToolCall ->
                    "tool:${m.name}:${m.id ?: ""}:${m.rawContent}:${m.resultRawContent}"
                else -> "other:"
            }
        })
        put(
            "runtime",
            jsonObject {
                put("kind", "AGENT_RUNTIME")
                put("stateSchemaVersion", 1)
            }
        )
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(String.format("%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
