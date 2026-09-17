package com.unuslumen.app.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.AiRepositoryException
import com.unuslumen.app.domain.model.PortalResult
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.model.ToolCallResultObject
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.use_case.GetAllEventsUseCase
import com.unuslumen.app.domain.use_case.GetNoteUseCase
import com.unuslumen.app.domain.use_case.GetTaskByIdUseCase
import com.unuslumen.app.domain.use_case.SearchNotesUseCase
import com.unuslumen.app.domain.use_case.SearchTasksUseCase
import com.unuslumen.app.domain.use_case.SendAiMessageUseCase
import com.unuslumen.app.domain.slashcommands.ConversationTaskSummary
import com.unuslumen.app.domain.slashcommands.SlashCommandHost
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.TokenEstimator
import com.unuslumen.app.domain.slashcommands.ToolCallFact
import com.unuslumen.app.presentation.components.CommandArgs
import com.unuslumen.app.presentation.components.GuruSlashCommand
import com.unuslumen.app.presentation.slashcommands.ChatHostRouter
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringSetPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProvider
import com.unuslumen.app.preferences.domain.repository.GuruThemeRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import com.unuslumen.app.ui.FontSizeSettings
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.toFontSizeScale
import com.unuslumen.app.ui.toIntList
import com.unuslumen.app.ui.toNotesView
import com.unuslumen.app.util.date.formatDate
import com.unuslumen.app.util.date.formatDateForMapping
import com.unuslumen.app.util.date.now
import com.unuslumen.app.util.date.todayPlusDays
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.android.annotation.KoinViewModel
import kotlin.uuid.Uuid

@KoinViewModel
class PortalViewModel(
    private val sendAiMessage: SendAiMessageUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
    private val searchNotes: SearchNotesUseCase,
    private val searchTasks: SearchTasksUseCase,
    private val getCalendarEvents: GetAllEventsUseCase,
    private val getNoteById: GetNoteUseCase,
    private val getTaskById: GetTaskByIdUseCase,
    private val memoryRepository: MemoryRepository,
    private val guruThemeRepository: GuruThemeRepository,
    private val visionCommandHandler: VisionCommandHandler,
    private val chatHostRouter: ChatHostRouter,
    private val application: android.app.Application
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AiMessage>>(emptyList())
    val messages: StateFlow<List<AiMessage>> = _messages.asStateFlow()

    private val _attachments = MutableStateFlow<List<AiMessageAttachment>>(emptyList())
    val attachments: StateFlow<List<AiMessageAttachment>> = _attachments.asStateFlow()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _aiEnabled = MutableStateFlow(false)
    val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()

    // Model config comes from the server, no local model state needed

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _activeTasks = MutableStateFlow<List<Task>>(emptyList())
    val activeTasks: StateFlow<List<Task>> = _activeTasks.asStateFlow()

    private val _toolCallGroups = MutableStateFlow<List<ToolCallGroup>>(emptyList())
    val toolCallGroups: StateFlow<List<ToolCallGroup>> = _toolCallGroups.asStateFlow()

    private val _userDisplayName = MutableStateFlow("You")
    val userDisplayName: StateFlow<String> = _userDisplayName.asStateFlow()

    val guruTheme: StateFlow<GuruTheme> = guruThemeRepository.getGuruTheme()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), GuruTheme.DEFAULT)

    /**
     * The Settings > font size choice (Small .8 / Normal 1.0 / Large 1.2 / XL 1.5).
     * The Portal chat canvas is a WebView, so Compose's typography scaling never
     * reaches it; this flow feeds the chat text CSS instead so one setting
     * genuinely drives both worlds.
     */
    val chatFontScale: StateFlow<Float> = getPreference(
        intPreferencesKey(PrefsConstants.FONT_SIZE_KEY),
        FontSizeSettings.NORMAL.value
    ).map { it.toFontSizeScale() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    private var conversationLoaded = false

    private var searchNotesJob: Job? = null
    private var searchTasksJob: Job? = null
    private var sendMessageJob: Job? = null

    init {
        loadLastConversation()

        viewModelScope.launch {
            getPreference(
                intPreferencesKey(PrefsConstants.NOTE_VIEW_KEY),
                ItemView.LIST.value
            ).onEach {
                _uiState.value = _uiState.value.copy(noteView = it.toNotesView())
            }.collect()
        }
        viewModelScope.launch {
            getPreference(intPreferencesKey(PrefsConstants.AI_PROVIDER_KEY), AiProvider.UnusLumen.id)
                .map { it.toAiProvider() }
                .collect { provider ->
                    _aiEnabled.value = provider != AiProvider.None
                }
        }
        viewModelScope.launch {
            val name = getPreference(
                stringPreferencesKey(PrefsConstants.USER_NAME_KEY),
                ""
            ).first()
            _userDisplayName.value = name.ifBlank { "You" }
        }
    }

    private fun loadLastConversation() {
        viewModelScope.launch {
            try {
                val conversations = memoryRepository.getAllConversations()
                val lastConversation = conversations.maxByOrNull { it.updatedDate }
                if (lastConversation != null) {
                    _currentConversationId.value = lastConversation.id
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
                        _messages.value = restored.reversed()
                        Log.d("PortalViewModel", "Restored ${restored.size} messages from conversation ${lastConversation.id}")
                    }
                }
            } catch (e: Exception) {
                Log.e("PortalViewModel", "Failed to load last conversation: ${e.message}", e)
            } finally {
                conversationLoaded = true
            }
        }
    }

    fun startNewConversation() {
        // Persist the current conversation to DB before clearing so context is NEVER lost
        val conversationId = _currentConversationId.value
        val currentMessages = _messages.value
        if (conversationId != null && currentMessages.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    memoryRepository.persistAiMessages(conversationId, currentMessages.reversed())
                } catch (e: Exception) {
                    Log.e("PortalViewModel", "Failed to persist conversation before clearing: ${e.message}", e)
                }
            }
        }
        _currentConversationId.value = null
        _messages.value = emptyList()
        _attachments.value = emptyList()
        _activeTasks.value = emptyList()
        _toolCallGroups.value = emptyList()
        _uiState.value = _uiState.value.copy(loading = false, error = null)
    }

    /**
     * Resolve a typed slash command through the domain dispatcher. Every command
     * behaves identically on Portal and Assistant; screen effects ride the host.
     * [screenHost] carries navigation-capable effects only a screen can perform.
     * Vision keeps its dedicated handler path (screen-agnostic pref toggle).
     */
    fun handleSlashCommand(command: GuruSlashCommand, args: CommandArgs?, screenHost: SlashCommandHost? = null) {
        if (command.key == "vision") {
            handleVisionCommand(args?.values?.get("mode"))
            return
        }
        viewModelScope.launch {
            chatHostRouter.route(command, args, portalCommandSession(), portalCommandHost(screenHost))
        }
    }

    /** Session snapshot for slash commands: what the command layer may read. */
    private fun portalCommandSession() = object : SlashCommandSession {
        override val conversationId: String? get() = _currentConversationId.value
        override val messageCount: Int get() = _messages.value.size
        override val isBusy: Boolean get() = _uiState.value.loading
        override val providerId: String? get() = null
        override val tokenEstimate: Int
            get() = TokenEstimator.estimate(
                _messages.value.map { msg ->
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
            get() = _messages.value.filterIsInstance<AiMessage.ToolCall>().map {
                ToolCallFact(name = it.name, timestamp = it.time, failed = it.isFailed)
            }
        override val conversationTasks: List<ConversationTaskSummary>
            get() = _activeTasks.value.map {
                ConversationTaskSummary(id = it.id, title = it.title, isCompleted = it.isCompleted)
            }
    }

    /** Host for slash command results: ViewModel effects here, screen effects delegate. */
    private fun portalCommandHost(screenHost: SlashCommandHost?) = object : SlashCommandHost {
        override fun showDetailList(title: String, items: List<String>) {
            val body = items.joinToString(separator = "\n") { "• $it" }
            showSystemReply("$title\n$body")
        }
        override fun showSystemReply(text: String) {
            _messages.value = listOf(
                AiMessage.AssistantMessage(
                    content = text,
                    time = now(),
                    uuid = Uuid.random().toString()
                )
            ) + _messages.value
        }

        override fun startNewConversation() {
            this@PortalViewModel.startNewConversation()
        }

        override fun cancelRun() {
            onEvent(PortalEvent.CancelMessage)
        }

        override fun forwardToEngine(commandText: String) {
            onEvent(PortalEvent.SendMessage(content = commandText, attachments = emptyList()))
        }

        // Navigation lives in the screen; fall back to no-op when no screen host
        override fun openSkills() = screenHost?.openSkills() ?: Unit
        override fun openPermissionGate() = screenHost?.openPermissionGate() ?: Unit
    }

    fun onEvent(event: PortalEvent) {
        when (event) {
            is PortalEvent.SendMessage -> {
                sendMessageJob?.cancel()

                // Drop orphaned StreamingAssistant messages with no content.
                // If there's visible text, preserve it as a finalized AssistantMessage.
                // If there's no text (empty thinking-only or tool-call-only turn), drop it
                // entirely so empty shells don't litter the chat with invisible padding.
                _messages.value = _messages.value.mapNotNull { msg ->
                    if (msg is AiMessage.StreamingAssistant) {
                        if (msg.partialContent.isNotBlank()) {
                            AiMessage.AssistantMessage(
                                content = msg.partialContent,
                                time = msg.time,
                                uuid = msg.uuid,
                                thinkingTokens = msg.partialThinking,
                            )
                        } else {
                            null
                        }
                    } else {
                        msg
                    }
                }

                sendMessageJob = viewModelScope.launch {
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

                    _messages.value = listOf(message) + _messages.value
                    _attachments.value = emptyList()

                    _uiState.value = _uiState.value.copy(
                        loading = true,
                        error = null
                    )

                    if (_currentConversationId.value == null) {
                        _currentConversationId.value = Uuid.random().toString()
                    }

                    sendAiMessage(_messages.value.reversed(), _currentConversationId.value)
                        .catch { e ->
                            delay(300)

                            val error = if (e is AiRepositoryException) {
                                e.failure
                            } else {
                                PortalResult.OtherError(e.message)
                            }

                            if (error !is PortalResult.ToolCallLimitExceeded) {
                                _messages.value = _messages.value.filterIndexed { i, _ -> i != 0 }
                            }

                            _uiState.value = _uiState.value.copy(
                                loading = false,
                                error = error
                            )
                        }
                        .onCompletion {
                            // Drop orphaned StreamingAssistant messages with no content.
                            // Preserve ones with actual text as finalized AssistantMessage.
                            // Empty shells from thinking-only or tool-call-only turns get
                            // dropped so they don't litter the chat with invisible padding.
                            val current = _messages.value
                            val hasOrphanedStreaming = current.any { it is AiMessage.StreamingAssistant }
                            if (hasOrphanedStreaming) {
                                _messages.value = current.mapNotNull { msg ->
                                    if (msg is AiMessage.StreamingAssistant) {
                                        if (msg.partialContent.isNotBlank()) {
                                            AiMessage.AssistantMessage(
                                                content = msg.partialContent,
                                                time = msg.time,
                                                uuid = msg.uuid,
                                                thinkingTokens = msg.partialThinking,
                                            )
                                        } else {
                                            null
                                        }
                                    } else {
                                        msg
                                    }
                                }
                            }
                            _uiState.value = _uiState.value.copy(loading = false)
                        }
                        .collect { msg ->
                            handleMessageFromStream(msg)
                        }
                }
            }

            is PortalEvent.SearchNotes -> {
                searchNotesJob?.cancel()
                searchNotesJob = viewModelScope.launch {
                    delay(300)
                    searchNotes(event.query).let {
                        _uiState.value = _uiState.value.copy(searchNotes = it)
                    }
                }
            }

            is PortalEvent.SearchTasks -> {
                searchTasksJob?.cancel()
                searchTasksJob = viewModelScope.launch {
                    delay(300)
                    searchTasks(event.query).first().let {
                        _uiState.value = _uiState.value.copy(searchTasks = it)
                    }
                }
            }

            PortalEvent.AddAttachmentEvents -> {
                _attachments.value = _attachments.value + AiMessageAttachment.CalenderEvents
            }

            is PortalEvent.AddAttachmentFile -> {
                // IO dispatcher: the cache copy and any video frame extraction are
                // blocking disk/media operations that would stutter the main thread
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val uri = event.uri
                    val fileName = getFileName(application, uri) ?: "unknown_file"
                    val mimeType = application.contentResolver.getType(uri) ?: "application/octet-stream"
                    val cachedPath = copyToCache(application, uri, fileName)
                    if (cachedPath != null) {
                        val file = java.io.File(cachedPath)
                        // Video attachments get a first-frame still so the chat can
                        // show a real thumbnail instead of a filename chip
                        val thumbPath = if (mimeType.startsWith("video/", ignoreCase = true)) {
                            extractVideoFrame(cachedPath)
                        } else null
                        _attachments.value = _attachments.value + AiMessageAttachment.File(
                            originalUri = uri.toString(),
                            fileName = fileName,
                            mimeType = mimeType,
                            cachedPath = cachedPath,
                            sizeBytes = file.length(),
                            thumbnailPath = thumbPath
                        )
                    }
                }
            }

            is PortalEvent.AddAttachmentNote -> viewModelScope.launch {
                val note = getNoteById(event.id) ?: return@launch
                _attachments.value = _attachments.value + AiMessageAttachment.Note(
                    note.copy(
                        title = note.title.ifBlank { "Untitled Note" }
                    )
                )
            }

            is PortalEvent.AddAttachmentTask -> viewModelScope.launch {
                _attachments.value = _attachments.value + AiMessageAttachment.Task(getTaskById(event.id) ?: return@launch )
            }

            is PortalEvent.RemoveAttachment -> {
                _attachments.value = _attachments.value.filterIndexed { i, _ -> i != event.index }
            }

            PortalEvent.CancelMessage -> {
                sendMessageJob?.cancel()
                // Remove the user message and any streaming messages that followed
                _messages.value = _messages.value.filter { msg ->
                    msg !is AiMessage.StreamingAssistant && msg !is AiMessage.StreamingToolCall
                }.let { msgs ->
                    if (msgs.firstOrNull() is AiMessage.UserMessage) msgs.drop(1) else msgs
                }
                _uiState.value = _uiState.value.copy(loading = false)
            }

            PortalEvent.NewConversation -> {
                startNewConversation()
            }

            is PortalEvent.VisionCommand -> {
                handleVisionCommand(event.mode)
            }

            is PortalEvent.PortalEventAction -> {
                sendMessageJob?.cancel()

                // Same orphaned streaming cleanup as SendMessage handler —
                // drop empty shells, preserve messages with actual content.
                _messages.value = _messages.value.mapNotNull { msg ->
                    if (msg is AiMessage.StreamingAssistant) {
                        if (msg.partialContent.isNotBlank()) {
                            AiMessage.AssistantMessage(
                                content = msg.partialContent,
                                time = msg.time,
                                uuid = msg.uuid,
                                thinkingTokens = msg.partialThinking,
                            )
                        } else {
                            null
                        }
                    } else {
                        msg
                    }
                }

                sendMessageJob = viewModelScope.launch {
                    while (!conversationLoaded) {
                        delay(50)
                    }

                    val message = AiMessage.UserMessage(
                        content = "[Portal Event: ${event.name}] ${event.payload}",
                        attachments = emptyList(),
                        attachmentsText = "",
                        time = now(),
                        uuid = Uuid.random().toString()
                    )

                    _messages.value = listOf(message) + _messages.value

                    _uiState.value = _uiState.value.copy(
                        loading = true,
                        error = null
                    )

                    if (_currentConversationId.value == null) {
                        _currentConversationId.value = Uuid.random().toString()
                    }

                    sendAiMessage(_messages.value.reversed(), _currentConversationId.value)
                        .catch { e ->
                            delay(300)
                            val error = if (e is AiRepositoryException) {
                                e.failure
                            } else {
                                PortalResult.OtherError(e.message)
                            }
                            if (error !is PortalResult.ToolCallLimitExceeded) {
                                _messages.value = _messages.value.filterIndexed { i, _ -> i != 0 }
                            }
                            _uiState.value = _uiState.value.copy(
                                loading = false,
                                error = error
                            )
                        }
                        .onCompletion {
                            // Same orphaned streaming cleanup — drop empty shells,
                            // preserve messages with actual content.
                            val current = _messages.value
                            val hasOrphanedStreaming = current.any { it is AiMessage.StreamingAssistant }
                            if (hasOrphanedStreaming) {
                                _messages.value = current.mapNotNull { msg ->
                                    if (msg is AiMessage.StreamingAssistant) {
                                        if (msg.partialContent.isNotBlank()) {
                                            AiMessage.AssistantMessage(
                                                content = msg.partialContent,
                                                time = msg.time,
                                                uuid = msg.uuid,
                                                thinkingTokens = msg.partialThinking,
                                            )
                                        } else {
                                            null
                                        }
                                    } else {
                                        msg
                                    }
                                }
                            }
                            _uiState.value = _uiState.value.copy(loading = false)
                        }
                        .collect { msg ->
                            handleMessageFromStream(msg)
                        }
                }
            }
        }
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
                    builder.appendLine("")
                    builder.appendLine("IMPORTANT — To process this file, use the processFile tool with the cachedPath above. The bundled tools handle everything on-device, no installs needed:")
                    builder.appendLine("- Images: dimensions + INLINE OCR TEXT extracted with the bundled tesseract engine, returned in the same result. The image is also delivered to you visually as an image block.")
                    builder.appendLine("- PDFs: text extraction via pdftotext, with PdfRenderer + OCR fallback for scanned PDFs.")
                    builder.appendLine("- Office docs (docx/xlsx/pptx): unzip + read the XML inside (unzip is bundled).")
                    builder.appendLine("- Videos: bundled ffmpeg extracts a 6-frame contact sheet + full metadata in the result.")
                    builder.appendLine("- Audio: metadata + bundled whisper transcription via the transcribe tools.")
                    builder.appendLine("- Archives: contents listed; extract with the bundled unzip.")
                    builder.appendLine("- Plain text: returns file content directly.")
                    builder.appendLine("")
                    builder.appendLine("DO NOT try to readFile() on binary files (images, videos, PDFs) — use processFile first, then follow the guidance it returns.")
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


    /**
     * Handle a message from the streaming flow.
     * - StreamingAssistant: replace existing streaming message with same uuid, or prepend new one
     * - AssistantMessage: replace any StreamingAssistant with same uuid
     * - ToolCall: replace any StreamingToolCall that was previewing it
     * - Other messages: prepend as before
     */
    private fun handleMessageFromStream(msg: AiMessage) {
        when (msg) {
            is AiMessage.StreamingAssistant -> {
                val existing = _messages.value
                val existingIndex = existing.indexOfFirst { it.uuid == msg.uuid }
                if (existingIndex >= 0) {
                    // Replace existing streaming message in place
                    _messages.value = existing.toMutableList().also { it[existingIndex] = msg }
                } else {
                    // New streaming message — prepend
                    _messages.value = listOf(msg) + _messages.value
                }
            }
            is AiMessage.AssistantMessage -> {
                val existing = _messages.value
                val streamingIndex = existing.indexOfFirst { it.uuid == msg.uuid && it is AiMessage.StreamingAssistant }
                if (streamingIndex >= 0) {
                    // Replace the streaming message with the final one
                    _messages.value = existing.toMutableList().also { it[streamingIndex] = msg }
                } else {
                    // No streaming version found — prepend
                    _messages.value = listOf(msg) + _messages.value
                }
            }
            is AiMessage.StreamingToolCall -> {
                val existing = _messages.value
                val existingIndex = existing.indexOfFirst { it.uuid == msg.uuid && it is AiMessage.StreamingToolCall }
                if (existingIndex >= 0) {
                    // Replace existing streaming tool call in place so multiple
                    // delta frames for the same tool call don't pile up as
                    // separate pills on the portal canvas. Each delta frame
                    // for one tool call shares one UUID (the stable call id
                    // from the server, e.g. call_xxx) thanks to AiRepositoryImpl.
                    _messages.value = existing.toMutableList().also { it[existingIndex] = msg }
                } else {
                    // New streaming tool call — prepend
                    _messages.value = listOf(msg) + _messages.value
                }
                rebuildToolCallGroups()
            }
            is AiMessage.ToolCall -> {
                val existing = _messages.value
                val streamingIndex = existing.indexOfFirst { it.uuid == msg.uuid && it is AiMessage.StreamingToolCall }
                if (streamingIndex >= 0) {
                    _messages.value = existing.toMutableList().also { it[streamingIndex] = msg }
                } else {
                    _messages.value = listOf(msg) + _messages.value
                }
                scanForTasks(msg)
                rebuildToolCallGroups()
            }
            else -> {
                _messages.value = listOf(msg) + _messages.value
            }
        }
    }

    private fun scanForTasks(msg: AiMessage) {
        if (msg !is AiMessage.ToolCall) return
        val result = msg.resultObject ?: return
        if (result !is ToolCallResultObject.Tasks) return
        val newTasks = result.tasks.filter { !it.isCompleted }
        if (newTasks.isNotEmpty()) {
            val existing = _activeTasks.value.associateBy { it.id }
            val merged = existing + newTasks.associateBy { it.id }
            _activeTasks.value = merged.values.filter { !it.isCompleted }.toList()
        }
    }

    fun toggleTaskCompletion(taskId: String) {
        _activeTasks.value = _activeTasks.value.filter { it.id != taskId }
    }

    fun clearActiveTasks() {
        _activeTasks.value = emptyList()
    }

    data class UiState(
        val loading: Boolean = false,
        val error: PortalResult.Failure? = null,
        val noteView: ItemView = ItemView.LIST,
        val searchNotes: List<Note> = emptyList(),
        val searchTasks: List<Task> = emptyList()
    )

    /**
     * A group of contiguous tool-call-shaped messages (ToolCall + StreamingToolCall)
     * that form one logical run in the conversation. The most recent group is shown
     * expanded in the dock, previous groups collapse to compact summary rows.
     */
    data class ToolCallGroup(
        val uuid: String,
        val toolCalls: List<AiMessage>,
        val summary: String,
        val timestamp: Long,
        val isStreaming: Boolean
    )

    /**
     * Rebuild tool call groups from the current messages list.
     * Scans chronological messages (newest first in _messages) and groups
     * contiguous runs of ToolCall and StreamingToolCall messages.
     */
    private fun rebuildToolCallGroups() {
        val messages = _messages.value
        if (messages.isEmpty()) {
            _toolCallGroups.value = emptyList()
            return
        }

        val chronological = messages.reversed()
        val groups = mutableListOf<ToolCallGroup>()
        var i = 0
        while (i < chronological.size) {
            val msg = chronological[i]
            if (msg is AiMessage.ToolCall || msg is AiMessage.StreamingToolCall) {
                var runEnd = i
                while (runEnd < chronological.size - 1 &&
                    (chronological[runEnd + 1] is AiMessage.ToolCall ||
                        chronological[runEnd + 1] is AiMessage.StreamingToolCall)) {
                    runEnd++
                }
                val runMessages = chronological.subList(i, runEnd + 1)
                val completedCalls = runMessages.filterIsInstance<AiMessage.ToolCall>()
                val summary = if (completedCalls.isNotEmpty()) {
                    val displayNames = completedCalls.map { call ->
                        val display = call.name.replace(Regex("([A-Z])"), " $1").trim()
                            .replaceFirstChar { it.uppercase() }
                        display
                    }
                    val distinct = displayNames.distinct()
                    when {
                        distinct.size == 1 -> distinct[0]
                        distinct.size == 2 -> "${distinct[0]} and ${distinct[1]}"
                        else -> "${distinct[0]}, ${distinct[1]}, and ${distinct.size - 2} more"
                    }
                } else {
                    val streamNames = runMessages.filterIsInstance<AiMessage.StreamingToolCall>()
                        .map { it.toolName.replace(Regex("([A-Z])"), " $1").trim().replaceFirstChar { c -> c.uppercase() } }
                    val distinct = streamNames.distinct()
                    when {
                        distinct.isEmpty() -> "Working"
                        distinct.size == 1 -> "${distinct[0]}..."
                        distinct.size == 2 -> "${distinct[0]} and ${distinct[1]}..."
                        else -> "${distinct[0]}, ${distinct[1]}, and ${distinct.size - 2} more..."
                    }
                }

                val firstMsg = runMessages.first()
                val firstTimestamp = when (firstMsg) {
                    is AiMessage.ToolCall -> firstMsg.time
                    is AiMessage.StreamingToolCall -> firstMsg.time
                    else -> 0L
                }

                groups.add(ToolCallGroup(
                    uuid = "group_${firstMsg.uuid}",
                    toolCalls = runMessages.toList(),
                    summary = summary,
                    timestamp = firstTimestamp,
                    isStreaming = runMessages.any { it is AiMessage.StreamingToolCall }
                ))
                i = runEnd + 1
            } else {
                i++
            }
        }
        _toolCallGroups.value = groups
    }

    fun dismissToolCallGroup(groupUuid: String) {
        val group = _toolCallGroups.value.find { it.uuid == groupUuid } ?: return
        val completedCalls = group.toolCalls.filterIsInstance<AiMessage.ToolCall>()
        if (completedCalls.isNotEmpty()) {
            val summaryLine = completedCalls.joinToString(", ") { call ->
                val display = call.name.replace(Regex("([A-Z])"), " $1").trim()
                    .replaceFirstChar { it.uppercase() }
                display
            }
            val summaryMessage = AiMessage.AssistantMessage(
                content = summaryLine,
                time = now(),
                uuid = Uuid.random().toString()
            )
            _messages.value = listOf(summaryMessage) + _messages.value
        }
        _toolCallGroups.value = _toolCallGroups.value.filter { it.uuid != groupUuid }
    }

    /**
     * Handle /vision on|off|status. The shared handler flips the preference; the
     * confirmation is prepended locally so it appears immediately in the chat with
     * no engine round trip.
     */
    private fun handleVisionCommand(mode: String?) {
        viewModelScope.launch {
            val confirmation = try {
                visionCommandHandler.handle(mode)
            } catch (e: Exception) {
                Log.e("PortalViewModel", "Vision command failed: ${e.message}", e)
                "Vision command failed: ${e.message}"
            }
            _messages.value = listOf(
                AiMessage.AssistantMessage(
                    content = confirmation,
                    time = now(),
                    uuid = Uuid.random().toString()
                )
            ) + _messages.value
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    if (name.isNullOrBlank()) {
        name = uri.lastPathSegment?.substringAfterLast('/') ?: "unknown_file"
    }
    return name
}

private fun copyToCache(context: Context, uri: Uri, fileName: String): String? {
    return try {
        val cacheDir = java.io.File(context.cacheDir, "attached_files")
        cacheDir.mkdirs()
        val destFile = java.io.File(cacheDir, "${System.currentTimeMillis()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        destFile.absolutePath
    } catch (e: Exception) {
        Log.e("PortalViewModel", "Failed to copy file to cache: ${e.message}", e)
        null
    }
}

/**
 * Grab the first frame of a video and save it as a JPEG beside the cached video.
 * Returns the absolute path of the still, or null on any failure — callers fall
 * back to the filename chip when null so nothing ever blocks on extraction.
 */
private fun extractVideoFrame(videoPath: String): String? {
    return try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(videoPath)
        val bitmap = retriever.getFrameAtTime(
            0,
            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        )
        retriever.release()
        if (bitmap == null) return null
        val outFile = java.io.File(
            java.io.File(videoPath).parentFile,
            "thumb_${System.currentTimeMillis()}_${java.io.File(videoPath).nameWithoutExtension}.jpg"
        )
        java.io.FileOutputStream(outFile).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        }
        bitmap.recycle()
        if (outFile.exists() && outFile.length() > 0L) outFile.absolutePath else null
    } catch (e: Exception) {
        Log.e("PortalViewModel", "Failed to extract video frame: ${e.message}")
        null
    }
}
