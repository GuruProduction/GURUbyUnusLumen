// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.AgentReview
import com.unuslumen.app.domain.repository.AgentStats
import com.unuslumen.app.domain.repository.CataloguePublisher
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/** The maker of an agent, as a screen needs them. */
data class MarketMaker(
    val handle: String,
    val displayName: String,
    val isVerified: Boolean,
    val isOfficial: Boolean,
    /** Two letters for the avatar, taken from the display name rather than invented. */
    val initials: String,
    /** What they have published and since when, or the honest shorter form. */
    val subtitle: String,
)

/** One row in the market: the catalogue entry plus everything real known about it. */
data class MarketEntry(
    val slug: String,
    val name: String,
    val roleDescription: String,
    val tools: List<String>,
    val category: String,
    val isBuiltin: Boolean,
    val priceCents: Long,
    val maker: MarketMaker?,
    val installed: Boolean,
    val installedVersion: Int?,
    val publishedVersion: Int?,
) {
    val toolCount: Int get() = tools.size
}

/** One agent opened in full, over the list. */
data class MarketDetail(
    val entry: MarketEntry,
    val stats: AgentStats?,
    val reviews: List<AgentReview>,
    val installing: Boolean,
)

enum class MarketFilter { All, Free, Paid, Owned }

data class MarketState(
    val isLoading: Boolean = true,
    val reachable: Boolean = true,
    val entries: List<MarketEntry> = emptyList(),
    val query: String = "",
    val filter: MarketFilter = MarketFilter.All,
    val error: String? = null,
    val notice: String? = null,
    val installingSlug: String? = null,
    val detail: MarketDetail? = null,
    /** How many published agents this phone already holds, for the corner stamp. */
    val ownedCount: Int = 0,
)

sealed interface MarketEvent {
    data object Load : MarketEvent
    data class Search(val query: String) : MarketEvent
    data class SetFilter(val filter: MarketFilter) : MarketEvent
    data class Install(val slug: String) : MarketEvent
    data class OpenDetail(val slug: String) : MarketEvent
    data object CloseDetail : MarketEvent
    data class Rate(val slug: String, val stars: Int, val comment: String?) : MarketEvent
    data object ClearNotice : MarketEvent
}

/**
 * The market, reading the same catalogue the sync worker reads.
 *
 * WHAT THIS NOW CARRIES THAT IT DID NOT. Makers, real install counts, real rating
 * averages, ownership and a chosen category. Every one of those is served. Where
 * the server has nothing to say the field stays null and the screen prints a dash
 * rather than a zero, because a zero is a claim about the world and an absence is
 * not.
 *
 * Installs go through the repository's synced lane, not the user lane, so a
 * downloaded agent can be force-updated and can be withdrawn, and can never
 * overwrite an agent the person wrote themselves.
 */
@KoinViewModel
class MarketViewModel(
    private val agentRepository: GuruAgentRepository,
    private val agentCatalogue: AgentCatalogue,
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
            is MarketEvent.SetFilter -> _state.update { it.copy(filter = event.filter) }
            is MarketEvent.Install -> install(event.slug)
            is MarketEvent.OpenDetail -> openDetail(event.slug)
            MarketEvent.CloseDetail -> _state.update { it.copy(detail = null) }
            is MarketEvent.Rate -> rate(event.slug, event.stars, event.comment)
            MarketEvent.ClearNotice -> _state.update { it.copy(notice = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val catalogue = agentCatalogue.listCatalogue()
            if (catalogue == null) {
                // COULD NOT ASK, WHICH IS NOT AN EMPTY MARKET. The two are reported
                // differently so a dead connection is never read as a catalogue
                // with nothing in it.
                _state.update {
                    it.copy(
                        isLoading = false,
                        reachable = false,
                        error = "GURU could not reach the market, so this is not an answer about " +
                            "what exists. Everything you already installed still works.",
                    )
                }
                return@launch
            }

            val published = agentCatalogue.publishedVersions() ?: emptyMap()
            val installedBySlug = agentRepository.getSyncedAgents().associateBy { it.slug }
            val owned = runCatching { agentCatalogue.ownedSlugs() }.getOrDefault(emptySet())

            // Makers are fetched once each, not once per agent, so a catalogue of
            // forty agents from four makers is four requests rather than forty.
            val handles = catalogue.mapNotNull { it.authorLabel }.distinct()
            val makerPages = handles.associateWith { label ->
                runCatching { agentCatalogue.fetchPublisher(label.toHandle()) }.getOrNull()?.publisher
            }

            val entries = catalogue.map { entry ->
                val publisher = entry.authorLabel?.let { makerPages[it] }
                MarketEntry(
                    slug = entry.slug,
                    name = entry.name,
                    roleDescription = entry.roleDescription,
                    tools = entry.toolPermissions,
                    category = entry.source.takeIf { it.isNotBlank() } ?: "other",
                    isBuiltin = entry.isBuiltin,
                    priceCents = entry.priceCents,
                    maker = publisher?.toMaker() ?: entry.authorLabel?.let { labelOnlyMaker(it) },
                    installed = installedBySlug.containsKey(entry.slug) || entry.slug in owned,
                    installedVersion = installedBySlug[entry.slug]?.version,
                    publishedVersion = published[entry.slug],
                )
            }.sortedBy { it.name.lowercase() }

            _state.update {
                it.copy(
                    isLoading = false,
                    reachable = true,
                    entries = entries,
                    error = null,
                    ownedCount = entries.count { entry -> entry.installed },
                )
            }
        }
    }

    private fun openDetail(slug: String) {
        val entry = _state.value.entries.firstOrNull { it.slug == slug } ?: return
        _state.update {
            it.copy(
                detail = MarketDetail(entry, stats = null, reviews = emptyList(), installing = false),
            )
        }
        viewModelScope.launch {
            // The numbers arrive after the sheet is on screen, so opening a detail
            // never waits on a second round trip.
            val stats = runCatching { agentCatalogue.fetchStats(slug) }.getOrNull()
            val reviews = runCatching { agentCatalogue.fetchReviews(slug) }.getOrDefault(emptyList())
            _state.update { current ->
                val open = current.detail
                if (open == null || open.entry.slug != slug) current
                else current.copy(detail = open.copy(stats = stats, reviews = reviews))
            }
        }
    }

    private fun install(slug: String) {
        val entry = _state.value.entries.firstOrNull { it.slug == slug } ?: return
        _state.update {
            it.copy(
                installingSlug = slug,
                error = null,
                detail = it.detail?.let { d ->
                    if (d.entry.slug == slug) d.copy(installing = true) else d
                },
            )
        }
        viewModelScope.launch {
            // A PAID AGENT IS NOT INSTALLED FROM HERE YET. Stripe is not wired, so
            // the honest answer is that the price is real and the payment is not,
            // rather than a silent free download of something with a price on it.
            if (entry.priceCents > 0) {
                _state.update {
                    it.copy(
                        installingSlug = null,
                        detail = it.detail?.copy(installing = false),
                        error = "${entry.name} costs ${priceLabel(entry.priceCents)}. Payments are " +
                            "not switched on yet, so nothing was charged and nothing was added.",
                    )
                }
                return@launch
            }

            val fetched = agentCatalogue.fetchAgent(slug)
            if (fetched == null) {
                _state.update {
                    it.copy(
                        installingSlug = null,
                        detail = it.detail?.copy(installing = false),
                        error = "${entry.name} could not be downloaded. Nothing changed on this phone.",
                    )
                }
                return@launch
            }

            try {
                val version = entry.publishedVersion ?: 1
                agentRepository.installSyncedAgent(
                    slug = fetched.slug,
                    name = fetched.name,
                    description = fetched.roleDescription,
                    systemPrompt = fetched.systemPrompt,
                    toolPermissions = fetched.toolPermissions,
                    version = version,
                )
                // The count is bookkeeping and must never fail the install, so its
                // result is reported rather than checked.
                val counted = runCatching { agentCatalogue.recordInstall(slug) }.getOrDefault(false)

                _state.update { current ->
                    current.copy(
                        installingSlug = null,
                        notice = buildString {
                            append("${fetched.name} added. It is in your agents now and stays.")
                            if (!counted) append(" This phone had already been counted for it.")
                        },
                        entries = current.entries.map {
                            if (it.slug == slug) it.copy(installed = true, installedVersion = version) else it
                        },
                        ownedCount = current.entries.count { it.installed },
                        detail = current.detail?.let { d ->
                            if (d.entry.slug == slug) {
                                d.copy(
                                    entry = d.entry.copy(installed = true, installedVersion = version),
                                    installing = false,
                                )
                            } else d
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        installingSlug = null,
                        detail = it.detail?.copy(installing = false),
                        error = e.message ?: "Could not add that agent",
                    )
                }
            }
        }
    }

    private fun rate(slug: String, stars: Int, comment: String?) {
        viewModelScope.launch {
            val replaced = runCatching { agentCatalogue.submitRating(slug, stars, comment) }
                .getOrDefault(false)
            // Re-read rather than guess: the average is the server's arithmetic.
            val stats = runCatching { agentCatalogue.fetchStats(slug) }.getOrNull()
            val reviews = runCatching { agentCatalogue.fetchReviews(slug) }.getOrDefault(emptyList())
            _state.update { current ->
                current.copy(
                    notice = if (replaced) "Your rating was updated." else "Your rating is in.",
                    detail = current.detail?.let { d ->
                        if (d.entry.slug == slug) d.copy(stats = stats ?: d.stats, reviews = reviews) else d
                    },
                )
            }
        }
    }

    companion object {

        /** The rows a search box and a filter should show. */
        fun visibleEntries(state: MarketState): List<MarketEntry> {
            val term = state.query.trim().lowercase()
            return state.entries.filter { entry ->
                val matchesTerm = term.isEmpty() ||
                    entry.name.lowercase().contains(term) ||
                    entry.slug.contains(term) ||
                    entry.roleDescription.lowercase().contains(term) ||
                    entry.category.lowercase().contains(term)

                val matchesFilter = when (state.filter) {
                    MarketFilter.All -> true
                    MarketFilter.Free -> entry.priceCents == 0L
                    MarketFilter.Paid -> entry.priceCents > 0L
                    MarketFilter.Owned -> entry.installed
                }
                matchesTerm && matchesFilter
            }
        }

        /** Category to how many agents it holds, largest first, for the strip. */
        fun categoryCounts(state: MarketState): List<Pair<String, Int>> =
            state.entries
                .groupBy { it.category }
                .map { (category, entries) -> category to entries.size }
                .sortedByDescending { it.second }

        /** A price as a person reads it. Free is a word, never a zero. */
        fun priceLabel(cents: Long): String = when {
            cents <= 0L -> "FREE"
            cents % 100L == 0L -> "£${cents / 100L}"
            else -> "£%.2f".format(cents / 100.0)
        }

        /** A display name as the handle the publisher route uses. */
        internal fun String.toHandle(): String = lowercase()
            .map { c -> if (c.isLetterOrDigit() && c.code < 128) c else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), "-")
            .ifBlank { "unknown" }

        private fun CataloguePublisher.toMaker(): MarketMaker {
            val initials = displayName
                .split(" ", ".", "-")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "?" }
            val agents = if (agentCount == 1) "1 agent" else "$agentCount agents"
            // A local val, because `since` is a property on a type from another
            // module and Kotlin cannot smart cast across that boundary.
            val publishedSince = since
            return MarketMaker(
                handle = handle,
                displayName = displayName,
                isVerified = isVerified,
                isOfficial = isOfficial,
                initials = initials,
                subtitle = if (publishedSince != null) {
                    "$agents · since ${publishedSince.take(4)}"
                } else {
                    agents
                },
            )
        }

        /**
         * A maker with a name but no publisher record behind it.
         *
         * Some content carries an author label and predates publisher records. The
         * name stays visible and the subtitle says plainly that there is nothing to
         * open, rather than presenting a name as a profile that does not exist.
         */
        private fun labelOnlyMaker(label: String): MarketMaker = MarketMaker(
            handle = "",
            displayName = label,
            isVerified = false,
            isOfficial = false,
            initials = label.take(2).uppercase(),
            subtitle = "no profile on the market",
        )
    }
}
