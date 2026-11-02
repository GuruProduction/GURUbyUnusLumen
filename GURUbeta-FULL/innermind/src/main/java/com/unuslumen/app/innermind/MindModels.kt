// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

/**
 * One stored thing, as the map draws it.
 *
 * [id] is the row's own identity, prefixed by family so two tables can never
 * collide on the same primary key. That prefix is load-bearing: a memory id and
 * a task id are both opaque strings from different stores, and a graph that
 * keyed them together would silently fuse two unrelated things.
 *
 * [weight] is how much of a node it is, not how important it is to its owner:
 * a memory's confidence, a task's priority, a hub's row count. The map sizes the
 * disc from it so a heavy node reads heavy before any text is legible.
 */
data class MindNode(
    val id: String,
    val family: MindFamily,
    val label: String,
    val body: String,
    val weight: Float,
    val updatedAt: Long,
    val kind: NodeKind = NodeKind.ITEM,
    /** Free-form facts a panel prints: layer, tool count, room, mood. */
    val facts: List<String> = emptyList(),
    /**
     * The rows a fold is holding back, ready to be revealed a few at a time.
     *
     * Carried on the fold itself rather than in a side table, so the graph stays
     * one immutable value. It is empty for every kind of node except [NodeKind.GROW],
     * and revealing them is a fold operation on the graph rather than a store read:
     * the rows were already loaded, they were only not drawn.
     */
    val pending: List<MindNode> = emptyList(),
)

/** What a node is structurally, which decides how it is drawn and behaves. */
enum class NodeKind {
    /** The centre. Never deletable, never linkable, always present. */
    CORE,

    /** A family's anchor. Owns its members. */
    HUB,

    /** One stored row. */
    ITEM,

    /** A fold that grows more of its family when tapped. */
    GROW,
}

/**
 * A real relationship between two stored things.
 *
 * [kind] carries what the brain actually recorded rather than a generic line:
 * a semantic edge means the two memories shared a signature above the
 * threshold, a causal edge means one produced the other, and a contradiction is
 * the brain's own record that they disagree. Drawing those identically would
 * throw away the most interesting thing the graph knows.
 */
data class MindEdge(
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val weight: Float = 1f,
)

enum class EdgeKind {
    /** The hub-to-member line. Structural, not semantic. */
    SPOKE,
    SEMANTIC,
    TEMPORAL,
    CAUSAL,
    ENTITY,
    RELATED,
    CONTRADICTS,
    SUPERSEDES,
    DUPLICATE,
    /** Drawn by the person rather than recorded by the brain. */
    LINKED,
}

/**
 * The ten families, in the order they sit around the centre.
 *
 * The order is fixed rather than sorted by size, because a map that rearranges
 * itself between openings cannot be learned. [ink] is the family's colour, taken
 * from the house rotation so a memory is the same red in the map as it is
 * everywhere else in the app.
 */
enum class MindFamily(val label: String, val singular: String) {
    MEMORY("Memories", "Memory"),
    SESSION("Conversations", "Conversation"),
    PERSON("People", "Person"),
    TASK("Tasks", "Task"),
    AGENT("Agents", "Agent"),
    SKILL("Skills", "Skill"),
    THOUGHT("Thoughts", "Thought"),
    NOTE("Notes", "Note"),
    PROJECT("Projects", "Project"),
    DEVICE("Devices", "Device"),
}

/**
 * Everything the map holds in one immutable frame.
 *
 * [totalByFamily] is the true count in each store, which is what a hub prints.
 * It is separate from the nodes actually present because the map folds a large
 * family behind a growth node: the hub must still say "412" while the canvas
 * only carries forty of them, otherwise the number would agree with the drawing
 * and lie about the mind.
 */
data class MindGraph(
    val nodes: List<MindNode>,
    val edges: List<MindEdge>,
    val totalByFamily: Map<MindFamily, Int>,
    val coreLabel: String = "GURU",
) {
    /** How many stored things are represented, hubs and folds excluded. */
    val itemCount: Int
        get() = nodes.count { it.kind == NodeKind.ITEM }

    val hubFor: Map<MindFamily, MindNode>
        get() = nodes.filter { it.kind == NodeKind.HUB }.associateBy { it.family }
}
