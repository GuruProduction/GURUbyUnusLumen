// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import com.unuslumen.app.data.repository.getExecutor
import com.unuslumen.app.data.repository.UnusLumenStreamingClient
import com.unuslumen.app.data.LlmConfigFetcher
import com.unuslumen.app.data.toLLModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
// All ToolSet classes migrated to registry — see AiDataModule.kt for executor bindings
import com.unuslumen.app.data.gurutools.GuruToolRegistryManager
import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.domain.MAX_CONSECUTIVE_TOOL_CALLS
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.ProjectAgentConfig
import com.unuslumen.app.domain.model.ProjectAgentRepository
import com.unuslumen.app.domain.repository.ProjectRepository
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProvider
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.PrefsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Project-specific AI agent implementation.
 * 
 * Each project agent is sandboxed to its project context:
 * - Only sees messages, documents, and facts from its project
 * - Has its own conversation history
 * - Can have custom prompt overlays
 * - Tools can be restricted per-project
 * 
 * The Master Guru can query across projects for summaries,
 * but project agents are isolated.
 */
@OptIn(ExperimentalUuidApi::class)
@Factory(binds = [ProjectAgentRepository::class])
class ProjectAgentRepositoryImpl(
    private val engine: io.ktor.client.engine.HttpClientEngine,
    private val projectRepository: ProjectRepository,
    private val getPreferenceUseCase: GetPreferenceUseCase,
    @Named("applicationScope") private val applicationScope: CoroutineScope,
    private val toolRegistryHolder: ToolRegistryHolder,
    private val guruToolRegistryManager: GuruToolRegistryManager,
    private val context: android.content.Context,
    /**
     * The same ledger the spawn path writes to.
     *
     * ONE EXECUTION HISTORY ARCHITECTURE, NOT TWO. A project agent is a different loop
     * with a different prompt and a different tool set, but it is still an agent doing
     * delegated work. A second, weaker persistence story for it would mean two answers
     * to "what did this agent do" depending on which surface started it, and the weaker
     * one would be the one nobody thinks to check.
     */
    private val runLedger: com.unuslumen.app.data.agents.AgentRunLedger,
) : ProjectAgentRepository {

    companion object {
        private const val TAG = "ProjectAgent"

        /**
         * The project agent's own contract version.
         *
         * Bumped when the built-in definition changes shape — the prompt it builds, the
         * way it filters tools, or the loop it runs. It is not a publisher version and not
         * an installed-agent version; those belong to `guru_agents`, and a project agent
         * has no row there. The run snapshot records this so an old execution is
         * explainable after the contract moves on.
         */
        const val PROJECT_AGENT_DEFINITION_VERSION = 1
    }

    /**
     * Record a failure that ended the turn.
     *
     * A RUN THAT THREW IS MARKED FAILED, not left RUNNING. A row still claiming to be
     * working is one the recovery scan keeps revisiting and a person keeps seeing as
     * alive, which is worse than an honest failure they can read.
     */
    private suspend fun recordRunFailure(runId: String, reason: String) {
        runCatching {
            runLedger.appendStateChange(
                runId,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_FAILED,
                reason,
                finishedAt = System.currentTimeMillis(),
            )
            runLedger.releaseLease(runId, runId, "failed")
        }
    }

    private val toolRegistry get() = toolRegistryHolder.toolRegistry
    
    // Cache of project-specific configurations
    private val configCache = mutableMapOf<String, ProjectAgentConfig>()
    
    // LLM executor and model (shared across projects, but context is sandboxed)
    private var llmExecutor: PromptExecutor? = null
    private var llModel: LLModel? = null
    private var toolsEnabled: Boolean = false
    private var humanName: String = ""
    private var streamingClient: UnusLumenStreamingClient? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        applicationScope.launch {
            initializeLlm()
        }
    }

    private suspend fun initializeLlm() {
        val aiProvider = getPreferenceUseCase(
            intPreferencesKey(com.unuslumen.app.preferences.PrefsConstants.AI_PROVIDER_KEY),
            AiProvider.None.id
        ).first().toAiProvider()

        val toolsEnabledPreferenceValue = getPreferenceUseCase(
            booleanPreferencesKey(com.unuslumen.app.preferences.PrefsConstants.AI_TOOLS_ENABLED_KEY),
            false
        ).first()

        toolsEnabled = toolsEnabledPreferenceValue

        humanName = getPreferenceUseCase(
            stringPreferencesKey(com.unuslumen.app.preferences.PrefsConstants.USER_NAME_KEY),
            ""
        ).first()

        if (aiProvider == AiProvider.None) {
            android.util.Log.w(TAG, "No AI provider selected")
            return
        }

        val serverUrl = "https://api.unuslumen.com/"
        val llmConfig = LlmConfigFetcher.getCachedConfig(context)

        if (llmConfig == null) {
            android.util.Log.e(TAG, "No LLM config from server. Cannot initialize model.")
            return
        }

        val model = llmConfig.baseModel
        if (model.isNotBlank()) {
            llModel = model.toLLModel(
                aiProvider,
                withTools = toolsEnabledPreferenceValue,
                contextWindow = llmConfig.contextWindow.toLong(),
                maxOutputTokens = llmConfig.maxTokens.toLong()
            )
        }

        llModel?.let {
            llmExecutor = aiProvider.getExecutor("", serverUrl, it, engine)

            // Sovereign egress — same policy as the main chat pipeline.
            val trimmedUrl = serverUrl.trimEnd('/') + "/"
            val sharedClient = com.unuslumen.app.data.tor.TorEgress.httpClient()
            streamingClient = UnusLumenStreamingClient(trimmedUrl, sharedClient)
        }
    }

    /**
     * Build the system prompt for a project agent.
     * Combines the base Guru identity with project-specific context.
     */
    private suspend fun buildProjectSystemPrompt(projectId: String, config: ProjectAgentConfig?): String {
        val project = projectRepository.getProject(projectId)
        val context = projectRepository.buildProjectContext(
            projectId = projectId,
            query = "",
            maxMessages = config?.contextWindowMessages ?: 20,
            maxDocuments = config?.contextWindowDocuments ?: 5,
            maxFacts = config?.contextWindowFacts ?: 10
        )

        val sb = StringBuilder()
        
        // Base Guru identity (simplified for project agents)
        sb.appendLine("You're Guru, an AI assistant helping with a project.")
        sb.appendLine("Project: ${project?.title ?: "Untitled"}")
        if (project?.description?.isNotBlank() == true) {
            sb.appendLine("Description: ${project.description}")
        }
        sb.appendLine()
        
        // Project-specific instructions
        if (config?.systemPromptOverlay?.isNotBlank() == true) {
            sb.appendLine("=== PROJECT INSTRUCTIONS ===")
            sb.appendLine(config.systemPromptOverlay)
            sb.appendLine()
        } else if (project?.promptOverlay?.isNotBlank() == true) {
            sb.appendLine("=== PROJECT INSTRUCTIONS ===")
            sb.appendLine(project.promptOverlay)
            sb.appendLine()
        }
        
        // Context from project
        if (context.preamble.isNotBlank()) {
            sb.appendLine(context.preamble)
        }
        
        return sb.toString()
    }

    override suspend fun sendMessage(
        projectId: String,
        messages: List<AiMessage>,
        config: ProjectAgentConfig?,
        conversationId: String?,
    ): Flow<AiMessage> = flow {
        val model = llModel ?: throw Exception("Model not selected")
        val client = streamingClient ?: throw Exception("AI Client not initialized")

        val effectiveConfig = config ?: getAgentConfig(projectId) ?: ProjectAgentConfig(projectId)

        // Fetch fresh LLM config from the Unus Lumen API so superadmin changes take
        // effect without app restart. Unconditional, no auth headers.
        val androidContext = context
        LlmConfigFetcher.fetchAndCache(androidContext)
        val currentLlmConfig = LlmConfigFetcher.getCachedConfig(androidContext) ?: throw Exception("No LLM config from server")

        // Build project-specific system prompt
        val systemPrompt = buildProjectSystemPrompt(projectId, effectiveConfig)

        /*
         * THE RUN IS OPENED BEFORE THE FIRST MODEL CALL.
         *
         * This path has always had an execution history of a sort — messages saved back to
         * the project — but nothing that says which invocation produced them, what tools
         * ran, or why a turn stopped. The ledger gives it the same record the spawn path
         * has, under the reserved project-agent identity, with the project and its overlay
         * frozen into the definition snapshot so the run stays explainable after the
         * overlay is edited.
         */
        val runId = Uuid.random().toString()
        val runStart = System.currentTimeMillis()
        val toolNames = if (toolsEnabled && effectiveConfig.enableTools) {
            getFilteredToolDescriptors(effectiveConfig).map { it.name }
        } else {
            emptyList()
        }
        runCatching {
            runLedger.createRun(
                com.unuslumen.app.database.entity.AgentRunEntity(
                    runId = runId,
                    conversationId = conversationId ?: projectId,
                    agentId = com.unuslumen.app.database.entity.AgentRunEntity.PROJECT_AGENT_ID,
                    agentSlug = com.unuslumen.app.database.entity.AgentRunEntity.PROJECT_AGENT_ID,
                    agentName = "Project agent",
                    agentVersion = PROJECT_AGENT_DEFINITION_VERSION,
                    assignment = messages.filterIsInstance<AiMessage.UserMessage>().lastOrNull()?.content.orEmpty(),
                    status = com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                    agentDefinitionSnapshotJson = json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
                            put("kind", kotlinx.serialization.json.JsonPrimitive("PROJECT_AGENT"))
                            put("projectId", kotlinx.serialization.json.JsonPrimitive(projectId))
                            put("definitionVersion", kotlinx.serialization.json.JsonPrimitive(PROJECT_AGENT_DEFINITION_VERSION))
                            put("overlay", kotlinx.serialization.json.JsonPrimitive(effectiveConfig.systemPromptOverlay))
                            put("allowedToolCategories", kotlinx.serialization.json.JsonPrimitive(effectiveConfig.allowedToolCategories.joinToString(",")))
                            put("blockedToolCategories", kotlinx.serialization.json.JsonPrimitive(effectiveConfig.blockedToolCategories.joinToString(",")))
                            put("enableTools", kotlinx.serialization.json.JsonPrimitive(effectiveConfig.enableTools))
                        }
                    ),
                    resolvedSystemPrompt = systemPrompt,
                    toolManifestSnapshotJson = json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
                            put("tools", kotlinx.serialization.json.JsonPrimitive(toolNames.joinToString(",")))
                        }
                    ),
                    modelProvider = model.provider.id,
                    modelName = model.id,
                    requestedConfigSnapshotJson = json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
                            put("temperature", kotlinx.serialization.json.JsonPrimitive(effectiveConfig.temperature))
                            put("maxTokens", kotlinx.serialization.json.JsonPrimitive(effectiveConfig.maxTokens))
                        }
                    ),
                    effectiveConfigSnapshotJson = json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
                            put("model", kotlinx.serialization.json.JsonPrimitive(model.id))
                            put("toolsEnabled", kotlinx.serialization.json.JsonPrimitive(toolsEnabled && effectiveConfig.enableTools))
                            put("maxConsecutiveToolCalls", kotlinx.serialization.json.JsonPrimitive(MAX_CONSECUTIVE_TOOL_CALLS))
                            put("reasoningCaptureEnabled", kotlinx.serialization.json.JsonPrimitive(true))
                            put("runtimeKind", kotlinx.serialization.json.JsonPrimitive("PROJECT_AGENT"))
                        }
                    ),
                    createdAt = runStart,
                    startedAt = runStart,
                    updatedAt = runStart,
                )
            )
            runLedger.acquireLease(runId, runId)
        }.onFailure {
            // NOT FATAL HERE, AND THE DIFFERENCE IS DELIBERATE. The spawn path refuses to
            // run without a record because it is handed a job and nothing else. A project
            // agent is the chat the person is already in, and refusing to answer because a
            // bookkeeping write failed would be the tail wagging the dog. The failure is
            // logged and the conversation continues without a run.
            android.util.Log.w(TAG, "Project-agent run could not be opened: ${it.message}")
        }

        // Get project context for enrichment
        val context = projectRepository.buildProjectContext(
            projectId = projectId,
            query = messages.filterIsInstance<AiMessage.UserMessage>().lastOrNull()?.content ?: "",
            maxMessages = effectiveConfig.contextWindowMessages,
            maxDocuments = effectiveConfig.contextWindowDocuments,
            maxFacts = effectiveConfig.contextWindowFacts
        )

        // Build enriched messages with context
        val enrichedMessages = buildEnrichedMessages(messages, context, systemPrompt)

        // Get available tools (filtered by config)
        val availableTools = if (toolsEnabled && effectiveConfig.enableTools) {
            getFilteredToolDescriptors(effectiveConfig)
        } else {
            emptyList()
        }

        var currentMessages = enrichedMessages
        var consecutiveToolCalls = 0

        do {
            if (consecutiveToolCalls >= MAX_CONSECUTIVE_TOOL_CALLS) {
                throw Exception("Tool call limit exceeded")
            }

            val chatPrompt = currentMessages.buildChatPrompt(
                systemMessage = systemPrompt,
                tools = availableTools
            )

            val textBuilder = StringBuilder()
            val collectedToolCalls = mutableListOf<StreamFrame.ToolCallComplete>()
            /*
             * THE REASONING LANE THIS PATH NEVER HAD.
             *
             * This `when` used to drop ReasoningDelta on the floor in exactly the way the
             * spawn path did, so a project agent's thinking was discarded before anything
             * could record it. The wire carries it, so it is captured here and flushed as
             * one batch per round, in arrival order, keeping text and reasoning distinct.
             */
            val reasoningBuilder = StringBuilder()
            val outputFrames = mutableListOf<com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame>()
            val requestId = Uuid.random().toString()

            runCatching {
                runLedger.append(
                    runId,
                    com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REQUEST_STARTED,
                        actorType = "RUNTIME",
                        correlationId = requestId,
                        payload = buildJsonObject {
                            put("requestId", kotlinx.serialization.json.JsonPrimitive(requestId))
                            put("provider", kotlinx.serialization.json.JsonPrimitive(model.provider.id))
                            put("model", kotlinx.serialization.json.JsonPrimitive(model.id))
                            put("roundIndex", kotlinx.serialization.json.JsonPrimitive(consecutiveToolCalls + 1))
                            put("runtimeKind", kotlinx.serialization.json.JsonPrimitive("PROJECT_AGENT"))
                        }.toString(),
                    )
                )
            }

            try {
                client.executeStreamingWithTools(
                    prompt = chatPrompt,
                    model = model,
                    tools = availableTools,
                    llmConfig = currentLlmConfig
                ).collect { frame ->
                    when (frame) {
                        is StreamFrame.TextDelta -> {
                            textBuilder.append(frame.text)
                            outputFrames.add(
                                com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame(
                                    com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame.Kind.TEXT,
                                    frame.text,
                                    null,
                                )
                            )
                        }
                        is StreamFrame.TextComplete -> {
                            textBuilder.clear()
                            textBuilder.append(frame.text)
                        }
                        is StreamFrame.ToolCallDelta -> {}
                        is StreamFrame.ToolCallComplete -> collectedToolCalls.add(frame)
                        is StreamFrame.ReasoningDelta -> {
                            // CAPTURED, NOT DISCARDED. Visibility is a display concern.
                            val chunk = frame.text.orEmpty()
                            if (chunk.isNotEmpty()) reasoningBuilder.append(chunk)
                            outputFrames.add(
                                com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame(
                                    com.unuslumen.app.domain.agents.AgentRunReplay.OutputFrame.Kind.REASONING,
                                    chunk,
                                    null,
                                )
                            )
                        }
                        is StreamFrame.ReasoningComplete -> {}
                        is StreamFrame.End -> {}
                    }
                }
            } catch (e: LLMClientException) {
                recordRunFailure(runId, "LLM error: ${e.message}")
                throw Exception("LLM error: ${e.message}")
            } catch (e: IOException) {
                recordRunFailure(runId, "Network error: ${e.message}")
                throw Exception("Network error: ${e.message}")
            }

            // Flush the batch, then the canonical completions. Both land before any tool
            // call is proposed, so a dispatch always has a recorded request behind it.
            runCatching {
                if (outputFrames.isNotEmpty()) {
                    runLedger.append(
                        runId,
                        com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_OUTPUT_BATCH,
                            actorType = "MODEL",
                            correlationId = requestId,
                            payload = buildJsonObject {
                                put("requestId", kotlinx.serialization.json.JsonPrimitive(requestId))
                                put(
                                    "frames",
                                    kotlinx.serialization.json.JsonPrimitive(
                                        com.unuslumen.app.domain.agents.OutputFrames.encode(outputFrames)
                                    )
                                )
                            }.toString(),
                        )
                    )
                }
                if (textBuilder.isNotEmpty()) {
                    runLedger.append(
                        runId,
                        com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_TEXT_COMPLETED,
                            actorType = "MODEL",
                            correlationId = requestId,
                            payload = buildJsonObject {
                                put("requestId", kotlinx.serialization.json.JsonPrimitive(requestId))
                                put("text", kotlinx.serialization.json.JsonPrimitive(textBuilder.toString()))
                            }.toString(),
                        )
                    )
                }
                if (reasoningBuilder.isNotEmpty()) {
                    runLedger.append(
                        runId,
                        com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REASONING_COMPLETED,
                            actorType = "MODEL",
                            correlationId = requestId,
                            payload = buildJsonObject {
                                put("requestId", kotlinx.serialization.json.JsonPrimitive(requestId))
                                put("reasoning", kotlinx.serialization.json.JsonPrimitive(reasoningBuilder.toString()))
                            }.toString(),
                        )
                    )
                }
            }

            val finalText = textBuilder.toString()

            if (collectedToolCalls.isEmpty()) {
                if (finalText.isNotBlank()) {
                    val msg = AiMessage.AssistantMessage(
                        content = finalText,
                        uuid = Uuid.random().toString(),
                        time = System.currentTimeMillis()
                    )
                    emit(msg)

                    if (effectiveConfig.autoSaveMessages) {
                        saveMessageToProject(projectId, msg)
                    }
                }
                // A FINISHED PROJECT TURN IS A FINISHED RUN. The row stops being
                // non-terminal so the recovery scan leaves it alone, and the report goes on
                // the run so the history answers what the work produced.
                runCatching {
                    runLedger.append(
                        runId,
                        com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REQUEST_COMPLETED,
                            actorType = "MODEL",
                            correlationId = requestId,
                            payload = buildJsonObject {
                                put("report", kotlinx.serialization.json.JsonPrimitive(finalText))
                            }.toString(),
                        )
                    )
                    runLedger.appendStateChange(
                        runId,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                        com.unuslumen.app.database.entity.AgentRunEntity.STATUS_COMPLETED,
                        "the project agent answered",
                        finishedAt = System.currentTimeMillis(),
                    )
                    runLedger.releaseLease(runId, runId, "completed")
                }
                break
            }

            // TOOL CALLS GO IN THE LEDGER BEFORE THEY RUN, for the same reason they do on
            // the spawn path: a started call with no completion must never be repeated.
            collectedToolCalls.forEach { frame ->
                val invocationId = Uuid.random().toString()
                runCatching {
                    runLedger.append(
                        runId,
                        com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.domain.agents.AgentEventTypes.TOOL_CALL_STARTED,
                            actorType = "RUNTIME",
                            idempotencyKey = "tool-start-$invocationId",
                            payload = buildJsonObject {
                                put("invocationId", kotlinx.serialization.json.JsonPrimitive(invocationId))
                                put("tool", kotlinx.serialization.json.JsonPrimitive(frame.name))
                                put("arguments", kotlinx.serialization.json.JsonPrimitive(frame.content))
                                put("runtimeKind", kotlinx.serialization.json.JsonPrimitive("PROJECT_AGENT"))
                                // RECORDED HONESTLY: this path dispatches through the koog
                                // tool list rather than ToolDispatcher, so no permission or
                                // security check precedes it. The ledger says so rather than
                                // implying checks that did not run.
                                put("permissionChecked", kotlinx.serialization.json.JsonPrimitive(false))
                            }.toString(),
                        )
                    )
                }
            }

            consecutiveToolCalls++

            if (finalText.isNotBlank()) {
                val msg = AiMessage.AssistantMessage(
                    content = finalText,
                    uuid = Uuid.random().toString(),
                    time = System.currentTimeMillis()
                )
                emit(msg)
            }

            // Execute tool calls from streaming frames
            val toolCallMessages = collectedToolCalls.map { frame ->
                val toolName = frame.name
                val argsContent = frame.content
                val resultContent = try {
                    val tool = toolRegistry.tools.find { it.descriptor.name == toolName }
                    if (tool != null) {
                        val argsJson = try {
                            json.parseToJsonElement(argsContent).let {
                                if (it is JsonObject) it else buildJsonObject {}
                            }
                        } catch (e: Exception) {
                            buildJsonObject {}
                        }
                        val args = tool.decodeArgs(argsJson)
                        val content = tool.encodeResult(tool.execute(args)).toString()
                        runCatching {
                            runLedger.append(
                                runId,
                                com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.TOOL_CALL_COMPLETED,
                                    actorType = "TOOL",
                                    payload = buildJsonObject {
                                        put("tool", kotlinx.serialization.json.JsonPrimitive(toolName))
                                        put("result", kotlinx.serialization.json.JsonPrimitive(content))
                                        put("runtimeKind", kotlinx.serialization.json.JsonPrimitive("PROJECT_AGENT"))
                                    }.toString(),
                                )
                            )
                        }
                        content
                    } else {
                        runCatching {
                            runLedger.append(
                                runId,
                                com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.TOOL_CALL_REFUSED,
                                    actorType = "RUNTIME",
                                    payload = buildJsonObject {
                                        put("tool", kotlinx.serialization.json.JsonPrimitive(toolName))
                                        put("reason", kotlinx.serialization.json.JsonPrimitive("no such tool in this build"))
                                    }.toString(),
                                )
                            )
                        }
                        "Tool not found: $toolName"
                    }
                } catch (e: Exception) {
                    // AN EXCEPTION DOES NOT PROVE THE CALL DID NOT RUN. It becomes an
                    // unknown outcome, never a failure, for the same reason as the spawn
                    // path: a side effect may already have happened.
                    runCatching {
                        runLedger.append(
                            runId,
                            com.unuslumen.app.data.agents.AgentRunLedger.EventDraft(
                                eventType = com.unuslumen.app.domain.agents.AgentEventTypes.TOOL_CALL_OUTCOME_UNKNOWN,
                                actorType = "RUNTIME",
                                payload = buildJsonObject {
                                    put("tool", kotlinx.serialization.json.JsonPrimitive(toolName))
                                    put("reason", kotlinx.serialization.json.JsonPrimitive(e.message ?: "the call threw"))
                                }.toString(),
                            )
                        )
                    }
                    "Error: ${e.message}"
                }

                AiMessage.ToolCall(
                    uuid = Uuid.random().toString(),
                    id = frame.id ?: "",
                    name = toolName,
                    rawContent = argsContent,
                    resultRawContent = resultContent,
                    time = System.currentTimeMillis()
                ).also { emit(it) }
            }

            currentMessages = currentMessages + toolCallMessages

        } while (consecutiveToolCalls < MAX_CONSECUTIVE_TOOL_CALLS)
    }

    private fun buildEnrichedMessages(
        messages: List<AiMessage>,
        context: com.unuslumen.app.domain.model.ProjectContext,
        systemPrompt: String
    ): List<AiMessage> {
        // Build the full message list including context
        val messageList = mutableListOf<AiMessage>()
        
        // Add context messages first (from project history)
        context.messages.forEach { projectMessage ->
            messageList.add(
                when (projectMessage.role) {
                    "user" -> AiMessage.UserMessage(
                        uuid = projectMessage.id,
                        content = projectMessage.content,
                        time = projectMessage.timestamp
                    )
                    "assistant" -> AiMessage.AssistantMessage(
                        content = projectMessage.content,
                        time = projectMessage.timestamp,
                        uuid = projectMessage.id
                    )
                    else -> AiMessage.UserMessage(
                        uuid = projectMessage.id,
                        content = projectMessage.content,
                        time = projectMessage.timestamp
                    )
                }
            )
        }
        
        // Add current conversation messages
        messageList.addAll(messages)
        
        return messageList
    }

    private suspend fun getFilteredToolDescriptors(config: ProjectAgentConfig): List<ToolDescriptor> {
        val allTools = toolRegistry.tools

        return if (config.allowedToolCategories.isNotEmpty()) {
            allTools.filter { tool ->
                config.allowedToolCategories.any { category ->
                    tool.descriptor.name.startsWith(category, ignoreCase = true)
                }
            }.map { ToolDescriptor(it.descriptor.name, it.descriptor.description) }
        } else if (config.blockedToolCategories.isNotEmpty()) {
            allTools.filterNot { tool ->
                config.blockedToolCategories.any { category ->
                    tool.descriptor.name.startsWith(category, ignoreCase = true)
                }
            }.map { ToolDescriptor(it.descriptor.name, it.descriptor.description) }
        } else {
            allTools.map { ToolDescriptor(it.descriptor.name, it.descriptor.description) }
        }
    }

    private data class ToolExecutionResult(
        val callId: String?,
        val toolName: String,
        val result: String
    )

    private suspend fun executeToolCalls(
        calls: List<Message.Tool.Call>,
        projectId: String
    ): List<ToolExecutionResult> {
        val results = mutableListOf<ToolExecutionResult>()

        for (call in calls) {
            try {
                val tool = toolRegistry.tools.find { it.descriptor.name == call.tool }
                if (tool != null) {
                    val args = tool.decodeArgs(call.contentJson)
                    val result = tool.execute(args)
                    results.add(ToolExecutionResult(call.id, call.tool, result.toString()))
                } else {
                    results.add(ToolExecutionResult(call.id, call.tool, "Tool not found: ${call.tool}"))
                }
            } catch (e: Exception) {
                results.add(ToolExecutionResult(call.id, call.tool, "Error: ${e.message}"))
            }
        }

        return results
    }

    private suspend fun saveMessageToProject(projectId: String, message: AiMessage.AssistantMessage) {
        val projectMessage = com.unuslumen.app.domain.model.ProjectMessage(
            id = message.uuid,
            projectId = projectId,
            role = "assistant",
            content = message.content,
            timestamp = message.time
        )
        projectRepository.addMessage(projectMessage)
    }

    override suspend fun getAgentConfig(projectId: String): ProjectAgentConfig? {
        return configCache[projectId] ?: run {
            val project = projectRepository.getProject(projectId)
            project?.let {
                ProjectAgentConfig(
                    projectId = projectId,
                    systemPromptOverlay = it.promptOverlay
                )
            }
        }
    }

    override suspend fun updateAgentConfig(config: ProjectAgentConfig) {
        configCache[config.projectId] = config
        
        // Also update the project's promptOverlay
        val project = projectRepository.getProject(config.projectId)
        if (project != null) {
            projectRepository.updateProject(
                project.copy(promptOverlay = config.systemPromptOverlay)
            )
        }
    }

    override suspend fun clearConversation(projectId: String) {
        // Delete all messages for this project
        val messages = projectRepository.getMessages(projectId)
        messages.forEach { message ->
            projectRepository.deleteMessage(message.id)
        }
    }

    override suspend fun getAvailableTools(projectId: String): List<String> {
        val config = getAgentConfig(projectId)
        val allTools = toolRegistry.tools.map { it.descriptor.name }

        return if (config?.allowedToolCategories?.isNotEmpty() == true) {
            allTools.filter { tool ->
                config.allowedToolCategories.any { category ->
                    tool.startsWith(category, ignoreCase = true)
                }
            }
        } else if (config?.blockedToolCategories?.isNotEmpty() == true) {
            allTools.filterNot { tool ->
                config.blockedToolCategories.any { category ->
                    tool.startsWith(category, ignoreCase = true)
                }
            }
        } else {
            allTools
        }
    }
}