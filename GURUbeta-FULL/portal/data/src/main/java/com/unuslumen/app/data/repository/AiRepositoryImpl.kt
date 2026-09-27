package com.unuslumen.app.data.repository

import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import com.unuslumen.app.data.UnusLumenClient
import com.unuslumen.app.data.tor.TorEgress
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import com.unuslumen.app.data.EmptyAiClient
import com.unuslumen.app.data.ContextManager
import com.unuslumen.app.data.buildChatPrompt
import com.unuslumen.app.data.PromptFetcher
import com.unuslumen.app.data.LlmConfig
import com.unuslumen.app.data.LlmConfigFetcher
import com.unuslumen.app.data.getRootCause
import com.unuslumen.app.data.nowMillis
import com.unuslumen.app.data.toLLModel
import com.unuslumen.app.data.gurutools.GuruToolRegistryManager
import com.unuslumen.app.domain.repository.LuxifyRepository
import com.unuslumen.app.domain.repository.PromptRepository
import com.unuslumen.app.data.multimodal.ImageTurnAssembler
import com.unuslumen.app.data.tools.SearchNotesResult
import com.unuslumen.app.data.tools.NoteIdResult
import com.unuslumen.app.data.tools.NoteIdsResult
import com.unuslumen.app.data.tools.NoteResult
import com.unuslumen.app.data.tools.TaskIdResult
import com.unuslumen.app.data.tools.TaskIdsResult
import com.unuslumen.app.data.tools.TaskResult
import com.unuslumen.app.data.tools.CalendarEventIdResult
import com.unuslumen.app.data.tools.CalendarEventIdsResult
import com.unuslumen.app.data.tools.CalendarEventResult
import com.unuslumen.app.data.tools.GetMonthEventsResult
import com.unuslumen.app.data.tools.SearchEventsResult
import com.unuslumen.app.data.tools.JournalEntryIdResult
import com.unuslumen.app.data.tools.SearchJournalEntriesResult
import com.unuslumen.app.data.tools.JournalEntryResult
import com.unuslumen.app.data.tools.BookmarkIdResult
import com.unuslumen.app.data.tools.SearchBookmarksResult
import com.unuslumen.app.data.tools.BookmarkResult as BookmarkToolResult
import com.unuslumen.app.data.tools.AlarmResult
import com.unuslumen.app.data.tools.AlarmsResult
import com.unuslumen.app.data.tools.MemoryFactsResult
import com.unuslumen.app.data.tools.PlansResult
import com.unuslumen.app.data.tools.PlanResult
import com.unuslumen.app.data.tools.WebSearchResult as WebSearchToolResult
import com.unuslumen.app.data.tools.AllPreferencesResult
import com.unuslumen.app.data.tools.VolumeResult
import com.unuslumen.app.data.tools.RingerModeResult
import com.unuslumen.app.data.tools.SpeakResult
import com.unuslumen.app.data.tools.VibrateResult
import com.unuslumen.app.data.tools.PlaySoundResult
import com.unuslumen.app.data.tools.AudioInfoResult as AudioInfoToolResult
import com.unuslumen.app.data.tools.SpotifyStatusResult
import com.unuslumen.app.data.tools.SpotifySearchResult
import com.unuslumen.app.data.tools.GifSearchResult
import com.unuslumen.app.data.tools.MemeCreateResult
import com.unuslumen.app.data.tools.VideoFrameResult
import com.unuslumen.app.data.tools.CameraResult
import com.unuslumen.app.data.tools.HueLightsResult
import com.unuslumen.app.data.tools.HueResult
import com.unuslumen.app.data.tools.SonosDiscoverResult
import com.unuslumen.app.data.tools.SonosResult
import com.unuslumen.app.data.tools.BluetoothDevicesResult
import com.unuslumen.app.data.tools.NetworkScanResult
import com.unuslumen.app.data.tools.WeatherResult
import com.unuslumen.app.data.tools.WeatherForecastResult
import com.unuslumen.app.data.tools.PlacesResult
import com.unuslumen.app.data.tools.GitHubPrListResult
import com.unuslumen.app.data.tools.GitHubIssueListResult
import com.unuslumen.app.data.tools.GitHubRepoInfoResult
import com.unuslumen.app.data.tools.TrelloBoardsResult
import com.unuslumen.app.data.tools.TrelloCardsResult
import com.unuslumen.app.data.tools.NotionSearchResult
import com.unuslumen.app.data.tools.NotionPageResult
import com.unuslumen.app.data.tools.TranscribeResult
import com.unuslumen.app.data.tools.TtsResult
import com.unuslumen.app.data.tools.AudioConvertResult
import com.unuslumen.app.data.tools.DiscordResult
import com.unuslumen.app.data.tools.SlackResult
import com.unuslumen.app.data.tools.WhatsAppResult
import com.unuslumen.app.data.tools.XResult
import com.unuslumen.app.data.tools.VoiceCallResult
import com.unuslumen.app.data.tools.HealthResult
import com.unuslumen.app.data.tools.SystemDeviceInfoResult
import com.unuslumen.app.data.tools.SessionLogsResult
import com.unuslumen.app.data.tools.EmailListResult
import com.unuslumen.app.data.tools.EmailReadResult
import com.unuslumen.app.data.tools.EmailSendResult
import com.unuslumen.app.data.tools.EmailActionResult
import com.unuslumen.app.data.tools.CameraListResult
import com.unuslumen.app.data.tools.CameraSnapshotResult
import com.unuslumen.app.data.tools.CameraRecordResult
import com.unuslumen.app.data.tools.CameraMotionResult
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.MAX_CONSECUTIVE_TOOL_CALLS
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiRepositoryException
import com.unuslumen.app.domain.model.MediaInfo
import com.unuslumen.app.domain.model.SmartHomeInfo
import com.unuslumen.app.domain.model.DeviceInfoData
import com.unuslumen.app.domain.model.WeatherInfoData
import com.unuslumen.app.domain.model.ForecastDayData
import com.unuslumen.app.domain.model.PlaceInfoData
import com.unuslumen.app.domain.model.GitHubInfoData
import com.unuslumen.app.domain.model.GitHubItemData
import com.unuslumen.app.domain.model.TrelloInfoData
import com.unuslumen.app.domain.model.TrelloItemData
import com.unuslumen.app.domain.model.NotionInfoData
import com.unuslumen.app.domain.model.NotionItemData
import com.unuslumen.app.domain.model.VoiceInfoData
import com.unuslumen.app.domain.model.CommunicationInfo
import com.unuslumen.app.domain.model.SystemInfo
import com.unuslumen.app.domain.model.DeviceInfo
import com.unuslumen.app.domain.model.EmailInfo
import com.unuslumen.app.domain.model.EmailSummary
import com.unuslumen.app.domain.model.EmailDetail
import com.unuslumen.app.domain.model.CameraInfo
import com.unuslumen.app.domain.model.CameraDevice
import com.unuslumen.app.domain.model.PortalResult
import com.unuslumen.app.domain.model.ToolCallResultObject
import com.unuslumen.app.domain.model.PlanInfo
import com.unuslumen.app.domain.model.AlarmInfo
import com.unuslumen.app.domain.model.SoundInfo
import com.unuslumen.app.domain.model.VolumeInfo
import com.unuslumen.app.domain.model.WebSearchItem
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.domain.use_case.GetCalendarEventByIdUseCase
import com.unuslumen.app.domain.use_case.GetNoteUseCase
import com.unuslumen.app.domain.use_case.GetTaskByIdUseCase
import com.unuslumen.app.preferences.PrefsConstants.AI_PROVIDER_KEY
import com.unuslumen.app.preferences.PrefsConstants.AI_TOOLS_ENABLED_KEY
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.fixedBaseUrl
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProvider
import com.unuslumen.app.data.toLLMProvider
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.database.entity.ToolResultEntity
import com.unuslumen.app.data.metadata.MetadataAssembler
import com.unuslumen.app.data.metadata.MetadataConfigFetcher
import com.unuslumen.app.data.metadata.MetadataSendContext
import com.unuslumen.app.data.hooks.HookEventBus
import com.unuslumen.app.data.tor.TorManager

/** A fallback model to try if the primary model fails after retries. */
data class FallbackModel(
    val provider: AiProvider,
    val modelName: String,
    val llModel: LLModel,
    val executor: PromptExecutor
)

@OptIn(ExperimentalUuidApi::class)
@Factory(binds = [AiRepository::class])
class AiRepositoryImpl(
    private val engine: io.ktor.client.engine.HttpClientEngine,
    private val getPreferenceUseCase: GetPreferenceUseCase,
    private val savePreferenceUseCase: SavePreferenceUseCase,
    @Named("applicationScope") private val applicationScope: CoroutineScope,
    private val context: android.content.Context,
    private val toolDispatcher: com.unuslumen.app.data.tools.registry.ToolDispatcher,
    private val toolRegistry: com.unuslumen.app.data.tools.registry.ToolRegistry,
    private val guruToolRegistryManager: GuruToolRegistryManager,
    private val memoryRepository: MemoryRepository,
    private val promptRepository: PromptRepository,
    private val luxifyRepository: LuxifyRepository,
    private val getNote: GetNoteUseCase,
    private val getTaskById: GetTaskByIdUseCase,
    private val getCalendarEventById: GetCalendarEventByIdUseCase,
    private val toolResultDao: ToolResultDao
) : AiRepository {

    companion object {
        // AGI doesn't have timeouts
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Multimodal plumbing: turns tool-result file paths into real image blocks the
     * engine can see. Created lazily on first send — all dependencies are injected.
     */
    private val imageTurnAssembler: com.unuslumen.app.data.multimodal.ImageTurnAssembler by lazy {
        com.unuslumen.app.data.multimodal.ImageTurnAssembler(
            context = context,
            seenImageDao = guruDatabase().seenImageDao(),
            shellExecutor = com.unuslumen.app.util.shell.ShellExecutor(context)
        )
    }

    /**
     * Metadata machinery. The assembler is created lazily with DB + Tor
     * dependencies. The metadata CONFIG is fetched per send (like LlmConfig)
     * so superadmin edits go live without an APK ship. The block itself is
     * rebuilt fresh on every do/while iteration so clock/gaps stay honest.
     */
    private val metadataAssembler: MetadataAssembler by lazy {
        MetadataAssembler(
            context = context,
            toolResultDao = toolResultDao,
            torManager = try {
                org.koin.java.KoinJavaComponent.getKoin().get<com.unuslumen.app.data.tor.TorManager>()
            } catch (e: Exception) { null }
        )
    }

    /** Per-conversation compaction state: has auto-compact ever run for this conversation. */
    private val compactedConversations = mutableSetOf<String>()

    /**
     * Notes to self: Guru's own keyword-triggered reminders, injected locally
     * when the user's message matches keywords Guru saved. Lazy so a cold
     * Koin graph only pays the lookup when the first send actually happens.
     */
    private val notesToSelfDao: com.unuslumen.app.database.dao.GuruNoteToSelfDao? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.unuslumen.app.database.dao.GuruNoteToSelfDao>()
        } catch (e: Exception) {
            android.util.Log.w("guru", "GuruNoteToSelfDao unavailable: ${e.message}")
            null
        }
    }

    /** Pull matched notes-to-self content for this user message, empty on any failure. */
    private suspend fun matchedNotesToSelf(userMessageContent: String): List<String> {
        val dao = notesToSelfDao ?: return emptyList()
        return try {
            com.unuslumen.app.data.notestoself.NotesToSelfEngine(dao).matchingContent(userMessageContent)
        } catch (e: Exception) {
            android.util.Log.w("guru", "Notes-to-self match failed: ${e.message}")
            emptyList()
        }
    }

    private fun guruDatabase(): com.unuslumen.app.database.guruDatabase =
        org.koin.java.KoinJavaComponent.getKoin().get<com.unuslumen.app.database.guruDatabase>()

    /** Whether screen vision is enabled. Read from DataStore at init and on every send. */
    private var visionEnabled: Boolean = false

    private var chatSystemMessage = ""
    private var llmExecutor: PromptExecutor? = null
    private var llModel: LLModel? = null
    // Fallback models — tried in order if primary fails after retries
    private var fallbackModels: List<FallbackModel> = emptyList()
    private var toolsEnabled: Boolean = false
    private var streamingClient: UnusLumenStreamingClient? = null
    private var humanName: String = ""

    init {
        applicationScope.launch {
            rebuildModelWiringOnce()
        }
    }

    private fun rebuildModelWiringOnce() {
        applicationScope.launch {
            val aiProvider = getPreferenceUseCase(
                intPreferencesKey(AI_PROVIDER_KEY),
                AiProvider.UnusLumen.id
            ).first().toAiProvider()

            // Migration: fix corrupted boolean preferences saved as strings by the LLM's savePreference tool.
            // The savePreference tool previously used stringPreferencesKey for everything, so "ai_tools_enabled"
            // may exist as a string "false"/"true" in DataStore while the boolean key returns its default (false).
            // This silently breaks all tool calling. Read the string-keyed value, convert it, and save it properly.
            // NOTE: after the SettingsToolSet fix, this value may already be stored as a Boolean, so catch the cast.
            try {
                val stringSavedToolsEnabled = getPreferenceUseCase(
                    stringPreferencesKey(AI_TOOLS_ENABLED_KEY),
                    ""
                ).first()
                if (stringSavedToolsEnabled.isNotBlank()) {
                    val boolValue = stringSavedToolsEnabled.toBooleanStrictOrNull() ?: true
                    savePreferenceUseCase(booleanPreferencesKey(AI_TOOLS_ENABLED_KEY), boolValue)
                    android.util.Log.d("guru", "Init: migrated ai_tools_enabled from string '$stringSavedToolsEnabled' to boolean $boolValue")
                }
            } catch (e: ClassCastException) {
                // Already stored as Boolean — migration already ran on a previous boot, nothing to do
                android.util.Log.d("guru", "Init: ai_tools_enabled already stored as boolean, skipping string migration")
            }

            val toolsEnabledPreferenceValue = getPreferenceUseCase(
                booleanPreferencesKey(AI_TOOLS_ENABLED_KEY),
                true
            ).first()

            toolsEnabled = toolsEnabledPreferenceValue

            // Screen vision preference — read once at boot, refreshed per-send below
            visionEnabled = getPreferenceUseCase(
                booleanPreferencesKey(PrefsConstants.VISION_ENABLED_KEY),
                false
            ).first()

            humanName = getPreferenceUseCase(
                stringPreferencesKey(PrefsConstants.USER_NAME_KEY),
                ""
            ).first()

            android.util.Log.d("guru", "Init: provider=$aiProvider, toolsEnabled=$toolsEnabledPreferenceValue, humanName='$humanName'")

            if (aiProvider == AiProvider.None) {
                android.util.Log.w("guru", "No AI provider selected")
                llmExecutor = null
                llModel = null
                chatSystemMessage = ""
                return@launch
            }

            // BYO model connect: the user connected their own model. Read the
            // three device prefs, route to the right executor, build the
            // streaming client against the user's endpoint. The Unus Lumen API
            // sync below (prompts, config) still runs for EVERY provider —
            // the connected model is the engine, the Lumen API is the fuel line.
            if (aiProvider != AiProvider.UnusLumen) {
                val initialized = initializeByoProvider(aiProvider, toolsEnabledPreferenceValue)
                try {
                    PromptFetcher.fetchAndCache(context)
                    chatSystemMessage = promptRepository.getSystemPrompt()
                    android.util.Log.d("guru", "Fetched system prompt (${chatSystemMessage.length} chars)")
                } catch (e: Exception) {
                    android.util.Log.w("guru", "Failed to fetch system prompt: ${e.message}")
                }
                if (initialized) {
                    // BYO carries its own chain; no Unus Lumen fallback models.
                    fallbackModels = emptyList()
                    return@launch
                }
                // initialization failed (blank config) — fall through to the
                // UnusLumen path below so the app keeps a working wire and the
                // UI can flag the missing connection.
            }

            val serverUrl = "https://api.unuslumen.com/"

            // Fetch LLM config from the Unus Lumen API. The connection is unconditional:
            // no auth headers, no token gating, the app is wired to the API from first boot.
            LlmConfigFetcher.fetchAndCache(context)
            val llmConfig = LlmConfigFetcher.getCachedConfig(context)

            if (llmConfig == null) {
                android.util.Log.e("guru", "Init: No LLM config from server and no cached config. Cannot initialize model.")
                llmExecutor = null
                llModel = null
                chatSystemMessage = ""
                return@launch
            }

            val model = llmConfig.baseModel
            android.util.Log.d("guru", "Init: model='$model' (from server config)")

            if (model.isNotBlank()) {
                llModel = model.toLLModel(
                    aiProvider,
                    withTools = toolsEnabledPreferenceValue,
                    contextWindow = llmConfig.contextWindow.toLong(),
                    maxOutputTokens = llmConfig.maxTokens.toLong()
                )
                android.util.Log.d("guru", "Init: llModel set to ${llModel?.id}, context=${llmConfig.contextWindow}, maxTokens=${llmConfig.maxTokens}")
            } else {
                android.util.Log.e("guru", "Init: Server returned empty model name!")
            }

            llModel?.let { model ->
                try {
                    llmExecutor = aiProvider.getExecutor("", serverUrl, model, engine)
                    val trimmedUrl = serverUrl.trimEnd('/') + "/"
                    val sharedClient = TorEgress.httpClient()
                    streamingClient = UnusLumenStreamingClient(trimmedUrl, sharedClient)
                    android.util.Log.d("guru", "Init: executor and streaming client created for ${model.provider}")
                } catch (e: Exception) {
                    android.util.Log.e("guru", "Init: Failed to create streaming client: ${e.message}", e)
                    llmExecutor = null
                    streamingClient = null
                }
            }

            // Fetch system prompt from the Unus Lumen API — unconditional, no auth gate
            try {
                PromptFetcher.fetchAndCache(context)
                chatSystemMessage = promptRepository.getSystemPrompt()
                android.util.Log.d("guru", "Fetched system prompt (${chatSystemMessage.length} chars)")
            } catch (e: Exception) {
                android.util.Log.w("guru", "Failed to fetch system prompt: ${e.message}")
            }

            // Load fallback models from server config
            fallbackModels = loadFallbackModels(aiProvider, toolsEnabledPreferenceValue, llmConfig)
        }
    }

    /**
     * Load fallback models from server config. Each fallback uses the same provider (UnusLumen)
     * and the same server URL. The model names come from the server's fallback_models array.
     */
    private fun loadFallbackModels(primaryProvider: AiProvider, withTools: Boolean, config: LlmConfig): List<FallbackModel> {
        val fallbacks = mutableListOf<FallbackModel>()
        val serverUrl = "https://api.unuslumen.com/"
        for (modelName in config.fallbackModels) {
            if (modelName.isBlank()) continue
            try {
                val fbModel = modelName.toLLModel(
                    primaryProvider,
                    withTools,
                    contextWindow = config.contextWindow.toLong(),
                    maxOutputTokens = config.maxTokens.toLong()
                )
                val fbExecutor = primaryProvider.getExecutor("", serverUrl, fbModel, engine)
                fallbacks.add(FallbackModel(primaryProvider, modelName, fbModel, fbExecutor))
                android.util.Log.d("guru", "Loaded fallback: ${primaryProvider.name}/$modelName")
            } catch (e: Exception) {
                android.util.Log.w("guru", "Failed to load fallback model '$modelName': ${e.message}")
            }
        }
        return fallbacks.toList()
    }

    /**
     * BYO model connect: build executor + streaming client against the
     * provider the user chose in Settings > Integrations > AI. Returns true
     * when llModel/llmExecutor/streamingClient are wired and ready; false
     * means blank config and the caller falls through to the UnusLumen path.
     *
     * Cloud brands (OpenAI, Anthropic, Gemini, Grok) use koog's real clients
     * with fixed endpoints — the user never types a URL. Local endpoints
     * (Ollama, OpenAI-compatible) ride the app's own UnusLumenStreamingClient
     * pointed at the user's URL. Keys live on device only, in the BYO prefs;
     * they ride one HTTPS Authorization header and are never logged.
     */
    private suspend fun initializeByoProvider(provider: AiProvider, withTools: Boolean): Boolean {
        val byoKey = getPreferenceUseCase(
            stringPreferencesKey(PrefsConstants.BYO_API_KEY_KEY),
            ""
        ).first().trim()
        val byoUrl = getPreferenceUseCase(
            stringPreferencesKey(PrefsConstants.BYO_BASE_URL_KEY),
            ""
        ).first().trim()
        val byoModel = getPreferenceUseCase(
            stringPreferencesKey(PrefsConstants.BYO_MODEL_NAME_KEY),
            ""
        ).first().trim()

        if (byoModel.isBlank()) {
            android.util.Log.e("guru", "BYO: no model name set for provider=$provider")
            return false
        }
        val needsUrl = provider == AiProvider.Ollama || provider == AiProvider.OpenAICompat
        if (needsUrl && byoUrl.isBlank()) {
            android.util.Log.e("guru", "BYO: no base URL set for provider=$provider")
            return false
        }

        try {
            llModel = when (provider) {
                AiProvider.Anthropic -> {
                    // Anthropic's client resolves versions through its internal map,
                    // so the BYO model rides a copy of a known catalog entry with
                    // the user's model id swapped in.
                    ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Sonnet_4_5.copy(
                        id = byoModel,
                        provider = provider.toLLMProvider()
                    )
                }
                else -> byoModel.toLLModel(
                    provider,
                    withTools = withTools,
                    contextWindow = 1_000_000L,
                    maxOutputTokens = 131_072L
                )
            }

            llmExecutor = when (provider) {
                AiProvider.OpenAI -> SingleLLMPromptExecutor(
                    ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
                        byoKey,
                        ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                            baseUrl = provider.fixedBaseUrl()!!
                        ),
                        baseClient = sharedLongTimeoutClient()
                    )
                )
                AiProvider.XAI -> SingleLLMPromptExecutor(
                    ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
                        byoKey,
                        ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                            baseUrl = provider.fixedBaseUrl()!!
                        ),
                        baseClient = sharedLongTimeoutClient()
                    )
                )
                AiProvider.Anthropic -> SingleLLMPromptExecutor(
                    ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient(
                        apiKey = byoKey,
                        baseClient = sharedLongTimeoutClient()
                    )
                )
                AiProvider.Google -> SingleLLMPromptExecutor(
                    ai.koog.prompt.executor.clients.google.GoogleLLMClient(
                        apiKey = byoKey,
                        baseClient = sharedLongTimeoutClient()
                    )
                )
                AiProvider.Ollama -> {
                    val trimmed = byoUrl.trimEnd('/')
                    SingleLLMPromptExecutor(
                        UnusLumenClient("$trimmed/", baseClient = sharedLongTimeoutClient(), timeoutConfig = NO_TIMEOUTS)
                    )
                }
                AiProvider.OpenAICompat -> openAICompatExecutor(byoKey, byoUrl)
                else -> return false
            }

            // Ollama and OpenAI-compat stream through the app's own client
            // (Ollama/OpenAI-compatible wire), with the user's key attached
            // only when they supplied one.
            if (provider == AiProvider.Ollama || provider == AiProvider.OpenAICompat) {
                val effectiveUrl = if (byoUrl.endsWith('/')) byoUrl else "$byoUrl/"
                streamingClient = UnusLumenStreamingClient(
                    effectiveUrl,
                    sharedLongTimeoutClient(),
                    byoApiKey = byoKey.ifBlank { null }
                )
            } else {
                // Cloud brands stream through their own koog executor; the app's
                // Ollama-format streaming client is not part of their path. It
                // stays null so the send path uses the executor directly.
                streamingClient = null
            }

            android.util.Log.d(
                "guru",
                "BYO: provider=$provider url=${byoUrl.ifBlank { provider.fixedBaseUrl() ?: "fixed" }} model='$byoModel' keySet=${byoKey.isNotBlank()}"
            )
            return true
        } catch (e: Exception) {
            android.util.Log.e("guru", "BYO init failed for provider=$provider: ${e.message}", e)
            llmExecutor = null
            llModel = null
            streamingClient = null
            return false
        }
    }

    /**
     * The sovereign LLM client. Every HTTP request this app makes to any model
     * endpoint rides TorEgress: the user's local/LAN endpoints go direct,
     * everything else goes through Tor, and when Tor is down the request fails
     * closed instead of leaking clearnet. Built on the TorEgress engine rather
     * than the bare injected engine so the policy cannot be bypassed by a
     * future construction site that forgets the selector.
     */
    private fun sharedLongTimeoutClient(): HttpClient = TorEgress.httpClient()

    /**
     * Rebuild the model wiring from current device settings immediately.
     * Called by the settings layer after a BYO save (provider, endpoint, key,
     * model name) so the change lands on the next send without an app
     * restart. Runs on the application scope; the next sendMessage/sendPrompt
     * reads the freshly built executor, model and streaming client.
     */
    override fun reinitialise() {
        android.util.Log.d("guru", "reinitialise: rebuilding model wiring from current settings")
        rebuildModelWiring(fromSettingsSave = true)
    }

    /** Serialises rebuild requests so concurrent saves cannot interleave builds. */
    private val rebuildMutex = kotlinx.coroutines.sync.Mutex()
    private var rebuildQueued = false

    private fun rebuildModelWiring(fromSettingsSave: Boolean = false) {
        if (!rebuildMutex.tryLock()) {
            // A rebuild is already in flight; mark one more pass so the latest
            // saved settings always win even if they change mid-rebuild.
            rebuildQueued = true
            return
        }
        applicationScope.launch {
            try {
                // A settings save lands through DataStore write-launches; give
                // the last write a beat to become visible before reading prefs.
                if (fromSettingsSave) kotlinx.coroutines.delay(500)
                do {
                    rebuildQueued = false
                    rebuildModelWiringOnce()
                } while (rebuildQueued)
            } finally {
                rebuildMutex.unlock()
            }
        }
    }

    override suspend fun sendPrompt(prompt: String): PortalResult<String> {
        val model = llModel ?: return PortalResult.OtherError()
        val client = streamingClient ?: return PortalResult.OtherError()

        LlmConfigFetcher.fetchAndCache(context)
        val currentLlmConfig = LlmConfigFetcher.getCachedConfig(context)
            ?: LlmConfig(baseModel = model.id)

        val llmPrompt = prompt("user_prompt", LLMParams()) {
            user(prompt)
        }

        return try {
            val textBuilder = StringBuilder()
            client.executeStreamingWithTools(
                prompt = llmPrompt,
                model = model,
                tools = emptyList(),
                llmConfig = currentLlmConfig
            ).collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> textBuilder.append(frame.text)
                    is StreamFrame.TextComplete -> {
                        textBuilder.clear()
                        textBuilder.append(frame.text)
                    }
                    is StreamFrame.ReasoningDelta -> {}
                    is StreamFrame.ToolCallDelta -> {}
                    is StreamFrame.ToolCallComplete -> {}
                    is StreamFrame.ReasoningComplete -> {}
                    is StreamFrame.End -> {}
                }
            }
            PortalResult.Success(textBuilder.toString())
        } catch (e: LLMClientException) {
            PortalResult.OtherError(e.message)
        } catch (e: IOException) {
            e.printStackTrace()
            PortalResult.InternetError
        } catch (e: Exception) {
            e.printStackTrace()
            PortalResult.OtherError(e.getRootCause().message ?: e.message)
        }
    }

    @OptIn(InternalAgentToolsApi::class)
    override fun sendMessage(messages: List<AiMessage>, conversationId: String?): Flow<AiMessage> = flow {
        val model =
            llModel ?: throw AiRepositoryException(PortalResult.OtherError("Model not selected"))
        val executor = llmExecutor
            ?: throw AiRepositoryException(PortalResult.OtherError("AI Client not initialized"))

        // Fetch fresh LLM config from the Unus Lumen API so superadmin changes take
        // effect without app restart. ONLY the Unus Lumen provider gates on this:
        // BYO endpoints carry no server model, so their wire options come from
        // device defaults. The old unconditional gate killed every BYO send with
        // "No LLM config from server" before the model endpoint was ever hit.
        val selectedProvider = getPreferenceUseCase(
            intPreferencesKey(AI_PROVIDER_KEY),
            AiProvider.UnusLumen.id
        ).first().toAiProvider()

        val currentLlmConfig: LlmConfig = if (selectedProvider == AiProvider.UnusLumen) {
            LlmConfigFetcher.fetchAndCache(context)
            LlmConfigFetcher.getCachedConfig(context)
                ?: throw AiRepositoryException(PortalResult.OtherError("No LLM config from server"))
        } else {
            // BYO: engine options come from device prefs (Model Connect > Engine
            // settings). Blank or unparseable fields fall back to LlmConfig defaults.
            suspend fun opt(key: com.unuslumen.app.preferences.domain.model.PrefsKey<String>, fallback: String): String =
                try {
                    getPreferenceUseCase(key, "").first().trim().takeIf { it.isNotEmpty() } ?: fallback
                } catch (e: Exception) { fallback }
            LlmConfig(
                baseModel = model.id,
                temperature = opt(stringPreferencesKey(PrefsConstants.BYO_TEMPERATURE_KEY), "").toFloatOrNull()
                    ?: LlmConfig().temperature,
                maxTokens = opt(stringPreferencesKey(PrefsConstants.BYO_MAX_TOKENS_KEY), "").toIntOrNull()
                    ?: LlmConfig().maxTokens,
                contextWindow = opt(stringPreferencesKey(PrefsConstants.BYO_CONTEXT_WINDOW_KEY), "").toIntOrNull()
                    ?: LlmConfig().contextWindow,
                thinkingLevel = opt(stringPreferencesKey(PrefsConstants.BYO_THINKING_LEVEL_KEY), LlmConfig().thinkingLevel),
                topP = opt(stringPreferencesKey(PrefsConstants.BYO_TOP_P_KEY), "").toFloatOrNull()
                    ?: LlmConfig().topP,
                topK = opt(stringPreferencesKey(PrefsConstants.BYO_TOP_K_KEY), "").toIntOrNull()
                    ?: LlmConfig().topK,
                repeatPenalty = opt(stringPreferencesKey(PrefsConstants.BYO_REPEAT_PENALTY_KEY), "").toFloatOrNull()
                    ?: LlmConfig().repeatPenalty,
                seed = opt(stringPreferencesKey(PrefsConstants.BYO_SEED_KEY), "").toIntOrNull()
                    ?: LlmConfig().seed
            )
        }

        var currentMessages = messages
        var consecutiveToolCalls = 0
        val resolvedConversationId = conversationId ?: kotlin.uuid.Uuid.random().toString()

        android.util.Log.d("guru", "sendMessage: model=${model.id}, provider=${model.provider}, toolsEnabled=$toolsEnabled")

        val memoryContext = try {
            val userMessage = messages.filterIsInstance<AiMessage.UserMessage>().lastOrNull()
            if (userMessage != null) {
                HookEventBus.fire(
                    com.unuslumen.app.domain.model.HookEventType.MESSAGE_SENT,
                    mapOf(
                        "conversationId" to (conversationId ?: "new"),
                        "messageId" to userMessage.uuid,
                        "content" to userMessage.content
                    )
                )
                memoryRepository.buildContextPreamble(userMessage.content, humanName, currentMessages)
            } else null
        } catch (e: Exception) {
            android.util.Log.w("guru", "Memory context failed: ${e.message}")
            null
        }

        // Notes to self: match Guru's saved keyword triggers against the user's
        // message BEFORE assembling the system message, so a fired note lands in
        // context on this very turn, not the next one.
        val notesToSelfContent = try {
            messages.filterIsInstance<AiMessage.UserMessage>().lastOrNull()?.content
                ?.let { matchedNotesToSelf(it) }
                ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("guru", "Notes-to-self lookup failed: ${e.message}")
            emptyList()
        }
        if (notesToSelfContent.isNotEmpty()) {
            android.util.Log.d("guru", "Notes-to-self fired: ${notesToSelfContent.size} note(s)")
        }

        val notesToSelfBlock = if (notesToSelfContent.isNotEmpty()) {
            "Notes to yourself (triggered by what ${humanName.ifBlank { "your human" } } just said, treat as your own reminders):\n" +
                notesToSelfContent.joinToString(separator = "\n\n") { "- $it" }
        } else ""

        val enrichedSystemMessage = when {
            memoryContext != null && memoryContext.preamble.isNotBlank() && notesToSelfBlock.isNotBlank() -> {
                android.util.Log.d("guru", "Using memory context (${memoryContext.preamble.length} chars) + notes-to-self (${notesToSelfBlock.length} chars)")
                "$chatSystemMessage\n\nContext from your memory:\n${memoryContext.preamble}\n\n$notesToSelfBlock"
            }
            memoryContext != null && memoryContext.preamble.isNotBlank() -> {
                android.util.Log.d("guru", "Using memory context (${memoryContext.preamble.length} chars)")
                "$chatSystemMessage\n\nContext from your memory:\n${memoryContext.preamble}"
            }
            notesToSelfBlock.isNotBlank() -> {
                android.util.Log.d("guru", "No memory context, notes-to-self present")
                "$chatSystemMessage\n\n$notesToSelfBlock"
            }
            else -> {
                android.util.Log.d("guru", "No memory context, using system message only")
                chatSystemMessage
            }
        }

        try {
            // Only create a new conversation if we don't have an existing one
            if (conversationId == null) {
                val firstUserMessage = messages.filterIsInstance<AiMessage.UserMessage>().firstOrNull()?.content
                val title = firstUserMessage?.let { truncateTitle(it, 77) } ?: "New conversation"
                memoryRepository.createConversation(
                    Conversation(
                        id = resolvedConversationId,
                        title = title,
                        createdDate = System.currentTimeMillis(),
                        updatedDate = System.currentTimeMillis()
                    )
                )
                android.util.Log.d("guru", "Created new conversation: $resolvedConversationId")
                HookEventBus.fire(
                    com.unuslumen.app.domain.model.HookEventType.CONVERSATION_STARTED,
                    mapOf(
                        "conversationId" to resolvedConversationId,
                        "firstMessage" to (firstUserMessage ?: "")
                    )
                )
            } else {
                // Update the existing conversation's updatedDate
                val existing = memoryRepository.getConversation(resolvedConversationId)
                if (existing != null) {
                    memoryRepository.updateConversation(
                        existing.copy(updatedDate = System.currentTimeMillis())
                    )
                } else {
                    // Conversation was deleted — recreate it
                    val firstUserMessage = messages.filterIsInstance<AiMessage.UserMessage>().firstOrNull()?.content
                    val title = firstUserMessage?.let { truncateTitle(it, 77) } ?: "Continued conversation"
                    memoryRepository.createConversation(
                        Conversation(
                            id = resolvedConversationId,
                            title = title,
                            createdDate = System.currentTimeMillis(),
                            updatedDate = System.currentTimeMillis()
                        )
                    )
                    android.util.Log.d("guru", "Recreated deleted conversation: $resolvedConversationId")
                }
                android.util.Log.d("guru", "Updated conversation timestamp: $resolvedConversationId")
            }
        } catch (e: Exception) {
            android.util.Log.e("guru", "CRITICAL: Failed to create/update conversation: ${e.message}", e)
            // Don't swallow — the conversation row will be ensured in persistAiMessages as a fallback
        }

        try {
            do {
                if (consecutiveToolCalls >= MAX_CONSECUTIVE_TOOL_CALLS) {
                    throw AiRepositoryException(PortalResult.ToolCallLimitExceeded)
                }

                android.util.Log.d("guru", "Executing LLM request (attempt ${consecutiveToolCalls + 1})...")

                // Refresh tool descriptors to include any newly approved Guru-defined tools.
                // Fix 1: keep the REAL ToolDefinitions — the model sees full parameter
                // schemas (names, types, required, enums) built from these downstream.
                // The old path collapsed each definition to ToolDescriptor(name, description),
                // which threw away the entire parameter list and left the LLM guessing.
                val currentToolDescriptors: List<ai.koog.agents.core.tools.ToolDescriptor> = if (toolsEnabled) {
                    try {
                        toolRegistry.getAllDefinitions().map { ai.koog.agents.core.tools.ToolDescriptor(it.name, it.description) }
                    } catch (e: Exception) {
                        android.util.Log.w("guru", "Failed to build tool descriptors: ${e.message}")
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                // Fix 1: parallel list of REAL definitions, aligned 1:1 with the descriptors
                // above (same source, same order, same exception fallback). The streaming
                // client uses these to emit full Ollama function parameter schemas.
                val currentToolDefinitions: List<com.unuslumen.app.data.tools.registry.ToolDefinition> = if (toolsEnabled) {
                    try {
                        toolRegistry.getAllDefinitions()
                    } catch (e: Exception) {
                        android.util.Log.w("guru", "Failed to load tool definitions: ${e.message}")
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                // Build the list of (model, executor) to try: primary first, then fallbacks
                val modelChain = buildList {
                    add(model to executor)
                    fallbackModels.forEach { fb -> add(fb.llModel to fb.executor) }
                }

                // Per-iteration metadata + compaction config. Fresh fetch per attempt so
                // superadmin edits apply immediately; cached config if the API is unreachable.
                MetadataConfigFetcher.fetchAndCache(context)
                val metadataConfig = MetadataConfigFetcher.getCachedConfig(context)

                // Context accounting for this attempt
                val tokenEstimate = ContextManager.estimateTokenCount(currentMessages)
                val contextWindow = currentLlmConfig.contextWindow

                // Layer 2: GURU self-compaction. When token usage crosses the
                // server-configured threshold, compact with the server's prompt, DB-first.
                var compactionPointerText: String? = null
                if (metadataConfig?.compaction?.enabled == true && currentLlmConfig.contextWindow > 0) {
                    val thresholdPercent = metadataConfig.compaction.thresholdPercent.coerceIn(1, 100)
                    if (tokenEstimate > contextWindow * thresholdPercent / 100) {
                        android.util.Log.w("guru", "Layer2 auto-compact triggered: est=$tokenEstimate threshold=$thresholdPercent% of $contextWindow")
                        val currentExecutor = llmExecutor
                        val currentModel = llModel
                        val compacted = ContextManager.performAutoCompact(
                            messages = currentMessages,
                            summaryPrompt = metadataConfig.compaction.summaryPrompt,
                            keepRecent = metadataConfig.compaction.keepRecent,
                            executorProvider = { currentExecutor as? ai.koog.prompt.executor.llms.SingleLLMPromptExecutor },
                            modelProvider = { currentModel },
                            persistDbFirst = {
                                // DB-FIRST: history is already written to device DB on every
                                // exit path via memoryRepository.persistAiMessages. Nothing to
                                // clear here — compaction only reshapes the LLM's view.
                            },
                            metadataConfig = metadataConfig,
                            conversationId = resolvedConversationId
                        )
                        if (compacted != null) {
                            compactionPointerText = compacted.pointerText
                            compactedConversations.add(resolvedConversationId)
                            currentMessages = compacted.messages
                            android.util.Log.d("guru", "Layer2 compact complete: new est=${ContextManager.estimateTokenCount(currentMessages)}")
                        }
                    }
                }

                // Build THIS iteration's metadata block — fresh values every pass.
                // Injected per receive direction when configured server-side.
                val iterationSystemMessage = if (metadataConfig?.enabled == true && metadataConfig.injectOnReceive) {
                    val block = try {
                        metadataAssembler.assemble(
                            config = metadataConfig,
                            sendContext = MetadataSendContext(
                                sessionId = resolvedConversationId,
                                messages = currentMessages,
                                contextWindowTokens = contextWindow,
                                currentTokenEstimate = tokenEstimate + (compactionPointerText?.length ?: 0) / 4,
                                compacted = compactedConversations.contains(resolvedConversationId)
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("guru", "Metadata assembly failed (continuing without block): ${e.message}")
                        null
                    }
                    listOfNotNull(
                        enrichedSystemMessage,
                        block?.takeIf { it.isNotBlank() },
                        compactionPointerText?.takeIf { it.isNotBlank() }
                    ).joinToString("\n\n")
                } else {
                    enrichedSystemMessage
                }

                if (metadataConfig?.enabled == true) {
                    android.util.Log.d("guru", "Metadata block: enabled, iterationSystemMessage length now ${iterationSystemMessage.length} (base ${enrichedSystemMessage.length})")
                }

                var lastError: LLMClientException? = null
                var succeeded = false
                var hadToolCalls = false
                val maxRetries = 4

                for ((tryModel, tryExecutor) in modelChain) {
                    var retryCount = 0
                    while (retryCount < maxRetries) {
                        try {
                            // Layer 1: Microcompact — strip old tool results before sending to LLM
                            val messagesForLLM = ContextManager.microcompact(currentMessages)

                            // Multimodal plumbing — BEFORE building the prompt, turn tool
                            // result / user message file paths into image blocks the engine
                            // can actually see. Capped at 4 images per request.
                            // Refresh vision flag per-send so /vision takes effect immediately.
                            visionEnabled = try {
                                getPreferenceUseCase(
                                    booleanPreferencesKey(PrefsConstants.VISION_ENABLED_KEY),
                                    false
                                ).first()
                            } catch (e: Exception) { visionEnabled }
                            imageTurnAssembler.visionEnabled = visionEnabled

                            val assembled = try {
                                imageTurnAssembler.assemble(messagesForLLM)
                            } catch (e: Exception) {
                                android.util.Log.w("guru", "ImageTurnAssembler failed (continuing text-only): ${e.message}")
                                null
                            }

                            // Vision capture failed while enabled: tell the model in-band so
                            // it can say its eyes are dark instead of silently going blind.
                            val visionFailureNote = if (visionEnabled && assembled?.captureFailed == true) {
                                "\n\n[System note: screen vision is ON but no frame could be captured this message. Ask the user to run requestScreenCapturePermission and re-allow, or /vision off to stop this note.]" +
                                " [If you are repeatedly unable to see the screen, tell the user directly rather than pretending you can see.]"
                            } else ""
                            if (visionFailureNote.isNotEmpty()) {
                                android.util.Log.w("guru", "Vision enabled but capture failed — in-band note attached")
                            }

                            streamingClient?.assembledImages = assembled?.byMessage ?: emptyMap()
                            if (assembled != null && assembled.totalImages > 0) {
                                android.util.Log.d("guru", "Multimodal: sending ${assembled.totalImages} image block(s) this request (paths: ${assembled.injectedPaths.size})")
                            }

                            val chatPromptBase = messagesForLLM.buildChatPrompt(
                                systemMessage = iterationSystemMessage,
                                tools = currentToolDescriptors
                            )

                            // Universal image delivery — stitch assembled blocks INTO the
                            // koog prompt as ContentPart.Image parts on the last user
                            // message. Only done when the Ollama-format streaming client
                            // is NOT in play: that client reads assembledImages directly
                            // (user images + toolImagesFallback onto the user message's
                            // images array), so stitching here too would deliver each
                            // image twice. Cloud BYO rides koog brand clients, which get
                            // their images ONLY from prompt parts — until this stitch,
                            // cloud paths silently dropped every capture.
                            val userBlocks = assembled?.byMessage?.get(ImageTurnAssembler.USER_KEY) ?: emptyList()
                            val chatPrompt: Prompt = if (streamingClient == null && userBlocks.isNotEmpty()) {
                                val lastIdx = chatPromptBase.messages.indexOfLast { it is Message.User }
                                if (lastIdx >= 0) {
                                    val baseUser = chatPromptBase.messages[lastIdx] as Message.User
                                    val textContent = baseUser.content + visionFailureNote
                                    val parts: List<ContentPart> = buildList {
                                        add(ContentPart.Text(textContent))
                                        for (block in userBlocks) {
                                            val b64 = block.dataUri.substringAfter(";base64,", "")
                                            if (b64.isNotBlank()) {
                                                add(ContentPart.Image(
                                                    content = AttachmentContent.Binary.Base64(b64),
                                                    format = "jpeg"
                                                ))
                                            }
                                        }
                                    }
                                    chatPromptBase.withMessages { msgs ->
                                        msgs.mapIndexed { idx, m ->
                                            if (idx == lastIdx) Message.User(
                                                parts,
                                                m.metaInfo as ai.koog.prompt.message.RequestMetaInfo
                                            ) else m
                                        }
                                    }
                                } else chatPromptBase
                            } else if (visionFailureNote.isNotEmpty()) {
                                // No images this turn (or Ollama wire): carry the
                                // vision-failure note in-band so a blind turn is honest
                                // on every provider too.
                                chatPromptBase.withMessages { msgs ->
                                    val lastUserIdx = msgs.indexOfLast { it is Message.User }
                                    if (lastUserIdx >= 0) {
                                        msgs.mapIndexed { idx, m ->
                                            if (idx == lastUserIdx) {
                                                val u = m as Message.User
                                                Message.User(
                                                    listOf(ContentPart.Text(u.content + visionFailureNote)) +
                                                        u.parts.filter { it !is ContentPart.Text },
                                                    u.metaInfo as ai.koog.prompt.message.RequestMetaInfo
                                                )
                                            } else m
                                        }
                                    } else msgs
                                }
                            } else chatPromptBase

                            val streamingMsgId = Uuid.random().toString()
                            val textBuilder = StringBuilder()
                            val thinkingBuilder = StringBuilder()
                            val collectedToolCalls = mutableListOf<StreamFrame.ToolCallComplete>()
                            val streamingStartTime = nowMillis()

                            emit(AiMessage.StreamingAssistant(
                                uuid = streamingMsgId,
                                partialContent = "",
                                partialThinking = "",
                                time = streamingStartTime
                            ))

                            // Stream source: the app's own Ollama-format client for the
                            // Lumen gateway and local endpoints; koog's brand client for
                            // cloud providers the user connected (their wire differs).
                            val streamFlow = streamingClient?.executeStreamingWithTools(
                                prompt = chatPrompt,
                                model = tryModel,
                                tools = currentToolDescriptors,
                                toolDefinitions = currentToolDefinitions,
                                llmConfig = currentLlmConfig
                            ) ?: run {
                                val koogExecutor = (tryExecutor as? SingleLLMPromptExecutor)
                                    ?: throw AiRepositoryException(PortalResult.OtherError("No streaming path available for the connected model"))
                                koogExecutor.executeStreaming(prompt = chatPrompt, model = tryModel, tools = currentToolDescriptors)
                            }

                            streamFlow.collect { frame ->
                                when (frame) {
                                    is StreamFrame.TextDelta -> {
                                        textBuilder.append(frame.text)
                                        emit(AiMessage.StreamingAssistant(
                                            uuid = streamingMsgId,
                                            partialContent = textBuilder.toString(),
                                            partialThinking = thinkingBuilder.toString(),
                                            time = streamingStartTime
                                        ))
                                    }
                                    is StreamFrame.TextComplete -> {
                                        if (frame.text.length > textBuilder.length) {
                                            textBuilder.clear()
                                            textBuilder.append(frame.text)
                                        }
                                        emit(AiMessage.StreamingAssistant(
                                            uuid = streamingMsgId,
                                            partialContent = textBuilder.toString(),
                                            partialThinking = thinkingBuilder.toString(),
                                            time = streamingStartTime
                                        ))
                                    }
                                    is StreamFrame.ReasoningDelta -> {
                                        thinkingBuilder.append(frame.text)
                                        emit(AiMessage.StreamingAssistant(
                                            uuid = streamingMsgId,
                                            partialContent = textBuilder.toString(),
                                            partialThinking = thinkingBuilder.toString(),
                                            time = streamingStartTime
                                        ))
                                    }
                                    is StreamFrame.ToolCallDelta -> {
                                        emit(AiMessage.StreamingToolCall(
                                            uuid = frame.id ?: Uuid.random().toString(),
                                            toolName = frame.name ?: "",
                                            partialContent = frame.content ?: "",
                                            time = nowMillis()
                                        ))
                                    }
                                    is StreamFrame.ToolCallComplete -> {
                                        collectedToolCalls.add(frame)
                                    }
                                    is StreamFrame.ReasoningComplete -> {}
                                    is StreamFrame.End -> {
                                        android.util.Log.d("guru", "Stream ended: ${frame.finishReason}")
                                    }
                                }
                            }

                            succeeded = true
                            val finalText = textBuilder.toString()
                            val finalThinking = thinkingBuilder.toString()

                            if (collectedToolCalls.isEmpty()) {
                                android.util.Log.d("guru", "No tool calls, emitting final assistant message (${finalText.length} chars)")
                                if (finalText.isNotBlank()) {
                                    val assistantMessage = AiMessage.AssistantMessage(
                                        content = finalText,
                                        time = nowMillis(),
                                        uuid = streamingMsgId,
                                        thinkingTokens = finalThinking
                                    )
                                    emit(assistantMessage)
                                    currentMessages = currentMessages + assistantMessage
                                    HookEventBus.fire(
                                        com.unuslumen.app.domain.model.HookEventType.MESSAGE_RECEIVED,
                                        mapOf(
                                            "conversationId" to resolvedConversationId,
                                            "messageId" to assistantMessage.uuid,
                                            "content" to assistantMessage.content
                                        )
                                    )
                                }
                                break
                            }

                            android.util.Log.d("guru", "Tool calls: ${collectedToolCalls.size}, names: ${collectedToolCalls.map { it.name }}")
                            consecutiveToolCalls++
                            hadToolCalls = true

                            if (finalText.isNotBlank()) {
                                val assistantMessage = AiMessage.AssistantMessage(
                                    content = finalText,
                                    time = nowMillis(),
                                    uuid = streamingMsgId,
                                    thinkingTokens = finalThinking
                                )
                                emit(assistantMessage)
                                currentMessages = currentMessages + assistantMessage
                            }

                            val toolCallMessages = collectedToolCalls.map { toolCallFrame ->
                                val toolCallMessageResult = executeToolCallFromFrame(toolCallFrame)
                                toolCallMessageResult.getOrNull()?.also {
                                    emit(it)
                                    HookEventBus.fire(
                                        com.unuslumen.app.domain.model.HookEventType.APP_STARTED,
                                        mapOf(
                                            "kind" to "tool_call",
                                            "conversationId" to resolvedConversationId,
                                            "toolName" to it.name,
                                            "success" to !it.isFailed
                                        )
                                    )
                                }
                                    ?: AiMessage.ToolCall(
                                        uuid = toolCallFrame.id ?: Uuid.random().toString(),
                                        id = toolCallFrame.id,
                                        name = toolCallFrame.name,
                                        rawContent = toolCallFrame.content,
                                        resultRawContent = toolCallMessageResult.exceptionOrNull()
                                            ?.getRootCause()?.toString() ?: "Error executing tool",
                                        time = nowMillis(),
                                        isFailed = true
                                    ).also { emit(it) }
                            }

                            // Write each tool result to the tool_results table (synchronous, awaited)
                            // Timestamp is taken from the AiMessage.ToolCall's time field, not a fresh clock read
                            // This ensures exact timestamp equality between entity and message for duplicate detection
                            for (toolCallMsg in toolCallMessages) {
                                if (toolCallMsg is AiMessage.ToolCall) {
                                    try {
                                        val truncatedResult = ToolResultEntity.truncateResult(toolCallMsg.resultRawContent)
                                        val truncatedParams = ToolResultEntity.truncateParameters(toolCallMsg.rawContent)
                                        val resultText = ToolResultEntity.flattenJsonToText(truncatedResult)
                                        val ttlMinutes = ToolResultEntity.getTtlForTool(toolCallMsg.name)
                                        val signature = try {
                                            com.unuslumen.app.data.brain.SignatureEngine.generateSignature(toolCallMsg.resultRawContent)
                                        } catch (e: Exception) { "" }
                                        toolResultDao.insertToolResult(ToolResultEntity(
                                            id = toolCallMsg.uuid,
                                            toolName = toolCallMsg.name,
                                            parameters = truncatedParams,
                                            result = truncatedResult,
                                            resultText = resultText,
                                            timestamp = toolCallMsg.time,
                                            conversationId = resolvedConversationId,
                                            success = !toolCallMsg.isFailed && !truncatedResult.contains("\"success\":false", ignoreCase = true) && !truncatedResult.contains("\"success\": false", ignoreCase = true),
                                            ttlMinutes = ttlMinutes,
                                            resultSignature = signature
                                        ))
                                    } catch (e: Exception) {
                                        android.util.Log.w("guru", "Failed to write tool result to DB: ${e.message}")
                                    }
                                }
                            }

                            currentMessages = currentMessages + toolCallMessages
                            break

                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Interruption (new user message or stop button) — propagate
                            // silently. Wrapping it would turn a clean cancel into a
                            // user-visible error and eat the interrupting message.
                            throw e
                        } catch (e: LLMClientException) {
                            lastError = e
                            val errMsg = e.message ?: ""
                            val isRetryable = errMsg.contains("503", ignoreCase = true) ||
                                    errMsg.contains("overloaded", ignoreCase = true) ||
                                    errMsg.contains("temporarily", ignoreCase = true) ||
                                    errMsg.contains("Connection refused", ignoreCase = true) ||
                                    errMsg.contains("ConnectException", ignoreCase = true) ||
                                    errMsg.contains("500", ignoreCase = true) ||
                                    errMsg.contains("timeout", ignoreCase = true)

                            retryCount++
                            if (isRetryable && retryCount < maxRetries) {
                                val delayMs = 2000L * retryCount
                                android.util.Log.w("guru", "LLM error on ${tryModel.id} (retry $retryCount/$maxRetries), retrying in ${delayMs}ms: ${errMsg.take(100)}")
                                kotlinx.coroutines.delay(delayMs)
                            } else if (modelChain.size > 1 && retryCount >= maxRetries) {
                                android.util.Log.w("guru", "Model ${tryModel.id} failed after $maxRetries retries, trying next fallback...")
                                break
                            } else {
                                throw e
                            }
                        }
                    }
                    if (succeeded) break
                }

                if (!succeeded) {
                    throw lastError ?: LLMClientException("unknown", "All models failed after retries")
                }

                if (!hadToolCalls) break

            } while (hadToolCalls)
        } catch (e: AiRepositoryException) {
            android.util.Log.e("guru", "AI error: ${e.message}")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Interruption — let it propagate untouched so the ViewModel treats it
            // as a clean stop, not an error, and keeps the interrupting message.
            throw e
        } catch (e: LLMClientException) {
            android.util.Log.e("guru", "LLM Client error: ${e.message}", e)
            val errorMsg = e.message ?: "Unknown error"
            val userMsg = when {
                errorMsg.contains("Connection refused", ignoreCase = true) ->
                    "Cannot connect to the server. Make sure the gateway is running and the URL is correct."
                errorMsg.contains("timeout", ignoreCase = true) ->
                    "Connection timed out. Check your network and server URL."
                errorMsg.contains("404", ignoreCase = true) ->
                    "Model not found. Make sure the model is available on the server."
                errorMsg.contains("500", ignoreCase = true) || errorMsg.contains("Internal Server Error", ignoreCase = true) ->
                    "Server error (500). The server crashed internally — try restarting it. If it keeps happening, the model may be too large for available memory. Try a smaller model."
                errorMsg.contains("503", ignoreCase = true) || errorMsg.contains("overloaded", ignoreCase = true) ->
                    "Model is temporarily overloaded (503). The server is busy — try again in a moment, or switch to a smaller model."
                else -> "LLM error: $errorMsg"
            }
            throw AiRepositoryException(PortalResult.OtherError(userMsg))
        } catch (e: IOException) {
            e.printStackTrace()
            throw AiRepositoryException(PortalResult.OtherError("Network error: ${e.message}. Check if the server is running and accessible."))
        } catch (e: Exception) {
            // CancellationException IS an exception — a cancel bubbling out of the
            // do/while must not be converted into an error for the UI.
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            val message = e.getRootCause().message ?: e.message
            throw AiRepositoryException(PortalResult.OtherError(message))
        } finally {
            // Persist on EVERY exit path — including interruption — so partial Guru
            // content and the interrupting user message both survive app restarts.
            // MUST run in NonCancellable: this block executes inside a coroutine that
            // an interrupt has just cancelled, so any suspend call here would throw
            // JobCancellationException before writing. NonCancellable lets the persist
            // finish on the cancelled coroutine.
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    memoryRepository.persistAiMessages(resolvedConversationId, currentMessages)
                    val messageCount = currentMessages.size
                    val existingConv = memoryRepository.getConversation(resolvedConversationId)
                    if (existingConv != null) {
                        memoryRepository.updateConversation(
                            existingConv.copy(
                                updatedDate = System.currentTimeMillis(),
                                messageCount = messageCount
                            )
                        )
                    }
                    android.util.Log.d("guru", "Persisted $messageCount messages for conversation $resolvedConversationId")
                } catch (e: Exception) {
                    android.util.Log.e("guru", "CRITICAL: Failed to persist messages: ${e.message}", e)
                }
            }
        }

        // Persistence now happens in the finally block above — on success, on error,
        // AND on interruption — so the conversation context always survives.
    }

    private suspend fun executeToolCallFromFrame(
        frame: StreamFrame.ToolCallComplete
    ): Result<AiMessage.ToolCall> = runCatching {
        if (frame.name.isBlank()) {
            throw IllegalArgumentException("Tool call received with empty tool name. Content: ${frame.content.take(200)}")
        }

        // Check if this is a Guru-defined tool first
        val isGuruTool = guruToolRegistryManager.isGuruTool(frame.name)
        if (isGuruTool) {
            val args = frame.contentJson.mapValues { (_, value) ->
                when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        val prim = value
                        when {
                            prim.isString -> prim.content
                            prim.content == "true" -> true
                            prim.content == "false" -> false
                            prim.content.contains('.') -> prim.content.toDoubleOrNull() ?: prim.content
                            else -> prim.content.toLongOrNull() ?: prim.content
                        }
                    }
                    else -> value.toString()
                }
            }
            val resultJson = guruToolRegistryManager.executeGuruTool(frame.name, args)
            val resultObject = extractResultObject(frame.name, resultJson)
            AiMessage.ToolCall(
                uuid = frame.id ?: Uuid.random().toString(),
                id = frame.id,
                name = frame.name,
                rawContent = frame.content,
                resultRawContent = resultJson,
                time = nowMillis(),
                resultObject = resultObject
            )
        } else if (toolRegistry.hasTool(frame.name)) {
            // New registry dispatch path
            android.util.Log.d("guru", "Dispatching '${frame.name}' through new ToolDispatcher")
            val dispatchResult = toolDispatcher.dispatch(frame.name, frame.contentJson)
            if (dispatchResult.success) {
                val resultObject = extractResultObject(frame.name, dispatchResult.rawJson)
                AiMessage.ToolCall(
                    uuid = frame.id ?: Uuid.random().toString(),
                    id = frame.id,
                    name = dispatchResult.resolvedName ?: frame.name,
                    rawContent = frame.content,
                    resultRawContent = dispatchResult.rawJson,
                    time = nowMillis(),
                    resultObject = resultObject
                )
            } else {
                android.util.Log.e("guru", "Tool dispatch failed for '${frame.name}': ${dispatchResult.error}")
                AiMessage.ToolCall(
                    uuid = frame.id ?: Uuid.random().toString(),
                    id = frame.id,
                    name = frame.name,
                    rawContent = frame.content,
                    resultRawContent = """{"error":"${dispatchResult.error}"}""",
                    time = nowMillis(),
                    resultObject = null
                )
            }
        } else {
            // Try ToolDispatcher's name resolution and security checks
            val dispatchResult = toolDispatcher.dispatch(frame.name, frame.contentJson)
            if (dispatchResult.success) {
                val resultObject = extractResultObject(dispatchResult.resolvedName ?: frame.name, dispatchResult.rawJson)
                AiMessage.ToolCall(
                    uuid = frame.id ?: Uuid.random().toString(),
                    id = frame.id,
                    name = dispatchResult.resolvedName ?: frame.name,
                    rawContent = frame.content,
                    resultRawContent = dispatchResult.rawJson,
                    time = nowMillis(),
                    resultObject = resultObject
                )
            } else {
                android.util.Log.e("guru", "Tool dispatch failed: ${dispatchResult.error}")
                AiMessage.ToolCall(
                    uuid = frame.id ?: Uuid.random().toString(),
                    id = frame.id,
                    name = frame.name,
                    rawContent = frame.content,
                    resultRawContent = """{"error":"${dispatchResult.error}"}""",
                    time = nowMillis(),
                    resultObject = null
                )
            }
        }
    }

    private suspend fun executeToolCall(
        toolCall: Message.Tool.Call
    ): Result<AiMessage.ToolCall> = runCatching {
        if (toolCall.tool.isBlank()) {
            throw IllegalArgumentException("Tool call received with empty tool name. Content: ${toolCall.content.take(200)}")
        }

        val isGuruTool = guruToolRegistryManager.isGuruTool(toolCall.tool)
        if (isGuruTool) {
            val args = toolCall.contentJson.mapValues { (_, value) ->
                when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        val prim = value
                        when {
                            prim.isString -> prim.content
                            prim.content == "true" -> true
                            prim.content == "false" -> false
                            prim.content.contains('.') -> prim.content.toDoubleOrNull() ?: prim.content
                            else -> prim.content.toLongOrNull() ?: prim.content
                        }
                    }
                    else -> value.toString()
                }
            }
            val resultJson = guruToolRegistryManager.executeGuruTool(toolCall.tool, args)
            val resultObject = extractResultObject(toolCall.tool, resultJson)
            AiMessage.ToolCall(
                uuid = toolCall.id ?: Uuid.random().toString(),
                id = toolCall.id,
                name = toolCall.tool,
                rawContent = toolCall.content,
                resultRawContent = resultJson,
                time = nowMillis(),
                resultObject = resultObject
            )
        } else {
            val dispatchResult = toolDispatcher.dispatch(toolCall.tool, toolCall.contentJson)
            if (dispatchResult.success) {
                val resultObject = extractResultObject(dispatchResult.resolvedName ?: toolCall.tool, dispatchResult.rawJson)
                AiMessage.ToolCall(
                    uuid = toolCall.id ?: Uuid.random().toString(),
                    id = toolCall.id,
                    name = dispatchResult.resolvedName ?: toolCall.tool,
                    rawContent = toolCall.content,
                    resultRawContent = dispatchResult.rawJson,
                    time = nowMillis(),
                    resultObject = resultObject
                )
            } else {
                AiMessage.ToolCall(
                    uuid = toolCall.id ?: Uuid.random().toString(),
                    id = toolCall.id,
                    name = toolCall.tool,
                    rawContent = toolCall.content,
                    resultRawContent = """{"error":"${dispatchResult.error}"}""",
                    time = nowMillis(),
                    resultObject = null
                )
            }
        }
    }

    private suspend fun extractResultObject(
        toolName: String,
        resultJson: String
    ): ToolCallResultObject? = when (toolName) {
        // Notes
        "searchNotes" -> runCatching {
            val searchResult = json.decodeFromString<SearchNotesResult>(resultJson)
            if (searchResult.notes.size == 1) ToolCallResultObject.Notes(searchResult.notes) else null
        }.getOrNull()

        "createNote" -> runCatching {
            val createResult = json.decodeFromString<NoteIdResult>(resultJson)
            getNote(createResult.createdNoteId)?.let {
                ToolCallResultObject.Notes(listOf(it))
            }
        }.getOrNull()

        "createMultipleNotes" -> runCatching {
            val createResult = json.decodeFromString<NoteIdsResult>(resultJson)
            val notes = createResult.createdNoteIds.mapNotNull { getNote(it) }
            if (notes.isNotEmpty()) ToolCallResultObject.Notes(notes) else null
        }.getOrNull()

        "getAllNotes" -> runCatching {
            val searchResult = json.decodeFromString<SearchNotesResult>(resultJson)
            if (searchResult.notes.size <= 5) ToolCallResultObject.Notes(searchResult.notes) else null
        }.getOrNull()

        "getNotesByFolder" -> runCatching {
            val searchResult = json.decodeFromString<SearchNotesResult>(resultJson)
            if (searchResult.notes.size <= 5) ToolCallResultObject.Notes(searchResult.notes) else null
        }.getOrNull()

        "updateNote" -> runCatching {
            val result = json.decodeFromString<NoteResult>(resultJson)
            ToolCallResultObject.Notes(listOf(result.note))
        }.getOrNull()

        // Tasks
        "createTask" -> runCatching {
            val createResult = json.decodeFromString<TaskIdResult>(resultJson)
            getTaskById(createResult.createdTaskId)?.let {
                ToolCallResultObject.Tasks(listOf(it))
            }
        }.getOrNull()

        "createMultipleTasks" -> runCatching {
            val createResult = json.decodeFromString<TaskIdsResult>(resultJson)
            val tasks = createResult.createdTaskIds.mapNotNull { getTaskById(it) }
            if (tasks.isNotEmpty()) ToolCallResultObject.Tasks(tasks) else null
        }.getOrNull()

        "updateTask" -> runCatching {
            val result = json.decodeFromString<TaskResult>(resultJson)
            ToolCallResultObject.Tasks(listOf(result.task))
        }.getOrNull()

        "getAllTasks" -> null // Too many to display as cards

        // Calendar
        "createEvent" -> runCatching {
            val createResult = json.decodeFromString<CalendarEventIdResult>(resultJson)
            createResult.createdEventId?.let { id ->
                getCalendarEventById(id)?.let {
                    ToolCallResultObject.CalendarEvents(listOf(it))
                }
            }
        }.getOrNull()

        "createEvents" -> runCatching {
            val createResult = json.decodeFromString<CalendarEventIdsResult>(resultJson)
            val events = createResult.createdEventIds.mapNotNull { id ->
                id?.let { getCalendarEventById(it) }
            }
            if (events.isNotEmpty()) ToolCallResultObject.CalendarEvents(events) else null
        }.getOrNull()

        "searchEventsByNameWithinRange" -> runCatching {
            val searchResult = json.decodeFromString<SearchEventsResult>(resultJson)
            if (searchResult.events.size == 1) ToolCallResultObject.CalendarEvents(searchResult.events) else null
        }.getOrNull()

        "getEventById" -> runCatching {
            val result = json.decodeFromString<CalendarEventResult>(resultJson)
            ToolCallResultObject.CalendarEvents(listOf(result.event))
        }.getOrNull()

        "updateEvent" -> runCatching {
            val result = json.decodeFromString<CalendarEventResult>(resultJson)
            ToolCallResultObject.CalendarEvents(listOf(result.event))
        }.getOrNull()

        "getMonthEvents" -> runCatching {
            val result = json.decodeFromString<GetMonthEventsResult>(resultJson)
            if (result.events.size <= 5) ToolCallResultObject.CalendarEvents(result.events) else null
        }.getOrNull()

        // Journal
        "createJournalEntry" -> runCatching {
            val createResult = json.decodeFromString<JournalEntryIdResult>(resultJson)
            ToolCallResultObject.JournalEntries(emptyList()) // Created, no full object returned
        }.getOrNull()

        "searchJournalEntries" -> runCatching {
            val searchResult = json.decodeFromString<SearchJournalEntriesResult>(resultJson)
            if (searchResult.entries.size <= 5) ToolCallResultObject.JournalEntries(searchResult.entries) else null
        }.getOrNull()

        "getJournalEntry" -> runCatching {
            val result = json.decodeFromString<JournalEntryResult>(resultJson)
            result.entry?.let { ToolCallResultObject.JournalEntries(listOf(it)) }
        }.getOrNull()

        "getAllJournalEntries" -> runCatching {
            val searchResult = json.decodeFromString<SearchJournalEntriesResult>(resultJson)
            if (searchResult.entries.size <= 5) ToolCallResultObject.JournalEntries(searchResult.entries) else null
        }.getOrNull()

        "updateJournalEntry" -> runCatching {
            val result = json.decodeFromString<JournalEntryResult>(resultJson)
            result.entry?.let { ToolCallResultObject.JournalEntries(listOf(it)) }
        }.getOrNull()

        // Bookmarks
        "createBookmark" -> runCatching {
            val createResult = json.decodeFromString<BookmarkIdResult>(resultJson)
            ToolCallResultObject.Bookmarks(emptyList()) // Created, no full object returned
        }.getOrNull()

        "searchBookmarks" -> runCatching {
            val searchResult = json.decodeFromString<SearchBookmarksResult>(resultJson)
            if (searchResult.bookmarks.size <= 5) ToolCallResultObject.Bookmarks(searchResult.bookmarks) else null
        }.getOrNull()

        "getBookmark" -> runCatching {
            val result = json.decodeFromString<BookmarkToolResult>(resultJson)
            ToolCallResultObject.Bookmarks(listOf(result.bookmark))
        }.getOrNull()

        "getAllBookmarks" -> runCatching {
            val searchResult = json.decodeFromString<SearchBookmarksResult>(resultJson)
            if (searchResult.bookmarks.size <= 5) ToolCallResultObject.Bookmarks(searchResult.bookmarks) else null
        }.getOrNull()

        "updateBookmark" -> runCatching {
            val result = json.decodeFromString<BookmarkToolResult>(resultJson)
            ToolCallResultObject.Bookmarks(listOf(result.bookmark))
        }.getOrNull()

        // Alarms
        "createAlarm" -> runCatching {
            val result = json.decodeFromString<AlarmResult>(resultJson)
            result.alarmId?.let { id ->
                ToolCallResultObject.Alarms(listOf(AlarmInfo(id = id, time = result.dueDate)))
            }
        }.getOrNull()

        "getAllAlarms" -> runCatching {
            val result = json.decodeFromString<AlarmsResult>(resultJson)
            if (result.alarms.size <= 5) ToolCallResultObject.Alarms(result.alarms) else null
        }.getOrNull()

        // Memory
        "searchMemoryFacts" -> runCatching {
            val result = json.decodeFromString<MemoryFactsResult>(resultJson)
            ToolCallResultObject.MemoryFacts(result.facts.map { it.fact })
        }.getOrNull()

        "listMemoryFacts" -> runCatching {
            val result = json.decodeFromString<MemoryFactsResult>(resultJson)
            ToolCallResultObject.MemoryFacts(result.facts.map { it.fact })
        }.getOrNull()

        // Planning
        "createPlan" -> runCatching {
            val result = json.decodeFromString<PlanResult>(resultJson)
            ToolCallResultObject.Plans(listOf(PlanInfo(result.planId, result.title, result.steps.size, 0)))
        }.getOrNull()

        "getActivePlans" -> runCatching {
            val result = json.decodeFromString<PlansResult>(resultJson)
            if (result.plans.size <= 5) ToolCallResultObject.Plans(result.plans.map { PlanInfo(it.id, it.title, it.stepCount, it.completedSteps) }) else null
        }.getOrNull()

        // Web
        "webSearch" -> runCatching {
            val result = json.decodeFromString<WebSearchToolResult>(resultJson)
            ToolCallResultObject.WebResults(result.results.map { WebSearchItem(it.title, it.url, it.snippet) })
        }.getOrNull()

        // Settings
        "getAllPreferences" -> runCatching {
            val result = json.decodeFromString<AllPreferencesResult>(resultJson)
            ToolCallResultObject.Settings(result.preferences.associate { it.key to it.value })
        }.getOrNull()

        // Sound
        "getVolume" -> runCatching {
            val result = json.decodeFromString<VolumeResult>(resultJson)
            if (result.success) ToolCallResultObject.Sound(SoundInfo(
                type = "volume",
                volumes = listOf(VolumeInfo(result.stream, result.currentVolume, result.maxVolume, result.percentage, result.currentVolume == 0)),
                error = result.error
            )) else null
        }.getOrNull()

        "setVolume" -> runCatching {
            val result = json.decodeFromString<VolumeResult>(resultJson)
            if (result.success) ToolCallResultObject.Sound(SoundInfo(
                type = "volume",
                volumes = listOf(VolumeInfo(result.stream, result.currentVolume, result.maxVolume, result.percentage, result.currentVolume == 0)),
                error = result.error
            )) else null
        }.getOrNull()

        "adjustVolume" -> runCatching {
            val result = json.decodeFromString<VolumeResult>(resultJson)
            if (result.success) ToolCallResultObject.Sound(SoundInfo(
                type = "volume",
                volumes = listOf(VolumeInfo(result.stream, result.currentVolume, result.maxVolume, result.percentage, result.currentVolume == 0)),
                error = result.error
            )) else null
        }.getOrNull()

        "getRingerMode" -> runCatching {
            val result = json.decodeFromString<RingerModeResult>(resultJson)
            if (result.success) ToolCallResultObject.Sound(SoundInfo(
                type = "ringer_mode",
                ringerMode = result.mode,
                error = result.error
            )) else null
        }.getOrNull()

        "setRingerMode" -> runCatching {
            val result = json.decodeFromString<RingerModeResult>(resultJson)
            if (result.success) ToolCallResultObject.Sound(SoundInfo(
                type = "ringer_mode",
                ringerMode = result.mode,
                error = result.error
            )) else null
        }.getOrNull()

        "speakText" -> runCatching {
            val result = json.decodeFromString<SpeakResult>(resultJson)
            ToolCallResultObject.Sound(SoundInfo(
                type = "speak",
                error = result.error
            ))
        }.getOrNull()

        "vibrate" -> runCatching {
            val result = json.decodeFromString<VibrateResult>(resultJson)
            ToolCallResultObject.Sound(SoundInfo(
                type = "vibrate",
                error = result.error
            ))
        }.getOrNull()

        "playRingtone" -> runCatching {
            val result = json.decodeFromString<PlaySoundResult>(resultJson)
            ToolCallResultObject.Sound(SoundInfo(
                type = result.soundType,
                error = result.error
            ))
        }.getOrNull()

        "playSoundFile" -> runCatching {
            val result = json.decodeFromString<PlaySoundResult>(resultJson)
            ToolCallResultObject.Sound(SoundInfo(
                type = result.soundType,
                error = result.error
            ))
        }.getOrNull()

        "getAudioInfo" -> runCatching {
            val result = json.decodeFromString<AudioInfoToolResult>(resultJson)
            if (result.success) ToolCallResultObject.Sound(SoundInfo(
                type = "audio_info",
                volumes = result.volumes.map { VolumeInfo(it.stream, it.currentVolume, it.maxVolume, it.percentage, it.isMuted) },
                ringerMode = result.ringerMode,
                isMusicActive = result.isMusicActive,
                hasVibrator = result.hasVibrator,
                error = result.error
            )) else null
        }.getOrNull()

        // Media tools
        "spotifyStatus", "spotifyPlay", "spotifyPause", "spotifyNext", "spotifyPrevious" -> runCatching {
            val result = json.decodeFromString<SpotifyStatusResult>(resultJson)
            ToolCallResultObject.Media(MediaInfo(
                type = "spotify",
                status = result.status,
                error = result.error
            ))
        }.getOrNull()

        "spotifySearch" -> runCatching {
            val result = json.decodeFromString<SpotifySearchResult>(resultJson)
            if (result.success && result.tracks.isNotEmpty()) ToolCallResultObject.Media(MediaInfo(
                type = "spotify_search",
                title = result.tracks.firstOrNull()?.name,
                artist = result.tracks.firstOrNull()?.artist,
                error = result.error
            )) else null
        }.getOrNull()

        "gifSearch" -> runCatching {
            val result = json.decodeFromString<GifSearchResult>(resultJson)
            if (result.success && result.gifs.isNotEmpty()) ToolCallResultObject.Media(MediaInfo(
                type = "gif",
                title = result.gifs.firstOrNull()?.title,
                path = result.gifs.firstOrNull()?.url,
                error = result.error
            )) else null
        }.getOrNull()

        "memeCreate" -> runCatching {
            val result = json.decodeFromString<MemeCreateResult>(resultJson)
            ToolCallResultObject.Media(MediaInfo(
                type = "meme",
                path = result.path,
                error = result.error
            ))
        }.getOrNull()

        "videoFrame", "videoSheet" -> runCatching {
            val result = json.decodeFromString<VideoFrameResult>(resultJson)
            ToolCallResultObject.Media(MediaInfo(
                type = "video_frame",
                path = result.path,
                error = result.error
            ))
        }.getOrNull()

        "cameraCapture" -> runCatching {
            val result = json.decodeFromString<CameraResult>(resultJson)
            ToolCallResultObject.Media(MediaInfo(
                type = "camera",
                path = result.path,
                error = result.error
            ))
        }.getOrNull()

        // Smart Home tools
        "hueListLights" -> runCatching {
            val result = json.decodeFromString<HueLightsResult>(resultJson)
            if (result.success) ToolCallResultObject.SmartHome(SmartHomeInfo(
                type = "hue_lights",
                devices = result.lights.map { DeviceInfoData(it.id, it.name, "light", if (it.on) "on" else "off") },
                error = result.error
            )) else null
        }.getOrNull()

        "hueSetLight", "hueSetRoom", "hueActivateScene" -> runCatching {
            val result = json.decodeFromString<HueResult>(resultJson)
            ToolCallResultObject.SmartHome(SmartHomeInfo(
                type = "hue_control",
                device = result.target,
                state = result.action,
                error = result.error
            ))
        }.getOrNull()

        "sonosDiscover" -> runCatching {
            val result = json.decodeFromString<SonosDiscoverResult>(resultJson)
            if (result.success) ToolCallResultObject.SmartHome(SmartHomeInfo(
                type = "sonos",
                devices = result.speakers.map { DeviceInfoData(it.ip, it.name, it.model) },
                error = result.error
            )) else null
        }.getOrNull()

        "sonosPlayPause", "sonosVolume", "sonosGroup" -> runCatching {
            val result = json.decodeFromString<SonosResult>(resultJson)
            ToolCallResultObject.SmartHome(SmartHomeInfo(
                type = "sonos_control",
                device = result.speaker,
                state = result.action,
                error = result.error
            ))
        }.getOrNull()

        "bluetoothList" -> runCatching {
            val result = json.decodeFromString<BluetoothDevicesResult>(resultJson)
            if (result.success) ToolCallResultObject.SmartHome(SmartHomeInfo(
                type = "bluetooth",
                devices = result.devices.map { DeviceInfoData(it.mac, it.name, "bluetooth") },
                error = result.error
            )) else null
        }.getOrNull()

        "networkScan" -> runCatching {
            val result = json.decodeFromString<NetworkScanResult>(resultJson)
            if (result.success) ToolCallResultObject.SmartHome(SmartHomeInfo(
                type = "network",
                devices = result.devices.map { DeviceInfoData(it.ip, it.name, "network") },
                error = result.error
            )) else null
        }.getOrNull()

        // Weather tools
        "weatherCurrent" -> runCatching {
            val result = json.decodeFromString<WeatherResult>(resultJson)
            if (result.success) ToolCallResultObject.Weather(WeatherInfoData(
                location = result.location,
                temperature = result.temperature,
                condition = result.condition,
                humidity = result.humidity,
                wind = result.wind,
                error = result.error
            )) else null
        }.getOrNull()

        "weatherForecast" -> runCatching {
            val result = json.decodeFromString<WeatherForecastResult>(resultJson)
            if (result.success) ToolCallResultObject.Weather(WeatherInfoData(
                location = result.location,
                forecast = result.forecast.map { ForecastDayData(it.date, it.maxTemp, it.minTemp, it.condition) },
                error = result.error
            )) else null
        }.getOrNull()

        // Places tools
        "placesSearch" -> runCatching {
            val result = json.decodeFromString<PlacesResult>(resultJson)
            if (result.success && result.places.isNotEmpty()) ToolCallResultObject.Places(result.places.map {
                PlaceInfoData(it.id, it.name, it.address, it.rating)
            }) else null
        }.getOrNull()

        // GitHub tools
        "githubPrList" -> runCatching {
            val result = json.decodeFromString<GitHubPrListResult>(resultJson)
            if (result.success) ToolCallResultObject.GitHub(GitHubInfoData(
                type = "pr_list",
                items = result.prs.map { GitHubItemData(it.number, it.title, it.state, it.author, it.url) },
                error = result.error
            )) else null
        }.getOrNull()

        "githubIssueList" -> runCatching {
            val result = json.decodeFromString<GitHubIssueListResult>(resultJson)
            if (result.success) ToolCallResultObject.GitHub(GitHubInfoData(
                type = "issue_list",
                items = result.issues.map { GitHubItemData(it.number, it.title, it.state, url = it.url) },
                error = result.error
            )) else null
        }.getOrNull()

        "githubRepoInfo" -> runCatching {
            val result = json.decodeFromString<GitHubRepoInfoResult>(resultJson)
            if (result.success && result.repo != null) ToolCallResultObject.GitHub(GitHubInfoData(
                type = "repo",
                title = result.repo.name,
                error = result.error
            )) else null
        }.getOrNull()

        // Trello tools
        "trelloBoards" -> runCatching {
            val result = json.decodeFromString<TrelloBoardsResult>(resultJson)
            if (result.success) ToolCallResultObject.Trello(TrelloInfoData(
                type = "boards",
                items = result.boards.map { TrelloItemData(it.id, it.name, "board") },
                error = result.error
            )) else null
        }.getOrNull()

        "trelloCards" -> runCatching {
            val result = json.decodeFromString<TrelloCardsResult>(resultJson)
            if (result.success) ToolCallResultObject.Trello(TrelloInfoData(
                type = "cards",
                items = result.cards.map { TrelloItemData(it.id, it.name, "card") },
                error = result.error
            )) else null
        }.getOrNull()

        // Notion tools
        "notionSearch" -> runCatching {
            val result = json.decodeFromString<NotionSearchResult>(resultJson)
            if (result.success) ToolCallResultObject.Notion(NotionInfoData(
                type = "search",
                items = result.results.map { NotionItemData(it.id, it.title) },
                error = result.error
            )) else null
        }.getOrNull()

        "notionGetPage" -> runCatching {
            val result = json.decodeFromString<NotionPageResult>(resultJson)
            if (result.success && result.page != null) ToolCallResultObject.Notion(NotionInfoData(
                type = "page",
                id = result.page.id,
                title = result.page.title,
                content = result.page.content,
                error = result.error
            )) else null
        }.getOrNull()

        // Voice tools
        "transcribeAudio", "transcribeSpeech" -> runCatching {
            val result = json.decodeFromString<TranscribeResult>(resultJson)
            ToolCallResultObject.Voice(VoiceInfoData(
                type = "transcription",
                text = result.text,
                language = result.language,
                duration = result.duration,
                error = result.error
            ))
        }.getOrNull()

        "ttsSpeak", "ttsStop" -> runCatching {
            val result = json.decodeFromString<TtsResult>(resultJson)
            ToolCallResultObject.Voice(VoiceInfoData(
                type = "tts",
                error = result.error
            ))
        }.getOrNull()

        "audioConvert", "audioTrim" -> runCatching {
            val result = json.decodeFromString<AudioConvertResult>(resultJson)
            ToolCallResultObject.Voice(VoiceInfoData(
                type = "audio_convert",
                error = result.error
            ))
        }.getOrNull()

        // CommunicationPlus tools
        "discordSend", "discordRead", "discordReact" -> runCatching {
            val result = json.decodeFromString<DiscordResult>(resultJson)
            ToolCallResultObject.Communication(CommunicationInfo(
                type = "discord",
                action = result.action,
                messageId = result.messageId,
                error = result.error
            ))
        }.getOrNull()

        "slackSend", "slackRead", "slackPin" -> runCatching {
            val result = json.decodeFromString<SlackResult>(resultJson)
            ToolCallResultObject.Communication(CommunicationInfo(
                type = "slack",
                action = result.action,
                messageId = result.messageId,
                error = result.error
            ))
        }.getOrNull()

        "whatsappSend", "whatsappSearch" -> runCatching {
            val result = json.decodeFromString<WhatsAppResult>(resultJson)
            ToolCallResultObject.Communication(CommunicationInfo(
                type = "whatsapp",
                action = result.action,
                recipient = result.recipient,
                error = result.error
            ))
        }.getOrNull()

        "xPost", "xReply", "xDm", "xSearch" -> runCatching {
            val result = json.decodeFromString<XResult>(resultJson)
            ToolCallResultObject.Communication(CommunicationInfo(
                type = "x",
                action = result.action,
                messageId = result.tweetId,
                error = result.error
            ))
        }.getOrNull()

        "voiceCallStart", "voiceCallStatus" -> runCatching {
            val result = json.decodeFromString<VoiceCallResult>(resultJson)
            ToolCallResultObject.Communication(CommunicationInfo(
                type = "voice_call",
                action = result.action,
                status = result.status,
                error = result.error
            ))
        }.getOrNull()

        // System tools
        "healthCheck" -> runCatching {
            val result = json.decodeFromString<HealthResult>(resultJson)
            ToolCallResultObject.System(SystemInfo(
                type = "health",
                battery = result.battery,
                storage = result.storage,
                memory = result.memory,
                security = result.security,
                error = result.error
            ))
        }.getOrNull()

        "deviceInfo" -> runCatching {
            val result = json.decodeFromString<SystemDeviceInfoResult>(resultJson)
            if (result.success) ToolCallResultObject.System(SystemInfo(
                type = "device",
                device = DeviceInfo(
                    manufacturer = result.manufacturer ?: "",
                    model = result.model ?: "",
                    androidVersion = result.androidVersion ?: "",
                    sdkVersion = result.sdkVersion ?: 0
                ),
                error = result.error
            )) else null
        }.getOrNull()

        "sessionLogsSearch", "sessionLogsExport" -> runCatching {
            val result = json.decodeFromString<SessionLogsResult>(resultJson)
            ToolCallResultObject.System(SystemInfo(
                type = "session_logs",
                error = result.error
            ))
        }.getOrNull()

        // Email tools
        "emailList", "emailSearch" -> runCatching {
            val result = json.decodeFromString<EmailListResult>(resultJson)
            ToolCallResultObject.Email(EmailInfo(
                type = "list",
                emails = result.emails.map { EmailSummary(it.id, it.from, it.subject, it.preview, it.date, it.isRead) },
                error = result.error
            ))
        }.getOrNull()

        "emailRead" -> runCatching {
            val result = json.decodeFromString<EmailReadResult>(resultJson)
            ToolCallResultObject.Email(EmailInfo(
                type = "read",
                email = result.email?.let { EmailDetail(it.id, it.from, it.to, it.subject, it.body, it.date) },
                error = result.error
            ))
        }.getOrNull()

        "emailSend", "emailReply", "emailForward" -> runCatching {
            val result = json.decodeFromString<EmailSendResult>(resultJson)
            ToolCallResultObject.Email(EmailInfo(
                type = "send",
                error = result.error
            ))
        }.getOrNull()

        "emailMove", "emailDelete" -> runCatching {
            val result = json.decodeFromString<EmailActionResult>(resultJson)
            ToolCallResultObject.Email(EmailInfo(
                type = result.action ?: "action",
                error = result.error
            ))
        }.getOrNull()

        // Camera tools
        "cameraList" -> runCatching {
            val result = json.decodeFromString<CameraListResult>(resultJson)
            ToolCallResultObject.Camera(CameraInfo(
                type = "list",
                cameras = result.cameras.map { CameraDevice(it.id, it.name, it.url, it.status) },
                error = result.error
            ))
        }.getOrNull()

        "cameraSnapshot" -> runCatching {
            val result = json.decodeFromString<CameraSnapshotResult>(resultJson)
            ToolCallResultObject.Camera(CameraInfo(
                type = "snapshot",
                imagePath = result.imagePath,
                error = result.error
            ))
        }.getOrNull()

        "cameraRecord" -> runCatching {
            val result = json.decodeFromString<CameraRecordResult>(resultJson)
            ToolCallResultObject.Camera(CameraInfo(
                type = "record",
                videoPath = result.videoPath,
                error = result.error
            ))
        }.getOrNull()

        "cameraMotionDetect" -> runCatching {
            val result = json.decodeFromString<CameraMotionResult>(resultJson)
            ToolCallResultObject.Camera(CameraInfo(
                type = "motion_detect",
                error = result.error
            ))
        }.getOrNull()

        else -> null
    }

}

// No timeouts — AGI runs until it finishes
private val NO_TIMEOUTS = ConnectionTimeoutConfig(
    requestTimeoutMillis = Long.MAX_VALUE,
    connectTimeoutMillis = Long.MAX_VALUE,
    socketTimeoutMillis = Long.MAX_VALUE
)

internal fun AiProvider.getExecutor(key: String, customUrl: String, llModel: LLModel, engine: io.ktor.client.engine.HttpClientEngine): PromptExecutor {
    val client = when (this) {
        AiProvider.UnusLumen -> {
            // The client uses Ktor defaultRequest { url(baseUrl) } with relative paths like "api/chat".
            // The base URL MUST end with a trailing slash for correct relative path resolution.
            val url = if (customUrl.isNotBlank()) {
                val trimmed = customUrl.trimEnd('/')
                "$trimmed/"
            } else ""
            // Sovereign egress: Tor for remote, direct for loopback/LAN,
            // fail-closed when Tor is down.
            val sharedClient = TorEgress.httpClient()
            if (url.isBlank()) UnusLumenClient(baseClient = sharedClient, timeoutConfig = NO_TIMEOUTS)
            else UnusLumenClient(url, baseClient = sharedClient, timeoutConfig = NO_TIMEOUTS)
        }
        // BYO branches never route through here — initializeByoProvider builds
        // their executors directly (koog brand clients / UnusLumenClient on the
        // user's URL). This arm keeps the when exhaustive for completeness.
        else -> EmptyAiClient
    }
    return SingleLLMPromptExecutor(client)
}

/**
 * OpenAI-wire client for user-supplied OpenAI-compatible endpoints
 * (LM Studio, llama.cpp server, vLLM). Koog's OpenAILLMClient speaks the
 * standard /v1/chat/completions wire with full streaming, tool-call and
 * structured-request support; the user's endpoint rides as the base URL
 * and their key as the bearer. The client rides TorEgress: local/LAN
 * endpoints go direct, remote endpoints go through Tor, fail-closed when
 * Tor is down — the rest of the app's socket stack never bypasses policy.
 */
private fun openAICompatExecutor(apiKey: String, userUrl: String): PromptExecutor {
    val trimmed = userUrl.trimEnd('/')
    // Accept what the user pasted: with or without /v1. Server roots get the
    // standard /v1 prefix; URLs already ending in /v1 stay untouched.
    val base = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
    return SingleLLMPromptExecutor(
        ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
            apiKey = apiKey.ifBlank { "no-key" },
            settings = ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(baseUrl = base),
            baseClient = TorEgress.httpClient()
        )
    )
}

private fun truncateTitle(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    
    val words = text.split(Regex("\\s+"))
    val result = StringBuilder()
    
    for (word in words) {
        if (result.length + word.length + 1 > maxLength - 3) {
            break
        }
        if (result.isNotEmpty()) {
            result.append(" ")
        }
        result.append(word)
    }
    
    return if (result.isEmpty()) {
        text.take(maxLength - 3) + "..."
    } else {
        result.toString().trimEnd() + "..."
    }
}