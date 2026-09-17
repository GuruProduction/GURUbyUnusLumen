package com.unuslumen.app.presentation.components

import android.content.res.Configuration
import org.json.JSONObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.datasource.LoremIpsum
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.AlarmInfo
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.PlanInfo
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.model.ToolCallResultObject
import com.unuslumen.app.domain.model.WebSearchItem
import com.unuslumen.app.domain.model.AiMessage.ToolCall
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.defaultMarkdownTypography
import com.unuslumen.app.ui.theme.guruTheme
import com.unuslumen.app.util.date.formatTime
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor

@Composable
fun LazyItemScope.MessageCard(
    message: AiMessage,
    onCopy: (String) -> Unit,
    onNoteClick: (Note) -> Unit = {},
    onTaskClick: (Task) -> Unit = {},
    onEventClick: (CalendarEvent) -> Unit = {},
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
    isStreaming: Boolean = false,
) {
    when (message) {
        is AiMessage.UserMessage -> UserMessageCard(message = message, onCopy = onCopy)
        is AiMessage.AssistantMessage -> AssistantMessageCard(message = message, onCopy = onCopy, onPortalEvent = onPortalEvent)
        is AiMessage.StreamingAssistant -> AssistantMessageCard(
            message = AiMessage.AssistantMessage(
                content = message.partialContent,
                time = message.time,
                uuid = message.uuid,
                thinkingTokens = message.partialThinking,
            ),
            onCopy = onCopy,
            onPortalEvent = onPortalEvent
        )
        is AiMessage.StreamingToolCall -> ToolCallCard(
            toolCall = AiMessage.ToolCall(
                uuid = message.uuid,
                id = null,
                name = message.toolName,
                rawContent = message.partialContent,
                resultRawContent = "",
                time = message.time,
            ),
            isStreaming = true
        )
        is AiMessage.PortalMessage -> PortalMessageCard(
            message = message,
            onPortalEvent = onPortalEvent
        )
        is AiMessage.ToolCall -> {
            Column {
                ToolCallCard(toolCall = message, isStreaming = isStreaming)
                message.resultObject?.let {
                    ToolCallResultPreview(
                        resultObject = it,
                        onNoteClick = onNoteClick,
                        onTaskClick = onTaskClick,
                        onEventClick = onEventClick,
                        onPortalEvent = onPortalEvent
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.UserMessageCard(
    message: AiMessage.UserMessage,
    onCopy: (String) -> Unit,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formattedTime by remember(message.time) {
        derivedStateOf { message.time.formatTime(context) }
    }
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, start = 48.dp, bottom = 4.dp, top = 8.dp)
            .messageCardAnimatedPlacement()
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 4.dp,
                bottomStart = 24.dp,
                bottomEnd = 14.dp
            ),
            elevation = CardDefaults.cardElevation(5.dp),
            onClick = { showContextMenu = true }
        ) {
            val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
            Column(
                modifier = Modifier
                    .drawBehind {
                        drawAiGradientRadials(surfaceVariant, radius = size.minDimension * 1.2f)
                    }
            ) {
                Markdown(
                    content = message.content,
                    modifier = Modifier.padding(top = 7.dp, start = 12.dp, end = 8.dp),
                    imageTransformer = Coil2ImageTransformerImpl,
                    colors = markdownColor(text = MaterialTheme.colorScheme.onSurfaceVariant),
                    typography = defaultMarkdownTypography()
                )
                if (message.attachments.isNotEmpty()) {
                    AiAttachmentsSection(
                        attachments = message.attachments,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.copy)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = stringResource(id = R.string.copy)
                            )
                        },
                        onClick = {
                            showContextMenu = false
                            onCopy(message.content)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.AssistantMessageCard(
    message: AiMessage.AssistantMessage,
    onCopy: (String) -> Unit,
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formattedTime by remember(message.time) {
        derivedStateOf { message.time.formatTime(context) }
    }

    // Detect portal content in the assistant message
    val portalContent = remember(message.content) { extractPortalContent(message.content) }
    val markdownContent = remember(message.content) { stripPortalContent(message.content) }

    Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 12.dp, start = 12.dp, bottom = 4.dp, top = 8.dp)
            .messageCardAnimatedPlacement()
            .clickable { showContextMenu = true }
    ) {
        Column {
            // Render markdown content (everything outside the portal block)
            if (markdownContent.isNotBlank()) {
                Markdown(
                    content = markdownContent,
                    imageTransformer = Coil2ImageTransformerImpl,
                    colors = markdownColor(text = MaterialTheme.colorScheme.onSurfaceVariant),
                    typography = defaultMarkdownTypography()
                )
            }
            // Render portal content inline
            if (portalContent != null) {
                PortalWebView(
                    html = portalContent.html,
                    css = portalContent.css,
                    js = portalContent.js,
                    height = portalContent.height,
                    interactive = portalContent.interactive,
                    onPortalEvent = onPortalEvent,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.copy)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = stringResource(id = R.string.copy)
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onCopy(message.content)
                    }
                )
            }
        }
    }
}

@Composable
private fun LazyItemScope.ToolCallCard(
    toolCall: AiMessage.ToolCall,
    isStreaming: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    val displayName = remember(toolCall.name) { toolDisplayName(toolCall.name) }
    val formattedArgs = remember(toolCall.rawContent) { formatToolArgs(toolCall.rawContent) }
    val resultPreview = remember(toolCall.resultRawContent) {
        if (toolCall.resultRawContent.isNotBlank()) formatResultPreview(toolCall.resultRawContent)
        else null
    }

    Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 48.dp, start = 8.dp)
            .messageCardAnimatedPlacement()
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (toolCall.isFailed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            ),
            onClick = { expanded = !expanded }
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_tools),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textDecoration = if (toolCall.isFailed) TextDecoration.LineThrough else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isStreaming) {
                        StreamingDot()
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (resultPreview != null && !expanded) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = resultPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(
                            id = if (expanded) R.string.message_card_collapse_content_description
                            else R.string.message_card_expand_content_description
                        ),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        // Raw toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = { showRaw = !showRaw },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (showRaw) Icons.Default.Terminal else Icons.Default.Code,
                                    contentDescription = stringResource(
                                        id = if (showRaw) R.string.message_card_human_view_content_description
                                        else R.string.message_card_raw_view_content_description
                                    ),
                                    tint = if (showRaw) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (showRaw) {
                            Text(
                                text = stringResource(id = R.string.message_card_input_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = prettyJson(toolCall.rawContent),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(top = 4.dp),
                                fontSize = 11.sp
                            )
                            Text(
                                text = stringResource(id = R.string.message_card_output_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                text = prettyJson(toolCall.resultRawContent),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(top = 4.dp),
                                fontSize = 11.sp
                            )
                        } else {
                            // Human-readable args
                            if (formattedArgs.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.tool_call_content),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                                formattedArgs.forEach { arg ->
                                    if (arg.isRaw) {
                                        Text(
                                            text = arg.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 3.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = arg.label,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.widthIn(max = 100.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = arg.value,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                                modifier = Modifier.weight(1f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // Result preview
                            if (toolCall.resultRawContent.isNotBlank() && toolCall.resultObject == null) {
                                Text(
                                    text = stringResource(R.string.tool_call_result),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Text(
                                    text = resultPreview ?: toolCall.resultRawContent,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StreamingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFFA726))
    )
}

@Composable
internal fun ToolCallResultPreview(
    resultObject: ToolCallResultObject,
    onNoteClick: (Note) -> Unit,
    onTaskClick: (Task) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
) {
    when (resultObject) {
        is ToolCallResultObject.Notes -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.notes) { note ->
                    AiNoteCard(
                        note = note,
                        onClick = onNoteClick,
                        modifier = Modifier.widthIn(max = 290.dp)
                    )
                }
            }
        }

        is ToolCallResultObject.Tasks -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.tasks) { task ->
                    AiTaskCard(
                        task = task,
                        onClick = { onTaskClick(task) },
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                }
            }
        }

        is ToolCallResultObject.CalendarEvents -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.events) { event ->
                    AiCalendarEventCard(
                        event = event,
                        onClick = onEventClick,
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                }
            }
        }

        is ToolCallResultObject.JournalEntries -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.entries) { entry ->
                    Card(
                        modifier = Modifier.widthIn(max = 260.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall)
                            if (entry.content.isNotBlank()) {
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    entry.content.take(100) + if (entry.content.length > 100) "…" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Bookmarks -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.bookmarks) { bookmark ->
                    Card(
                        modifier = Modifier.widthIn(max = 260.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(bookmark.title, style = MaterialTheme.typography.titleSmall)
                            if (bookmark.url.isNotBlank()) {
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    bookmark.url.take(80) + if (bookmark.url.length > 80) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Alarms -> {
            val context = LocalContext.current
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.alarms) { alarm ->
                    Card(
                        modifier = Modifier.widthIn(max = 200.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Alarm", style = MaterialTheme.typography.titleSmall)
                            Text(alarm.time.formatTime(context), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.MemoryFacts -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                resultObject.facts.forEach { fact ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(fact, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        is ToolCallResultObject.Plans -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.plans) { plan ->
                    Card(
                        modifier = Modifier.widthIn(max = 260.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(plan.title, style = MaterialTheme.typography.titleSmall)
                            Text("${plan.completedSteps}/${plan.stepCount} steps", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.WebResults -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resultObject.results) { result ->
                    Card(
                        modifier = Modifier.widthIn(max = 260.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleSmall)
                            if (result.snippet.isNotBlank()) {
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(result.snippet.take(150) + if (result.snippet.length > 150) "…" else "", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Settings -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                resultObject.settings.forEach { (key, value) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(key, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.FileResults -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                resultObject.files.forEach { file ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(file, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        is ToolCallResultObject.Sound -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val soundInfo = resultObject.soundInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(id = R.string.tool_call_result_sound_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (soundInfo.type) {
                                    "volume" -> "🔊 Volume"
                                    "ringer_mode" -> "🔇 Ringer Mode"
                                    "speak" -> "🗣️ Text-to-Speech"
                                    "vibrate" -> "📳 Vibration"
                                    "ringtone" -> "🔔 Ringtone"
                                    "file" -> "🎵 Sound File"
                                    "audio_info" -> "📊 Audio Info"
                                    "stop" -> "⏹️ Sound Stopped"
                                    else -> "🔊 Sound"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        soundInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        soundInfo.volumes.forEach { vol ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${vol.stream}: ${vol.currentVolume}/${vol.maxVolume}", style = MaterialTheme.typography.bodySmall)
                                Text("${vol.percentage}%${if (vol.isMuted) " 🔇" else ""}", style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { vol.percentage / 100f },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            )
                        }

                        soundInfo.ringerMode?.let { mode ->
                            Spacer(modifier = Modifier.height(4.dp))
                            val modeIcon = when (mode) {
                                "silent" -> "🔇"
                                "vibrate" -> "📳"
                                "normal" -> "🔊"
                                else -> "❓"
                            }
                            Text("Ringer: $modeIcon $mode", style = MaterialTheme.typography.bodySmall)
                        }

                        soundInfo.isMusicActive?.let { active ->
                            if (active) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("🎵 Music playing", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        soundInfo.hasVibrator?.let { has ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Vibrator: ${if (has) "✅ Available" else "❌ Not available"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Media -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val mediaInfo = resultObject.mediaInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(id = R.string.tool_call_result_media_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (mediaInfo.type) {
                                    "spotify" -> "🎵 Spotify"
                                    "spotify_search" -> "🔍 Spotify Search"
                                    "gif" -> "🎬 GIF"
                                    "meme" -> "😂 Meme"
                                    "video" -> "📹 Video"
                                    "camera" -> "📷 Camera"
                                    "song" -> "🎵 Song Recognition"
                                    else -> "📺 Media"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        mediaInfo.status?.let { status ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                        mediaInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.SmartHome -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val homeInfo = resultObject.homeInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = stringResource(id = R.string.tool_call_result_smart_home_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (homeInfo.type) {
                                    "hue_lights" -> "💡 Hue Lights"
                                    "hue_control" -> "💡 Hue Control"
                                    "sonos" -> "🔊 Sonos"
                                    "sonos_control" -> "🔊 Sonos Control"
                                    "bluetooth" -> "📱 Bluetooth"
                                    "network" -> "🌐 Network"
                                    else -> "🏠 Smart Home"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        homeInfo.device?.let { device ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Device: $device", style = MaterialTheme.typography.bodySmall)
                        }
                        homeInfo.state?.let { state ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("State: $state", style = MaterialTheme.typography.bodySmall)
                        }
                        homeInfo.devices.forEach { device ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• ${device.name} (${device.type})", style = MaterialTheme.typography.bodySmall)
                        }
                        homeInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Weather -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val weatherInfo = resultObject.weatherInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = stringResource(id = R.string.tool_call_result_weather_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🌤️ Weather", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        weatherInfo.location.let { location ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("📍 $location", style = MaterialTheme.typography.bodySmall)
                        }
                        weatherInfo.temperature?.let { temp ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("🌡️ ${temp}°C", style = MaterialTheme.typography.bodySmall)
                        }
                        weatherInfo.condition?.let { condition ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("☁️ $condition", style = MaterialTheme.typography.bodySmall)
                        }
                        weatherInfo.humidity?.let { humidity ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("💧 Humidity: $humidity%", style = MaterialTheme.typography.bodySmall)
                        }
                        weatherInfo.wind?.let { wind ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("💨 Wind: ${wind} km/h", style = MaterialTheme.typography.bodySmall)
                        }
                        weatherInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Places -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(resultObject.places) { place ->
                        Card(
                            modifier = Modifier.widthIn(max = 260.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = stringResource(id = R.string.tool_call_result_place_content_description),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("📍 ${place.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                place.address.let { address ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(address, style = MaterialTheme.typography.bodySmall)
                                }
                                place.rating?.let { rating ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("⭐ $rating", style = MaterialTheme.typography.bodySmall)
                                }
                                place.isOpen?.let { isOpen ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(if (isOpen) "✅ Open" else "❌ Closed", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.GitHub -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val githubInfo = resultObject.githubInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = stringResource(id = R.string.tool_call_result_github_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (githubInfo.type) {
                                    "pr_list" -> "🔀 Pull Requests"
                                    "issue_list" -> "📋 Issues"
                                    "repo" -> "📦 Repository"
                                    else -> "🐙 GitHub"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        githubInfo.title?.let { title ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(title, style = MaterialTheme.typography.bodySmall)
                        }
                        githubInfo.items.forEach { item ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• #${item.number} ${item.title} (${item.state})", style = MaterialTheme.typography.bodySmall)
                        }
                        githubInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Trello -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val trelloInfo = resultObject.trelloInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ViewKanban,
                                contentDescription = stringResource(id = R.string.tool_call_result_trello_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (trelloInfo.type) {
                                    "boards" -> "📋 Boards"
                                    "cards" -> "📝 Cards"
                                    else -> "📋 Trello"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        trelloInfo.name?.let { name ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(name, style = MaterialTheme.typography.bodySmall)
                        }
                        trelloInfo.items.forEach { item ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• ${item.name} (${item.type})", style = MaterialTheme.typography.bodySmall)
                        }
                        trelloInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Notion -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val notionInfo = resultObject.notionInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Article,
                                contentDescription = stringResource(id = R.string.tool_call_result_notion_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (notionInfo.type) {
                                    "search" -> "🔍 Search Results"
                                    "page" -> "📄 Page"
                                    else -> "📝 Notion"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        notionInfo.title?.let { title ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(title, style = MaterialTheme.typography.bodySmall)
                        }
                        notionInfo.content?.let { content ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(content.take(200) + if (content.length > 200) "…" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        notionInfo.items.forEach { item ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• ${item.title}", style = MaterialTheme.typography.bodySmall)
                        }
                        notionInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Voice -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val voiceInfo = resultObject.voiceInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = stringResource(id = R.string.tool_call_result_voice_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (voiceInfo.type) {
                                    "transcription" -> "🎤 Transcription"
                                    "diarization" -> "👥 Speaker Diarization"
                                    "tts" -> "🗣️ Text-to-Speech"
                                    "audio_convert" -> "🔄 Audio Conversion"
                                    else -> "🎙️ Voice"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        voiceInfo.text?.let { text ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        }
                        voiceInfo.language?.let { language ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("🌐 Language: $language", style = MaterialTheme.typography.bodySmall)
                        }
                        voiceInfo.duration?.let { duration ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("⏱️ Duration: ${duration}s", style = MaterialTheme.typography.bodySmall)
                        }
                        voiceInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Communication -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val commInfo = resultObject.commInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = stringResource(id = R.string.tool_call_result_communication_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (commInfo.type) {
                                    "discord" -> "💬 Discord"
                                    "slack" -> "💼 Slack"
                                    "whatsapp" -> "📱 WhatsApp"
                                    "x" -> "🐦 X/Twitter"
                                    "voice_call" -> "📞 Voice Call"
                                    else -> "📡 Communication"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        commInfo.action?.let { action ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Action: $action", style = MaterialTheme.typography.bodySmall)
                        }
                        commInfo.recipient?.let { recipient ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("To: $recipient", style = MaterialTheme.typography.bodySmall)
                        }
                        commInfo.messageId?.let { messageId ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Message ID: $messageId", style = MaterialTheme.typography.bodySmall)
                        }
                        commInfo.status?.let { status ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Status: $status", style = MaterialTheme.typography.bodySmall)
                        }
                        commInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.System -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val systemInfo = resultObject.systemInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(id = R.string.tool_call_result_system_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (systemInfo.type) {
                                    "health" -> "🔋 System Health"
                                    "device" -> "📱 Device Info"
                                    "session_logs" -> "📋 Session Logs"
                                    "canvas" -> "🎨 Canvas"
                                    else -> "⚙️ System"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        systemInfo.battery?.let { battery ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("🔋 Battery: ${battery.level}% - ${battery.status}", style = MaterialTheme.typography.bodySmall)
                        }
                        systemInfo.storage?.let { storage ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("💾 Storage: ${storage.percentUsed}% used", style = MaterialTheme.typography.bodySmall)
                        }
                        systemInfo.memory?.let { memory ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("🧠 Memory: ${memory.percentUsed}% used", style = MaterialTheme.typography.bodySmall)
                        }
                        systemInfo.security?.let { security ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("🔒 Security: ${if (security.isSecure) "✓ Secure" else "⚠ Issues"}", style = MaterialTheme.typography.bodySmall)
                        }
                        systemInfo.device?.let { device ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("📱 ${device.manufacturer} ${device.model}", style = MaterialTheme.typography.bodySmall)
                        }
                        systemInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Email -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val emailInfo = resultObject.emailInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = stringResource(id = R.string.tool_call_result_email_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (emailInfo.type) {
                                    "list" -> "📧 Inbox"
                                    "read" -> "📖 Read Email"
                                    "send" -> "📤 Send Email"
                                    "move" -> "📁 Move Email"
                                    "delete" -> "🗑️ Delete Email"
                                    else -> "✉️ Email"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        emailInfo.emails.forEach { email ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• ${email.from}: ${email.subject}", style = MaterialTheme.typography.bodySmall)
                        }
                        emailInfo.email?.let { email ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("From: ${email.from}", style = MaterialTheme.typography.bodySmall)
                            Text("Subject: ${email.subject}", style = MaterialTheme.typography.bodySmall)
                        }
                        emailInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Camera -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val cameraInfo = resultObject.cameraInfo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Camera,
                                contentDescription = stringResource(id = R.string.tool_call_result_camera_content_description),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (cameraInfo.type) {
                                    "list" -> "📹 Camera List"
                                    "snapshot" -> "📸 Snapshot"
                                    "record" -> "🎬 Recording"
                                    "motion" -> "👁️ Motion Detection"
                                    else -> "📷 Camera"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        cameraInfo.cameras.forEach { camera ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• ${camera.name} (${camera.status})", style = MaterialTheme.typography.bodySmall)
                        }
                        cameraInfo.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.Portal -> {
            PortalWebView(
                html = resultObject.html,
                css = resultObject.css,
                js = resultObject.js,
                height = resultObject.height,
                interactive = resultObject.interactive,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                onPortalEvent = onPortalEvent
            )
        }
        is ToolCallResultObject.ClosePortal -> {
            // ClosePortal result — no visual rendering needed
            // The portal is removed from the message list by the ViewModel
        }

        is ToolCallResultObject.SkillLoaded -> {
            val gold = Color(0xFFDAA520)
            val toolCount = resultObject.toolsAllowed.size
            val toolPlural = if (toolCount == 1) "tool" else "tools"
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (resultObject.success) "\u26A1" else "\u26A0",
                            fontSize = 18.sp,
                            color = if (resultObject.success) gold else MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = resultObject.skillName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (resultObject.success) gold else MaterialTheme.colorScheme.error,
                                ),
                            )
                            Text(
                                text = if (resultObject.success)
                                    "Successfully loaded skill \u00B7 $toolCount $toolPlural allowed"
                                else
                                    "Failed to load${resultObject.error?.let { ": $it" } ?: ""}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                ),
                            )
                        }
                    }
                }
            }
        }

        is ToolCallResultObject.ToolResults -> {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                resultObject.results.forEach { tr ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(tr.toolName, style = MaterialTheme.typography.titleSmall)
                            if (tr.result.isNotBlank()) {
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    tr.result.take(200) + if (tr.result.length > 200) "\u2026" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (tr.isStale) {
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    "\u26A0 Stale data \u2014 consider re-calling",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
context(scope: LazyItemScope)
internal fun Modifier.messageCardAnimatedPlacement() = with(scope) {
    this@messageCardAnimatedPlacement.animateItem(
        fadeInSpec = spring(
            dampingRatio = 0.95f,
            stiffness = Spring.StiffnessMedium
        ),
        placementSpec = spring(
            dampingRatio = 0.95f,
            stiffness = Spring.StiffnessMedium
        )
    )
}

/**
 * Renders a PortalMessage inline in the chat.
 * This is the "portal" — a WebView that renders whatever HTML/CSS/JS Guru sends,
 * with theme injection and bidirectional communication.
 */
@Composable
private fun LazyItemScope.PortalMessageCard(
    message: AiMessage.PortalMessage,
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .messageCardAnimatedPlacement()
    ) {
        PortalWebView(
            html = message.html,
            css = message.css,
            js = message.js,
            height = message.height,
            interactive = message.interactive,
            onPortalEvent = onPortalEvent
        )
        Text(
            text = message.time.formatTime(LocalContext.current),
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * Data class representing extracted portal content from an assistant message.
 */
private data class ExtractedPortal(
    val html: String,
    val css: String = "",
    val js: String = "",
    val height: Int? = null,
    val interactive: Boolean = true,
)

/**
 * Detects and extracts portal content from an assistant message.
 * Supports two marker formats:
 * 1. ```portal code fence with optional YAML front-matter (css, js, height, interactive)
 * 2. <!-- PORTAL:{"html":"...","css":"..."} --> HTML comment marker
 *
 * Returns null if no portal content is found.
 */
private fun extractPortalContent(content: String): ExtractedPortal? {
    // Format 1: ```portal code fence
    val portalFenceRegex = Regex("""```portal\s*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    val fenceMatch = portalFenceRegex.find(content)
    if (fenceMatch != null) {
        val rawBody = fenceMatch.groupValues[1].trim()
        return parsePortalBody(rawBody)
    }

    // Format 2: <!-- PORTAL:{...} --> HTML comment
    val commentRegex = Regex("""<!--\s*PORTAL:\s*(.*?)\s*-->""", RegexOption.DOT_MATCHES_ALL)
    val commentMatch = commentRegex.find(content)
    if (commentMatch != null) {
        val json = commentMatch.groupValues[1].trim()
        return try {
            val jsonObj = org.json.JSONObject(json)
            ExtractedPortal(
                html = jsonObj.optString("html", ""),
                css = jsonObj.optString("css", ""),
                js = jsonObj.optString("js", ""),
                height = jsonObj.optInt("height", -1).takeIf { it > 0 },
                interactive = jsonObj.optBoolean("interactive", true),
            )
        } catch (e: Exception) {
            null
        }
    }

    return null
}

/**
 * Parses the body of a ```portal code fence.
 * The body can optionally start with YAML-like front-matter:
 *   ---
 *   css: "body { background: red; }"
 *   js: "console.log('hi')"
 *   height: 300
 *   interactive: true
 *   ---
 *   <div>Hello</div>
 */
private fun parsePortalBody(body: String): ExtractedPortal {
    var css = ""
    var js = ""
    var height: Int? = null
    var interactive = true
    var html = body

    // Check for YAML front-matter delimited by ---
    val frontMatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)
    val fmMatch = frontMatterRegex.find(body)
    if (fmMatch != null) {
        val frontMatter = fmMatch.groupValues[1]
        html = fmMatch.groupValues[2].trim()

        // Simple YAML-like parsing (key: value pairs)
        frontMatter.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("css:")) {
                css = trimmed.removePrefix("css:").trim().trimSurrounding('"')
            } else if (trimmed.startsWith("js:")) {
                js = trimmed.removePrefix("js:").trim().trimSurrounding('"')
            } else if (trimmed.startsWith("height:")) {
                height = trimmed.removePrefix("height:").trim().toIntOrNull()
            } else if (trimmed.startsWith("interactive:")) {
                interactive = trimmed.removePrefix("interactive:").trim().lowercase() == "true"
            }
        }
    }

    return ExtractedPortal(html = html, css = css, js = js, height = height, interactive = interactive)
}

/**
 * Strips portal content from the message, leaving only the regular markdown text.
 */
private fun stripPortalContent(content: String): String {
    // Strip ```portal code fences
    val portalFenceRegex = Regex("""```portal\s*\n.*?```""", RegexOption.DOT_MATCHES_ALL)
    var result = portalFenceRegex.replace(content, "")

    // Strip <!-- PORTAL:... --> comments
    val commentRegex = Regex("""<!--\s*PORTAL:\s*.*?\s*-->""", RegexOption.DOT_MATCHES_ALL)
    result = commentRegex.replace(result, "")

    return result.trim()
}

/**
 * Helper to trim surrounding quotes from a string.
 */
private fun String.trimSurrounding(char: Char): String {
    if (length >= 2 && first() == char && last() == char) {
        return substring(1, length - 1)
    }
    return this
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview
@Composable
fun MessageCardPreview() {
    guruTheme {
        val demoText = remember {
            LoremIpsum(60).values.first()
        }
        LazyColumn(
            Modifier.background(MaterialTheme.colorScheme.background)
        ) {
            item {
                MessageCard(
                    message = AiMessage.UserMessage(
                        content = demoText,
                        time = 1111111111,
                        uuid = "123",
                        attachments = listOf(
                            AiMessageAttachment.Note(
                                Note(
                                    "This is a test tile for the note",
                                    "Description",
                                    1111111111,
                                    id = "1"
                                )
                            ),
                            AiMessageAttachment.CalenderEvents,
                        )
                    ),
                    onCopy = {},
                    onNoteClick = {},
                    onTaskClick = {},
                    onEventClick = {}
                )
            }
            item {
                MessageCard(
                    message = AiMessage.ToolCall(
                        id = "test-id",
                        name = "searchNotes",
                        rawContent = "{\"query\": \"meeting notes\"}",
                        resultRawContent = "[{\"id\": 1, \"title\": \"Meeting Notes\", \"content\": \"Discussion about project...\"}]",
                        time = 1111111113,
                        uuid = "890"
                    ),
                    onCopy = {},
                    onNoteClick = {},
                    onTaskClick = {},
                    onEventClick = {}
                )
            }
            item {
                MessageCard(
                    message = AiMessage.ToolCall(
                        id = "test-id-",
                        name = "searchNotes",
                        rawContent = "{\"query\": \"meeting notes\"}",
                        resultRawContent = "[{\"id\": 1, \"title\": \"Meeting Notes\", \"content\": \"Discussion about project...\"}]",
                        time = 1111111113,
                        uuid = "890"
                    ),
                    onCopy = {},
                    onNoteClick = {},
                    onTaskClick = {},
                    onEventClick = {}
                )
            }
            item {
                MessageCard(
                    message = AiMessage.AssistantMessage(
                        content = demoText,
                        time = 1111111112,
                        uuid = "567"
                    ),
                    onCopy = {},
                    onNoteClick = {},
                    onTaskClick = {},
                    onEventClick = {}
                )
            }
            item {
                MessageCard(
                    message = AiMessage.ToolCall(
                        id = "test-id-1",
                        name = "searchNotes",
                        rawContent = "{\"query\": \"meeting notes\"}",
                        resultRawContent = "Found 1 note",
                        time = 1111111113,
                        uuid = "890",
                        resultObject = ToolCallResultObject.Notes(
                            listOf(
                                Note(
                                    title = "Meeting Notes",
                                    content = "Discussion about project architecture and upcoming deadlines.",
                                    createdDate = 1111111111,
                                    updatedDate = 1111111111,
                                    id = "1"
                                )
                            )
                        )
                    ),
                    onCopy = {},
                    onNoteClick = {},
                    onTaskClick = {},
                    onEventClick = {}
                )
            }
            item {
                MessageCard(
                    message = AiMessage.ToolCall(
                        id = "test-id-2",
                        name = "createMultipleTasks",
                        rawContent = "Create 2 tasks",
                        resultRawContent = "Tasks created",
                        time = 1111111114,
                        uuid = "891",
                        resultObject = ToolCallResultObject.Tasks(
                            listOf(
                                Task(
                                    title = "Fix UI bugs",
                                    description = "Check the padding in the message card",
                                    id = "task1"
                                ),
                                Task(
                                    title = "Update documentation",
                                    description = "Add info about AI tools",
                                    id = "task2",
                                    isCompleted = true
                                )
                            )
                        )
                    ),
                    onCopy = {},
                    onNoteClick = {},
                    onTaskClick = {},
                    onEventClick = {}
                )
            }
            item {
                MessageCard(
                    message = AiMessage.ToolCall(
                        id = "test-id-3",
                        name = "createEvent",
                        rawContent = "Create calendar event",
                        resultRawContent = "Event created",
                        time = 1111111115,
                        uuid = "892",
                        resultObject = ToolCallResultObject.CalendarEvents(
                            listOf(
                                CalendarEvent(
                                    title = "Design Sync",
                                    start = 1111111111,
                                    end = 1111111111 + 3600000,
                                    id = 123,
                                    calendarId = 1,
                                    color = 0xFF00FF00.toInt()
                                )
                            )
                        )
                    ),
                    onCopy = {},
                    onNoteClick = {},
                    onTaskClick = {},
                    onEventClick = {}
                )
            }
        }
    }
}