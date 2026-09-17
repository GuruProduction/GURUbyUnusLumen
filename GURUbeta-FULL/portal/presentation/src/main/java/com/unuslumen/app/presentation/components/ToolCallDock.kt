package com.unuslumen.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.presentation.PortalViewModel

private val GlassBg = Color(0xFFEDE4D3)
private val GoldAccent = Color(0xFFDAA520)
private val GoldAccentDim = Color(0xFFB8956A)
private val GoldEdge = Color(0xFFD4C9B8)
private val RowBg = Color(0xFFEDE4D3)
private val TextPrimary = Color(0xFF2B241C)
private val TextDim = Color(0xFF5A4F43)
private val TextFaint = Color(0xFF8A7E70)
private val RunningColour = Color(0xFFE78A00)
private val DoneColour = Color(0xFF1E9651)
private val FailedColour = Color(0xFFD53A2F)

/**
 * Tool call dock — right-aligned, collapsed by default.
 * True glass: near-black at 70% opacity, gold top edge, canvas bleeds through.
 * Expanded view is a small scrollable card, never fills the portal.
 */
@Composable
fun ToolCallDock(
    groups: List<PortalViewModel.ToolCallGroup>,
    onDismiss: (String) -> Unit,
    onToolCallTap: (AiMessage.ToolCall) -> Unit,
    modifier: Modifier = Modifier,
    forceExpanded: Boolean = false,
) {
    if (groups.isEmpty()) return

    var isExpanded by remember { mutableStateOf(forceExpanded) }
    var expandedGroupIndex by remember(groups.size) {
        mutableStateOf(groups.size - 1)
    }

    if (!isExpanded) {
        CollapsedStrip(
            groups = groups,
            onExpand = { isExpanded = true },
            onDismiss = onDismiss,
            modifier = modifier,
        )
    } else {
        ExpandedCard(
            groups = groups,
            expandedGroupIndex = expandedGroupIndex.coerceIn(0, groups.size - 1),
            onExpandGroup = { expandedGroupIndex = it },
            onCollapse = { isExpanded = false },
            onDismiss = onDismiss,
            onToolCallTap = onToolCallTap,
            modifier = modifier,
        )
    }
}

@Composable
private fun GlassBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GlassBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GoldEdge)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        )
        content()
    }
}

@Composable
private fun CollapsedStrip(
    groups: List<PortalViewModel.ToolCallGroup>,
    onExpand: () -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalCalls = groups.sumOf { it.toolCalls.size }
    val anyRunning = groups.any { it.isStreaming }
    val anyFailed = groups.any { group ->
        group.toolCalls.any { it is AiMessage.ToolCall && it.isFailed }
    }

    GlassBox(
        modifier = modifier.wrapContentWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = GoldAccent,
                modifier = Modifier.size(14.dp),
            )

            Spacer(Modifier.width(6.dp))

            DockStatusDot(running = anyRunning, failed = anyFailed, size = 7.dp)

            Spacer(Modifier.width(6.dp))

            Text(
                text = "$totalCalls tool${if (totalCalls != 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Default,
                ),
                color = TextPrimary,
            )

            Spacer(Modifier.width(4.dp))

            Text(
                text = if (anyRunning) "running" else "ran",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default,
                ),
                color = TextDim,
            )

            Spacer(Modifier.width(6.dp))

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Expand",
                tint = GoldAccentDim,
                modifier = Modifier.size(14.dp),
            )

            Spacer(Modifier.width(6.dp))

            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss all",
                tint = TextFaint,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable(onClick = {
                        groups.forEach { onDismiss(it.uuid) }
                    }),
            )
        }
    }
}

@Composable
private fun ExpandedCard(
    groups: List<PortalViewModel.ToolCallGroup>,
    expandedGroupIndex: Int,
    onExpandGroup: (Int) -> Unit,
    onCollapse: () -> Unit,
    onDismiss: (String) -> Unit,
    onToolCallTap: (AiMessage.ToolCall) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassBox(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCollapse)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = GoldAccent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Tools",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    ),
                    color = GoldAccent,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Collapse",
                    tint = GoldAccentDim,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.size(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groups.forEachIndexed { index, group ->
                    val isGroupExpanded = index == expandedGroupIndex
                    GroupRow(
                        group = group,
                        isExpanded = isGroupExpanded,
                        onToggle = { onExpandGroup(index) },
                        onDismiss = { onDismiss(group.uuid) },
                        onToolCallTap = onToolCallTap,
                    )
                    if (index < groups.size - 1) {
                        Spacer(Modifier.size(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: PortalViewModel.ToolCallGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onToolCallTap: (AiMessage.ToolCall) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RowBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupStatusIndicator(group = group)

            Spacer(Modifier.width(8.dp))

            Text(
                text = group.summary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (group.toolCalls.size > 1) {
                Text(
                    text = "${group.toolCalls.size}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = GoldAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoldAccent.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = GoldAccentDim,
                modifier = Modifier.size(14.dp),
            )

            Spacer(Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = TextFaint,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                group.toolCalls.forEach { msg ->
                    val toolCall = when (msg) {
                        is AiMessage.ToolCall -> msg
                        is AiMessage.StreamingToolCall -> AiMessage.ToolCall(
                            uuid = msg.uuid,
                            id = null,
                            name = msg.toolName,
                            rawContent = msg.partialContent,
                            resultRawContent = "",
                            time = msg.time,
                        )
                        else -> null
                    }
                    if (toolCall != null) {
                        ToolCallRow(
                            toolCall = toolCall,
                            isStreaming = group.isStreaming,
                            onTap = { onToolCallTap(toolCall) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallRow(
    toolCall: AiMessage.ToolCall,
    isStreaming: Boolean,
    onTap: () -> Unit,
) {
    val displayName = remember(toolCall.name) { toolDisplayName(toolCall.name) }
    val running = isStreaming && toolCall.resultRawContent.isBlank()
    val preview = remember(toolCall.resultRawContent) {
        when {
            toolCall.resultRawContent.isNotBlank() -> formatResultPreview(toolCall.resultRawContent, 50)
            running -> "Running..."
            toolCall.isFailed -> "Failed"
            else -> "Done"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockStatusDot(
            running = running,
            failed = toolCall.isFailed,
            size = 7.dp,
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = preview,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Default,
            ),
            color = TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GroupStatusIndicator(group: PortalViewModel.ToolCallGroup) {
    val anyRunning = group.isStreaming
    val anyFailed = group.toolCalls.any { it is AiMessage.ToolCall && it.isFailed }

    DockStatusDot(
        running = anyRunning,
        failed = anyFailed,
        size = 8.dp,
    )
}

@Composable
private fun DockStatusDot(
    running: Boolean,
    failed: Boolean,
    size: androidx.compose.ui.unit.Dp,
) {
    when {
        running -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(RunningColour),
            )
        }
        failed -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(FailedColour),
            )
        }
        else -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(DoneColour),
            )
        }
    }
}