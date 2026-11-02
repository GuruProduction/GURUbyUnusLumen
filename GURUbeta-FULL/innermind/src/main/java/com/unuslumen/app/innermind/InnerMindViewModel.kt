// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the map is currently showing.
 *
 * [visibleFamilies] is a set rather than ten booleans, because "which families am
 * I looking at" is exactly a set: a chip toggles membership, and the empty case
 * means the person silenced everything and gets told so rather than an empty
 * canvas with no explanation.
 */
data class InnerMindState(
    val isLoading: Boolean = true,
    val read: MindRead? = null,
    val layout: MindLayout? = null,
    val visibleFamilies: Set<MindFamily> = MindFamily.entries.toSet(),
    val query: String = "",
    val selectedId: String? = null,
    val linkingFromId: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val graph: MindGraph? get() = read?.graph

    val selectedNode: MindNode?
        get() = selectedId?.let { id -> graph?.nodes?.firstOrNull { it.id == id } }

    /** Every node that survives the family chips and the search. */
    val visibleNodes: List<MindNode>
        get() {
            val nodes = graph?.nodes ?: return emptyList()
            val needle = query.trim().lowercase()
            return nodes.filter { node ->
                when (node.kind) {
                    NodeKind.CORE -> true
                    NodeKind.HUB -> node.family in visibleFamilies
                    NodeKind.GROW -> node.family in visibleFamilies
                    NodeKind.ITEM -> node.family in visibleFamilies &&
                        (needle.isEmpty() || node.label.lowercase().contains(needle))
                }
            }
        }

    val nothingVisible: Boolean
        get() = !isLoading && visibleFamilies.isEmpty()
}

sealed interface InnerMindEvent {
    data object Load : InnerMindEvent
    data object Reload : InnerMindEvent
    data class ToggleFamily(val family: MindFamily) : InnerMindEvent
    data object ShowAllFamilies : InnerMindEvent
    data class Search(val query: String) : InnerMindEvent
    data class Select(val nodeId: String?) : InnerMindEvent
    /** Link the selected node to the next one tapped. */
    data object BeginLink : InnerMindEvent
    data object CancelLink : InnerMindEvent
    data class CompleteLink(val targetId: String) : InnerMindEvent
    /** Grow the family a fold belongs to. */
    data class Grow(val growNodeId: String) : InnerMindEvent
    data object ClearNotice : InnerMindEvent
}

/**
 * Holds the map still while a person reads it.
 *
 * WHY THE VIEW MODEL DOES NOT RE-READ ON EVERY FRAME. The stores behind this map
 * change constantly: a message arrives, a task is ticked, the brain writes a
 * memory. Watching them live would re-lay out the whole graph under the person's
 * finger, and a map that moves while you are reading it is worse than a stale
 * one. It reads once on open, and again only when asked.
 *
 * THE GROWTH IS LOCAL. Tapping a fold reveals rows that are already in the read.
 * It never hits a store, so it cannot fail and cannot disagree with the totals
 * the hubs print.
 */
class InnerMindViewModel(
    private val source: MindSource,
) : ViewModel() {

    private val _state = MutableStateFlow(InnerMindState())
    val state: StateFlow<InnerMindState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: InnerMindEvent) {
        when (event) {
            InnerMindEvent.Load, InnerMindEvent.Reload -> load()
            is InnerMindEvent.ToggleFamily -> toggleFamily(event.family)
            InnerMindEvent.ShowAllFamilies -> _state.update {
                it.copy(visibleFamilies = MindFamily.entries.toSet())
            }
            is InnerMindEvent.Search -> _state.update { it.copy(query = event.query) }
            is InnerMindEvent.Select -> _state.update { it.copy(selectedId = event.nodeId) }
            InnerMindEvent.BeginLink -> _state.update { current ->
                val id = current.selectedId
                if (id == null) current else current.copy(linkingFromId = id, selectedId = null)
            }
            InnerMindEvent.CancelLink -> _state.update { it.copy(linkingFromId = null) }
            is InnerMindEvent.CompleteLink -> completeLink(event.targetId)
            is InnerMindEvent.Grow -> grow(event.growNodeId)
            InnerMindEvent.ClearNotice -> _state.update { it.copy(notice = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { source.read() }.fold(
                onSuccess = { read ->
                    val layout = MindLayoutEngine.layout(read.graph)
                    _state.update { current ->
                        current.copy(
                            isLoading = false,
                            read = read,
                            layout = layout,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    // A read that threw outright is a fact worth stating, not an
                    // empty map. The room prints this sentence rather than showing
                    // a canvas with nothing on it.
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "The mind could not be read.",
                        )
                    }
                },
            )
        }
    }

    private fun toggleFamily(family: MindFamily) {
        _state.update { current ->
            val next = if (family in current.visibleFamilies) {
                current.visibleFamilies - family
            } else {
                current.visibleFamilies + family
            }
            current.copy(visibleFamilies = next)
        }
    }

    /**
     * Draw a link between two stored things.
     *
     * A link made here is this room's own record, drawn in the graph's own
     * vocabulary, and it is held on screen for the session. It is deliberately
     * not written back to the brain's edge tables: those describe what the brain
     * inferred from content, and a person's hand-drawn line is a different claim.
     * Writing one as the other would make the mind's own evidence
     * indistinguishable from an assertion.
     */
    private fun completeLink(targetId: String) {
        _state.update { current ->
            val fromId = current.linkingFromId
            val graph = current.graph
            if (fromId == null || graph == null || fromId == targetId) {
                return@update current.copy(linkingFromId = null)
            }
            if (graph.edges.any { it.touches(fromId, targetId) }) {
                return@update current.copy(
                    linkingFromId = null,
                    notice = "Those two are already joined.",
                )
            }
            val linked = current.read?.let { read ->
                read.copy(
                    graph = read.graph.copy(
                        edges = read.graph.edges + MindEdge(fromId, targetId, EdgeKind.LINKED)
                    )
                )
            } ?: return@update current.copy(linkingFromId = null)

            current.copy(
                read = linked,
                layout = MindLayoutEngine.layout(linked.graph),
                linkingFromId = null,
                notice = "Joined.",
            )
        }
    }

    /**
     * Reveal a handful of the rows a fold is holding.
     *
     * The rows were already read; this only moves them from behind the fold onto
     * the canvas, so it cannot fail and cannot disagree with the totals the hub
     * prints. When the fold empties its node is dropped, because a "+0" would be a
     * control that does nothing.
     */
    private fun grow(growNodeId: String) {
        val current = _state.value
        val read = current.read ?: return
        val fold = read.graph.nodes.firstOrNull { it.id == growNodeId } ?: return
        if (fold.kind != NodeKind.GROW || fold.pending.isEmpty()) return

        val revealed = fold.pending.take(REVEAL_BATCH)
        val stillHeld = fold.pending.drop(REVEAL_BATCH)

        val nodes = read.graph.nodes.toMutableList()
        val foldAt = nodes.indexOfFirst { it.id == fold.id }
        if (foldAt >= 0) nodes.removeAt(foldAt)

        if (stillHeld.isNotEmpty()) {
            nodes.add(
                fold.copy(
                    label = "+${stillHeld.size}",
                    body = "${stillHeld.size} more ${fold.family.label.lowercase()} are stored than " +
                        "this view draws. Tap to bring a few into the map.",
                    facts = listOf(stillHeld.size.toString()),
                    pending = stillHeld,
                )
            )
        }

        // The revealed rows go beside their hub in the node list, so the layout
        // seeds them around the same anchor rather than at the origin.
        nodes.addAll(revealed)

        val hubId = MindSource.HUB_PREFIX + fold.family.name
        val edges = read.graph.edges + revealed.map { member ->
            MindEdge(hubId, member.id, EdgeKind.SPOKE)
        }

        val grown = read.copy(graph = read.graph.copy(nodes = nodes, edges = edges))
        _state.update {
            it.copy(read = grown, layout = MindLayoutEngine.layout(grown.graph))
        }
    }

    private companion object {
        /** How many rows a fold reveals per tap. */
        const val REVEAL_BATCH = 5
    }
}

/** Whether an edge joins two given ids, in either direction. */
private fun MindEdge.touches(a: String, b: String): Boolean =
    (fromId == a && toId == b) || (fromId == b && toId == a)
