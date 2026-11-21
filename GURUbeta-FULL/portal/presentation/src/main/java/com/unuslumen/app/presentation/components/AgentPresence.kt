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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.unuslumen.app.ui.theme.MemphisSurface
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
    /**
     * Whether anything in this run's conversation is waiting to be read.
     *
     * A DIFFERENT FACT FROM THE STATUS, WITH A DIFFERENT LIFETIME. The glow clears when the run ends;
     * this survives it, because a message that arrived a second before the run finished is still
     * unread after it is gone. Driving both from the status is how a dot vanished unread.
     */
    val hasUnread: Boolean = false,
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

/**
 * The Portal's ambient presence: a coloured edge bloom and one tappable mark, and nothing else.
 *
 * WHY THERE IS NO STRIP, NO CHIP AND NO LINE OF TEXT. This is the specification's section 9.1 and the
 * owner's ruling of 2026-11-19 in the same breath: while an agent is active the Portal shows a glow
 * around the screen in that agent's colour and one thing to tap into its room. Every fact — the name,
 * the state, the rounds, the tools — belongs on the dashboard one tap away, because the Portal is the
 * person's conversation with GURU and an agent's presence in it must be ambient and wordless.
 *
 * THE GLOW CARRIES THE STATE, WHICH IS WHY IT IS NOT DECORATION. It is drawn in the agent's colour and
 * breathes only while work is genuine; a run waiting on a person is a still glow, and a run waiting on
 * an answer is a still glow in a different colour. A pulse over a run that is not moving would say
 * "busy" about something that is waiting for the person.
 *
 * GONE WHEN NOTHING IS RUNNING. No bloom, no mark, no placeholder. A permanent indicator of an absence
 * is a fact nobody needs while GURU is answering a question.
 *
 * @param node the single live run, or null when no agent is executing. Only one is drawn because the
 *   runtime admits one concurrent run today, and pretending otherwise would imply work that is not
 *   happening; when concurrency arrives this takes the most urgent node and says so.
 */
@Composable
fun AgentPortalGlow(
    node: AgentPresenceNode?,
    onOpen: (AgentPresenceNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (node == null) return

    val ink = Memphis.inkFor(node.colourIndex)
    /*
     * THE PULSE IS THE DIFFERENCE BETWEEN "WORKING" AND "STOPPED", and the ceiling is low on purpose.
     *
     * THE FIRST VERSION WAS A SOLID FRAME, AND THE REASON WAS ARITHMETIC. It drew four bands, each
     * six per cent of the screen wide, at up to 85 per cent alpha, so what reached the glass was a
     * flat opaque border in a saturated colour. That reads as a phone case, not as light. A bloom is
     * many thin layers near the rim and nothing further in, at an alpha low enough that the layer
     * count does the work rather than the opacity.
     */
    val transition = rememberInfiniteTransition(label = "portalGlow")
    val bloom by transition.animateFloat(
        initialValue = if (node.isExecuting) 0.10f else 0.22f,
        targetValue = if (node.isExecuting) 0.34f else 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "portalGlowAlpha",
    )

    /*
     * THE EDGE, AS NESTED OUTLINES RATHER THAN FOUR FLAT BANDS.
     *
     * Each ring is a rectangle stroke one step further in, at an alpha that rises as it approaches
     * the rim. Lines that get denser toward the edge accumulate into a smooth falloff, which is what
     * the eye reads as light coming from behind the glass. Four rectangular gradients could never do
     * that: their corners were the darkest point of the picture.
     *
     * It takes no touch anywhere, because the Portal underneath is the person's conversation and must
     * stay usable while an agent works.
     */
    Box(modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxSize(),
        ) {
            /*
             * ONE RADIAL DRAW, NOT FOURTEEN RECTANGLES.
             *
             * THE RECTANGLES WERE THE BOXINESS. Fourteen nested `drawRect` strokes produced a band
             * whose corners were the darkest point on the screen, because every rectangle's corner
             * sits further from the centre than its midpoint and the strokes therefore accumulate
             * hardest exactly where a bloom should be faintest. On the glass it read as a frame
             * around the phone.
             *
             * A radial gradient has no corners. It is transparent through the middle, rises to the
             * rim, and its radius is measured to the CORNER so the falloff is even all the way round
             * rather than clipped into a rectangle halfway up the falloff. One draw call, one shader,
             * and the shape of the light is the shape of the light rather than the shape of a box.
             *
             * The vignette is drawn four times at decreasing radii so the ramp is dense near the rim,
             * which is what makes it read as coming from behind the glass instead of as a tinted
             * overlay. Each pass is one draw, so this is four calls where it used to be fourteen.
             */
            val corner = kotlin.math.hypot(size.width, size.height) / 2f
            val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            for (pass in 0 until 4) {
                // 1.0 at the outermost pass down to 0.55: each ring's transparent inner stop sits
                // further in, so the outer few percent of the screen carries the densest colour.
                val scale = 1f - pass * 0.15f
                val radius = corner * scale
                val stroke = corner * 0.16f
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to ink.copy(alpha = 0f),
                            // Rises late, so the middle of the screen stays clean and the last few
                            // percent carry the light.
                            0.78f to ink.copy(alpha = bloom * 0.12f),
                            0.94f to ink.copy(alpha = bloom * 0.55f),
                            1.00f to ink.copy(alpha = 0f),
                        ),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                    // STROKE, NOT FILL, AND WIDE ENOUGH TO CARRY THE RAMP. A filled radial would
                    // tint the whole conversation; a hairline stroke would band. This covers the
                    // outer slice of the screen and nothing else.
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
            }
        }

        /*
         * THE ONE TAP TARGET, AND IT SAYS NOTHING.
         *
         * IT WAS 18dp AT 10dp FROM THE CORNER, WHICH PUT IT UNDER THE STATUS BAR ICONS. That is why it
         * could not be tapped: the system's own clock, wifi and battery sat on top of it. It now
         * clears the status bar, has a real 48dp touch area with a 26dp mark inside, and carries a
         * border and a drop so it reads as something pressable rather than a stray dot.
         */
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 10.dp, end = 10.dp)
                .size(48.dp)
                .clickable(onClick = { onOpen(node) }),
            contentAlignment = Alignment.Center,
        ) {
            MemphisSurface(
                modifier = Modifier.size(26.dp),
                color = ink,
                borderWidth = 2.5.dp,
                shadowOffset = 3.dp,
            ) {}

            /*
             * THE UNREAD DOT, AND WHY IT IS NOT THE GLOW.
             *
             * THE GLOW SAYS "WORK IS HAPPENING" AND THE DOT SAYS "SOMETHING WAS SAID TO YOU". They
             * are different facts with different lifetimes: the glow clears the instant a run ends,
             * and the dot survives it, because a message that arrived one second before the run
             * finished is still unread after the run is gone. Driving both from the run status is
             * how the dot used to disappear under a person's eyes having never been read.
             *
             * A CONVERSATION, NOT AN AGENT, DECIDES IT. Reading one thread must not clear another's
             * mark, which is exactly what an agent-keyed watermark does once an agent has two.
             */
            if (node.hasUnread) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(12.dp)
                        .background(Memphis.Coral)
                        .border(2.dp, Memphis.Ink),
                )
            }
        }
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
