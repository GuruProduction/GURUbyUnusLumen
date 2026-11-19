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
    private val context: android.content.Context
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

        private val NO_TIMEOUTS = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(
            requestTimeoutMillis = Long.MAX_VALUE,
            connectTimeoutMillis = Long.MAX_VALUE,
            socketTimeoutMillis = Long.MAX_VALUE
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

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
        var compactions: Int = 0
    ) {
        /** Exchanges this session has already spent asking the assistant something. */
        fun exchangesUsed(): Int = exchangeCount
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
        onEvent: ((String) -> Unit)? = null
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

        val wiring = wiring()
        if (wiring == null) {
            emit(failed(agent.name, "No model is connected right now, so '${agent.name}' could not run."))
            return@flow
        }

        val allowed = allowedTools(agent)
        val session = Session(
            id = Uuid.random().toString(),
            agent = agent,
            wiring = wiring,
            allowed = allowed,
            descriptors = allowed.map { GuruToolSchemaMapper.definitionToDescriptor(it) },
            systemPrompt = buildSystemPrompt(agent, allowed),
            messages = listOf(
                AiMessage.UserMessage(
                    uuid = Uuid.random().toString(),
                    content = job,
                    time = System.currentTimeMillis()
                )
            )
        )
        sessions[session.id] = session

        emit(bounded(session, onEvent))
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
        val session = sessions[sessionId.trim()]
        if (session == null) {
            emit(
                failed(
                    "",
                    "No live session called '$sessionId'. A session lasts while the app is running; " +
                        "if this one has gone, start the job again with spawnAgent."
                )
            )
            return@flow
        }
        if (answer.isBlank()) {
            emit(bounded(session, onEvent, errorOverride = "The answer was empty, so the agent is still waiting."))
            return@flow
        }

        session.pendingQuestion = null
        session.messages = session.messages + AiMessage.UserMessage(
            uuid = Uuid.random().toString(),
            content = answer.trim(),
            time = System.currentTimeMillis()
        )
        emit(bounded(session, onEvent))
    }

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
            throw e
        } catch (e: Exception) {
            sessions.remove(session.id)
            val reason = e.getRootCause().message ?: e.message ?: e::class.simpleName
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

        while (rounds < TOOL_ROUNDS_UNLIMITED) {
            rounds++
            session.rounds = rounds

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

            streamFlow.collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> textBuilder.append(frame.text)
                    is StreamFrame.TextComplete -> {
                        // The complete form supersedes any deltas already
                        // gathered for this block, exactly as the main path
                        // treats it.
                        if (frame.text.length > textBuilder.length) {
                            textBuilder.clear()
                            textBuilder.append(frame.text)
                        }
                    }
                    is StreamFrame.ToolCallComplete -> toolCalls.add(frame)
                    else -> Unit
                }
            }

            lastText = textBuilder.toString()

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

                if (session.exchangesUsed() >= MAX_EXCHANGES) {
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

            val executed = toolCalls.map { call ->
                val toolName = call.name
                val declared = allowed.firstOrNull { it.name == toolName }

                val resultContent = if (declared == null) {
                    // A name outside the declaration. Refuse it here rather
                    // than trusting the model to stay inside its brief.
                    """{"error":"Refused: '${agent.name}' is not allowed to use '$toolName'."}"""
                } else {
                    try {
                        val argsJson = runCatching {
                            json.parseToJsonElement(call.content) as? JsonObject
                        }.getOrNull() ?: buildJsonObject { }

                        // The dispatcher is the only door: it checks the device
                        // permissions, runs the security policy and repairs the
                        // arguments before anything executes.
                        val dispatch = toolDispatcher.dispatch(toolName, argsJson)
                        if (dispatch.success) {
                            dispatch.rawJson
                        } else {
                            """{"error":${jsonString(dispatch.error ?: "tool failed")}}"""
                        }
                    } catch (e: Exception) {
                        """{"error":${jsonString(e.getRootCause().message ?: "tool failed")}}"""
                    }
                }

                AiMessage.ToolCall(
                    uuid = Uuid.random().toString(),
                    id = call.id ?: Uuid.random().toString(),
                    name = toolName,
                    rawContent = call.content,
                    resultRawContent = resultContent,
                    time = System.currentTimeMillis(),
                    isFailed = resultContent.contains("\"error\"")
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
    private fun compactIfNeeded(session: Session) {
        val total = session.messages.sumOf { messageWeight(it) }
        if (total < COMPACTION_THRESHOLD_CHARS) return
        if (session.messages.size <= RECENT_MESSAGES_KEPT + 2) return

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
