package com.unuslumen.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.R

enum class ThinkingDisplayLevel {
    OFF, LOW, MEDIUM, HIGH, XHIGH
}

@Composable
fun ThinkingBlockCard(
    thinkingTokens: String,
    displayLevel: ThinkingDisplayLevel,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (displayLevel == ThinkingDisplayLevel.OFF || thinkingTokens.isBlank()) return

    var expanded by remember { mutableStateOf(displayLevel == ThinkingDisplayLevel.HIGH || displayLevel == ThinkingDisplayLevel.XHIGH) }

    val isCollapsed = !expanded
    val showContent = when (displayLevel) {
        ThinkingDisplayLevel.OFF -> false
        ThinkingDisplayLevel.LOW -> false // just the indicator, no content
        ThinkingDisplayLevel.MEDIUM, ThinkingDisplayLevel.HIGH, ThinkingDisplayLevel.XHIGH -> true
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (showContent) expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_otio_placeholder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isStreaming) stringResource(id = R.string.thinking_block_card_thinking)
                    else stringResource(id = R.string.thinking_block_card_thought_process),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
                if (isStreaming) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    )
                }
            }

            // Content area
            if (showContent) {
                if (isCollapsed) {
                    // Collapsed: show a few lines with gradient fade
                    val fadeColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    Text(
                        text = thinkingTokens,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .drawBehind {
                                if (thinkingTokens.lines().size > 3) {
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                fadeColor,
                                            ),
                                            startY = size.height * 0.4f,
                                            endY = size.height,
                                        ),
                                    )
                                }
                            },
                    )
                } else {
                    // Expanded: full scrollable view
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = thinkingTokens,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}
