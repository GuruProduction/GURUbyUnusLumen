// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in graph space, in the same units as [MindLayout]'s radii. */
data class MindPoint(val x: Float, val y: Float)

/** A placed node: where it sits and how big it is drawn. */
data class PlacedNode(
    val node: MindNode,
    val position: MindPoint,
    val radius: Float,
)

/** The whole laid-out map, plus the box it occupies. */
data class MindLayout(
    val placed: List<PlacedNode>,
    val bounds: MindBounds,
) {
    val byId: Map<String, PlacedNode> get() = placed.associateBy { it.node.id }
}

/** An axis-aligned box in graph space. */
data class MindBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f

    /** [padding] around every side. */
    fun padded(padding: Float) = MindBounds(
        left = left - padding,
        top = top - padding,
        right = right + padding,
        bottom = bottom + padding,
    )
}

/**
 * Places a mind graph on a canvas.
 *
 * WHY THIS IS ANDROID-FREE. The interesting part of a force graph is not the
 * drawing, it is the arithmetic: which node ends up where, whether two families
 * overlap, whether the picture is stable between openings. Kept here, the JVM
 * can prove all of that in milliseconds. Composed into a canvas it could only be
 * checked by eye, and "looks about right" is how a map ends up rearranging itself
 * every time somebody opens it.
 *
 * DETERMINISM IS THE POINT. Every position derives from the node's family, its
 * index within that family, and the graph's structure. There is no randomness
 * anywhere, so the same graph always produces the same picture. A map whose
 * contents drift between opens cannot be learned, and a person who cannot learn
 * where things are has been handed a toy rather than a map.
 */
object MindLayoutEngine {

    /** Radius of the ring the family hubs sit on. */
    const val HUB_RING = 252f

    /** Radius of the centre block. */
    const val CORE_RADIUS = 26f

    /** Space reserved around every node when fitting the canvas. */
    const val FIT_PADDING = 104f

    /** How many relaxation passes. Fixed, so cost and result are both predictable. */
    private const val PASSES = 220

    /** The smallest a node is ever drawn, so a faint row is still tappable. */
    private const val MIN_ITEM_RADIUS = 5.5f

    /** The largest, so one confident memory cannot swallow its family. */
    private const val MAX_ITEM_RADIUS = 14f

    /**
     * Lay out [graph].
     *
     * Hubs go on the ring first, at their fixed family order. Members are then
     * seeded evenly around their own hub, and a bounded relaxation settles the
     * overlaps without letting a cluster wander off its anchor.
     */
    fun layout(graph: MindGraph): MindLayout {
        val working = ArrayList<Working>(graph.nodes.size)

        val hubs = graph.nodes.filter { it.kind == NodeKind.HUB }
        val hubPositions = HashMap<MindFamily, MindPoint>()
        val familyCount = MindFamily.entries.size

        hubs.forEach { hub ->
            val angle = ringAngle(hub.family, familyCount)
            hubPositions[hub.family] = MindPoint(
                x = cos(angle) * HUB_RING,
                y = sin(angle) * HUB_RING,
            )
        }

        val membersByFamily = graph.nodes
            .filter { it.kind == NodeKind.ITEM || it.kind == NodeKind.GROW }
            .groupBy { it.family }

        graph.nodes.forEach { node ->
            when (node.kind) {
                NodeKind.CORE -> working.add(
                    Working(node, MindPoint(0f, 0f), CORE_RADIUS * 1.35f, anchor = null)
                )

                NodeKind.HUB -> {
                    // A hub is anchored to its own ring point, because the ring is
                    // the design rather than a tendency. Left to the relaxation it
                    // drifts inward: ten hubs each pushed by their own members'
                    // repulsion ended up at radius 139 instead of 252, which
                    // collapsed the ring into a blob and lost the family order.
                    val at = hubPositions.getValue(node.family)
                    working.add(
                        Working(node, at, hubRadius(node), anchor = at)
                    )
                }

                NodeKind.ITEM, NodeKind.GROW -> {
                    val anchor = hubPositions[node.family] ?: MindPoint(0f, 0f)
                    val siblings = membersByFamily[node.family].orEmpty()
                    val index = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)
                    val total = max(1, siblings.size)

                    // Even spacing with a fixed offset per family, so two
                    // neighbouring hubs never seed their members on the same
                    // bearings and produce a mirrored, obviously generated ring.
                    val spread = (index.toFloat() / total) * TWO_PI
                    val seedAngle = spread + familyOffset(node.family)
                    val distance = if (node.kind == NodeKind.GROW) {
                        HUB_SPOKE_DISTANCE + 56f
                    } else {
                        HUB_SPOKE_DISTANCE + (index % 3) * 20f
                    }

                    working.add(
                        Working(
                            node = node,
                            position = MindPoint(
                                x = anchor.x + cos(seedAngle) * distance,
                                y = anchor.y + sin(seedAngle) * distance,
                            ),
                            radius = itemRadius(node),
                            anchor = anchor,
                        )
                    )
                }
            }
        }

        relax(working, graph.edges)

        val placed = working.map { PlacedNode(it.node, it.position, it.radius) }
        return MindLayout(placed = placed, bounds = boundsOf(placed))
    }

    /** The angle a family's hub occupies on the ring. Fixed by enum order. */
    fun ringAngle(family: MindFamily, familyCount: Int): Float =
        (family.ordinal.toFloat() / max(1, familyCount)) * TWO_PI - (TWO_PI / 4f)

    /** A deterministic bearing offset per family, so clusters do not mirror. */
    private fun familyOffset(family: MindFamily): Float =
        family.ordinal * 0.37f

    /** A hub's radius carries its row count: a busy family is a heavier anchor. */
    fun hubRadius(node: MindNode): Float {
        val total = node.facts.firstOrNull()?.toIntOrNull() ?: 0
        return 15f + min(14f, sqrt(total.toFloat()) * 0.9f)
    }

    /** A member's radius carries its weight, clamped at both ends. */
    fun itemRadius(node: MindNode): Float {
        val scaled = MIN_ITEM_RADIUS + node.weight * (MAX_ITEM_RADIUS - MIN_ITEM_RADIUS)
        return scaled.coerceIn(MIN_ITEM_RADIUS, MAX_ITEM_RADIUS)
    }

    /** Distance a member seeds at, before relaxation moves it. */
    private const val HUB_SPOKE_DISTANCE = 74f
    private const val TWO_PI = (Math.PI * 2.0).toFloat()

    private class Working(
        val node: MindNode,
        var position: MindPoint,
        val radius: Float,
        /** Where this node is pulled back to, so a cluster keeps its shape. */
        val anchor: MindPoint?,
    ) {
        var vx: Float = 0f
        var vy: Float = 0f
    }

    /**
     * Settle the graph.
     *
     * Three forces, and no more: everything pushes everything else apart so
     * discs do not stack, edges pull their ends toward a comfortable length,
     * and a member is drawn back toward its family so a hub stays the visual
     * centre of its own cluster. The pass count is fixed rather than
     * convergence-based, so a bigger mind costs linearly more and never hangs.
     */
    private fun relax(nodes: List<Working>, edges: List<MindEdge>) {
        if (nodes.isEmpty()) return

        val indexById = HashMap<String, Working>(nodes.size * 2)
        nodes.forEach { indexById[it.node.id] = it }

        val springs = edges.mapNotNull { edge ->
            val from = indexById[edge.fromId] ?: return@mapNotNull null
            val to = indexById[edge.toId] ?: return@mapNotNull null
            if (from === to) return@mapNotNull null
            Spring(from, to, springStrength(edge.kind))
        }

        for (pass in 0 until PASSES) {
            val cooling = 1f - pass.toFloat() / PASSES

            // Repulsion. The pair count is quadratic, which is why the caller
            // caps how many rows reach the canvas.
            for (i in nodes.indices) {
                val a = nodes[i]
                for (j in i + 1 until nodes.size) {
                    val b = nodes[j]
                    var dx = b.position.x - a.position.x
                    var dy = b.position.y - a.position.y
                    if (dx == 0f && dy == 0f) {
                        // Two nodes on the same point would divide by zero, and
                        // nudging with a constant keeps the result deterministic.
                        dx = 0.5f
                        dy = 0.5f
                    }
                    val distanceSquared = max(8f, dx * dx + dy * dy)
                    val distance = sqrt(distanceSquared)
                    val push = (a.radius + b.radius + 13f) * 26f / distanceSquared
                    val ux = dx / distance
                    val uy = dy / distance
                    a.vx -= ux * push
                    a.vy -= uy * push
                    b.vx += ux * push
                    b.vy += uy * push
                }
            }

            // Springs.
            springs.forEach { spring ->
                val dx = spring.to.position.x - spring.from.position.x
                val dy = spring.to.position.y - spring.from.position.y
                val distance = max(1f, hypot(dx, dy))
                val rest = spring.from.radius + spring.to.radius + spring.restGap
                val force = (distance - rest) * spring.strength
                val ux = dx / distance
                val uy = dy / distance
                spring.from.vx += ux * force
                spring.from.vy += uy * force
                spring.to.vx -= ux * force
                spring.to.vy -= uy * force
            }

            nodes.forEach { node ->
                // The centre is pinned. A GURU block that drifts is not a centre.
                if (node.node.kind == NodeKind.CORE) {
                    node.position = MindPoint(0f, 0f)
                    node.vx = 0f
                    node.vy = 0f
                    return@forEach
                }

                // A hub is pinned outright. Anything softer let the ring collapse
                // under the members' own repulsion, which is the one thing this
                // layout must not do: the family order IS the map's grammar.
                if (node.node.kind == NodeKind.HUB) {
                    val anchor = node.anchor
                    if (anchor != null) {
                        node.position = anchor
                        node.vx = 0f
                        node.vy = 0f
                    }
                    return@forEach
                }

                // A member is drawn gently back toward its family, so a hub stays
                // the visual centre of its own cluster.
                node.anchor?.let { anchor ->
                    node.vx += (anchor.x - node.position.x) * 0.014f
                    node.vy += (anchor.y - node.position.y) * 0.014f
                }

                // A weak pull toward the middle so no cluster escapes the page.
                node.vx += -node.position.x * 0.0016f
                node.vy += -node.position.y * 0.0016f

                node.position = MindPoint(
                    x = node.position.x + node.vx * cooling,
                    y = node.position.y + node.vy * cooling,
                )
                node.vx *= 0.4f
                node.vy *= 0.4f
            }
        }
    }

    private class Spring(
        val from: Working,
        val to: Working,
        val strength: Float,
        val restGap: Float = 44f,
    )

    /**
     * Spokes are stiff because they are structure; a semantic edge is loose
     * because it is evidence. A contradiction is drawn tight, because two
     * memories that disagree belong near each other to be compared.
     */
    private fun springStrength(kind: EdgeKind): Float = when (kind) {
        EdgeKind.SPOKE -> 0.016f
        EdgeKind.SEMANTIC -> 0.004f
        EdgeKind.TEMPORAL -> 0.003f
        EdgeKind.CAUSAL -> 0.006f
        EdgeKind.ENTITY -> 0.005f
        EdgeKind.RELATED -> 0.004f
        EdgeKind.CONTRADICTS -> 0.009f
        EdgeKind.SUPERSEDES -> 0.008f
        EdgeKind.DUPLICATE -> 0.010f
        EdgeKind.LINKED -> 0.007f
    }

    private fun boundsOf(placed: List<PlacedNode>): MindBounds {
        if (placed.isEmpty()) return MindBounds(0f, 0f, 0f, 0f)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        placed.forEach { node ->
            left = min(left, node.position.x - node.radius)
            top = min(top, node.position.y - node.radius)
            right = max(right, node.position.x + node.radius)
            bottom = max(bottom, node.position.y + node.radius)
        }
        return MindBounds(left, top, right, bottom)
    }
}
