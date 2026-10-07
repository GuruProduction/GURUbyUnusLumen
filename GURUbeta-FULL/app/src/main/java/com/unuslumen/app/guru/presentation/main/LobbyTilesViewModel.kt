// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.ModuleStatus
import com.unuslumen.app.domain.model.TileOrder
import com.unuslumen.app.domain.repository.ModuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/**
 * The unified lobby surface: grown rooms and static tiles in one order,
 * drag-and-drop for everything, and the same order the numen reorders by
 * voice through reorderTiles.
 */
@KoinViewModel
class LobbyTilesViewModel(
    private val moduleRepository: ModuleRepository
) : ViewModel() {

    /** Static tile keys in their canonical (never-before-dragged) order. */
    enum class StaticTile(val key: String, val label: String) {
        NOTES("notes", "Notes"),
        JOURNAL("journal", "Journal"),
        PROJECTS("projects", "Projects"),
        MEDIA("media", "Media"),
        CALENDAR("calendar", "Calendar"),
        SETTINGS("settings", "Settings"),
        THOUGHTS("thoughts", "Thoughts"),
        SKILLS("skills", "Skills");

        companion object {
            fun fromKey(key: String): StaticTile? = entries.find { it.key == key.substringAfter("static:") }
        }
    }

    /**
     * One unified lobby tile. Static tiles carry their built-in identity;
     * module tiles carry their vessel. Both drag identically because the
     * order sees only [key].
     */
    data class LobbyTile(
        val key: String,             // "module:<id>" | "static:<key>"
        val title: String,
        val iconPath: String? = null, // module's saved icon, null = default art
        val isModule: Boolean = false,
        val moduleId: String? = null
    )

    data class LobbyTilesState(
        val tiles: List<LobbyTile> = emptyList(),
        val orderLoaded: Boolean = false
    )

    private val _state = MutableStateFlow(LobbyTilesState())
    val state: StateFlow<LobbyTilesState> = _state.asStateFlow()

    // The full static+module inventory merged, then ordered by the persisted
    // tile order. New arrivals without an order row join at the top for
    // modules (doctrine) and after ordered tiles for statics on first drag.
    private val tilesWithoutOrder = combine(
        moduleRepository.getAllModulesFlow(),
        moduleRepository.getTileOrdersFlow()
    ) { modules, orders ->
        buildOrderedTiles(modules.filter { it.status == ModuleStatus.ACTIVE }, orders)
    }

    val tiles: StateFlow<List<LobbyTile>> =
        tilesWithoutOrder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildOrderedTiles(
        modules: List<com.unuslumen.app.domain.model.GuruModule>,
        orders: List<TileOrder>
    ): List<LobbyTile> {
        val orderIndex = orders.mapIndexed { i, o -> o.id to i }.toMap()

        val moduleTiles = modules.map { m ->
            LobbyTile(
                key = "module:${m.id}",
                title = m.displayName,
                iconPath = m.iconPath,
                isModule = true,
                moduleId = m.id
            )
        }
        val staticTiles = StaticTile.entries.map { st ->
            LobbyTile(key = "static:${st.key}", title = st.label)
        }

        val known = moduleTiles + staticTiles
        // Doctrine: unordered modules lead (the room just built comes
        // first), ordered tiles fall in their saved positions after.
        val unorderedModules = moduleTiles.filter { it.key !in orderIndex }
        val ordered = known.filter { it.key in orderIndex }.sortedBy { orderIndex[it.key]!! }
        // Statics with no order row yet park at the end in canonical order;
        // they enter the order on the first drag.
        val unorderedStatics = staticTiles.filter { it.key !in orderIndex }

        val seen = HashSet<String>()
        val result = mutableListOf<LobbyTile>()
        for (t in unorderedModules + ordered + unorderedStatics) {
            if (seen.add(t.key)) result.add(t)
        }
        return result
    }

    /**
     * Persist a full reorder — one drag surface, user finger or numen voice.
     * Writes through the same store reorderTiles writes to.
     */
    fun persistOrder(tilesInNewOrder: List<LobbyTile>) {
        viewModelScope.launch {
            moduleRepository.saveTileOrders(tilesInNewOrder.mapIndexed { i, t ->
                TileOrder(id = t.key, sortOrder = i)
            })
        }
    }
}