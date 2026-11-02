// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.CreateModuleRequest
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
        SKILLS("skills", "Skills"),
        AGENTS("agents", "Agents"),
        /**
         * Ottio: the marketplace, as a room of its own.
         *
         * IT USED TO BE A DOOR INSIDE AGENTS, which made the shop a sub-screen of the
         * thing it sells. A market is where you go to find work somebody else did, and
         * an agent is what you make for yourself; they are different errands, so they
         * are different rooms.
         */
        OTTIO("ottio", "Ottio"),
        /**
         * The heartbeat room: the prompt GURU beats with, which used to be a
         * compiled asset and is now the owner's to edit.
         */
        HEARTBEAT("heartbeat", "Heartbeat"),
        /**
         * The inner mind: everything stored, drawn as one interactive graph.
         *
         * A room rather than a settings sub-screen, because it is a place you go
         * to look at the whole thing rather than a control you adjust.
         */
        INNER_MIND("innermind", "Inner Mind");

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
     * Make a blank room with a name.
     *
     * A VESSEL, NOT A FINISHED ROOM. The name is all this creates; the composition
     * inside is written afterwards through conversation, which is exactly how the
     * module-forge doctrine already builds rooms. Creating it here means it appears
     * in the lobby immediately, which is what a person expects after pressing Create
     * on the front door.
     *
     * THE NAME BECOMES BOTH THE SLUG AND THE DISPLAY NAME. A person does not know
     * what a slug is and should never be asked for one; the slug is derived, and a
     * clash is resolved by the same unique rule the repository applies to modules.
     */
    fun createRoom(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                moduleRepository.createModule(
                    CreateModuleRequest(
                        name = trimmed.lowercase()
                            .map { if (it.isLetterOrDigit() && it.code < 128) it else ' ' }
                            .joinToString("")
                            .trim()
                            .replace(Regex("\\s+"), "_")
                            .ifBlank { "room" },
                        displayName = trimmed,
                        description = "",
                        category = "home",
                    )
                )
            }
        }
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