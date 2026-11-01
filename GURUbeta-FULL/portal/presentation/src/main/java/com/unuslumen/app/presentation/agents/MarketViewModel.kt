// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/** One row in the marketplace: the catalogue entry plus what this phone already has. */
data class MarketEntry(
    val slug: String,
    val name: String,
    val roleDescription: String,
    val tools: List<String>,
    val authorLabel: String?,
    val installed: Boolean,
    val installedVersion: Int?,
    val publishedVersion: Int
)

data class MarketState(
    val isLoading: Boolean = true,
    val reachable: Boolean = true,
    val entries: List<MarketEntry> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val notice: String? = null,
    val installingSlug: String? = null
)

sealed interface MarketEvent {
    data object Load : MarketEvent
    data class Search(val query: String) : MarketEvent
    data class Install(val slug: String) : MarketEvent
    data object ClearNotice : MarketEvent
}

/**
 * The marketplace, reading the same catalogue the sync worker reads.
 *
 * Installs go through the repository's synced lane, not the user lane, so a
 * downloaded agent can be force-updated and can be withdrawn, and can never
 * overwrite an agent the person wrote themselves.
 */
@KoinViewModel
class MarketViewModel(
    private val agentRepository: GuruAgentRepository,
    private val agentCatalogue: AgentCatalogue
) : ViewModel() {

    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: MarketEvent) {
        when (event) {
            MarketEvent.Load -> load()
            is MarketEvent.Search -> _state.update { it.copy(query = event.query) }
            is MarketEvent.Install -> install(event.slug)
            MarketEvent.ClearNotice -> _state.update { it.copy(notice = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val catalogue = agentCatalogue.listCatalogue()
            if (catalogue == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        reachable = false,
                        error = "The catalogue could not be reached. Anything you already installed still works."
                    )
                }
                return@launch
            }

            // Versions come from the manifest, which is the only place the
            // catalogue publishes them: /v1/agents carries definitions and
            // /v1/manifest carries slug to version for everything. A missing
            // manifest is not fatal; the rows just carry no version.
            val published = agentCatalogue.publishedVersions() ?: emptyMap()

            val installedBySlug = agentRepository.getSyncedAgents().associateBy { it.slug }
            val entries = catalogue.map { entry ->
                MarketEntry(
                    slug = entry.slug,
                    name = entry.name,
                    roleDescription = entry.roleDescription,
                    tools = entry.toolPermissions,
                    authorLabel = entry.authorLabel,
                    installed = installedBySlug.containsKey(entry.slug),
                    installedVersion = installedBySlug[entry.slug]?.version,
                    publishedVersion = published[entry.slug] ?: 0
                )
            }.sortedBy { it.name.lowercase() }

            _state.update {
                it.copy(isLoading = false, reachable = true, entries = entries, error = null)
            }
        }
    }

    private fun install(slug: String) {
        val entry = _state.value.entries.firstOrNull { it.slug == slug } ?: return
        _state.update { it.copy(installingSlug = slug, error = null) }
        viewModelScope.launch {
            val fetched = agentCatalogue.fetchAgent(slug)
            if (fetched == null) {
                _state.update {
                    it.copy(
                        installingSlug = null,
                        error = "${entry.name} could not be downloaded. Nothing changed on this phone."
                    )
                }
                return@launch
            }
            try {
                val version = entry.publishedVersion.takeIf { it > 0 } ?: 1
                agentRepository.installSyncedAgent(
                    slug = fetched.slug,
                    name = fetched.name,
                    description = fetched.roleDescription,
                    systemPrompt = fetched.systemPrompt,
                    toolPermissions = fetched.toolPermissions,
                    version = version
                )
                _state.update { current ->
                    current.copy(
                        installingSlug = null,
                        notice = "${fetched.name} added. It is in your agents now and stays.",
                        entries = current.entries.map {
                            if (it.slug == slug) it.copy(installed = true, installedVersion = version) else it
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(installingSlug = null, error = e.message ?: "Could not add that agent")
                }
            }
        }
    }

    companion object {
        /** The rows a search box should show for a given query. */
        fun visibleEntries(state: MarketState): List<MarketEntry> {
            val term = state.query.trim().lowercase()
            if (term.isEmpty()) return state.entries
            return state.entries.filter { entry ->
                entry.name.lowercase().contains(term) ||
                    entry.slug.contains(term) ||
                    entry.roleDescription.lowercase().contains(term)
            }
        }
    }
}
