// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBody
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel

/**
 * The market, as a shop rather than a settings list.
 *
 * WHAT CHANGED AND WHY. This was a column of rounded cards with a name, a tool
 * list and a grey "In your agents" line. It showed nothing you would use to
 * decide: no price of its own, no maker you could look up, no category and no
 * real number anywhere. A market has to answer who made this, what it costs and
 * whether anyone else has it, or the word above the screen is a lie.
 *
 * EVERY FACT HERE IS SERVED. Counts come from the install and rating tables,
 * makers from the publishers table, prices from the content row. Where the
 * server has nothing to say, the screen says nothing rather than inventing a
 * figure, which is why a rating with no raters shows no stars at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMarketScreen(
    navController: NavHostController,
    viewModel: MarketViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visible = MarketViewModel.visibleEntries(state)
    val categories = MarketViewModel.categoryCounts(state)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = "Market",
                stamp = if (state.ownedCount > 0) "${state.ownedCount} owned" else null,
                stampColor = Memphis.Yellow,
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                state.isLoading -> PageNote("Reading the market…")

                !state.reachable -> CantReach(
                    message = state.error,
                    onRetry = { viewModel.onEvent(MarketEvent.Load) },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 28.dp),
                ) {
                    item(key = "search") {
                        MarketSearch(
                            value = state.query,
                            onChange = { viewModel.onEvent(MarketEvent.Search(it)) },
                        )
                    }

                    item(key = "filters") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 11.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            FilterChip("All", state.filter == MarketFilter.All) {
                                viewModel.onEvent(MarketEvent.SetFilter(MarketFilter.All))
                            }
                            FilterChip("Free", state.filter == MarketFilter.Free) {
                                viewModel.onEvent(MarketEvent.SetFilter(MarketFilter.Free))
                            }
                            FilterChip("Paid", state.filter == MarketFilter.Paid) {
                                viewModel.onEvent(MarketEvent.SetFilter(MarketFilter.Paid))
                            }
                            FilterChip("Owned", state.filter == MarketFilter.Owned) {
                                viewModel.onEvent(MarketEvent.SetFilter(MarketFilter.Owned))
                            }
                        }
                    }

                    // Categories only when there is more than one, because a strip
                    // of one category is furniture.
                    if (categories.size > 1) {
                        item(key = "categories") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                categories.take(4).forEach { (label, count) ->
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .background(Memphis.Paper)
                                            .border(2.5.dp, Memphis.Ink)
                                            .padding(vertical = 9.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text("$count", style = posterStyle(17f))
                                        Text(label.uppercase(), style = captionStyle(7.5f))
                                    }
                                }
                            }
                        }
                    }

                    state.notice?.let { notice ->
                        item(key = "notice") {
                            MarketNotice(notice, Memphis.Teal) {
                                viewModel.onEvent(MarketEvent.ClearNotice)
                            }
                        }
                    }
                    state.error?.let { error ->
                        item(key = "error") {
                            MarketNotice(error, Memphis.Coral) {
                                viewModel.onEvent(MarketEvent.ClearNotice)
                            }
                        }
                    }

                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            NothingMatched(
                                query = state.query,
                                answered = true,
                            )
                        }
                    }

                    item(key = "band") {
                        MarketSectionBand(
                            label = if (state.filter == MarketFilter.Owned) "Yours" else "Everything published",
                            count = visible.size,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }

                    itemsIndexed(visible, key = { _, entry -> entry.slug }) { index, entry ->
                        ProductBlock(
                            entry = entry,
                            ink = Memphis.inkFor(index),
                            installing = state.installingSlug == entry.slug,
                            onOpen = { viewModel.onEvent(MarketEvent.OpenDetail(entry.slug)) },
                            onInstall = { viewModel.onEvent(MarketEvent.Install(entry.slug)) },
                        )
                    }
                }
            }
        }
    }

    // The detail sheet, over the page rather than a second route, so going back
    // from a detail returns to where the person was in the list.
    state.detail?.let { detail ->
        AgentDetailSheet(
            detail = detail,
            onDismiss = { viewModel.onEvent(MarketEvent.CloseDetail) },
            onInstall = { viewModel.onEvent(MarketEvent.Install(detail.entry.slug)) },
            onRate = { stars, comment ->
                viewModel.onEvent(MarketEvent.Rate(detail.entry.slug, stars, comment))
            },
        )
    }
}

// ------------------------------------------------------------------
// Pieces
// ------------------------------------------------------------------

@Composable
private fun MarketSearch(value: String, onChange: (String) -> Unit) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⌕", style = posterStyle(14f))
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text("Search agents", style = bodyStyle().copy(color = Memphis.Ink.copy(alpha = 0.40f)))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = bodyStyle(),
                    cursorBrush = SolidColor(Memphis.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RowScope.FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .background(if (on) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = captionStyle(8.5f, if (on) Memphis.Paper else Memphis.Ink),
        )
    }
}

/** One agent as a saturated slab with its price band and its maker. */
@Composable
private fun ProductBlock(
    entry: MarketEntry,
    ink: Color,
    installing: Boolean,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 13.dp)
            .clickable { onOpen() },
        color = ink,
        borderWidth = 3.dp,
        shadowOffset = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(entry.category.uppercase())
                    Spacer(Modifier.width(6.dp))
                    Tag(if (entry.isBuiltin) "BUILT IN" else "COMMUNITY", ghost = true)
                    if (entry.toolCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Tag("${entry.toolCount} TOOLS", ghost = true)
                    }
                }

                Text(
                    text = entry.name.uppercase(),
                    style = posterStyle(23f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 9.dp),
                )

                if (entry.roleDescription.isNotBlank()) {
                    Text(
                        text = entry.roleDescription,
                        style = bodyStyle().copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }

                MakerRow(
                    maker = entry.maker,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }

            // The price band. What it costs is never something you hunt for.
            Row(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink)) {}
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .background(if (entry.priceCents > 0) Memphis.Ink else Memphis.Paper)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = MarketViewModel.priceLabel(entry.priceCents),
                        style = posterStyle(16f, if (entry.priceCents > 0) Memphis.Paper else Memphis.Ink),
                    )
                }
                Box(Modifier.width(3.dp).height(44.dp).background(Memphis.Ink))
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (entry.installed) Memphis.Paper else Memphis.Ink)
                        .clickable(enabled = !entry.installed && !installing) { onInstall() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            entry.installed -> "In your agents"
                            installing -> "Adding…"
                            entry.priceCents > 0 -> "Buy"
                            else -> "Get it"
                        }.uppercase(),
                        style = captionStyle(10f, if (entry.installed) Memphis.Ink else Memphis.Paper),
                    )
                }
            }
        }
    }
}

/** The maker line: their initials, their name, and what they have published. */
@Composable
private fun MakerRow(maker: MarketMaker?, modifier: Modifier = Modifier) {
    if (maker == null) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(Memphis.Paper)
                .border(2.dp, Memphis.Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text(maker.initials, style = posterStyle(11f))
        }
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
            Text(
                maker.displayName.uppercase(),
                style = captionStyle(10f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                maker.subtitle,
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f)),
                maxLines = 1,
            )
        }
        if (maker.isVerified || maker.isOfficial) {
            Box(
                Modifier
                    .background(if (maker.isOfficial) Memphis.Ink else Memphis.Teal)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    if (maker.isOfficial) "OFFICIAL" else "VERIFIED",
                    style = captionStyle(7.5f, Memphis.Paper),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, ghost: Boolean = false) {
    Box(
        Modifier
            .background(if (ghost) Memphis.Paper else Memphis.Ink)
            .then(if (ghost) Modifier.border(1.5.dp, Memphis.Ink) else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = captionStyle(8f, if (ghost) Memphis.Ink else Memphis.Paper),
        )
    }
}

@Composable
internal fun MarketSectionBand(label: String, count: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Memphis.Ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        if (count != null) {
            Text(
                "$count",
                style = captionStyle(9.5f, Memphis.Ink),
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** Offline, said as what it is rather than as an empty market. */
@Composable
private fun CantReach(message: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MemphisSurface(
            Modifier.fillMaxWidth().padding(18.dp),
            color = Memphis.Paper,
            borderWidth = 3.dp,
            shadowOffset = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("CANNOT REACH THE MARKET", style = posterStyle(21f))
                Spacer(Modifier.height(8.dp))
                Text(
                    message ?: "GURU could not ask, so this is not the market being empty. " +
                        "Everything you already own still works and nothing has been removed.",
                    style = bodyStyle().copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                )
                Spacer(Modifier.height(13.dp))
                Box(
                    Modifier
                        .background(Memphis.Ink)
                        .clickable { onRetry() }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text("TRY AGAIN", style = captionStyle(9f, Memphis.Paper))
                }
            }
        }
    }
}

/** A real empty result, distinguished from a connection that failed. */
@Composable
private fun NothingMatched(query: String, answered: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (query.isBlank()) "NOTHING PUBLISHED YET" else "NOTHING MATCHES",
            style = posterStyle(20f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            if (!answered) {
                "GURU could not ask, so this says nothing about what exists."
            } else if (query.isBlank()) {
                "The market answered and has nothing published."
            } else {
                "The market answered and none of it is for that."
            },
            style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MarketNotice(message: String, ink: Color, onDismiss: () -> Unit) {
    MemphisSurface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = captionStyle(9f, Memphis.Paper),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.clickable { onDismiss() }.padding(6.dp),
            ) { Text("DISMISS", style = captionStyle(9f, Memphis.Paper)) }
        }
    }
}

@Composable
private fun PageNote(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)))
    }
}

/** The body face at a readable size, in the house voice. */
internal fun bodyStyle(): TextStyle = TextStyle(
    fontFamily = MemphisBody,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
    color = Memphis.Ink,
)
