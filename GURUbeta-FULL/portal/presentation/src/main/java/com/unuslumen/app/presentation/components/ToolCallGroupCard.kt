package com.unuslumen.app.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.util.date.formatTime

// ── collapsed bar rendered inline in chat ──

@Composable
fun ToolCallGroupCard(
    toolCalls: List<AiMessage.ToolCall>,
    isStreaming: Boolean,
    onTap: () -> Unit,
) {
    val hasRunning = toolCalls.any { isStreaming && it == toolCalls.lastOrNull() && it.resultRawContent.isBlank() }
    val hasFailed = toolCalls.any { it.isFailed }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficLight(
            isFailed = hasFailed && !hasRunning,
            isRunning = hasRunning,
            size = 10.dp,
        )

        Spacer(Modifier.width(10.dp))

        Text(
            "${toolCalls.size} tool${if (toolCalls.size > 1) "s" else ""}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.width(8.dp))

        Text(
            "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
        )

        Spacer(Modifier.width(8.dp))

        val nameLine = toolCalls.joinToString(", ") { toolDisplayName(it.name).lowercase().replaceFirstChar { it.uppercase() } }
        Text(
            nameLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── sheet: level 2 — tool list ──

@Composable
fun ToolCallListSheet(
    toolCalls: List<AiMessage.ToolCall>,
    isStreaming: Boolean,
    onSelect: (AiMessage.ToolCall) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(toolCalls, key = { it.uuid }) { call ->
            val running = isStreaming && call == toolCalls.lastOrNull() && call.resultRawContent.isBlank()
            val name = toolDisplayName(call.name)
            val summary = when {
                call.isFailed -> "Failed"
                running -> "Running…"
                call.resultRawContent.isNotBlank() -> formatResultPreview(call.resultRawContent, 100)
                else -> "Completed"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(call) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrafficLight(call.isFailed, running, 12.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            call.isFailed -> MaterialTheme.colorScheme.error
                            running -> Color(0xFFFFA726)
                            else -> MaterialTheme.colorScheme.onBackground
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ── sheet: level 3 — tool detail ──

@Composable
fun ToolCallDetailSheet(
    toolCall: AiMessage.ToolCall,
    isStreaming: Boolean,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onTaskClick: (Task) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onPortalEvent: ((name: String, payload: String) -> Unit)?,
) {
    var showRaw by remember { mutableStateOf(false) }
    val name = toolDisplayName(toolCall.name)
    val ctx = LocalContext.current

    val safeInputJson = remember(toolCall.rawContent) {
        try { prettyJson(toolCall.rawContent) } catch (_: Exception) { toolCall.rawContent.take(1000) }
    }
    val safeOutputJson = remember(toolCall.resultRawContent) {
        try { prettyJson(toolCall.resultRawContent) } catch (_: Exception) { toolCall.resultRawContent.take(1000) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(28.dp).clickable(onClick = onBack).padding(4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (showRaw) Icons.Default.Code else Icons.Default.Info,
                contentDescription = null,
                tint = if (showRaw) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                modifier = Modifier.size(24.dp).clickable { showRaw = !showRaw }.padding(4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        if (showRaw) {
            RawJsonView(safeInputJson, safeOutputJson, toolCall.isFailed)
        } else {
            DetailHumanView(toolCall, isStreaming, onNoteClick, onTaskClick, onEventClick, onPortalEvent)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            toolCall.time.formatTime(ctx),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )
    }
}

// ── human-readable detail ──

@Composable
private fun DetailHumanView(
    call: AiMessage.ToolCall,
    isStreaming: Boolean,
    onNoteClick: (Note) -> Unit,
    onTaskClick: (Task) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onPortalEvent: ((name: String, payload: String) -> Unit)?,
) {
    val args = remember(call.rawContent) {
        try { formatToolArgs(call.rawContent) }
        catch (_: Exception) { listOf(LabelValue(call.rawContent.take(500), "", isRaw = true)) }
    }

    if (args.isNotEmpty()) {
        Text(
            "Arguments",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            args.forEach { arg ->
                if (arg.isRaw) {
                    Text(
                        arg.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            arg.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        Text(
                            arg.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    Text(
        "Result",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = when {
            call.isFailed -> MaterialTheme.colorScheme.error
            isStreaming && call.resultRawContent.isBlank() -> Color(0xFFFFA726)
            else -> MaterialTheme.colorScheme.primary
        },
        modifier = Modifier.padding(bottom = 12.dp),
    )

    when {
        isStreaming && call.resultRawContent.isBlank() -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrafficLight(false, true, 10.dp)
                Spacer(Modifier.width(8.dp))
                Text("Executing…", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFA726))
            }
        }
        call.resultObject != null -> {
            ToolCallResultPreview(
                resultObject = call.resultObject!!,
                onNoteClick = onNoteClick,
                onTaskClick = onTaskClick,
                onEventClick = onEventClick,
                onPortalEvent = onPortalEvent,
            )
        }
        call.resultRawContent.isNotBlank() -> {
            Text(
                formatResultPreview(call.resultRawContent, 500),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                lineHeight = 22.sp,
            )
        }
        else -> {
            Text(
                "No result",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            )
        }
    }
}

// ── raw JSON view ──

@Composable
private fun RawJsonView(input: String, output: String, isFailed: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text(
                "Input",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                input,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Column {
            Text(
                "Output",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                output,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// ── traffic light dot ──

@Composable
private fun TrafficLight(isFailed: Boolean, isRunning: Boolean, size: androidx.compose.ui.unit.Dp) {
    when {
        isRunning -> {
            val t = rememberInfiniteTransition("pulse")
            val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), "a")
            Box(Modifier.size(size).alpha(a).clip(CircleShape).background(Color(0xFFFFA726)))
        }
        isFailed -> {
            Icon(Icons.Default.Close, "Failed", tint = Color(0xFFEF5350), modifier = Modifier.size(size))
        }
        else -> {
            Icon(Icons.Default.Check, "Done", tint = Color(0xFF4CAF50), modifier = Modifier.size(size))
        }
    }
}
