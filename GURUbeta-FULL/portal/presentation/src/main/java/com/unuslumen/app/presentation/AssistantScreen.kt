@file:Suppress("AssignedValueIsNeverRead")

package com.unuslumen.app.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.presentation.components.AssistantChatBar
import com.unuslumen.app.presentation.components.AttachNoteSheet
import com.unuslumen.app.presentation.components.AttachTaskSheet
import com.unuslumen.app.presentation.components.AttachmentDropDownMenu
import com.unuslumen.app.presentation.components.AttachmentMenuItem
import com.unuslumen.app.presentation.components.FilePreviewContent
import com.unuslumen.app.presentation.components.resolveFilePreviewInfo
import com.unuslumen.app.presentation.components.CommandArgMenuResult
import com.unuslumen.app.presentation.components.GuruSlashCommand
import com.unuslumen.app.presentation.components.GuruSlashCommandRegistry
import com.unuslumen.app.presentation.components.MessageCard
import com.unuslumen.app.presentation.components.SlashCommandArgMenu
import com.unuslumen.app.presentation.components.ThinkingBlockCard
import com.unuslumen.app.presentation.components.ThinkingDisplayLevel
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.LeftToRight
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.presentation.findHostActivity
import com.unuslumen.app.ui.theme.guruTheme
import com.unuslumen.app.preferences.permission.PermissionGateController
import org.koin.compose.koinInject
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.koin.androidx.compose.koinViewModel

@Composable
fun AssistantScreen(
    navController: NavHostController,
) {
    // Scope the streaming ViewModel to the Activity, same as PortalScreen:
    // a native back press pops this destination without destroying the
    // AssistantViewModel, so an in-flight LLM stream keeps running and the
    // screen re-attaches on return.
    val activityOwner = LocalContext.current.findHostActivity() as? androidx.lifecycle.ViewModelStoreOwner
    if (activityOwner != null) {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides activityOwner
        ) {
            AssistantScreenScoped(navController)
        }
    } else {
        AssistantScreenScoped(navController)
    }
}

@Composable
private fun AssistantScreenScoped(
    navController: NavHostController,
    viewModel: AssistantViewModel = koinViewModel(),
) {
    AssistantScreenContent(
        uiState = viewModel.uiState,
        messages = viewModel.messages,
        attachments = viewModel.attachments,
        aiEnabled = viewModel.aiEnabled,
        thinkingLevel = viewModel.thinkingLevel,
        onEvent = viewModel::onEvent,
        navController = navController
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreenContent(
    uiState: AssistantViewModel.UiState,
    messages: List<AiMessage>,
    attachments: List<AiMessageAttachment>,
    aiEnabled: Boolean,
    thinkingLevel: ThinkingDisplayLevel = ThinkingDisplayLevel.MEDIUM,
    onEvent: (AssistantEvent) -> Unit,
    navController: NavHostController,
) {
    val context = LocalContext.current
    val loading = uiState.loading
    val error = uiState.error
    var text by rememberSaveable { mutableStateOf("") }
    var attachmentsMenuExpanded by remember { mutableStateOf(false) }
    // File preview sheet — set to the cached file path of a tapped file chip
    var expandedFilePath by remember { mutableStateOf<String?>(null) }
    val noteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val taskSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var openNoteSheet by remember { mutableStateOf(false) }
    var openTaskSheet by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val liquidState = rememberLiquidState()
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val permissionGateController = koinInject<PermissionGateController>()

    // Slash command state
    var activeCommand by remember { mutableStateOf<GuruSlashCommand?>(null) }
    var activeArgMenu by remember { mutableStateOf<CommandArgMenuResult?>(null) }

    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible) focusManager.clearFocus()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            lazyListState.animateScrollToItem(0)
        }
    }

    // When text changes, check if we need to show an arg menu
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
            }
        } else {
            activeCommand = null
            activeArgMenu = null
        }
    }

    fun handleSlashCommandSelected(command: GuruSlashCommand) {
        val commandText = "/${command.name} "
        text = commandText
        activeCommand = command
        val args = GuruSlashCommandRegistry.parseArgs(command, commandText)
        activeArgMenu = GuruSlashCommandRegistry.needsArgMenu(context.resources, command, args)
    }

    fun handleArgSelected(value: String) {
        val cmd = activeCommand ?: return
        // Append the arg value to the current text
        text = text.trimEnd() + " " + value
        val args = GuruSlashCommandRegistry.parseArgs(cmd, text)
        activeArgMenu = GuruSlashCommandRegistry.needsArgMenu(context.resources, cmd, args)
    }

    Scaffold(
        topBar = {
            guruAppBar(
                title = stringResource(id = R.string.assistant),
                actions = {
                    IconButton(onClick = { onEvent(AssistantEvent.NewConversation) }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(id = R.string.assistant_screen_new_chat_content_description),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { navController.navigate(Screen.MemoryScreen) }) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = stringResource(id = R.string.assistant_screen_memory_content_description),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { navController.navigate(Screen.ConversationHistoryScreen) }) {
                        Text(
                            text = stringResource(id = R.string.assistant_screen_history_button),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Arg menu overlay
                if (activeArgMenu != null) {
                    SlashCommandArgMenu(
                        menu = activeArgMenu!!,
                        onArgSelected = { handleArgSelected(it) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }

                AssistantChatBar(
                    text = text,
                    enabled = aiEnabled && (text.isNotBlank() || attachments.isNotEmpty()),
                    attachments = attachments,
                    onTextChange = { text = it },
                    onAttachClick = { attachmentsMenuExpanded = true },
                    onRemoveAttachment = {
                        onEvent(AssistantEvent.RemoveAttachment(it))
                    },
                    loading = loading,
                    onSend = {
                        val cmd = activeCommand
                        if (cmd != null) {
                            val args = GuruSlashCommandRegistry.parseArgs(cmd, text)
                            // Screen-only effects (skills nav, permission gate) ride a
                            // nav host alongside; all other semantics are domain.
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
                            onEvent(
                                AssistantEvent.SlashCommand(
                                    command = cmd,
                                    args = args,
                                    fullText = text,
                                    screenHost = screenHost,
                                )
                            )
                        } else {
                            onEvent(
                                AssistantEvent.SendMessage(
                                    content = text,
                                    attachments = attachments.toList()
                                )
                            )
                        }
                        text = ""
                        activeCommand = null
                        activeArgMenu = null
                        keyboardController?.hide()
                    },
                    onCancel = {
                        onEvent(AssistantEvent.CancelMessage)
                    },
                    onSlashCommandSelected = { handleSlashCommandSelected(it) },
                    liquidState = liquidState,
                    onFilePreview = { path -> expandedFilePath = path },
                )
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
                        AttachmentMenuItem.CalendarEvents -> onEvent(AssistantEvent.AddAttachmentEvents)
                        AttachmentMenuItem.File -> {} // handled by file picker launcher
                    }
                    attachmentsMenuExpanded = false
                }
            )
        },
        modifier = Modifier.imePadding()
    ) { paddingValues ->
        if (openNoteSheet) AttachNoteSheet(
            state = noteSheetState,
            onDismissRequest = { openNoteSheet = false },
            notes = uiState.searchNotes,
            view = uiState.noteView,
            onQueryChange = { onEvent(AssistantEvent.SearchNotes(it)) }
        ) {
            onEvent(AssistantEvent.AddAttachmentNote(it.id))
            openNoteSheet = false
        }
        if (openTaskSheet) AttachTaskSheet(
            state = taskSheetState,
            onDismissRequest = { openTaskSheet = false },
            tasks = uiState.searchTasks,
            onQueryChange = { onEvent(AssistantEvent.SearchTasks(it)) }
        ) {
            onEvent(AssistantEvent.AddAttachmentTask(it.id))
            openTaskSheet = false
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
        Column(
            modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (aiEnabled) {
                LeftToRight {
                    LazyColumn(
                        state = lazyListState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize().liquefiable(liquidState)
                    ) {
                        item(key = "initial_spacer") {
                            Spacer(
                                Modifier
                                    .padding(bottom = paddingValues.calculateBottomPadding())
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                            )
                        }
                        error?.let { error ->
                            item(key = "error_message") {
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    colors = CardDefaults.cardColors(
                                        contentColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(
                                        text = error.toUserMessage(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .align(Alignment.CenterHorizontally),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        items(messages, key = { it.uuid }) { message ->
                            // Show thinking block above assistant messages that have thinking tokens
                            if (message is AiMessage.AssistantMessage && message.thinkingTokens.isNotBlank()) {
                                ThinkingBlockCard(
                                    thinkingTokens = message.thinkingTokens,
                                    displayLevel = thinkingLevel,
                                    isStreaming = uiState.loading,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                            MessageCard(
                                message = message,
                                onCopy = { content ->
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
                                    navController.navigate(
                                        Screen.CalendarEventDetailsScreen(
                                            event.id
                                        )
                                    )
                                },
                                onPortalEvent = { name, payload ->
                                    onEvent(AssistantEvent.PortalEvent(name = name, payload = payload))
                                }
                            )
                        }
                    }
                }
            } else {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onErrorContainer
                    ),
                    colors = CardDefaults.cardColors(
                        contentColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.ai_not_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.CenterHorizontally),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AssistantScreenContentPreview() {
    guruTheme {
        AssistantScreenContent(
            uiState = AssistantViewModel.UiState(),
            messages = listOf(
                AiMessage.AssistantMessage(
                    content = "After carefully reviewing the collection of notes you provided, I detected several recurring themes and insights that could be valuable for your upcoming projects. In addition to the summary I mentioned, I can also suggest specific actionable steps, categorize the information by priority, and highlight any hidden patterns that might inform your strategy. Let me know if you'd like a detailed report, a visual diagram, or a concise bullet-point overview.",
                    time = 5,
                    uuid = "5"
                ),
                AiMessage.ToolCall(
                    uuid = "4",
                    id = "4",
                    name = "searchNotes",
                    rawContent = "",
                    resultRawContent = "",
                    time = 4
                ),
                AiMessage.UserMessage(
                    content = "I'm juggling a tight deadline for the project next week and feel a bit overwhelming. Could you help me break down my tasks, prioritize them, and suggest an organized plan to ensure I meet all milestones on time?",
                    time = 3,
                    uuid = "3"
                ),
                AiMessage.AssistantMessage(
                    content = "Welcome! I'm your AI assistant, ready to support you with managing notes, creating and tracking tasks, and handling calendar events. I can help you set up smart reminders, generate project outlines, automate repetitive workflows, and even suggest productivity techniques tailored to your habits. Just tell me what you need—whether it's organizing information, setting reminders, or anything else to boost your efficiency—and I'll get started right away.",
                    time = 2,
                    uuid = "2"
                ),
                AiMessage.UserMessage(
                    content = "Hello! I'm just getting started on improving my workflow and would love some guidance on how to set up an effective productivity system. Can you walk me through the steps to organize my tasks, set up a reliable routine, and keep everything synchronized across my devices?",
                    time = 1,
                    uuid = "1"
                )
            ),
            attachments = emptyList(),
            aiEnabled = true,
            onEvent = {},
            navController = rememberNavController()
        )
    }
}
