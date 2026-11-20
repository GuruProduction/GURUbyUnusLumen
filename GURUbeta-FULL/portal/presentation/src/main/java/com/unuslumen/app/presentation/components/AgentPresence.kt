// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.captionStyle

/**
 * The agent presence strip in the Portal.
 *
 * WHAT THIS REPLACES. A row of four fixed chips reading CODER, GRAFTER, WINGMAN and SLIK, drawn
 * from a hardcoded enum. That violated the specification's section 12 outright and, worse, told a
 * person those were the agents that existed rather than what was actually running.
 *
 * THIS SHOWS ACTUAL RUNS. Each node is one live run, its colour comes from the agent definition
 * rather than the list's index, and its state is the run's own status. When nothing is running
 * there is no strip at all, which is the honest answer.
 *
 * THE PULSE IS STATE, NOT DECORATION. It breathes only while the work is genuinely executing, so a
 * waiting run is visibly still. A dot that pulsed for a run blocked on an answer would say "busy"
 * about something that is waiting for the person.
 */
data class AgentPresenceNode(
    val runId: String,
    val agentId: String,
    val agentName: String,
    val colourIndex: Int,
    val status: String,
) {
    /** Is work actually executing, as opposed to waiting or finished? */
    val isExecuting: Boolean
        get() = status == AgentRunStatuses.RUNNING || status == AgentRunStatuses.STARTING

    /** One short word for the state. The room has the detail; this is the door. */
    val stateLabel: String
        get() = when (status) {
            AgentRunStatuses.WAITING_FOR_GURU -> "ASKING"
            AgentRunStatuses.WAITING_FOR_USER -> "NEEDS YOU"
            AgentRunStatuses.WAITING_FOR_TOOL -> "WAITING ON A TOOL"
            AgentRunStatuses.PAUSED -> "PAUSED"
            AgentRunStatuses.INTERRUPTED -> "INTERRUPTED"
            AgentRunStatuses.RUNNING -> "WORKING"
            AgentRunStatuses.STARTING -> "STARTING"
            AgentRunStatuses.CREATED -> "STARTING"
            else -> status
        }
}

@Composable
fun AgentPresenceStrip(
    nodes: List<AgentPresenceNode>,
    onOpen: (AgentPresenceNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // NOTHING RUNNING MEANS NO STRIP. A permanent header saying "no agents" is a fact nobody needs
    // while GURU is answering a question.
    if (nodes.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(nodes, key = { it.runId }) { node ->
            AgentPresenceChip(node, onOpen = { onOpen(node) })
        }
    }
}

@Composable
private fun AgentPresenceChip(node: AgentPresenceNode, onOpen: () -> Unit) {
    val ink = Memphis.inkFor(node.colourIndex)

    // THE PULSE ONLY MEANS SOMETHING IF IT STOPS. A node that always breathes says nothing about
    // whether the work is moving, so only an executing run animates and a waiting one is static.
    val transition = rememberInfiniteTransition(label = "presence")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "presenceAlpha",
    )

    Row(
        modifier = Modifier
            .border(2.5.dp, Memphis.Ink)
            .background(Memphis.Paper)
            .clickable { onOpen() }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(ink)
                .then(if (node.isExecuting) Modifier.alpha(pulse) else Modifier),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            node.agentName.uppercase(),
            style = captionStyle(8f, Memphis.Ink),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            node.stateLabel,
            style = captionStyle(7f, Memphis.Ink.copy(alpha = 0.55f)),
        )
    }
}
