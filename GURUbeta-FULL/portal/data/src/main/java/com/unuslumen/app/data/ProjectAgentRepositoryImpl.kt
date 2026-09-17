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
    private val context: android.content.Context
) : ProjectAgentRepository {

    companion object {
        private const val TAG = "ProjectAgent"
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
        config: ProjectAgentConfig?
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

            try {
                client.executeStreamingWithTools(
                    prompt = chatPrompt,
                    model = model,
                    tools = availableTools,
                    llmConfig = currentLlmConfig
                ).collect { frame ->
                    when (frame) {
                        is StreamFrame.TextDelta -> textBuilder.append(frame.text)
                        is StreamFrame.TextComplete -> {
                            textBuilder.clear()
                            textBuilder.append(frame.text)
                        }
                        is StreamFrame.ToolCallDelta -> {}
                        is StreamFrame.ToolCallComplete -> collectedToolCalls.add(frame)
                        is StreamFrame.ReasoningDelta -> {}
                        is StreamFrame.ReasoningComplete -> {}
                        is StreamFrame.End -> {}
                    }
                }
            } catch (e: LLMClientException) {
                throw Exception("LLM error: ${e.message}")
            } catch (e: IOException) {
                throw Exception("Network error: ${e.message}")
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
                break
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
                        tool.encodeResult(tool.execute(args)).toString()
                    } else {
                        "Tool not found: $toolName"
                    }
                } catch (e: Exception) {
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