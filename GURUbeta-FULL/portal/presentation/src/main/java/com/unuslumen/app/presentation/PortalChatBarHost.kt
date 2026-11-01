// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.CompositionLocalProvider
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.presentation.components.GuruSlashCommand
import com.unuslumen.app.presentation.components.GuruSlashCommandRegistry
import com.unuslumen.app.presentation.components.PortalChatBar
import io.github.fletchmckee.liquid.rememberLiquidState
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import com.unuslumen.app.preferences.permission.PermissionGateController

/**
 * The portal's chat bar, hosted for any screen that is not the portal.
 *
 * WHAT THIS IS FOR: the human wants the real chat input on every screen, not a
 * lookalike. So this renders [PortalChatBar] itself, against the SAME
 * PortalViewModel the portal uses, resolved from the Activity's ViewModelStore.
 * That is what makes it the same bar rather than a second one that happens to
 * look similar: one view model, one send path, one set of attachments, one slash
 * command registry.
 *
 * THE VIEW MODEL IS ACTIVITY-SCOPED ON PURPOSE. The portal already scopes its
 * view model to the Activity so an in-flight stream survives navigation. Sharing
 * that same owner here means text typed on Notes is the same draft the portal
 * sees when it opens, and a send is one conversation rather than two.
 *
 * SENDING WHERE IT STANDS: a plain sentence is sent immediately, from this bar,
 * and the answer streams into the conversation the portal will show. Only a
 * sentence that genuinely needs the chat surface visible, and slash commands
 * with screen effects, are handed to [onSentToPortal] to open the portal.
 */
@Composable
fun PortalChatBarHost(
    modifier: Modifier = Modifier,
    onSentToPortal: (String) -> Unit = {},
    onOpenChat: () -> Unit = {},
) {
    val activityOwner = LocalContextOwner()
    if (activityOwner != null) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides activityOwner) {
            PortalChatBarHostScoped(modifier, onSentToPortal, onOpenChat)
        }
    } else {
        PortalChatBarHostScoped(modifier, onSentToPortal, onOpenChat)
    }
}

@Composable
private fun LocalContextOwner(): ViewModelStoreOwner? {
    val context = LocalContext.current
    return remember(context) { context.findHostActivity() as? ViewModelStoreOwner }
}

@Composable
private fun PortalChatBarHostScoped(
    modifier: Modifier,
    onSentToPortal: (String) -> Unit,
    onOpenChat: () -> Unit,
    viewModel: PortalViewModel = koinViewModel(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val liquidState = rememberLiquidState()

    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val mediaProcessing by viewModel.mediaProcessing.collectAsStateWithLifecycle()
    val toolCallGroups by viewModel.toolCallGroups.collectAsStateWithLifecycle()

    // The draft lives on the view model so it survives leaving a screen, and so
    // two screens never show different text for the same conversation.
    var text by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        PortalChatBar(
            text = text,
            mediaProcessing = mediaProcessing,
            enabled = aiEnabled && (text.isNotBlank() || attachments.isNotEmpty()) && !mediaProcessing,
            attachments = attachments,
            onTextChange = { text = it },
            // Attachment picking is a portal-screen concern: it opens sheets and
            // a file picker that live there. Opening the chat is the honest
            // behaviour rather than a button that does nothing.
            onAttachClick = { onOpenChat() },
            onRemoveAttachment = { viewModel.onEvent(PortalEvent.RemoveAttachment(it)) },
            loading = uiState.loading,
            onSend = {
                val sentence = text
                val resolved = GuruSlashCommandRegistry.resolve(sentence)
                when {
                    // A slash command with screen effects belongs where those
                    // effects can run, which is the portal.
                    resolved != null -> onSentToPortal(sentence)
                    // Anything with attachments needs the chat surface to show
                    // what was attached before it is sent.
                    attachments.isNotEmpty() -> onSentToPortal(sentence)
                    else -> {
                        viewModel.onEvent(
                            PortalEvent.SendMessage(
                                content = sentence,
                                attachments = emptyList<AiMessageAttachment>(),
                            )
                        )
                    }
                }
                text = ""
                keyboard?.hide()
            },
            onCancel = { viewModel.onEvent(PortalEvent.CancelMessage) },
            liquidState = liquidState,
            toolCallCount = toolCallGroups.sumOf { group -> group.toolCalls.size },
            isToolCallActive = toolCallGroups.any { group -> group.isStreaming },
            onToolCallClick = { onOpenChat() },
            onFilePreview = { onOpenChat() },
            onOtioClick = onOpenChat,
        )
    }
}
