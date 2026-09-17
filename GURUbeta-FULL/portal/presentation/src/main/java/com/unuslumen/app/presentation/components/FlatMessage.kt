@file:Suppress("SameParameterValue")

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.defaultMarkdownTypography
import com.unuslumen.app.ui.components.common.withHardLineBreaks
import com.unuslumen.app.util.date.formatTime
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor

// Thinking block UI (explicit imports from same package per project convention)
import com.unuslumen.app.presentation.components.ThinkingBlockCard
import com.unuslumen.app.presentation.components.ThinkingDisplayLevel

private val UnusLabelGold = Color(0xFFDAA520)
private val UserLabelDark = Color(0xFF2B2B2B)
private val HairlineWidth = 28.dp
private val HairlineHeight = 1.dp

@Composable
fun LazyItemScope.FlatMessage(
    message: AiMessage,
    onCopy: (String) -> Unit,
    onNoteClick: (Note) -> Unit = {},
    onTaskClick: (Task) -> Unit = {},
    onEventClick: (CalendarEvent) -> Unit = {},
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
    isStreaming: Boolean = false,
    username: String = "You",
    onToolCallTap: ((AiMessage.ToolCall) -> Unit)? = null,
) {
    when (message) {
        is AiMessage.UserMessage -> FlatUserMessage(
            message = message,
            onCopy = onCopy,
            username = username,
        )
        is AiMessage.AssistantMessage -> FlatAssistantMessage(
            message = message,
            onCopy = onCopy,
            onPortalEvent = onPortalEvent,
            isStreaming = isStreaming,
        )
        is AiMessage.StreamingAssistant -> FlatAssistantMessage(
            message = AiMessage.AssistantMessage(
                content = message.partialContent,
                time = message.time,
                uuid = message.uuid,
                thinkingTokens = message.partialThinking,
            ),
            onCopy = onCopy,
            onPortalEvent = onPortalEvent,
            isStreaming = true,
        )
        is AiMessage.StreamingToolCall -> {
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                FlatToolCallInline(
                    toolCall = AiMessage.ToolCall(
                        uuid = message.uuid,
                        id = null,
                        name = message.toolName,
                        rawContent = message.partialContent,
                        resultRawContent = "",
                        time = message.time,
                    ),
                    isStreaming = true,
                    onTap = { },
                )
            }
        }
        is AiMessage.PortalMessage -> FlatPortalMessage(
            message = message,
            onPortalEvent = onPortalEvent,
        )
        is AiMessage.ToolCall -> {
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                FlatToolCallInline(
                    toolCall = message,
                    isStreaming = isStreaming,
                    onTap = { onToolCallTap?.invoke(message) },
                )
                message.resultObject?.let { obj ->
                    ToolCallResultPreview(
                        resultObject = obj,
                        onNoteClick = onNoteClick,
                        onTaskClick = onTaskClick,
                        onEventClick = onEventClick,
                        onPortalEvent = onPortalEvent,
                    )
                }
            }
        }
    }
}

/**
 * Meta-row: label with its own font/weight/colour, timestamp at 50% opacity,
 * short hairline underneath. Alignment controls which side the block sits on.
 */
@Composable
private fun MessageMetaRow(
    label: String,
    labelStyle: androidx.compose.ui.text.TextStyle,
    formattedTime: String,
    alignment: Alignment.Horizontal,
    hairlineColor: Color,
) {
    Column(
        horizontalAlignment = alignment,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = labelStyle)
            Text(
                text = "  $formattedTime",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = labelStyle.color.copy(alpha = 0.5f),
                ),
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(HairlineWidth)
                .height(HairlineHeight)
                .background(hairlineColor.copy(alpha = 0.18f))
        )
        Spacer(modifier = Modifier.height(7.dp))
    }
}

@Composable
private fun LazyItemScope.FlatAssistantMessage(
    message: AiMessage.AssistantMessage,
    onCopy: (String) -> Unit,
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
    isStreaming: Boolean = false,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formattedTime by remember(message.time) {
        derivedStateOf { message.time.formatTime(context) }
    }
    val portalContent = remember(message.content) { extractPortalContent(message.content) }
    val markdownContent = remember(message.content) { stripLuxifyMarkers(stripPortalContent(message.content)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { showContextMenu = true }
    ) {
        MessageMetaRow(
            label = stringResource(id = R.string.flat_message_unus_label),
            labelStyle = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Serif,
                color = UnusLabelGold,
                letterSpacing = 1.sp,
            ),
            formattedTime = formattedTime,
            alignment = Alignment.Start,
            hairlineColor = UnusLabelGold,
        )
        if (markdownContent.isNotBlank()) {
            val processedContent = remember(markdownContent) {
                markdownContent.withHardLineBreaks()
            }
            Markdown(
                content = processedContent,
                imageTransformer = Coil2ImageTransformerImpl,
                colors = markdownColor(text = MaterialTheme.colorScheme.onBackground),
                typography = defaultMarkdownTypography(),
            )
        }
        if (portalContent != null) {
            Spacer(modifier = Modifier.height(4.dp))
            PortalWebView(
                html = portalContent.html,
                css = portalContent.css,
                js = portalContent.js,
                height = portalContent.height,
                interactive = portalContent.interactive,
                onPortalEvent = onPortalEvent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Render thinking block if present
        if (message.thinkingTokens.isNotBlank()) {
            ThinkingBlockCard(
                thinkingTokens = message.thinkingTokens,
                displayLevel = ThinkingDisplayLevel.MEDIUM,
                isStreaming = isStreaming,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
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
                        contentDescription = stringResource(id = R.string.copy),
                    )
                },
                onClick = {
                    showContextMenu = false
                    onCopy(message.content)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LazyItemScope.FlatUserMessage(
    message: AiMessage.UserMessage,
    onCopy: (String) -> Unit,
    username: String = "You",
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formattedTime by remember(message.time) {
        derivedStateOf { message.time.formatTime(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { showContextMenu = true }
    ) {
        MessageMetaRow(
            label = username.ifBlank { "YOU" },
            labelStyle = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                color = UserLabelDark,
                letterSpacing = 1.sp,
            ),
            formattedTime = formattedTime,
            alignment = Alignment.End,
            hairlineColor = UserLabelDark,
        )
        if (message.content.isNotBlank()) {
            val processedContent = remember(message.content) {
                message.content.withHardLineBreaks()
            }
            Markdown(
                content = processedContent,
                imageTransformer = Coil2ImageTransformerImpl,
                colors = markdownColor(text = MaterialTheme.colorScheme.onBackground),
                typography = defaultMarkdownTypography(),
            )
        }
        if (message.attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            FlatAttachmentChips(attachments = message.attachments)
        }
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
                        contentDescription = stringResource(id = R.string.copy),
                    )
                },
                onClick = {
                    showContextMenu = false
                    onCopy(message.content)
                },
            )
        }
    }
}

@Composable
private fun FlatAttachmentChips(
    attachments: List<AiMessageAttachment>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        attachments.forEach { attachment ->
            val chipText = when (attachment) {
                is AiMessageAttachment.Note -> stringResource(
                    id = R.string.flat_message_attachment_note_prefix,
                    attachment.note.title
                )
                is AiMessageAttachment.Task -> stringResource(
                    id = R.string.flat_message_attachment_task_prefix,
                    attachment.task.title
                )
                is AiMessageAttachment.CalenderEvents -> stringResource(
                    id = R.string.flat_message_attachment_calendar_events
                )
                is AiMessageAttachment.File -> attachment.fileName
            }
            Text(
                text = chipText,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FlatToolCallInline(
    toolCall: AiMessage.ToolCall,
    isStreaming: Boolean = false,
    onTap: () -> Unit,
) {
    val displayName = remember(toolCall.name) { toolDisplayName(toolCall.name) }
    val running = isStreaming && toolCall.resultRawContent.isBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .alpha(0.85f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        when {
            running -> {
                val pulseAlpha by rememberInfiniteTransition("pulse")
                    .animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "pulse",
                    )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            Color(0xFFFFA726).copy(alpha = pulseAlpha),
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
            toolCall.isFailed -> {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(id = R.string.flat_message_tool_status_failed),
                    tint = Color(0xFFEF5350),
                    modifier = Modifier.size(12.dp),
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(id = R.string.flat_message_tool_status_done),
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "> $displayName",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (toolCall.resultRawContent.isNotBlank() && !running) {
            val preview = remember(toolCall.resultRawContent) {
                formatResultPreview(toolCall.resultRawContent, 60)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LazyItemScope.FlatPortalMessage(
    message: AiMessage.PortalMessage,
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        PortalWebView(
            html = message.html,
            css = message.css,
            js = message.js,
            height = message.height,
            interactive = message.interactive,
            onPortalEvent = onPortalEvent,
        )
        Text(
            text = message.time.formatTime(context),
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            ),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private data class FlatExtractedPortal(
    val html: String,
    val css: String = "",
    val js: String = "",
    val height: Int? = null,
    val interactive: Boolean = true,
)

private fun extractPortalContent(content: String): FlatExtractedPortal? {
    val portalFenceRegex = Regex("""```portal\s*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    val fenceMatch = portalFenceRegex.find(content)
    if (fenceMatch != null) {
        val rawBody = fenceMatch.groupValues[1].trim()
        return parsePortalBody(rawBody)
    }
    val commentRegex = Regex("""<!--\s*PORTAL:\s*(.*?)\s*-->""", RegexOption.DOT_MATCHES_ALL)
    val commentMatch = commentRegex.find(content)
    if (commentMatch != null) {
        val json = commentMatch.groupValues[1].trim()
        return try {
            val jsonObj = org.json.JSONObject(json)
            FlatExtractedPortal(
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

private fun parsePortalBody(body: String): FlatExtractedPortal {
    var css = ""
    var js = ""
    var height: Int? = null
    var interactive = true
    var html = body

    val frontMatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)
    val fmMatch = frontMatterRegex.find(body)
    if (fmMatch != null) {
        val frontMatter = fmMatch.groupValues[1]
        html = fmMatch.groupValues[2].trim()
        frontMatter.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("css:")) {
                css = trimmed.removePrefix("css:").trim().flatTrimSurrounding('"')
            } else if (trimmed.startsWith("js:")) {
                js = trimmed.removePrefix("js:").trim().flatTrimSurrounding('"')
            } else if (trimmed.startsWith("height:")) {
                height = trimmed.removePrefix("height:").trim().toIntOrNull()
            } else if (trimmed.startsWith("interactive:")) {
                interactive = trimmed.removePrefix("interactive:").trim().lowercase() == "true"
            }
        }
    }
    return FlatExtractedPortal(html = html, css = css, js = js, height = height, interactive = interactive)
}

private fun stripPortalContent(content: String): String {
    val portalFenceRegex = Regex("""```portal\s*\n.*?```""", RegexOption.DOT_MATCHES_ALL)
    var result = portalFenceRegex.replace(content, "")
    val commentRegex = Regex("""<!--\s*PORTAL:\s*.*?\s*-->""", RegexOption.DOT_MATCHES_ALL)
    result = commentRegex.replace(result, "")
    return result.trim()
}

private fun stripLuxifyMarkers(content: String): String {
    val questionRegex = Regex("""\[LUXIFY_QUESTION\].*?\[/LUXIFY_QUESTION\]""", RegexOption.DOT_MATCHES_ALL)
    var result = questionRegex.replace(content, "")
    val skillRegex = Regex("""\[LUXIFY_SKILL\].*?\[/LUXIFY_SKILL\]""", RegexOption.DOT_MATCHES_ALL)
    result = skillRegex.replace(result, "")
    return result.trim()
}

private fun String.flatTrimSurrounding(char: Char): String {
    if (length >= 2 && first() == char && last() == char) {
        return substring(1, length - 1)
    }
    return this
}