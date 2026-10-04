// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.thoughts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.navigation.NavHostController
import com.unuslumen.app.thoughts.domain.model.GuruInsight
import com.unuslumen.app.thoughts.domain.model.GuruThoughtCycle
import com.unuslumen.app.thoughts.domain.model.ThoughtTriggerType
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DividerSlate = Color(0xFF9A9384)
private val GoldAccent = Color(0xFFDAA520)
private val PaperCard = Color(0xFFF0E8DA)

/**
 * Thought Cycles overview — the background mind's own room, per the approved
 * design: portal paper gradient, small-caps header with ink divider, totals
 * strip, cycle cards (plain-English trigger line, counts, last thought,
 * run-now, enable toggle), then the mixed insights feed with acknowledge and
 * dismiss. One scrollable list so no list nests inside another. Light warm
 * paper only — these screens never render a dark variant.
 */
@Composable
fun ThoughtCyclesScreen(
    navController: NavHostController,
    viewModel: ThoughtsViewModel = koinViewModel(),
) {
    val cycles by viewModel.cycles.collectAsState()
    val insights by viewModel.insights.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val runningCycleId by viewModel.runningCycleId.collectAsState()
    val lastRunMessage by viewModel.lastRunMessage.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFF0E8DA).copy(alpha = 0.9f),
                            Color(0xFFE4D9C6).copy(alpha = 0.6f)
                        )
                    )
                )
        )

        LazyColumn(Modifier.fillMaxSize()) {
            // Header
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, start = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "‹",
                        style = MaterialTheme.typography.titleLarge.copy(color = DividerSlate),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { navController.popBackStack() }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                    Text(
                        text = "THOUGHT CYCLES",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.26.em
                        )
                    )
                }
                Box(
                    Modifier
                        .padding(start = 54.dp, top = 2.dp)
                        .width(38.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DividerSlate)
                )
                Spacer(Modifier.height(12.dp))
            }

            // Totals strip
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(PaperCard)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StripItem("${summary?.enabledCycles ?: 0}", "Cycles active")
                    StripItem("${summary?.totalRuns ?: 0}", "Runs total")
                    StripItem(
                        "${summary?.unacknowledgedInsights ?: 0}",
                        "Waiting",
                        warn = (summary?.unacknowledgedInsights ?: 0) > 0
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            // Cycle cards
            items(cycles, key = { it.id }) { cycle ->
                ThoughtCycleCard(
                    cycle = cycle,
                    timeFormat = timeFormat,
                    isRunning = runningCycleId == cycle.id,
                    onRun = { viewModel.runCycle(cycle.id) },
                    onToggle = { enabled ->
                        if (enabled) viewModel.enableCycle(cycle.id)
                        else viewModel.disableCycle(cycle.id)
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            if (cycles.isEmpty()) {
                item {
                    Text(
                        text = "No cycles yet. Ask Guru in chat to create one — it writes the steps itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }

            if (!lastRunMessage.isNullOrBlank()) {
                item {
                    Text(
                        text = lastRunMessage!!,
                        style = MaterialTheme.typography.labelMedium.copy(color = GoldAccent),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                    )
                }
            }

            // Insights feed
            item {
                Text(
                    text = "INSIGHTS",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = DividerSlate,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.18.em
                    ),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }
            items(insights, key = { it.id }) { insight ->
                InsightCard(
                    insight = insight,
                    onAcknowledge = { viewModel.acknowledgeInsight(insight.id) },
                    onDismiss = { viewModel.dismissInsight(insight.id) }
                )
                Spacer(Modifier.height(10.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StripItem(number: String, label: String, warn: Boolean = false) {
    Column {
        Text(
            text = number,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = if (warn) Color(0xFFD53A2F) else MaterialTheme.colorScheme.onBackground
            )
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                letterSpacing = 0.08.em
            )
        )
    }
}

@Composable
private fun ThoughtCycleCard(
    cycle: GuruThoughtCycle,
    timeFormat: SimpleDateFormat,
    isRunning: Boolean,
    onRun: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(4.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = PaperCard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .alpha(if (cycle.enabled) 1f else 0.45f)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = cycle.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = triggerLine(cycle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF5B7C99)
                        )
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = cycle.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF1E9651)
                    )
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "${cycle.runCount} runs",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Text(
                    text = "${cycle.insightCount} insights",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                cycle.lastRunAt?.let {
                    Text(
                        text = "last: ${timeFormat.format(Date(it))}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            cycle.lastResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "LAST THOUGHT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = GoldAccent,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GoldAccent.copy(alpha = 0.15f))
                    .clickable(enabled = !isRunning, onClick = onRun)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) GoldAccent else Color(0xFF1E9651))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "RUNNING…" else "RUN NOW",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = GoldAccent,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

@Composable
private fun InsightCard(
    insight: GuruInsight,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val insightTimeFormat = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    Card(
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.elevatedCardElevation(3.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = PaperCard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = insight.type.name.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = typeColor(insight.type.name)
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = insight.cycleName.ifBlank { "cycle" },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = String.format(Locale.getDefault(), "%.2f", insight.confidence),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = insight.content,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = insightTimeFormat.format(Date(insight.createdAt)),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E9651).copy(alpha = 0.12f))
                        .clickable(onClick = onAcknowledge)
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ACKNOWLEDGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFF1E9651),
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFD53A2F).copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "DISMISS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFFD53A2F),
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/** Name-based so it stays correct whatever constants the enum carries. */
private fun typeColor(typeName: String): Color = when (typeName.uppercase(Locale.ROOT)) {
    "ANOMALY" -> Color(0xFFD53A2F)
    "SUGGESTION" -> Color(0xFF1E9651)
    "PREDICTION" -> Color(0xFF6F4CAD)
    else -> Color(0xFF5B7C99)
}

private fun triggerLine(cycle: GuruThoughtCycle): String = when (cycle.triggerType) {
    ThoughtTriggerType.SCHEDULED -> "Scheduled · on its configured interval"
    ThoughtTriggerType.EVENT -> "Event · fires when the subscribed event happens"
    ThoughtTriggerType.THRESHOLD -> "Threshold · fires when its metric crosses the line"
}