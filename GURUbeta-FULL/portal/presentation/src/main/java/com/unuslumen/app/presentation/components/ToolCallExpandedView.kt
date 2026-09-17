package com.unuslumen.app.presentation.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.presentation.PortalViewModel
import java.io.IOException

// ── Status ──
private enum class ToolCallStatus { DONE, RUNNING, FAILED }

private fun groupStatus(group: PortalViewModel.ToolCallGroup): ToolCallStatus {
    val anyFailed = group.toolCalls.any { (it is AiMessage.ToolCall) && it.isFailed }
    return when {
        group.isStreaming -> ToolCallStatus.RUNNING
        anyFailed -> ToolCallStatus.FAILED
        else -> ToolCallStatus.DONE
    }
}

private fun toolStatus(msg: AiMessage, groupIsStreaming: Boolean): ToolCallStatus {
    return when (msg) {
        is AiMessage.StreamingToolCall -> ToolCallStatus.RUNNING
        is AiMessage.ToolCall -> when {
            msg.isFailed -> ToolCallStatus.FAILED
            groupIsStreaming && msg.resultRawContent.isBlank() -> ToolCallStatus.RUNNING
            else -> ToolCallStatus.DONE
        }
        else -> ToolCallStatus.DONE
    }
}

private fun groupLabel(group: PortalViewModel.ToolCallGroup): String {
    val first = group.toolCalls.firstOrNull() ?: return "Tools"
    val name = when (first) {
        is AiMessage.ToolCall -> first.name
        is AiMessage.StreamingToolCall -> first.toolName
        else -> return "Tools"
    }
    return toolCategory(name)
}

private fun extractToolCall(msg: AiMessage): AiMessage.ToolCall? {
    return when (msg) {
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
}

// ── Colours ── Match the app's warm paper theme exactly.
// Theme values: background = 0xFFEDE4D3, surface/surfaceVariant = 0xFFF0E8DA,
// onBackground/onSurfaceVariant = 0xFF2B241C (DarkGray).
// Tiles use background blended over surface to get the same frosted warm beige
// the attachment sheets use via MaterialTheme.colorScheme.background.compositeOver(surfaceVariant).
private val TileGlass = Color(0xFFEDE4D3)
private val TileGlassActive = Color(0xFFE8D9B8)
private val TileGlassFailed = Color(0xFFE8D8D5)
private val TileGlassCached = Color(0xFFEDE4D3)
private val TextPrimary = Color(0xFF2B241C)
private val TextSecondary = Color(0xFF5A4F43)
private val TextFaint = Color(0xFF8A7E70)
private val StatusGreen = Color(0xFF1E9651)
private val StatusAmber = Color(0xFFE78A00)
private val StatusRed = Color(0xFFD53A2F)
private val ScrimLine = Color(0xFFC4B8A8)
private val CachedChip = Color(0xFFE8D9B8)

// ── Icon cache: loads PNG icons from assets/tools_icons/ on first access ──
private class IconCache(context: Context) {
    private val iconPaths: Map<String, String> = try {
        val files = context.assets.list("tools_icons") ?: emptyArray()
        files.filter { it.endsWith(".png", ignoreCase = true) }.associate { file ->
            val category = file.substringBeforeLast('.').trim()
            category to "tools_icons/$file"
        }
    } catch (e: IOException) {
        emptyMap()
    }

    fun pathFor(category: String): String? = iconPaths[category]
}

// ── Loads a PNG from assets and returns a Painter for Compose Image ──
@Composable
private fun rememberAssetPainter(assetPath: String?): Painter {
    val context = LocalContext.current
    return remember(assetPath) {
        if (assetPath == null) {
            return@remember BitmapPainter(ImageBitmap(1, 1))
        }
        try {
            context.assets.open(assetPath).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    BitmapPainter(bitmap.asImageBitmap())
                } else {
                    BitmapPainter(ImageBitmap(1, 1))
                }
            }
        } catch (e: IOException) {
            BitmapPainter(ImageBitmap(1, 1))
        }
    }
}

// ── Main composable ──
@Composable
fun ToolCallExpandedView(
    groups: List<PortalViewModel.ToolCallGroup>,
    onDismiss: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onTaskClick: (Task) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onPortalEvent: (name: String, payload: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentLevel by remember { mutableIntStateOf(1) }
    var selectedGroupIndex by remember { mutableIntStateOf(-1) }
    var selectedToolIndex by remember { mutableIntStateOf(-1) }
    var browserUrl by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val iconCache = remember { IconCache(context) }

    LaunchedEffect(groups.size) {
        if (selectedGroupIndex >= groups.size) {
            selectedGroupIndex = -1
            selectedToolIndex = -1
            currentLevel = 1
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Live progress strip — only visible when tools are actively running
        LiveProgressStrip(groups = groups)

        when (currentLevel) {
            1 -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                CategoryGrid(
                    groups = groups,
                    iconCache = iconCache,
                    onTileTap = { idx ->
                        selectedGroupIndex = idx
                        currentLevel = 2
                    },
                )
            }

            2 -> {
                val group = groups.getOrNull(selectedGroupIndex)
                if (group != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        LogView(
                            group = group,
                            onToolTap = { tIdx ->
                                selectedToolIndex = tIdx
                                currentLevel = 3
                            },
                            onBack = { currentLevel = 1 },
                        )
                    }
                }
            }

            3 -> {
                val group = groups.getOrNull(selectedGroupIndex)
                val tool = group?.toolCalls?.getOrNull(selectedToolIndex)
                val toolCall = tool?.let { extractToolCall(it) }
                if (toolCall != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        FocusCardView(
                            toolCall = toolCall,
                            isStreaming = group.isStreaming,
                            toolIndex = selectedToolIndex,
                            totalTools = group.toolCalls.size,
                            onPrev = { if (selectedToolIndex > 0) selectedToolIndex-- },
                            onNext = { if (selectedToolIndex < group.toolCalls.size - 1) selectedToolIndex++ },
                            onDotTap = { idx -> selectedToolIndex = idx },
                            onBack = { currentLevel = 2 },
                            onResultTap = { currentLevel = 4 },
                            onNoteClick = onNoteClick,
                            onTaskClick = onTaskClick,
                            onEventClick = onEventClick,
                            onPortalEvent = onPortalEvent,
                        )
                    }
                }
            }

            4 -> {
                val group = groups.getOrNull(selectedGroupIndex)
                val tool = group?.toolCalls?.getOrNull(selectedToolIndex)
                val toolCall = tool?.let { extractToolCall(it) }
                if (toolCall != null) {
                    FullResultView(
                        toolCall = toolCall,
                        toolIndex = selectedToolIndex,
                        totalTools = group.toolCalls.size,
                        onPrev = { if (selectedToolIndex > 0) selectedToolIndex-- },
                        onNext = { if (selectedToolIndex < group.toolCalls.size - 1) selectedToolIndex++ },
                        onDotTap = { idx -> selectedToolIndex = idx },
                        onBack = { currentLevel = 3 },
                        onUrlTap = { url ->
                            browserUrl = url
                            currentLevel = 5
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            5 -> {
                val url = browserUrl
                if (url != null) {
                    BrowserView(
                        url = url,
                        onBack = {
                            browserUrl = null
                            currentLevel = 4
                        },
                    )
                }
            }
        }
    }
}

// ── Live progress strip ──
// Only shows when tools are actively running. Clears when all done.
@Composable
private fun LiveProgressStrip(groups: List<PortalViewModel.ToolCallGroup>) {
    val runningCount = groups.count { it.isStreaming }
    val completedCount = groups.count { groupStatus(it) == ToolCallStatus.DONE }
    val failedCount = groups.count { groupStatus(it) == ToolCallStatus.FAILED }
    val totalCount = groups.size

    AnimatedVisibility(
        visible = runningCount > 0,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("$completedCount completed")
                        if (failedCount > 0) append(" · $failedCount failed")
                    },
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = StatusAmber,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$runningCount running",
                        fontSize = 11.sp,
                        color = StatusAmber,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            // Progress bar fill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ScrimLine),
            ) {
                val progress = if (totalCount > 0) {
                    (completedCount.toFloat() / totalCount).coerceIn(0f, 1f)
                } else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (failedCount > 0) StatusAmber else StatusGreen),
                )
            }
        }
    }
}

// ── Level 1: Category grid ──
// Active groups at top, completed groups collapsed into "Cached Tools" at bottom.
@Composable
private fun CategoryGrid(
    groups: List<PortalViewModel.ToolCallGroup>,
    iconCache: IconCache,
    onTileTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Split into active (streaming or not done) and completed (done, not streaming)
    val activeGroups = groups.filter {
        it.isStreaming || groupStatus(it) == ToolCallStatus.RUNNING || groupStatus(it) == ToolCallStatus.FAILED
    }.reversed() // newest active first
    val completedGroups = groups.filter { groupStatus(it) == ToolCallStatus.DONE && !it.isStreaming }

    Column(modifier = modifier.fillMaxWidth()) {
        // Session counter
        if (groups.isNotEmpty()) {
            SessionCounter(
                running = activeGroups.count { groupStatus(it) == ToolCallStatus.RUNNING },
                completed = completedGroups.size,
                failed = activeGroups.count { groupStatus(it) == ToolCallStatus.FAILED },
            )
            Spacer(Modifier.height(12.dp))
        }

        // Active group tiles
        if (activeGroups.isNotEmpty()) {
            activeGroups.chunked(2).forEachIndexed { rowIdx, rowGroups ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (rowIdx < (activeGroups.size + 1) / 2 - 1) 10.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowGroups.forEachIndexed { colIdx, group ->
                        val idx = groups.indexOf(group)
                        val status = groupStatus(group)
                        val category = group.toolCalls.firstOrNull()?.let { msg ->
                            when (msg) {
                                is AiMessage.ToolCall -> toolCategory(msg.name)
                                is AiMessage.StreamingToolCall -> toolCategory(msg.toolName)
                                else -> "Misc"
                            }
                        } ?: "Misc"
                        val completedCount = group.toolCalls.count {
                            it is AiMessage.ToolCall && !it.isFailed
                        }
                        val totalCount = group.toolCalls.size
                        val richPreview = buildRichPreview(group)

                        CategoryTile(
                            category = category,
                            iconPath = iconCache.pathFor(category),
                            status = status,
                            completedCount = completedCount,
                            totalCount = totalCount,
                            richPreview = richPreview,
                            onClick = { onTileTap(idx) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowGroups.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Completed tools section — just a divider line, tiles below always visible
        if (completedGroups.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ScrimLine),
            )
            Spacer(Modifier.height(16.dp))
            completedGroups.reversed().chunked(2).forEachIndexed { rowIdx, rowGroups ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (rowIdx < (completedGroups.size + 1) / 2 - 1) 10.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowGroups.forEachIndexed { colIdx, group ->
                        val idx = groups.indexOf(group)
                        val category = group.toolCalls.firstOrNull()?.let { msg ->
                            when (msg) {
                                is AiMessage.ToolCall -> toolCategory(msg.name)
                                is AiMessage.StreamingToolCall -> toolCategory(msg.toolName)
                                else -> "Misc"
                            }
                        } ?: "Misc"
                        val totalCount = group.toolCalls.size
                        val richPreview = buildRichPreview(group)

                        CategoryTile(
                            category = category,
                            iconPath = iconCache.pathFor(category),
                            status = ToolCallStatus.DONE,
                            completedCount = totalCount,
                            totalCount = totalCount,
                            richPreview = richPreview,
                            onClick = { onTileTap(idx) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowGroups.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// Extracts a meaningful content summary from a group's tool calls.
// Shows what GURU actually found, not just a tool count.
private fun buildRichPreview(group: PortalViewModel.ToolCallGroup): String {
    val completedCalls = group.toolCalls.filterIsInstance<AiMessage.ToolCall>()
    if (completedCalls.isEmpty()) {
        // All streaming, show what is running
        val streamingNames = group.toolCalls.filterIsInstance<AiMessage.StreamingToolCall>()
            .map { toolDisplayName(it.toolName) }
        return if (streamingNames.isNotEmpty()) {
            streamingNames.distinct().take(2).joinToString(", ") +
                if (streamingNames.distinct().size > 2) " +${streamingNames.distinct().size - 2} more"
                else ""
        } else "Working..."
    }
    // Use the summariseGroup function for a natural English summary
    return summariseGroup(completedCalls)
}

// Session counter at the top of level 1
@Composable
private fun SessionCounter(
    running: Int,
    completed: Int,
    failed: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = StatusAmber,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$running running",
                    fontSize = 12.sp,
                    color = StatusAmber,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Text(
                text = "$completed completed" + if (failed > 0) " · $failed failed" else "",
                fontSize = 12.sp,
                color = TextSecondary,
                fontWeight = FontWeight.Medium,
            )
        }
        if (running > 0 && completed > 0) {
            Text(
                text = "$completed completed" + if (failed > 0) " · $failed failed" else "",
                fontSize = 12.sp,
                color = TextSecondary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun CategoryTile(
    category: String,
    iconPath: String?,
    status: ToolCallStatus,
    completedCount: Int,
    totalCount: Int,
    richPreview: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileBg = when (status) {
        ToolCallStatus.RUNNING -> TileGlassActive
        ToolCallStatus.FAILED -> TileGlassFailed
        ToolCallStatus.DONE -> TileGlass
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tileBg)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            // Category icon from assets
            if (iconPath != null) {
                Image(
                    painter = rememberAssetPainter(iconPath),
                    contentDescription = category,
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                // Fallback: first two letters in a circle
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE0D5C3)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = category.take(2),
                        fontSize = 10.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Status indicator
            when (status) {
                ToolCallStatus.RUNNING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = StatusAmber,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "$completedCount/$totalCount",
                            fontSize = 9.sp,
                            color = StatusAmber,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                ToolCallStatus.DONE -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = StatusGreen,
                        modifier = Modifier.size(16.dp),
                    )
                }
                ToolCallStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Failed",
                        tint = StatusRed,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = category,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = when {
                status == ToolCallStatus.RUNNING -> "$completedCount / $totalCount done"
                status == ToolCallStatus.FAILED && completedCount > 0 -> "$completedCount done · failed"
                status == ToolCallStatus.FAILED -> "Failed"
                richPreview.isNotBlank() -> richPreview
                else -> "$totalCount tool${if (totalCount != 1) "s" else ""}"
            },
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Level 2: Activity log ──
@Composable
private fun LogView(
    group: PortalViewModel.ToolCallGroup,
    onToolTap: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = groupLabel(group),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
        }
        group.toolCalls.forEachIndexed { tIdx, msg ->
            val toolCall = extractToolCall(msg)
            if (toolCall != null) {
                val status = toolStatus(msg, group.isStreaming)
                LogEntry(
                    toolCall = toolCall,
                    status = status,
                    onClick = { onToolTap(tIdx) },
                )
            }
        }
    }
}

@Composable
private fun LogEntry(
    toolCall: AiMessage.ToolCall,
    status: ToolCallStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = remember(toolCall.name) { toolDisplayName(toolCall.name) }
    val preview = remember(toolCall.resultRawContent, toolCall.isFailed) {
        when {
            toolCall.isFailed -> "Failed"
            toolCall.resultRawContent.isNotBlank() -> formatResultPreview(toolCall.resultRawContent, 60)
            else -> "Running..."
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusDot(status = status, size = 8.dp, modifier = Modifier.padding(top = 5.dp))

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextFaint,
                modifier = Modifier
                    .size(14.dp)
                    .padding(top = 4.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ScrimLine),
        )
    }
}

// ── Level 3: Focus card ──
@Composable
private fun FocusCardView(
    toolCall: AiMessage.ToolCall,
    isStreaming: Boolean,
    toolIndex: Int,
    totalTools: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDotTap: (Int) -> Unit,
    onBack: () -> Unit,
    onResultTap: () -> Unit,
    onNoteClick: (Note) -> Unit,
    onTaskClick: (Task) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onPortalEvent: (name: String, payload: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = toolStatus(toolCall, isStreaming)
    val displayName = remember(toolCall.name) { toolDisplayName(toolCall.name) }
    val args = remember(toolCall.rawContent) { formatToolArgs(toolCall.rawContent) }
    val resultPreview = remember(toolCall.resultRawContent) {
        if (toolCall.resultRawContent.isNotBlank()) formatResultPreview(toolCall.resultRawContent, 500)
        else null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(4.dp),
            )
        }

        // Main card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(TileGlass)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusDot(status = status, size = 12.dp)

            Spacer(Modifier.height(14.dp))

            Text(
                text = displayName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )

            Text(
                text = when {
                    toolCall.isFailed -> "Failed"
                    isStreaming && toolCall.resultRawContent.isBlank() -> "Running..."
                    toolCall.resultRawContent.isNotBlank() -> formatResultPreview(toolCall.resultRawContent, 80)
                    else -> "Done"
                },
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Arguments section
        if (args.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TileGlass)
                    .padding(16.dp),
            ) {
                Text(
                    text = "ARGUMENTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusGreen,
                    letterSpacing = 0.5.sp,
                )

                Spacer(Modifier.height(8.dp))

                args.forEach { arg ->
                    if (arg.isRaw) {
                        Text(
                            text = arg.label,
                            fontSize = 13.sp,
                            color = TextSecondary,
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = arg.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary,
                                modifier = Modifier.width(100.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = arg.value,
                                fontSize = 13.sp,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Result section
        if (isStreaming && toolCall.resultRawContent.isBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TileGlass)
                    .padding(16.dp),
            ) {
                Text(
                    text = "EXECUTING",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusAmber,
                    letterSpacing = 0.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Running...",
                    fontSize = 13.sp,
                    color = StatusAmber,
                )
            }
        } else if (resultPreview != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TileGlass)
                    .clickable(onClick = onResultTap)
                    .padding(16.dp),
            ) {
                Text(
                    text = "RESULT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (toolCall.isFailed) StatusRed else StatusGreen,
                    letterSpacing = 0.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = resultPreview,
                    fontSize = 13.sp,
                    color = TextPrimary,
                )
            }
        }

        // Navigation between tool calls
        if (totalTools > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Prev
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TileGlass)
                        .then(
                            if (toolIndex > 0) Modifier.clickable(onClick = onPrev)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Pagination dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 0 until totalTools) {
                        val isActive = i == toolIndex
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .then(
                                    if (isActive) Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .width(18.dp)
                                        .background(StatusGreen)
                                    else Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFC4B8A8)),
                                )
                                .clickable { onDotTap(i) },
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Next
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TileGlass)
                        .then(
                            if (toolIndex < totalTools - 1) Modifier.clickable(onClick = onNext)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── Level 4: Full Result View ──
// Uses LazyColumn with a flattened result list so only visible entries are
// composed and measured. This prevents the crash when DB search results
// contain thousands of rows. Every entry is preserved. Nothing is truncated.
@Composable
private fun FullResultView(
    toolCall: AiMessage.ToolCall,
    toolIndex: Int,
    totalTools: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDotTap: (Int) -> Unit,
    onBack: () -> Unit,
    onUrlTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = toolStatus(toolCall, false)
    val displayName = remember(toolCall.name) { toolDisplayName(toolCall.name) }
    val flatItems = remember(toolCall.resultRawContent) {
        flattenResults(formatFullResult(toolCall.resultRawContent))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(4.dp),
            )
        }

        // Tool name header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusDot(status = status, size = 12.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = displayName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = if (toolCall.isFailed) "Failed" else "Completed",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Full result card header
        Text(
            text = "FULL RESULT",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (toolCall.isFailed) StatusRed else StatusGreen,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        // Lazy list of flattened result items — only visible entries are composed
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(TileGlass)
                .padding(20.dp),
        ) {
            itemsIndexed(flatItems, key = { index, _ -> index }) { _, item ->
                RenderFlatResultItem(item, onUrlTap)
            }
        }

        // Navigation between tool calls
        if (totalTools > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TileGlass)
                        .then(
                            if (toolIndex > 0) Modifier.clickable(onClick = onPrev)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(Modifier.width(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 0 until totalTools) {
                        val isActive = i == toolIndex
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .then(
                                    if (isActive) Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .width(18.dp)
                                        .background(StatusGreen)
                                    else Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFC4B8A8)),
                                )
                                .clickable { onDotTap(i) },
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TileGlass)
                        .then(
                            if (toolIndex < totalTools - 1) Modifier.clickable(onClick = onNext)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── Flat result item renderer ──
// Renders a single FlatResultItem in the LazyColumn. Indent controls
// horizontal padding to show nesting depth. Each type renders its own
// key-value layout. No recursion. No nested composables.
@Composable
private fun RenderFlatResultItem(
    item: FlatResultItem,
    onUrlTap: (String) -> Unit,
) {
    val indentPadding = (item.indent * 16).dp

    when (item.type) {
        FlatItemType.STRING -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = item.key,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.value,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(start = indentPadding),
                )
            }
        }

        FlatItemType.URL -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = item.key,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.url ?: "",
                    fontSize = 14.sp,
                    color = StatusGreen,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indentPadding)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onUrlTap(item.url ?: "") }
                        .padding(vertical = 4.dp),
                )
            }
        }

        FlatItemType.NUMBER -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = item.key,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.value,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = indentPadding),
                )
            }
        }

        FlatItemType.BOOLEAN -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = item.key,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.value,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
            }
        }

        FlatItemType.NULL -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = item.key,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "null",
                    fontSize = 14.sp,
                    color = TextFaint,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(start = indentPadding),
                )
            }
        }

        FlatItemType.ARRAY_HEADER -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = "${item.key} (${item.value})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        FlatItemType.OBJECT_HEADER -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = item.key,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = indentPadding),
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        FlatItemType.RAW -> {
            Text(
                text = item.value,
                fontSize = 14.sp,
                color = TextPrimary,
                lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 6.dp, horizontal = indentPadding),
            )
        }

        FlatItemType.DIVIDER -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ScrimLine),
            )
        }
    }
}

// ── Level 5: Browser View ──
// Full WebView so the user can browse the actual webpage GURU fetched.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserView(
    url: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        // Top bar with back button and page title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = pageTitle.ifEmpty { "Browser" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = StatusAmber,
                )
            }
        }

        // URL bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(TileGlass)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentUrl,
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        // WebView
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFFFFFFF)),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        isNestedScrollingEnabled = true

                        setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    parent.requestDisallowInterceptTouchEvent(true)
                                }
                                android.view.MotionEvent.ACTION_UP,
                                android.view.MotionEvent.ACTION_CANCEL -> {
                                    parent.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                isLoading = newProgress < 100
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                pageTitle = title ?: ""
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                currentUrl = url ?: currentUrl
                            }
                        }

                        loadUrl(url)
                        webView = this
                    }
                },
                update = { wv ->
                    if (currentUrl != wv.url && !isLoading) {
                        // External URL change from browserUrl state
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Loading overlay
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFFFFFF).copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = StatusAmber,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Action row: Open in browser, Copy link
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TileGlass)
                    .clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl))
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Open in Browser",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TileGlass)
                    .clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", currentUrl))
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Copy Link",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                try {
                    if (wv.parent != null) {
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                    }
                    wv.destroy()
                } catch (_: Throwable) {
                    // Already destroyed
                }
            }
        }
    }
}

// ── Shared status dot ──
@Composable
private fun StatusDot(
    status: ToolCallStatus,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    when (status) {
        ToolCallStatus.RUNNING -> {
            CircularProgressIndicator(
                modifier = modifier.size(size),
                strokeWidth = 2.dp,
                color = StatusAmber,
            )
        }
        ToolCallStatus.DONE -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(StatusGreen),
            )
        }
        ToolCallStatus.FAILED -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(StatusRed),
            )
        }
    }
}