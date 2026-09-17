@file:Suppress("AssignedValueIsNeverRead")

package com.unuslumen.app.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.presentation.components.ChatToHtml
import com.unuslumen.app.presentation.components.CommandArgs
import com.unuslumen.app.presentation.components.GuruSlashCommand
import com.unuslumen.app.presentation.components.ChatTextConfig
import com.unuslumen.app.presentation.components.FilePreviewContent
import com.unuslumen.app.presentation.components.resolveFilePreviewInfo
import com.unuslumen.app.presentation.components.PortalCanvas
import com.unuslumen.app.presentation.components.PortalCanvasBridge
import com.unuslumen.app.presentation.components.PortalChatBar
import com.unuslumen.app.presentation.components.PortalHeader
import com.unuslumen.app.presentation.components.AttachNoteSheet
import com.unuslumen.app.presentation.components.AttachTaskSheet
import com.unuslumen.app.presentation.components.AttachmentDropDownMenu
import com.unuslumen.app.presentation.components.AttachmentMenuItem
import com.unuslumen.app.presentation.components.GuruMask
import com.unuslumen.app.presentation.components.GuruMaskSelector
import com.unuslumen.app.presentation.components.SlashCommandArgMenu
import com.unuslumen.app.presentation.components.CommandArgMenuResult
import com.unuslumen.app.presentation.components.SlashCommandOverlay
import com.unuslumen.app.presentation.components.GuruSlashCommandRegistry
import com.unuslumen.app.presentation.components.ToolCallListSheet
import com.unuslumen.app.presentation.components.ToolCallDetailSheet
import com.unuslumen.app.presentation.components.ToolCallDock
import com.unuslumen.app.presentation.components.ToolCallExpandedView
import com.unuslumen.app.presentation.activetasks.ActiveTasksPopup
import com.unuslumen.app.adspace.StickmanAdSpace
import com.unuslumen.app.presentation.luxify.LuxifyInterviewViewModel
import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.permission.PermissionGateController
import com.unuslumen.app.preferences.domain.repository.GuruThemeRepository
import com.unuslumen.app.presentation.findHostActivity
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.R
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.webkit.WebView

@Composable
fun PortalScreen(
    navController: NavHostController,
) {
    // Scope the streaming ViewModel to the Activity, not to this nav entry.
    // koinViewModel resolves against LocalViewModelStoreOwner; providing the
    // Activity's owner means a native back press can pop Portal off the stack
    // while the ViewModel (and its in-flight LLM stream in viewModelScope)
    // survives. Returning re-attaches to the same live stream.
    val activityOwner = LocalContext.current.findHostActivity() as? ViewModelStoreOwner
    if (activityOwner != null) {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides activityOwner
        ) {
            PortalScreenScoped(navController)
        }
    } else {
        PortalScreenScoped(navController)
    }
}

@Composable
private fun PortalScreenScoped(
    navController: NavHostController,
    viewModel: PortalViewModel = koinViewModel(),
    interviewViewModel: LuxifyInterviewViewModel = koinViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val interviewState by interviewViewModel.state.collectAsStateWithLifecycle()
    val activeTasks by viewModel.activeTasks.collectAsStateWithLifecycle()
    val toolCallGroups by viewModel.toolCallGroups.collectAsStateWithLifecycle()
    val userDisplayName by viewModel.userDisplayName.collectAsStateWithLifecycle()
    val guruTheme by viewModel.guruTheme.collectAsStateWithLifecycle()
    val chatFontScale by viewModel.chatFontScale.collectAsStateWithLifecycle(1.0f)

    // Detect interview markers in the latest assistant message
    val latestAssistant = messages.firstOrNull()?.let { it as? AiMessage.AssistantMessage }
    LaunchedEffect(latestAssistant?.uuid) {
        if (latestAssistant != null) {
            interviewViewModel.handleGuruResponse(latestAssistant.content)
        }
    }

    PortalScreenContent(
        uiState = uiState,
        messages = messages,
        attachments = attachments,
        aiEnabled = aiEnabled,
        onEvent = viewModel::onEvent,
        onSlashCommand = { command, args, screenHost ->
            viewModel.handleSlashCommand(command, args, screenHost)
        },
        navController = navController,
        onOtioClick = {
            // The Otio slot in the chat bar is the way back to the lobby.
            navController.navigate(Screen.LobbyScreen) {
                launchSingleTop = true
            }
        },
        interviewState = interviewState,
        onInterviewAnswer = { answer ->
            interviewViewModel.submitAnswer(answer)
            viewModel.onEvent(PortalEvent.SendMessage(content = answer, attachments = emptyList()))
        },
        onConfirmSkill = { interviewViewModel.confirmSkill() },
        onCancelInterview = { interviewViewModel.cancelInterview() },
        activeTasks = activeTasks,
        onTaskComplete = { viewModel.toggleTaskCompletion(it) },
        toolCallGroups = toolCallGroups,
        onDismissToolCallGroup = { viewModel.dismissToolCallGroup(it) },
        userDisplayName = userDisplayName,
        guruTheme = guruTheme,
        chatFontScale = chatFontScale
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalScreenContent(
    uiState: PortalViewModel.UiState,
    messages: List<AiMessage>,
    attachments: List<AiMessageAttachment>,
    aiEnabled: Boolean,
    onEvent: (PortalEvent) -> Unit,
    onSlashCommand: (GuruSlashCommand, CommandArgs?, com.unuslumen.app.domain.slashcommands.SlashCommandHost?) -> Unit,
    navController: NavHostController,
    onOtioClick: () -> Unit = {},
    interviewState: com.unuslumen.app.presentation.luxify.LuxifyInterviewState = com.unuslumen.app.presentation.luxify.LuxifyInterviewState(),
    onInterviewAnswer: (String) -> Unit = {},
    onConfirmSkill: () -> Unit = {},
    onCancelInterview: () -> Unit = {},
    activeTasks: List<com.unuslumen.app.domain.model.Task> = emptyList(),
    onTaskComplete: (String) -> Unit = {},
    toolCallGroups: List<PortalViewModel.ToolCallGroup> = emptyList(),
    onDismissToolCallGroup: (String) -> Unit = {},
    userDisplayName: String = "You",
    guruTheme: GuruTheme? = null,
    chatFontScale: Float = 1.0f,
) {
    val context = LocalContext.current
    val loading = uiState.loading
    val error = uiState.error
    var text by rememberSaveable { mutableStateOf("") }
    var attachmentsMenuExpanded by remember { mutableStateOf(false) }
    var openNoteSheet by remember { mutableStateOf(false) }
    var openTaskSheet by remember { mutableStateOf(false) }
    var selectedGuruMask by rememberSaveable { mutableStateOf(GuruMask.Coder) }
    var masksExpanded by rememberSaveable { mutableStateOf(false) }

    // Slash command arg-menu state — same tap flow as AssistantScreen
    var activeCommand by remember { mutableStateOf<GuruSlashCommand?>(null) }
    var activeArgMenu by remember { mutableStateOf<CommandArgMenuResult?>(null) }

    LaunchedEffect(text) {
        if (text.startsWith("/")) {
            val resolved = GuruSlashCommandRegistry.resolve(text)
            if (resolved != null && resolved != activeCommand) {
                activeCommand = resolved
                val args = GuruSlashCommandRegistry.parseArgs(resolved, text)
                activeArgMenu = GuruSlashCommandRegistry.needsArgMenu(context.resources, resolved, args)
            } else if (resolved == null) {
                activeCommand = null
                activeArgMenu = null
            } else {
                val args = GuruSlashCommandRegistry.parseArgs(resolved, text)
                activeArgMenu = GuruSlashCommandRegistry.needsArgMenu(context.resources, resolved, args)
            }
        } else {
            activeCommand = null
            activeArgMenu = null
        }
    }

    // Build chat text config from GuruTheme preferences, with the Settings
    // font size choice (0.8/1.0/1.2/1.5) folded into both scales. The canvas
    // is a WebView: Compose typography scaling never reaches it, so this is
    // how one setting drives chat text in the portal as well as the app shell.
    val chatTextConfig = remember(guruTheme, chatFontScale) {
        fun scale(base: Float?): Float = (base ?: 1.0f) * chatFontScale
        ChatTextConfig(
            guruFont = guruTheme?.guruChatFont,
            guruColour = guruTheme?.guruChatColour,
            guruFontScale = scale(guruTheme?.guruChatFontScale),
            userFont = guruTheme?.userChatFont,
            userColour = guruTheme?.userChatColour,
            userFontScale = scale(guruTheme?.userChatFontScale)
        )
    }

    // Tool call sheet
    var openGroupKey by remember { mutableStateOf<String?>(null) }
    var selectedToolCallUuid by remember { mutableStateOf<String?>(null) }
    var showToolCallSheet by remember { mutableStateOf(false) }
    var selectedMessageUuid by remember { mutableStateOf<String?>(null) }
    val enableRevert = false
    val toolCallSheetScope = rememberCoroutineScope()

    val keyboardController = LocalSoftwareKeyboardController.current
    val liquidState = rememberLiquidState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val permissionGateController = koinInject<PermissionGateController>()
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    // Canvas bridge — receives events from the WebView canvas
    val canvasBridge = remember { PortalCanvasBridge() }

    // Track the WebView reference for sending commands
    var canvasWebView by remember { mutableStateOf<WebView?>(null) }

    // Fullscreen image viewer — set to the cached file path of the tapped attachment
    var expandedImagePath by remember { mutableStateOf<String?>(null) }

    // Fullscreen video player — set to the video file path when tapped open
    var expandedVideoPath by remember { mutableStateOf<String?>(null) }

    // File preview sheet — set to the cached file path of a tapped file chip
    var expandedFilePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible) focusManager.clearFocus()
        else {
            // Keyboard expanded — wait for the IME layout animation to settle,
            // then scroll the canvas to the bottom so the latest messages stay
            // visible above the chat bar instead of hiding behind it.
            kotlinx.coroutines.delay(300)
            PortalCanvas_sendCommand(canvasWebView, "scrollToBottom")
        }
    }

    // Track which messages have been rendered to the canvas
    val renderedMessageIds = remember { mutableStateOf(setOf<String>()) }
    // Track which messages have been finalized (innerhHTML set to final HTML)
    // so Path 2 doesn't re-finalize them on every state change
    val finalizedMessageIds = remember { mutableStateOf(setOf<String>()) }
    var canvasReady by remember { mutableStateOf(false) }

    // Update ChatToHtml config when theme or font size changes. Already-rendered
    // messages keep their old em size because the CSS variable only applies to
    // newly appended HTML, so a config change also wipes the render bookkeeping
    // and clears the canvas — the messages effect re-pushes the full conversation
    // at the new size.
    LaunchedEffect(chatTextConfig) {
        ChatToHtml.chatConfig = chatTextConfig
        if (canvasReady) {
            renderedMessageIds.value = emptySet()
            finalizedMessageIds.value = emptySet()
            PortalCanvas_sendCommand(canvasWebView, "clear")
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        // One picker pass, multiple attachments — each URI walks the same
        // AddAttachmentFile pipeline (cache copy + video frame extraction)
        for (uri in uris) {
            onEvent(PortalEvent.AddAttachmentFile(uri))
        }
    }

    // Wire up canvas bridge events
    LaunchedEffect(Unit) {
        canvasBridge.onEvent = { name, payload ->
            when (name) {
                "toolcall_tap" -> {
                    // Find the tool call in messages and open the bottom sheet
                    val toolCall = messages.filterIsInstance<AiMessage.ToolCall>()
                        .find { it.uuid == payload }
                    if (toolCall != null) {
                        openGroupKey = "single_${toolCall.uuid}"
                        selectedToolCallUuid = toolCall.uuid
                    }
                }
                "note_click" -> navController.navigate(Screen.NoteDetailsScreen(noteId = payload, folderId = ""))
                "task_click" -> navController.navigate(Screen.TaskDetailScreen(taskId = payload))
                "event_click" -> navController.navigate(Screen.CalendarEventDetailsScreen(eventId = payload.toLongOrNull() ?: 0L))
                "message_tap" -> { selectedMessageUuid = payload }
                "attachment_image_tap" -> {
                    // Video thumbs carry a "video:" prefix so their tap opens
                    // the fullscreen player; everything else opens the image viewer
                    if (payload.startsWith("video:")) {
                        expandedVideoPath = payload.removePrefix("video:")
                    } else {
                        expandedImagePath = payload
                    }
                }
                "attachment_file_tap" -> expandedFilePath = payload
                else -> onEvent(PortalEvent.PortalEventAction(name = name, payload = payload))
            }
        }
    }

    // Update the user's display name in ChatToHtml
    LaunchedEffect(userDisplayName) {
        ChatToHtml.userDisplayName = userDisplayName
    }

    // Push messages to canvas when they change
    LaunchedEffect(messages, canvasReady) {
        if (!canvasReady) return@LaunchedEffect

        val currentRendered = renderedMessageIds.value
        val newMessages = messages.reversed().filter {
            it.uuid !in currentRendered && it !is AiMessage.ToolCall && it !is AiMessage.StreamingToolCall
        }

        if (newMessages.isEmpty()) {
            // Check for streaming updates or finalizations
            val currentFinalized = finalizedMessageIds.value
            for (message in messages) {
                if (message is AiMessage.StreamingAssistant && message.uuid in currentRendered) {
                    PortalCanvas_sendCommand(canvasWebView, "updateInProgress", message.uuid, message.partialContent)
                    PortalCanvas_sendCommand(canvasWebView, "updateThinking", message.uuid, message.partialThinking)
                } else if (message is AiMessage.AssistantMessage && message.uuid in currentRendered && message.uuid !in currentFinalized) {
                    val html = ChatToHtml.toHtml(message)
                    val finalType = ChatToHtml.finalAssistantType(message.content)
                    PortalCanvas_sendCommand(canvasWebView, "finalizeMessage", message.uuid, html, finalType)
                    finalizedMessageIds.value = finalizedMessageIds.value + message.uuid
                }
            }
            return@LaunchedEffect
        }

        // Batch: build a single JS call that appends all new messages at once
        val sb = StringBuilder()
        var i = 0
        while (i < newMessages.size) {
            val message = newMessages[i]

            // Skip messages that are purely Luxify interview markers
            if (message is AiMessage.AssistantMessage && ChatToHtml.isLuxifyOnly(message.content)) {
                renderedMessageIds.value = renderedMessageIds.value + message.uuid
                i++
                continue
            }
            if (message is AiMessage.StreamingAssistant && ChatToHtml.isLuxifyOnly(message.partialContent)) {
                renderedMessageIds.value = renderedMessageIds.value + message.uuid
                i++
                continue
            }

            val html = ChatToHtml.toHtml(message)
            val type = ChatToHtml.messageType(message)
            val escapedHtml = html.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
            val escapedUuid = message.uuid.replace("'", "\\'")
            sb.append("PortalCanvas.appendMessage('$escapedUuid','$escapedHtml','$type');")
            renderedMessageIds.value = renderedMessageIds.value + message.uuid
            i++
        }

        android.util.Log.d("PortalScreen", "Batch pushing ${newMessages.size} messages to canvas")
        val js = "if (typeof PortalCanvas !== 'undefined') { $sb }"
        try {
            canvasWebView?.evaluateJavascript(js, null)
        } catch (e: Exception) {
            android.util.Log.w("PortalScreen", "Batch push failed: ${e.message}")
        }

        // Scroll to bottom after batch
        PortalCanvas_sendCommand(canvasWebView, "scrollToBottom")
    }

    // Clear canvas when messages are cleared
    LaunchedEffect(messages.isEmpty()) {
        if (messages.isEmpty() && canvasReady) {
            PortalCanvas_sendCommand(canvasWebView, "clear")
            renderedMessageIds.value = emptySet()
            finalizedMessageIds.value = emptySet()
        }
    }

    // Scaffold with canvas as content
    Scaffold(
        topBar = {},
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (activeTasks.isNotEmpty()) {
                        ActiveTasksPopup(
                            tasks = activeTasks,
                            onTaskComplete = onTaskComplete,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                // Arg menu overlay — pick values for typed commands before sending
                if (activeArgMenu != null) {
                    SlashCommandArgMenu(
                        menu = activeArgMenu!!,
                        onArgSelected = { value ->
                            val cmd = activeCommand ?: return@SlashCommandArgMenu
                            text = text.trimEnd() + " " + value
                            val args = GuruSlashCommandRegistry.parseArgs(cmd, text)
                            activeArgMenu = GuruSlashCommandRegistry.needsArgMenu(context.resources, cmd, args)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                SlashCommandOverlay(
                    text = text,
                    onCommandSelected = { command -> text = command.displayName + " " },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                StickmanAdSpace(
                    loading = loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                PortalChatBar(
                    text = text,
                    enabled = aiEnabled && (text.isNotBlank() || attachments.isNotEmpty()),
                    attachments = attachments,
                    onTextChange = { text = it },
                    onAttachClick = { attachmentsMenuExpanded = true },
                    onRemoveAttachment = {
                        onEvent(PortalEvent.RemoveAttachment(it))
                    },
                    loading = loading,
                    onSend = {
                        val resolved = GuruSlashCommandRegistry.resolve(text)
                        if (resolved != null) {
                            // Screen-only effects (skills nav, permission gate) ride a
                            // nav host passed alongside; all other semantics are domain.
                            val screenHost = object : com.unuslumen.app.domain.slashcommands.SlashCommandHost {
                                override fun showSystemReply(text: String) {}
                                override fun showDetailList(title: String, items: List<String>) {}
                                override fun startNewConversation() {}
                                override fun cancelRun() {}
                                override fun forwardToEngine(commandText: String) {}
                                override fun openSkills() {
                                    navController.navigate(Screen.SkillsScreen)
                                }
                                override fun openPermissionGate() {
                                    permissionGateController.requestShow()
                                }
                            }
                            onSlashCommand(
                                resolved,
                                GuruSlashCommandRegistry.parseArgs(resolved, text),
                                screenHost,
                            )
                        } else {
                            onEvent(
                                PortalEvent.SendMessage(
                                    content = text,
                                    attachments = attachments.toList()
                                )
                            )
                        }
                        text = ""
                        keyboardController?.hide()
                    },
                    onCancel = {
                        onEvent(PortalEvent.CancelMessage)
                    },
                    liquidState = liquidState,
                    toolCallCount = toolCallGroups.sumOf { it.toolCalls.size },
                    isToolCallActive = toolCallGroups.any { it.isStreaming },
                    onToolCallClick = {
                        keyboardController?.hide()
                        showToolCallSheet = true
                    },
                    onFilePreview = { path -> expandedFilePath = path },
                    onOtioClick = onOtioClick,
                )
                }
            }
            val excludedItems by remember {
                derivedStateOf {
                    if (attachments.contains(AiMessageAttachment.CalenderEvents)) {
                        listOf(AttachmentMenuItem.CalendarEvents)
                    } else {
                        emptyList()
                    }
                }
            }
            AttachmentDropDownMenu(
                modifier = Modifier.fillMaxWidth(),
                expanded = attachmentsMenuExpanded,
                liquidState = liquidState,
                onDismiss = { attachmentsMenuExpanded = false },
                excludedItems = excludedItems,
                onItemClick = {
                    when (it) {
                        AttachmentMenuItem.Note -> openNoteSheet = true
                        AttachmentMenuItem.Task -> openTaskSheet = true
                        AttachmentMenuItem.CalendarEvents -> onEvent(PortalEvent.AddAttachmentEvents)
                        AttachmentMenuItem.File -> filePickerLauncher.launch("*/*")
                    }
                    attachmentsMenuExpanded = false
                }
            )
        },
        containerColor = Color.Transparent,
        modifier = Modifier.fillMaxSize().imePadding()
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Full-bleed art background (trial) — sits beneath logo and overlay.
            Image(
                painter = painterResource(id = R.drawable.portal_bg_art),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            // Warm paper overlay — aged cream fading to warm bone at the bottom.
            // Replaces the old cool purple gradient with something that reads as ink-on-page.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF0E8DA).copy(alpha = 0.75f),
                                Color(0xFFE4D9C6).copy(alpha = 0.55f),
                            )
                        )
                    )
            )

            // Centre fold GURU logo — background watermark, everything renders on top.
            // Sits ABOVE the art and overlay so the branding reads over the busy texture.
            Image(
                painter = painterResource(id = R.drawable.guru_logo),
                contentDescription = "GURU",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-50).dp)
                    .alpha(0.15f)
                    .width(400.dp)
                    .height(218.dp),
                contentScale = ContentScale.Fit,
            )

            // Canvas always in the composition tree — never conditionally rendered
            // to prevent WebView recreation on state changes
            PortalCanvas(
                onPortalEvent = { name, payload ->
                    canvasBridge.onEvent?.invoke(name, payload)
                },
                onWebViewCreated = { wv -> canvasWebView = wv },
                onCanvasReady = {
                    android.util.Log.d("PortalScreen", "Canvas ready callback fired")
                    canvasReady = true
                    renderedMessageIds.value = emptySet()
                    finalizedMessageIds.value = emptySet()

                    // Load tool icon overrides from assets/tools_icons/{Category}.png
                    // and pass them to the canvas JS. The canvas uses them when rendering
                    // grouped tool call cards.
                    try {
                        val iconFiles = context.assets.list("tools_icons") ?: emptyArray()
                        val overrides = mutableMapOf<String, String>()
                        for (file in iconFiles) {
                            if (!file.endsWith(".png", ignoreCase = true)) continue
                            val category = file.substringBeforeLast('.').trim()
                            if (category.isNotEmpty()) {
                                overrides[category] = "file:///android_asset/tools_icons/$file"
                            }
                        }
                        if (overrides.isNotEmpty()) {
                            val jsonMap = overrides.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                                "\"${k.replace("\"", "\\\"")}\":\"${v.replace("\"", "\\\"")}\""
                            }
                            val js = "if (typeof PortalCanvas !== 'undefined') { PortalCanvas.setToolIconOverrides($jsonMap); }"
                            canvasWebView?.evaluateJavascript(js, null)
                            android.util.Log.d("PortalScreen", "Loaded ${overrides.size} tool icon overrides")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("PortalScreen", "Failed loading tool icon overrides: ${e.message}")
                    }
                },
                guruTheme = guruTheme,
                chatTextConfig = chatTextConfig,
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            )
        }

        // Bottom sheets
        if (openNoteSheet) {
            val noteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            AttachNoteSheet(
                state = noteSheetState,
                onDismissRequest = { openNoteSheet = false },
                notes = uiState.searchNotes,
                view = uiState.noteView,
                onQueryChange = { onEvent(PortalEvent.SearchNotes(it)) }
            ) {
                onEvent(PortalEvent.AddAttachmentNote(it.id))
                openNoteSheet = false
            }
        }
        if (openTaskSheet) {
            val taskSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            AttachTaskSheet(
                state = taskSheetState,
                onDismissRequest = { openTaskSheet = false },
                tasks = uiState.searchTasks,
                onQueryChange = { onEvent(PortalEvent.SearchTasks(it)) }
            ) {
                onEvent(PortalEvent.AddAttachmentTask(it.id))
                openTaskSheet = false
            }
        }

        // Tool call bottom sheet
        if (openGroupKey != null) {
            val calls = messages.filterIsInstance<AiMessage.ToolCall>()
                .filter { it.uuid == selectedToolCallUuid }
            val selectedCall = calls.firstOrNull()

            if (selectedCall != null) {
                ModalBottomSheet(
                    onDismissRequest = {
                        openGroupKey = null
                        selectedToolCallUuid = null
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    ToolCallDetailSheet(
                        toolCall = selectedCall,
                        isStreaming = uiState.loading,
                        onBack = { selectedToolCallUuid = null },
                        onCopy = { content ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("label", content)
                            clipboard.setPrimaryClip(clip)
                        },
                        onNoteClick = { note ->
                            navController.navigate(Screen.NoteDetailsScreen(noteId = note.id, folderId = note.folderId))
                        },
                        onTaskClick = { task ->
                            navController.navigate(Screen.TaskDetailScreen(taskId = task.id))
                        },
                        onEventClick = { event ->
                            navController.navigate(Screen.CalendarEventDetailsScreen(event.id))
                        },
                        onPortalEvent = { name, payload ->
                            onEvent(PortalEvent.PortalEventAction(name = name, payload = payload))
                        },
                    )
                }
            }
        }

        // Tool call expanded card overlay
        if (showToolCallSheet && toolCallGroups.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2B241C).copy(alpha = 0.45f))
                    .clickable { showToolCallSheet = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .offset(y = (-24).dp)
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.3f).compositeOver(MaterialTheme.colorScheme.surfaceVariant))
                        .clickable(enabled = false) {}
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    ToolCallExpandedView(
                        groups = toolCallGroups,
                        onDismiss = { onDismissToolCallGroup(it) },
                        onNoteClick = { note ->
                            navController.navigate(Screen.NoteDetailsScreen(noteId = note.id, folderId = note.folderId))
                        },
                        onTaskClick = { task ->
                            navController.navigate(Screen.TaskDetailScreen(taskId = task.id))
                        },
                        onEventClick = { event ->
                            navController.navigate(Screen.CalendarEventDetailsScreen(event.id))
                        },
                        onPortalEvent = { name, payload ->
                            onEvent(PortalEvent.PortalEventAction(name = name, payload = payload))
                        },
                    )
                }
            }
        }

        // File preview sheet — opens on file chip tap, shows what's inside
        if (expandedFilePath != null) {
            val path = expandedFilePath ?: ""
            ModalBottomSheet(
                onDismissRequest = { expandedFilePath = null },
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                FilePreviewContent(
                    context = context,
                    info = resolveFilePreviewInfo(path)
                )
            }
        }

        // Message context menu
        if (selectedMessageUuid != null) {
            val selectedMessage = messages.find { it.uuid == selectedMessageUuid } as? AiMessage.AssistantMessage
            if (selectedMessage != null) {
                ModalBottomSheet(
                    onDismissRequest = { selectedMessageUuid = null },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        if (enableRevert) {
                            Text(
                                text = "Revert to here",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedMessageUuid = null
                                    }
                                    .padding(vertical = 14.dp),
                            )
                        }
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("label", selectedMessage.content)
                                    clipboard.setPrimaryClip(clip)
                                    selectedMessageUuid = null
                                }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
        }

        // Error display
        error?.let { err ->
            val errorHtml = "<div class=\"error-card\">${err.toUserMessage()}</div>"
            PortalCanvas_sendCommand(canvasWebView, "appendMessage", "error_${System.currentTimeMillis()}", errorHtml, "error")
        }

        // Fullscreen image viewer overlay — opens on attachment thumbnail tap,
        // tap anywhere to dismiss
        if (expandedImagePath != null) {
            val path = expandedImagePath ?: ""
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2B241C).copy(alpha = 0.92f))
                    .clickable { expandedImagePath = null },
                contentAlignment = Alignment.Center,
            ) {
                coil.compose.AsyncImage(
                    model = java.io.File(path),
                    contentDescription = "Attachment image",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // Fullscreen video player overlay — opens on video thumbnail tap.
        // Native MediaPlayer in a TextureView, tap anywhere to dismiss.
        if (expandedVideoPath != null) {
            val path = expandedVideoPath ?: ""
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF000000))
                    .clickable { expandedVideoPath = null },
                contentAlignment = Alignment.Center,
            ) {
                FullscreenVideoPlayer(
                    videoPath = path,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Minimal fullscreen video player for attachment playback.
 * Android MediaPlayer + TextureView via AndroidView, no external dependency.
 * Starts paused; user taps play via the media controls. Tap outside dismisses.
 */
@Composable
private fun FullscreenVideoPlayer(
    videoPath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(videoPath) {
        android.media.MediaPlayer().apply {
            setDataSource(videoPath)
            isLooping = false
        }
    }
    var prepared by remember(videoPath) { mutableStateOf(false) }
    DisposableEffect(videoPath) {
        player.setOnPreparedListener { mp ->
            prepared = true
            mp.start()
        }
        player.prepareAsync()
        onDispose {
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (_: Throwable) {}
        }
    }
    AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        player.setSurface(android.view.Surface(surface))
                    }
                    override fun onSurfaceTextureSizeChanged(
                        surface: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {}
                    override fun onSurfaceTextureDestroyed(
                        surface: android.graphics.SurfaceTexture,
                    ): Boolean {
                        player.setSurface(null)
                        return true
                    }
                    override fun onSurfaceTextureUpdated(
                        surface: android.graphics.SurfaceTexture,
                    ) {}
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Send a command to the canvas WebView.
 */
private fun PortalCanvas_sendCommand(
    webView: WebView?,
    command: String,
    vararg args: String
) {
    if (webView == null) {
        android.util.Log.w("PortalScreen", "sendCommand: webView is null, skipping $command")
        return
    }
    val jsArgs = args.joinToString(", ") { "'${it.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")}'" }
    val js = "if (typeof PortalCanvas !== 'undefined') { PortalCanvas.$command($jsArgs); } else { console.log('PortalCanvas not defined, cannot run $command'); }"
    try {
        webView.evaluateJavascript(js, null)
    } catch (e: Exception) {
        android.util.Log.w("PortalScreen", "sendCommand failed: ${e.message}")
    }
}