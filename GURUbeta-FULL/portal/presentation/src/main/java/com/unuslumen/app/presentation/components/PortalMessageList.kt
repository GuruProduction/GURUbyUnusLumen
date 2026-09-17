package com.unuslumen.app.presentation.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable

@Composable
fun PortalMessageList(
    chatItems: List<ChatItem>,
    isStreaming: Boolean,
    lazyListState: LazyListState,
    liquidState: LiquidState,
    bottomPadding: PaddingValues,
    onCopy: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onTaskClick: (Task) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onPortalEvent: (name: String, payload: String) -> Unit,
    onToolCallTap: (AiMessage.ToolCall) -> Unit,
    onToolCallGroupTap: (List<AiMessage.ToolCall>) -> Unit,
    navController: androidx.navigation.NavHostController,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = lazyListState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize().liquefiable(liquidState)
    ) {
        item(key = "initial_spacer") {
            Spacer(
                Modifier
                    .padding(bottom = bottomPadding.calculateBottomPadding())
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
        items(chatItems.size, key = { index ->
            when (val item = chatItems[index]) {
                is ChatItem.Single -> item.message.uuid
                is ChatItem.ToolCallGroup -> "group_${item.toolCalls.first().uuid}"
            }
        }) { index ->
            when (val item = chatItems[index]) {
                is ChatItem.Single -> {
                    FlatMessage(
                        message = item.message,
                        onCopy = onCopy,
                        onNoteClick = onNoteClick,
                        onTaskClick = onTaskClick,
                        onEventClick = onEventClick,
                        onPortalEvent = onPortalEvent,
                        isStreaming = isStreaming,
                        onToolCallTap = { toolCall -> onToolCallTap(toolCall) }
                    )
                }
                is ChatItem.ToolCallGroup -> {
                    ToolCallGroupCard(
                        toolCalls = item.toolCalls,
                        isStreaming = isStreaming,
                        onTap = { onToolCallGroupTap(item.toolCalls) }
                    )
                }
            }
        }
    }
}