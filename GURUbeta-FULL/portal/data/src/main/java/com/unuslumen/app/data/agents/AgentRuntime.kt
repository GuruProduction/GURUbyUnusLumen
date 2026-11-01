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
 * Spawns a saved agent and returns its report.
 *
 * WHAT THIS IS. The main GURU hands a job to a named agent. The agent runs with
 * its own system prompt and exactly the tools it declares, and answers with a
 * report. It has no conversation of its own and writes nothing to the person's
 * chat history — it exists for the length of the job.
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
 * COST IS BOUNDED. [MAX_TOOL_ROUNDS] rounds, one model call each. The main
 * conversation's limit is far larger because a person is waiting; an agent is
 * background work and has to be cheap enough to be worth calling.
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
        /** Tool rounds one spawned agent may take before it must answer. */
        const val MAX_TOOL_ROUNDS = 12

        /** Tool names no agent may ever call, whatever its definition says. */
        val NEVER_SPAWNABLE = setOf(
            "spawnAgent",
            "listAgents",
            "saveAgent",
            "deleteAgent"
        )

        private const val MAX_REPORT_CHARS = 8_000

        private val NO_TIMEOUTS = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(
            requestTimeoutMillis = Long.MAX_VALUE,
            connectTimeoutMillis = Long.MAX_VALUE,
            socketTimeoutMillis = Long.MAX_VALUE
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** What one spawn produced, honest about whether it finished or failed. */
    data class SpawnResult(
        val success: Boolean,
        val agentName: String,
        val report: String,
        val toolRounds: Int,
        val error: String? = null
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
     * Run one job.
     *
     * The flow emits once, at the end. An agent's work is a report rather than a
     * conversation, so there is nothing to stream to a person; [onEvent] exists
     * for a caller that wants to show progress while it waits.
     */
    fun spawn(
        slugOrName: String,
        job: String,
        onEvent: ((String) -> Unit)? = null
    ) = flow {
        val agent = agentRepository.resolveAgent(slugOrName)
        if (agent == null) {
            emit(
                SpawnResult(
                    success = false,
                    agentName = slugOrName,
                    report = "",
                    toolRounds = 0,
                    error = "No agent called '$slugOrName'. Check the listing for the exact name."
                )
            )
            return@flow
        }
        if (!agent.enabled) {
            emit(
                SpawnResult(
                    success = false,
                    agentName = agent.name,
                    report = "",
                    toolRounds = 0,
                    error = "'${agent.name}' is switched off, so it was not called."
                )
            )
            return@flow
        }

        val wiring = wiring()
        if (wiring == null) {
            emit(
                SpawnResult(
                    success = false,
                    agentName = agent.name,
                    report = "",
                    toolRounds = 0,
                    error = "No model is connected right now, so '${agent.name}' could not run."
                )
            )
            return@flow
        }

        val result = try {
            runLoop(agent, job, wiring, onEvent)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.getRootCause().message ?: e.message ?: e::class.simpleName
            SpawnResult(
                success = false,
                agentName = agent.name,
                report = "",
                toolRounds = 0,
                error = "${agent.name} failed: $reason"
            )
        }
        emit(result)
    }

    private suspend fun runLoop(
        agent: GuruAgent,
        job: String,
        wiring: Wiring,
        onEvent: ((String) -> Unit)?
    ): SpawnResult {
        val allowed = allowedTools(agent)
        val descriptors = allowed.map { GuruToolSchemaMapper.definitionToDescriptor(it) }
        val systemPrompt = buildSystemPrompt(agent, allowed)

        var messages: List<AiMessage> = listOf(
            AiMessage.UserMessage(
                uuid = Uuid.random().toString(),
                content = job,
                time = System.currentTimeMillis()
            )
        )

        var rounds = 0
        var lastText = ""

        while (rounds < MAX_TOOL_ROUNDS) {
            rounds++

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

            if (toolCalls.isEmpty()) {
                return SpawnResult(
                    success = true,
                    agentName = agent.name,
                    report = lastText.take(MAX_REPORT_CHARS),
                    toolRounds = rounds
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
        }

        return SpawnResult(
            success = true,
            agentName = agent.name,
            report = (lastText.ifBlank {
                "${agent.name} used its $MAX_TOOL_ROUNDS tool rounds without writing a report."
            }).take(MAX_REPORT_CHARS),
            toolRounds = rounds
        )
    }

    /**
     * The complete tool set this agent may use: what it declares, filtered to
     * what actually exists in the registry and what no agent is ever allowed to
     * call. An empty declaration yields an empty list on purpose.
     */
    fun allowedTools(agent: GuruAgent): List<ToolDefinition> {
        if (agent.toolPermissions.isEmpty()) return emptyList()
        return agent.toolPermissions
            .filter { it.isNotBlank() }
            .filter { it !in NEVER_SPAWNABLE }
            .mapNotNull { name -> toolRegistry.getDefinition(name) }
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
