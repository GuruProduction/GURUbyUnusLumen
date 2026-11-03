// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel
import kotlin.math.hypot
import kotlin.math.min

/**
 * The Inner Mind: the whole of what GURU holds, drawn as one tissue.
 *
 * WHY THIS IS NOT A LIST. Every other room is a page of rows because a row is the
 * honest shape for a list of things. This room is about the relationships between
 * things, and a relationship has no row. The map is the only shape that can show
 * that two memories are the same idea twice, or that a task came out of a
 * conversation, without inventing a column to store the sentence in.
 *
 * THE STYLE IS PAPER, NOT A DARK SCREEN. The house is warm paper and ink, and this
 * room is part of the house. The tissue fades by being pale and quiet rather than
 * by being dark: a resting node is a pencil mark, and only what you touch, what
 * changed recently, and the lines running to it come forward in colour.
 *
 * COLOUR IS PER FAMILY AND FIXED. A memory is coral wherever it appears. That is
 * what makes the map learnable: after two visits you know where the memories live
 * without reading a legend.
 */
@Composable
fun InnerMindScreen(
    navController: NavHostController,
    viewModel: InnerMindViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = Color.Transparent) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                guruAppBar(
                    title = "Inner Mind",
                    // The one fact worth printing in the corner: how much of the
                    // mind is on the canvas, against everything that exists.
                    stamp = state.graph?.let { graph ->
                        val total = graph.totalByFamily.values.sum()
                        "${graph.itemCount}/$total"
                    },
                    stampColor = Memphis.Yellow,
                )

                SearchBar(
                    query = state.query,
                    onQuery = { viewModel.onEvent(InnerMindEvent.Search(it)) },
                )

                FamilyRail(
                    visible = state.visibleFamilies,
                    totals = state.graph?.totalByFamily.orEmpty(),
                    onToggle = { viewModel.onEvent(InnerMindEvent.ToggleFamily(it)) },
                )

                Box(Modifier.weight(1f)) {
                    when {
                        state.isLoading -> CentreMessage("Reading the mind…")

                        state.error != null -> CentreMessage(state.error!!)

                        state.nothingVisible -> NothingVisible(
                            onShowAll = { viewModel.onEvent(InnerMindEvent.ShowAllFamilies) }
                        )

                        else -> MindCanvas(
                            state = state,
                            onSelect = { viewModel.onEvent(InnerMindEvent.Select(it)) },
                            onGrow = { viewModel.onEvent(InnerMindEvent.Grow(it)) },
                            onLinkComplete = { viewModel.onEvent(InnerMindEvent.CompleteLink(it)) },
                        )
                    }

                    // The read failures are shown rather than swallowed: a family
                    // that could not be read is a fact about the machine, and a
                    // circle that is simply empty would be a lie about it.
                    state.read?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
                        ReadFailures(failures)
                    }

                    // The card sits over the map rather than over the page, so the
                    // masthead's BACK block stays reachable while it is up. A notice
                    // that traps a person in the room until they acknowledge it is a
                    // worse defect than the thing it is warning about.
                    if (state.developmentNotice) {
                        DevelopmentNotice(
                            onDismiss = {
                                viewModel.onEvent(InnerMindEvent.DismissDevelopmentNotice)
                            }
                        )
                    }
                }
            }

            state.selectedNode?.let { node ->
                NodePanel(
                    node = node,
                    linkCount = state.graph?.edges?.count { it.touchesNode(node.id) } ?: 0,
                    onClose = { viewModel.onEvent(InnerMindEvent.Select(null)) },
                    onBeginLink = { viewModel.onEvent(InnerMindEvent.BeginLink) },
                )
            }

            state.linkingFromId?.let {
                LinkingBanner(
                    label = state.graph?.nodes?.firstOrNull { node -> node.id == it }?.label.orEmpty(),
                    onCancel = { viewModel.onEvent(InnerMindEvent.CancelLink) },
                )
            }

            state.notice?.let { notice ->
                NoticeBar(notice = notice, onDismiss = { viewModel.onEvent(InnerMindEvent.ClearNotice) })
            }
        }
    }
}

/**
 * The card that says this room is unfinished.
 *
 * WHY IT IS A CARD AND NOT A LINE. The room's search, chips and zoom controls are
 * instruments floating over the canvas, so a passing line of text would read as
 * another instrument. A printed block with the room's own border and drop reads as
 * a note pinned over the page, which is what it is.
 *
 * IT COVERS THE CANVAS ON PURPOSE. The map is the thing that looks finished, so a
 * message in its margin would be read after the first impression it is meant to
 * correct. The masthead stays clear above it, because the door out of the room
 * must not be behind an acknowledgement.
 */
@Composable
private fun DevelopmentNotice(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        MemphisSurface(
            modifier = Modifier.fillMaxWidth(),
            color = Memphis.Yellow,
            borderWidth = 3.dp,
            shadowOffset = 7.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "IN DEVELOPMENT",
                    style = posterStyle(26f),
                )

                Text(
                    text = "This room is still being built. You can look around the map, " +
                        "but editing, deleting and pulling in the hidden rows are not " +
                        "finished yet, so treat it as a preview for now.",
                    style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.75f)).copy(
                        fontFamily = com.unuslumen.app.ui.theme.MemphisBody,
                        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                    ),
                    modifier = Modifier.padding(top = 12.dp),
                )

                PanelButton(
                    label = "Got it",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    onClick = onDismiss,
                )

                Text(
                    text = "THIS WILL NOT BE SHOWN AGAIN",
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The floating search, drawn as one more block in the page. */
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search the mind", style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.45f))) },
            textStyle = captionStyle(10f),
            shape = CircleShape,
        )
    }
}

/** The horizontal strip of family chips. */
@Composable
private fun FamilyRail(
    visible: Set<MindFamily>,
    totals: Map<MindFamily, Int>,
    onToggle: (MindFamily) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MindFamily.entries.forEach { family ->
            val on = family in visible
            val ink = MindStyle.inkFor(family)
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .border(2.dp, Memphis.Ink, CircleShape)
                    .background(if (on) Memphis.Paper else Memphis.Paper.copy(alpha = 0.4f))
                    .clickable { onToggle(family) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) ink else ink.copy(alpha = 0.25f)))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = family.label.uppercase(),
                    style = captionStyle(8f, if (on) Memphis.Ink else Memphis.Ink.copy(alpha = 0.3f)),
                )
                val total = totals[family] ?: 0
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "$total",
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = if (on) 0.5f else 0.22f)),
                )
            }
        }
    }
}

/**
 * The canvas.
 *
 * PAN AND ZOOM ARE ONE GESTURE HANDLER. [detectTransformGestures] delivers pan,
 * zoom and rotation together, which is exactly what a two-finger pinch on this
 * canvas should do: the same finger movement can move and scale the map without
 * the person choosing a mode first.
 *
 * A TAP IS A HIT TEST IN GRAPH SPACE. The tap arrives in pixels; it is walked back
 * through the same scale and offset the drawing used, then the nearest node
 * within a generous radius wins. The radius is generous because this is a phone
 * and a five-pixel dot under a finger is not a target.
 */
@Composable
private fun MindCanvas(
    state: InnerMindState,
    onSelect: (String?) -> Unit,
    onGrow: (String) -> Unit,
    onLinkComplete: (String) -> Unit,
) {
    val layout = state.layout ?: return
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val visibleIds = remember(state.visibleNodes) { state.visibleNodes.map { it.id }.toSet() }
    val selectedId = state.selectedId
    val linking = state.linkingFromId

    val positioned = remember(layout, visibleIds) {
        layout.placed.filter { it.node.id in visibleIds }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(layout, visibleIds, scale, offset, linking) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset += pan
                    }
                }
                .pointerInput(layout, visibleIds, scale, offset) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size != 1) continue
                            val change = event.changes.first()
                            if (!change.pressed) continue
                            val released = change.previousPressed && !change.pressed
                            if (!released) continue

                            val hit = hitTest(
                                tap = change.position,
                                nodes = positioned,
                                bounds = layout.bounds,
                                canvas = canvasSize,
                                scale = scale,
                                offset = offset,
                            )
                            if (hit == null) {
                                onSelect(null)
                            } else {
                                when {
                                    linking != null -> onLinkComplete(hit.node.id)
                                    hit.node.kind == NodeKind.GROW -> onGrow(hit.node.id)
                                    else -> onSelect(hit.node.id)
                                }
                            }
                        }
                    }
                },
        ) {
            drawTissue(
                layout = layout,
                nodes = positioned,
                edges = state.graph?.edges.orEmpty(),
                selectedId = selectedId,
                scale = scale,
                offset = offset,
            )
        }

        ZoomControls(
            onIn = { scale = (scale * 1.3f).coerceIn(MIN_SCALE, MAX_SCALE) },
            onOut = { scale = (scale / 1.3f).coerceIn(MIN_SCALE, MAX_SCALE) },
            onReset = { scale = 1f; offset = Offset.Zero },
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
        )

        Text(
            text = if (linking != null) "TAP A NODE TO JOIN IT" else "DRAG TO PAN · PINCH TO ZOOM",
            style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.3f)),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 18.dp),
        )
    }
}

private const val MIN_SCALE = 0.55f
private const val MAX_SCALE = 4f

/** How far from a node's centre a tap still counts, in graph units. */
private const val TAP_SLOP = 26f

/**
 * The nearest node to a tap, or null when the tap landed on nothing.
 *
 * The whole picture is drawn through a fit transform, so the tap has to be walked
 * back through it before it can be compared with node positions. Distance is
 * measured with the tap slop added to the node's own radius, so a small dot is as
 * easy to hit as a large one.
 */
private fun hitTest(
    tap: Offset,
    nodes: List<PlacedNode>,
    bounds: MindBounds,
    canvas: IntSize,
    scale: Float,
    offset: Offset,
): PlacedNode? {
    if (canvas.width == 0 || canvas.height == 0) return null
    val fit = fitScale(bounds, canvas)
    val screenCentre = Offset(canvas.width / 2f, canvas.height / 2f)
    val graphCentre = Offset(bounds.centreX, bounds.centreY)

    var best: PlacedNode? = null
    var bestDistance = Float.MAX_VALUE

    nodes.forEach { node ->
        val screen = Offset(
            x = screenCentre.x + (node.position.x - graphCentre.x) * fit * scale + offset.x,
            y = screenCentre.y + (node.position.y - graphCentre.y) * fit * scale + offset.y,
        )
        val distance = hypot(tap.x - screen.x, tap.y - screen.y)
        val reach = (node.radius * fit * scale) + TAP_SLOP
        if (distance <= reach && distance < bestDistance) {
            best = node
            bestDistance = distance
        }
    }
    return best
}

/** The scale that fits the whole graph inside the canvas with its padding. */
private fun fitScale(bounds: MindBounds, canvas: IntSize): Float {
    val padded = bounds.padded(MindLayoutEngine.FIT_PADDING)
    val width = padded.width.coerceAtLeast(1f)
    val height = padded.height.coerceAtLeast(1f)
    return min(canvas.width / width, canvas.height / height)
}

/**
 * Draw the whole tissue.
 *
 * Order matters and is the whole design: the resting web first, then the lit web,
 * then the nodes on top. Anything drawn later covers what came before, so a node
 * is never hidden behind a line.
 */
private fun DrawScope.drawTissue(
    layout: MindLayout,
    nodes: List<PlacedNode>,
    edges: List<MindEdge>,
    selectedId: String?,
    scale: Float,
    offset: Offset,
) {
    val canvas = IntSize(size.width.toInt(), size.height.toInt())
    val fit = fitScale(layout.bounds, canvas)
    val screenCentre = Offset(size.width / 2f, size.height / 2f)
    val graphCentre = Offset(layout.bounds.centreX, layout.bounds.centreY)

    fun screenOf(point: MindPoint): Offset = Offset(
        x = screenCentre.x + (point.x - graphCentre.x) * fit * scale + offset.x,
        y = screenCentre.y + (point.y - graphCentre.y) * fit * scale + offset.y,
    )

    val byId = nodes.associateBy { it.node.id }
    val neighbours = if (selectedId == null) emptySet() else {
        edges.filter { it.touchesNode(selectedId) }
            .mapNotNull { edge ->
                when (selectedId) {
                    edge.fromId -> edge.toId
                    edge.toId -> edge.fromId
                    else -> null
                }
            }
            .toSet() + selectedId
    }

    // The resting web: every edge, faintly, so the shape of the tissue is visible
    // before you touch anything.
    edges.forEach { edge ->
        val from = byId[edge.fromId] ?: return@forEach
        val to = byId[edge.toId] ?: return@forEach
        val lit = selectedId != null && (edge.fromId in neighbours && edge.toId in neighbours)

        drawSynapse(
            start = screenOf(from.position),
            end = screenOf(to.position),
            colour = if (lit) MindStyle.inkFor(from.node.family) else MindStyle.RestingRim,
            width = if (lit) MindStyle.ACTIVE_WIDTH else MindStyle.SPOKE_WIDTH,
            alpha = if (lit) MindStyle.ACTIVE_LINE_ALPHA else MindStyle.RESTING_LINE_ALPHA,
        )
    }

    nodes.forEach { placed ->
        val node = placed.node
        val isSelected = node.id == selectedId
        val isNeighbour = node.id in neighbours
        val ink = when (node.kind) {
            NodeKind.CORE -> MindStyle.Core
            else -> MindStyle.inkFor(node.family)
        }

        val centre = screenOf(placed.position)
        val radius = (placed.radius * fit * scale).coerceAtLeast(MIN_DRAWN_RADIUS)

        val fillAlpha = when {
            isSelected -> 1f
            isNeighbour -> 0.8f
            node.kind == NodeKind.CORE -> 1f
            node.kind == NodeKind.HUB -> 0.75f
            node.kind == NodeKind.GROW -> 0.1f
            else -> MindStyle.RESTING_FILL_ALPHA
        }
        val rimAlpha = when {
            isSelected -> 1f
            isNeighbour -> 0.9f
            node.kind == NodeKind.GROW -> 0.35f
            else -> MindStyle.RESTING_RIM_ALPHA
        }

        if (node.kind == NodeKind.CORE) {
            // The centre is an ink block rather than a disc, because it is not a
            // thing among things, it is the mind they belong to.
            val half = radius
            drawRect(
                color = MindStyle.Core,
                topLeft = Offset(centre.x - half, centre.y - half * 0.75f),
                size = androidx.compose.ui.geometry.Size(half * 2f, half * 1.5f),
            )
        } else if (node.kind == NodeKind.GROW) {
            drawCircle(
                color = ink.copy(alpha = fillAlpha),
                radius = radius,
                center = centre,
                style = Stroke(width = 1.5f),
            )
        } else {
            drawCircle(color = ink.copy(alpha = fillAlpha), radius = radius, center = centre)
            drawCircle(
                color = ink.copy(alpha = rimAlpha),
                radius = radius,
                center = centre,
                style = Stroke(width = if (isSelected) 2.5f else 1.5f),
            )
        }

        if (isSelected) {
            drawCircle(
                color = ink.copy(alpha = 0.5f),
                radius = radius + 6f,
                center = centre,
                style = Stroke(width = 1.2f),
            )
        }

        // A label only where it can be read: a hub always, a member once the map
        // is close enough or the member is the point of the tap.
        val showLabel = node.kind == NodeKind.HUB ||
            node.kind == NodeKind.CORE ||
            isSelected ||
            isNeighbour ||
            radius > LABEL_RADIUS_THRESHOLD

        if (showLabel) {
            drawNodeLabel(
                text = if (node.kind == NodeKind.CORE) "GURU" else node.label,
                centre = centre,
                radius = radius,
                alpha = if (isSelected || isNeighbour) 1f else MindStyle.RESTING_LABEL_ALPHA,
            )
        }
    }
}

/** A drawn node never becomes smaller than this, or it stops being a target. */
private const val MIN_DRAWN_RADIUS = 3.5f

/** Past this drawn radius a member's name is worth printing. */
private const val LABEL_RADIUS_THRESHOLD = 11f

/**
 * One relationship as a curve rather than a straight line.
 *
 * A brain is not wired in straight lines, and a straight line between two
 * unrelated-looking points reads as a table of contents. The bow is proportional
 * to the distance so a long edge curves gently and a short one hardly at all,
 * which keeps a dense cluster from turning into a knot.
 */
private fun DrawScope.drawSynapse(
    start: Offset,
    end: Offset,
    colour: Color,
    width: Float,
    alpha: Float,
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx, dy)
    if (length < 0.5f) return

    val bow = min(46f, length * 0.17f)
    val midX = (start.x + end.x) / 2f - (dy / length) * bow
    val midY = (start.y + end.y) / 2f + (dx / length) * bow

    val path = Path().apply {
        moveTo(start.x, start.y)
        quadraticTo(midX, midY, end.x, end.y)
    }
    drawPath(path, color = colour.copy(alpha = alpha), style = Stroke(width = width))
}

/** A node's name, printed with a paper halo so it survives on top of a line. */
private fun DrawScope.drawNodeLabel(
    text: String,
    centre: Offset,
    radius: Float,
    alpha: Float,
) {
    val trimmed = if (text.length > 22) text.take(21) + "\u2026" else text
    val canvas = drawContext.canvas.nativeCanvas

    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb((alpha * 255).toInt(), 18, 16, 14)
        textSize = 22f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD,
        )
    }

    // The halo: paper behind the glyphs so a label crossing a line stays legible.
    val halo = android.graphics.Paint(paint).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6f
        color = android.graphics.Color.argb((alpha * 235).toInt(), 255, 251, 240)
    }

    val baseline = centre.y + radius + 26f
    canvas.drawText(trimmed, centre.x, baseline, halo)
    canvas.drawText(trimmed, centre.x, baseline, paint)
}

/** The three floating zoom controls. */
@Composable
private fun ZoomControls(
    onIn: () -> Unit,
    onOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZoomButton("+", onIn)
        ZoomButton("\u2212", onOut)
        ZoomButton("\u25CE", onReset, small = true)
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit, small: Boolean = false) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Memphis.Paper)
            .border(2.dp, Memphis.Ink, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = posterStyle(if (small) 11f else 15f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The panel for whatever is selected.
 *
 * A hub and a fold get the same panel as a row, because they are all things on
 * this map and a person who tapped one deserves to be told what it is. Editing and
 * deleting are only offered where they mean something: a hub is not a row and has
 * nothing to delete.
 */
@Composable
private fun NodePanel(
    node: MindNode,
    linkCount: Int,
    onClose: () -> Unit,
    onBeginLink: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Memphis.Ink.copy(alpha = 0.18f))
            .clickable { onClose() },
    ) {
        MemphisSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .heightIn(max = 460.dp)
                .clickable(enabled = false) {},
            color = Memphis.Paper,
            borderWidth = 2.5.dp,
            shadowOffset = 6.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val ink = if (node.kind == NodeKind.CORE) MindStyle.Core else MindStyle.inkFor(node.family)
                        Box(Modifier.size(9.dp).clip(CircleShape).background(ink).border(1.dp, Memphis.Ink, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when (node.kind) {
                                NodeKind.CORE -> "THE CENTRE"
                                NodeKind.HUB -> "${node.family.label.uppercase()} HUB"
                                NodeKind.GROW -> "${node.family.singular.uppercase()} FOLD"
                                NodeKind.ITEM -> node.family.singular.uppercase()
                            },
                            style = captionStyle(8.5f),
                        )
                    }
                    Text(
                        text = "CLOSE",
                        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable { onClose() },
                    )
                }

                Text(
                    text = node.label,
                    style = posterStyle(20f),
                    modifier = Modifier.padding(top = 12.dp),
                )

                Text(
                    text = node.body,
                    style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.72f)).copy(
                        fontFamily = com.unuslumen.app.ui.theme.MemphisBody,
                        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                    ),
                    modifier = Modifier.padding(top = 10.dp),
                )

                if (node.facts.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        node.facts.take(3).forEach { fact ->
                            Text(
                                text = fact,
                                style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .border(1.5.dp, Memphis.Ink.copy(alpha = 0.35f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Text(
                    text = "$linkCount ${if (linkCount == 1) "LINK" else "LINKS"}",
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.4f)),
                    modifier = Modifier.padding(top = 12.dp),
                )

                if (node.kind == NodeKind.ITEM) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PanelButton("Link", Modifier.weight(1f), onBeginLink)
                        PanelButton("Close", Modifier.weight(1f), onClose)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(Memphis.Paper)
            .border(2.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = captionStyle(9f))
    }
}

/** The banner shown while a link is being drawn. */
@Composable
private fun LinkingBanner(label: String, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            color = Memphis.Yellow,
            borderWidth = 2.5.dp,
            shadowOffset = 4.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "JOINING FROM ${label.uppercase().take(28)}. TAP ANOTHER NODE.",
                    style = captionStyle(8.5f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "CANCEL",
                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                    modifier = Modifier.clickable { onCancel() },
                )
            }
        }
    }
}

/** A one-line notice, dismissed by tapping. */
@Composable
private fun NoticeBar(notice: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 76.dp)
                .clickable { onDismiss() },
            color = Memphis.Cyan,
            borderWidth = 2.5.dp,
            shadowOffset = 4.dp,
        ) {
            Text(
                text = notice,
                style = captionStyle(9f),
                modifier = Modifier.padding(11.dp),
            )
        }
    }
}

/** The families that could not be read, said plainly. */
@Composable
private fun ReadFailures(failures: List<MindReadFailure>) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            color = Memphis.Coral,
            borderWidth = 2.5.dp,
            shadowOffset = 3.dp,
        ) {
            Column(Modifier.padding(9.dp)) {
                failures.forEach { failure ->
                    Text(
                        text = "${failure.family.label.uppercase()}: ${failure.reason}",
                        style = captionStyle(8f, Memphis.Paper),
                    )
                }
            }
        }
    }
}

/** Every family silenced. */
@Composable
private fun NothingVisible(onShowAll: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NOTHING SHOWING", style = captionStyle(10f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Every family is switched off. Turn one back on to see the mind.",
            style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.55f)),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        PanelButton("Show everything", Modifier.width(180.dp), onShowAll)
    }
}

@Composable
private fun CentreMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.55f)),
            modifier = Modifier.padding(24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Whether an edge joins a given node. */
private fun MindEdge.touchesNode(id: String): Boolean = fromId == id || toId == id
