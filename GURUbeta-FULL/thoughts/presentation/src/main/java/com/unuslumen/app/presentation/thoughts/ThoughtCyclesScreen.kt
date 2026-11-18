// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.thoughts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.unuslumen.app.thoughts.domain.model.GuruInsight
import com.unuslumen.app.thoughts.domain.model.GuruThoughtCycle
import com.unuslumen.app.thoughts.domain.model.ThoughtTriggerType
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBodyText
import com.unuslumen.app.ui.theme.MemphisSwitch
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The background mind's own room, in the house language.
 *
 * WHAT IT WAS. The last untouched Material page in the app: a grey rounded stat pill
 * floating over the artwork, rounded 16dp elevated cards on cream, a Material switch in
 * green, a gold-accented "run now" pill with a circle in it, and insight cards with
 * translucent green and red tinted buttons. It was the room that most obviously belonged
 * to a different product.
 *
 * WHAT IT IS NOW. The same page every other room wears: a masthead with a stamp, an ink
 * band per group, paper cards with hard edges and printed drops, the totals as three
 * printed blocks that each carry a colour, the enable switch as a printed rectangle, and
 * run and acknowledge and dismiss as printed cells.
 *
 * Every view model call and every callback is unchanged.
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
    val waiting = summary?.unacknowledgedInsights ?: 0

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = "Thought cycles",
                stamp = if (waiting > 0) "$waiting waiting" else null,
                stampColor = if (waiting > 0) Memphis.Coral else Memphis.Cyan,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp),
        ) {
            // The totals, as three printed blocks that each carry a colour.
            item(key = "totals") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatBlock(
                        modifier = Modifier.weight(1f),
                        number = "${summary?.enabledCycles ?: 0}",
                        label = "Active",
                        ink = Memphis.Yellow,
                    )
                    StatBlock(
                        modifier = Modifier.weight(1f),
                        number = "${summary?.totalRuns ?: 0}",
                        label = "Runs",
                        ink = Memphis.Cyan,
                    )
                    StatBlock(
                        modifier = Modifier.weight(1f),
                        number = "$waiting",
                        label = "Waiting",
                        ink = if (waiting > 0) Memphis.Coral else Memphis.Lilac,
                    )
                }
            }

            item(key = "cycles-band") {
                Band("Your cycles", "${cycles.size}", Modifier.padding(top = 14.dp))
            }

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
                Spacer(Modifier.height(9.dp))
            }

            if (cycles.isEmpty()) {
                item(key = "no-cycles") {
                    Text(
                        text = "NO CYCLES YET. ASK GURU IN CHAT TO MAKE ONE AND IT WRITES THE STEPS ITSELF.",
                        style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.6f)),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }

            lastRunMessage?.takeIf { it.isNotBlank() }?.let { message ->
                item(key = "last-run") {
                    MemphisSurface(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        color = Memphis.Sun,
                        borderWidth = 2.5.dp,
                        shadowOffset = 3.dp,
                    ) {
                        Text(
                            text = message,
                            style = captionStyle(9f),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            item(key = "insights-band") {
                Band("Insights", "${insights.size}", Modifier.padding(top = 16.dp))
            }

            items(insights, key = { it.id }) { insight ->
                InsightCard(
                    insight = insight,
                    onAcknowledge = { viewModel.acknowledgeInsight(insight.id) },
                    onDismiss = { viewModel.dismissInsight(insight.id) }
                )
                Spacer(Modifier.height(9.dp))
            }
        }
    }
}

/** One number and its label, on a coloured printed block. */
@Composable
private fun StatBlock(
    modifier: Modifier = Modifier,
    number: String,
    label: String,
    ink: Color,
) {
    MemphisSurface(
        modifier = modifier,
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(number, style = posterStyle(26f))
            Text(label.uppercase(), style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.7f)))
        }
    }
}

/** An ink bar with the label in paper type and the count on a paper tab. */
@Composable
private fun Band(label: String, note: String?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Memphis.Ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        Text(
            text = note ?: "",
            style = captionStyle(8.5f, Memphis.Ink),
            modifier = Modifier.background(Memphis.Paper).padding(horizontal = 6.dp),
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
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (cycle.enabled) 1f else 0.5f),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = cycle.displayName.uppercase(),
                        style = posterStyle(14f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = triggerLine(cycle).uppercase(),
                        style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f))
                    )
                }
                Spacer(Modifier.width(10.dp))
                MemphisSwitch(
                    checked = cycle.enabled,
                    onChange = onToggle,
                    ink = Memphis.Teal,
                )
            }

            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "${cycle.runCount} RUNS",
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f))
                )
                Text(
                    text = "${cycle.insightCount} INSIGHTS",
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f))
                )
                cycle.lastRunAt?.let {
                    Text(
                        text = "LAST ${timeFormat.format(Date(it))}",
                        style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f))
                    )
                }
            }

            cycle.lastResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text("LAST THOUGHT", style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.6f)))
                Spacer(Modifier.height(2.dp))
                Text(
                    text = result,
                    style = MemphisBodyText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(9.dp))
            MemphisSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isRunning) Modifier else Modifier.clickable { onRun() }),
                color = if (isRunning) Memphis.Paper else Memphis.Ink,
                borderWidth = 2.5.dp,
                shadowOffset = 3.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(7.dp)
                            .height(7.dp)
                            .background(if (isRunning) Memphis.Ink.copy(alpha = 0.4f) else Memphis.Yellow)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (isRunning) "RUNNING…" else "RUN NOW",
                        style = captionStyle(9f, if (isRunning) Memphis.Ink.copy(alpha = 0.4f) else Memphis.Paper)
                    )
                }
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
    val ink = typeColor(insight.type.name)

    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = insight.type.name.uppercase(),
                    style = captionStyle(8f),
                    modifier = Modifier
                        .background(ink)
                        .border(1.5.dp, Memphis.Ink)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = insight.cycleName.ifBlank { "CYCLE" }.uppercase(),
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f))
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = String.format(Locale.getDefault(), "%.2f", insight.confidence),
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.5f))
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(text = insight.title, style = posterStyle(12.5f))
            Spacer(Modifier.height(3.dp))
            Text(text = insight.content, style = MemphisBodyText)
            Spacer(Modifier.height(4.dp))
            Text(
                text = insightTimeFormat.format(Date(insight.createdAt)).uppercase(),
                style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.5f))
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemphisSurface(
                    modifier = Modifier.weight(1f).clickable { onAcknowledge() },
                    color = Memphis.Teal,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("ACKNOWLEDGE", style = captionStyle(8.5f, Memphis.Paper))
                    }
                }
                MemphisSurface(
                    modifier = Modifier.weight(1f).clickable { onDismiss() },
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("DISMISS", style = captionStyle(8.5f))
                    }
                }
            }
        }
    }
}

/** Name-based so it stays correct whatever constants the enum carries. */
private fun typeColor(typeName: String): Color = when (typeName.uppercase(Locale.ROOT)) {
    "ANOMALY" -> Memphis.Coral
    "SUGGESTION" -> Memphis.Teal
    "PREDICTION" -> Memphis.Purple
    else -> Memphis.Cyan
}

private fun triggerLine(cycle: GuruThoughtCycle): String = when (cycle.triggerType) {
    ThoughtTriggerType.SCHEDULED -> "Scheduled on its own interval"
    ThoughtTriggerType.EVENT -> "Fires when the subscribed event happens"
    ThoughtTriggerType.THRESHOLD -> "Fires when its metric crosses the line"
}
