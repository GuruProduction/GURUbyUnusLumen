package com.unuslumen.app.presentation.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.ui.navigation.Screen
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalToolCallSheet(
    toolCalls: List<AiMessage.ToolCall>,
    selectedToolCallUuid: String?,
    isStreaming: Boolean,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelectCall: (AiMessage.ToolCall) -> Unit,
    onNoteClick: (Note) -> Unit,
    onTaskClick: (Task) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onPortalEvent: (name: String, payload: String) -> Unit,
    navController: NavHostController,
) {
    val context = LocalContext.current
    val selectedCall = toolCalls.find { it.uuid == selectedToolCallUuid }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(40.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        if (selectedCall != null) {
            ToolCallDetailSheet(
                toolCall = selectedCall,
                isStreaming = isStreaming,
                onBack = onBack,
                onCopy = { content ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("label", content)
                    clipboard.setPrimaryClip(clip)
                },
                onNoteClick = onNoteClick,
                onTaskClick = onTaskClick,
                onEventClick = onEventClick,
                onPortalEvent = onPortalEvent,
            )
        } else {
            ToolCallListSheet(
                toolCalls = toolCalls,
                isStreaming = isStreaming,
                onSelect = { call -> onSelectCall(call) },
            )
        }
    }
}