package com.unuslumen.app.presentation

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.AiRepositoryException
import com.unuslumen.app.domain.model.PortalResult
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.slashcommands.ConversationTaskSummary
import com.unuslumen.app.domain.slashcommands.SlashCommandHost
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.TokenEstimator
import com.unuslumen.app.domain.slashcommands.ToolCallFact
import com.unuslumen.app.domain.model.ToolCallResultObject
import com.unuslumen.app.presentation.components.CommandArgs
import com.unuslumen.app.presentation.components.GuruSlashCommand
import com.unuslumen.app.presentation.components.GuruSlashCommandRegistry
import com.unuslumen.app.presentation.slashcommands.ChatHostRouter
import com.unuslumen.app.presentation.components.ThinkingDisplayLevel
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.use_case.GetAllEventsUseCase
import com.unuslumen.app.domain.use_case.GetNoteUseCase
import com.unuslumen.app.domain.use_case.GetTaskByIdUseCase
import com.unuslumen.app.domain.use_case.SearchNotesUseCase
import com.unuslumen.app.domain.use_case.SearchTasksUseCase
import com.unuslumen.app.domain.use_case.SendAiMessageUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringSetPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProvider
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.toIntList
import com.unuslumen.app.ui.toNotesView
import com.unuslumen.app.util.date.formatDate
import com.unuslumen.app.util.date.formatDateForMapping
import com.unuslumen.app.util.date.now
import com.unuslumen.app.util.date.todayPlusDays
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.android.annotation.KoinViewModel
import kotlin.uuid.Uuid

@KoinViewModel
class AssistantViewModel(
    private val sendAiMessage: SendAiMessageUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
    private val searchNotes: SearchNotesUseCase,
    private val searchTasks: SearchTasksUseCase,
    private val getCalendarEvents: GetAllEventsUseCase,
    private val getNoteById: GetNoteUseCase,
    private val getTaskById: GetTaskByIdUseCase,
    private val memoryRepository: MemoryRepository,
    private val visionCommandHandler: VisionCommandHandler,
    private val chatHostRouter: ChatHostRouter
) : ViewModel() {

    private val _messages = mutableStateListOf<AiMessage>()
    val messages: List<AiMessage> = _messages
    val attachments = mutableStateListOf<AiMessageAttachment>()

    var uiState by mutableStateOf(UiState())
        private set

    var aiEnabled by mutableStateOf(false)
        private set

    // Model config comes from the server, no local model state needed

    var thinkingLevel by mutableStateOf(ThinkingDisplayLevel.MEDIUM)
        private set

    // Track current conversation for persistence
    private var currentConversationId: String? by mutableStateOf(null)

    // Guard to prevent sending messages before conversation history is loaded
    private var conversationLoaded = false

    private var searchNotesJob: Job? = null
    private var searchTasksJob: Job? = null
    private var sendMessageJob: Job? = null

    init {
        // Load the most recent conversation on startup
        loadLastConversation()

        viewModelScope.launch {
            getPreference(
                intPreferencesKey(PrefsConstants.NOTE_VIEW_KEY),
                ItemView.LIST.value
            ).onEach {
                uiState = uiState.copy(noteView = it.toNotesView())
            }.collect()
        }
        viewModelScope.launch {
            getPreference(intPreferencesKey(PrefsConstants.AI_PROVIDER_KEY), AiProvider.None.id)
                .map { it.toAiProvider() }
                .collect { provider ->
                    aiEnabled = provider != AiProvider.None
                }
        }
        // Load thinking level
        viewModelScope.launch {
            getPreference(
                stringPreferencesKey(PrefsConstants.THINKING_LEVEL_KEY),
                "medium"
            ).collect { level ->
                thinkingLevel = when (level.lowercase()) {
                    "off" -> ThinkingDisplayLevel.OFF
                    "low" -> ThinkingDisplayLevel.LOW
                    "medium" -> ThinkingDisplayLevel.MEDIUM
                    "high" -> ThinkingDisplayLevel.HIGH
                    "xhigh" -> ThinkingDisplayLevel.XHIGH
                    else -> ThinkingDisplayLevel.MEDIUM
                }
            }
        }
        fetchAvailableModels()
    }

    fun fetchAvailableModels() {
        // Model fetching is now handled by LlmConfigFetcher on the AI repository side
    }

    fun selectModel(modelName: String) {
        // Model selection is now server-side
    }

    fun updateThinkingLevel(level: ThinkingDisplayLevel) {
        thinkingLevel = level
        viewModelScope.launch {
            savePreference(
                stringPreferencesKey(PrefsConstants.THINKING_LEVEL_KEY),
                when (level) {
                    ThinkingDisplayLevel.OFF -> "off"
                    ThinkingDisplayLevel.LOW -> "low"
                    ThinkingDisplayLevel.MEDIUM -> "medium"
                    ThinkingDisplayLevel.HIGH -> "high"
                    ThinkingDisplayLevel.XHIGH -> "xhigh"
                }
            )
        }
    }

    private fun loadLastConversation() {
        viewModelScope.launch {
            try {
                val conversations = memoryRepository.getAllConversations()
                val lastConversation = conversations.maxByOrNull { it.updatedDate }
                if (lastConversation != null) {
                    currentConversationId = lastConversation.id
                    val persistedMessages = memoryRepository.getMessagesByConversation(lastConversation.id)
                    if (persistedMessages.isNotEmpty()) {
                        val restored = persistedMessages.mapNotNull { msg ->
                            when (msg.role) {
                                "user" -> AiMessage.UserMessage(
                                    uuid = msg.id,
                                    content = msg.content,
                                    time = msg.timestamp,
                                    attachments = emptyList(),
                                    attachmentsText = ""
                                )
                                "assistant" -> AiMessage.AssistantMessage(
                                    content = msg.content,
                                    time = msg.timestamp,
                                    uuid = msg.id
                                )
                                "tool" -> AiMessage.ToolCall(
                                    uuid = msg.id,
                                    id = null,
                                    name = "",
                                    rawContent = msg.toolCalls.ifBlank { msg.content },
                                    resultRawContent = msg.toolResults.ifBlank { msg.content },
                                    time = msg.timestamp
                                )
                                else -> null
                            }
                        }
                        // Messages are stored newest-first in the UI, so reverse to chronological
                        _messages.clear()
                        _messages.addAll(restored.reversed())
                        Log.d("AssistantViewModel", "Restored ${restored.size} messages from conversation ${lastConversation.id}")
                    }
                }
            } catch (e: Exception) {
                Log.e("AssistantViewModel", "Failed to load last conversation: ${e.message}", e)
            } finally {
                conversationLoaded = true
            }
        }
    }

    fun startNewConversation() {
        // Persist the current conversation to DB before clearing so context is NEVER lost
        val conversationId = currentConversationId
        val currentMessages = _messages.toList()
        if (conversationId != null && currentMessages.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    memoryRepository.persistAiMessages(conversationId, currentMessages.reversed())
                } catch (e: Exception) {
                    Log.e("AssistantViewModel", "Failed to persist conversation before clearing: ${e.message}", e)
                }
            }
        }
        currentConversationId = null
        _messages.clear()
        attachments.clear()
        uiState = uiState.copy(loading = false, error = null)
    }

    fun onEvent(event: AssistantEvent) {
        when (event) {
            is AssistantEvent.SendMessage -> {
                sendMessageJob?.cancel()
                sendMessageJob = viewModelScope.launch {
                    // Wait for conversation history to finish loading before sending
                    while (!conversationLoaded) {
                        delay(50)
                    }

                    val message = AiMessage.UserMessage(
                        content = event.content,
                        attachments = event.attachments,
                        attachmentsText = getAttachmentText(event.attachments),
                        time = now(),
                        uuid = Uuid.random().toString()
                    )

                    _messages.add(0, message)
                    attachments.clear()

                    uiState = uiState.copy(
                        loading = true,
                        error = null
                    )
                    
                    // Generate a conversation ID if this is a new conversation
                    if (currentConversationId == null) {
                        currentConversationId = Uuid.random().toString()
                    }

                    sendAiMessage(_messages.reversed(), currentConversationId)
                        .catch { e ->
                            delay(300)
                            
                            val error = if (e is AiRepositoryException) {
                                e.failure
                            } else {
                                PortalResult.OtherError(e.message)
                            }

                            if (error !is PortalResult.ToolCallLimitExceeded) {
                                _messages.removeAt(0)
                            }

                            uiState = uiState.copy(
                                loading = false,
                                error = error
                            )
                        }
                        .onCompletion {
                            uiState = uiState.copy(loading = false)
                        }
                        .collect { msg ->
                            _messages.add(0, msg)
                        }
                }
            }

            is AssistantEvent.SearchNotes -> {
                searchNotesJob?.cancel()
                searchNotesJob = viewModelScope.launch {
                    delay(300)
                    searchNotes(event.query).let {
                        uiState = uiState.copy(searchNotes = it)
                    }
                }
            }

            is AssistantEvent.SearchTasks -> {
                searchTasksJob?.cancel()
                searchTasksJob = viewModelScope.launch {
                    delay(300)
                    searchTasks(event.query).first().let {
                        uiState = uiState.copy(searchTasks = it)
                    }
                }
            }

            AssistantEvent.AddAttachmentEvents -> {
                attachments.add(AiMessageAttachment.CalenderEvents)
            }

            is AssistantEvent.AddAttachmentNote -> viewModelScope.launch {
                val note = getNoteById(event.id) ?: return@launch
                attachments.add(
                    AiMessageAttachment.Note(
                        note.copy(
                            title = note.title.ifBlank { "Untitled Note" }
                        )
                    )
                )
            }

            is AssistantEvent.AddAttachmentTask -> viewModelScope.launch {
                attachments.add(AiMessageAttachment.Task(getTaskById(event.id) ?: return@launch ))
            }

            is AssistantEvent.RemoveAttachment -> {
                attachments.removeAt(event.index)
            }

            AssistantEvent.CancelMessage -> {
                sendMessageJob?.cancel()
                if (messages.firstOrNull() is AiMessage.UserMessage) {
                    _messages.removeAt(0)
                }
                uiState = uiState.copy(loading = false)
            }

            AssistantEvent.NewConversation -> {
                startNewConversation()
            }

            is AssistantEvent.AddAttachmentFile -> {
                // File attachments not yet supported in Assistant; handled in Portal
            }

            is AssistantEvent.SlashCommand -> {
                // Vision keeps its dedicated handler path (screen-agnostic pref toggle)
                if (event.command.key == "vision") {
                    handleVisionCommand(event)
                    return
                }
                // Screen effects ride with the command; the screen builds its nav host.
                handleSlashCommand(event.command, event.args, event.screenHost)
            }

            is AssistantEvent.PortalEvent -> {
                sendMessageJob?.cancel()
                sendMessageJob = viewModelScope.launch {
                    while (!conversationLoaded) {
                        delay(50)
                    }

                    // Send portal event as a structured user message so Guru can respond
                    val message = AiMessage.UserMessage(
                        content = "[Portal Event: ${event.name}] ${event.payload}",
                        attachments = emptyList(),
                        attachmentsText = "",
                        time = now(),
                        uuid = Uuid.random().toString()
                    )

                    _messages.add(0, message)

                    uiState = uiState.copy(
                        loading = true,
                        error = null
                    )

                    if (currentConversationId == null) {
                        currentConversationId = Uuid.random().toString()
                    }

                    sendAiMessage(_messages.reversed(), currentConversationId)
                        .catch { e ->
                            delay(300)
                            val error = if (e is AiRepositoryException) {
                                e.failure
                            } else {
                                PortalResult.OtherError(e.message)
                            }
                            if (error !is PortalResult.ToolCallLimitExceeded) {
                                _messages.removeAt(0)
                            }
                            uiState = uiState.copy(
                                loading = false,
                                error = error
                            )
                        }
                        .onCompletion {
                            uiState = uiState.copy(loading = false)
                        }
                        .collect { msg ->
                            _messages.add(0, msg)
                        }
                }
            }
        }
    }

    private fun handleVisionCommand(event: AssistantEvent.SlashCommand) {
        val mode = event.args?.values?.get("mode")
        viewModelScope.launch {
            val confirmation = try {
                visionCommandHandler.handle(mode)
            } catch (e: Exception) {
                Log.e("AssistantViewModel", "Vision command failed: ${e.message}", e)
                "Vision command failed: ${e.message}"
            }
            appendLocalMessage(confirmation)
        }
    }

    /**
     * Resolve a typed slash command through the domain dispatcher, same path as
     * Portal. [screenHost] carries the navigation-capable effects only a screen
     * can perform (skills screen, permission gate).
     */
    fun handleSlashCommand(command: GuruSlashCommand, args: CommandArgs?, screenHost: SlashCommandHost? = null) {
        viewModelScope.launch {
            val host = object : SlashCommandHost {
                override fun showSystemReply(text: String) = appendLocalMessage(text)
                override fun showDetailList(title: String, items: List<String>) {
                    appendLocalMessage(title + "\n" + items.joinToString(separator = "\n") { "• $it" })
                }
                override fun startNewConversation() = this@AssistantViewModel.startNewConversation()
                override fun cancelRun() {
                    onEvent(AssistantEvent.CancelMessage)
                }
                override fun forwardToEngine(commandText: String) {
                    onEvent(AssistantEvent.SendMessage(content = commandText, attachments = emptyList()))
                }
                // Navigation delegates to the screen host, matching Portal.
                override fun openSkills() = screenHost?.openSkills() ?: Unit
                override fun openPermissionGate() = screenHost?.openPermissionGate() ?: Unit
            }
            chatHostRouter.route(command, args, assistantCommandSession(), host)
        }
    }

    /** Session snapshot for slash commands: what the command layer may read. */
    private fun assistantCommandSession() = object : SlashCommandSession {
        override val conversationId: String? get() = currentConversationId
        override val messageCount: Int get() = _messages.size
        override val isBusy: Boolean get() = uiState.loading
        override val providerId: String? get() = null
        override val tokenEstimate: Int
            get() = TokenEstimator.estimate(
                _messages.map { msg ->
                    when (msg) {
                        is AiMessage.UserMessage -> msg.content.length + msg.attachmentsText.length
                        is AiMessage.AssistantMessage -> msg.content.length + msg.thinkingTokens.length
                        is AiMessage.ToolCall -> msg.rawContent.length + msg.resultRawContent.length
                        is AiMessage.StreamingAssistant -> msg.partialContent.length
                        is AiMessage.StreamingToolCall -> 0
                        is AiMessage.PortalMessage -> 0
                    }
                }
            )
        override val recentToolCalls: List<ToolCallFact>
            get() = _messages.filterIsInstance<AiMessage.ToolCall>().map {
                ToolCallFact(name = it.name, timestamp = it.time, failed = it.isFailed)
            }
        override val conversationTasks: List<ConversationTaskSummary>
            get() = _messages.filterIsInstance<AiMessage.ToolCall>()
                .mapNotNull { call -> call.resultObject as? ToolCallResultObject.Tasks }
                .flatMap { it.tasks }
                .distinctBy { it.id }
                .map { ConversationTaskSummary(id = it.id, title = it.title, isCompleted = it.isCompleted) }
    }

    /** Add a local system-style message to the conversation without sending to the engine. */
    private fun appendLocalMessage(content: String) {
        _messages.add(
            0,
            AiMessage.AssistantMessage(
                content = content,
                time = com.unuslumen.app.util.date.now(),
                uuid = Uuid.random().toString()
            )
        )
    }

    private suspend fun getAttachmentText(attachments: List<AiMessageAttachment>): String {
        val builder = StringBuilder()
        if (attachments.isEmpty()) return ""
        builder.appendLine()
        builder.appendLine("Attached content from the user:")
        for (attachment in attachments) {
            when (attachment) {
                is AiMessageAttachment.Note -> {
                    builder.appendLine("Attached Note:")
                    builder.appendLine(Json.encodeToString(attachment.note))
                }

                is AiMessageAttachment.Task -> {
                    builder.appendLine("Attached Task:")
                    builder.appendLine(Json.encodeToString(attachment.task))
                }

                is AiMessageAttachment.CalenderEvents -> {
                    builder.appendLine("Next 7 days events:")
                    builder.appendLine(Json.encodeToString(getEventsForNext7Days()))
                    builder.appendLine("(Today's date: ${now().formatDate(forceShowYear = true)})")
                }

                is AiMessageAttachment.File -> {
                    builder.appendLine("Attached File:")
                    builder.appendLine("File Name: ${attachment.fileName}")
                    builder.appendLine("File Type: ${attachment.mimeType}")
                    builder.appendLine("File Size: ${attachment.sizeBytes} bytes")
                    builder.appendLine("File Path (cached): ${attachment.cachedPath}")
                }
            }
        }
        return builder.toString()
    }

    private suspend fun getEventsForNext7Days(): Map<String, List<CalendarEvent>> {
        val excluded = getPreference(
            stringSetPreferencesKey(PrefsConstants.EXCLUDED_CALENDARS_KEY),
            emptySet()
        ).first()
        return getCalendarEvents(excluded.toIntList(), todayPlusDays(7)) {
            it.start.formatDateForMapping()
        }
    }


    data class UiState(
        val loading: Boolean = false,
        val error: PortalResult.Failure? = null,
        val noteView: ItemView = ItemView.LIST,
        val searchNotes: List<Note> = emptyList(),
        val searchTasks: List<Task> = emptyList()
    )
}